"use strict";

(() => {
    const scenes = {
        COMBAT_FLOW: [
            "战斗叙事顺序",
            "在顺序编辑区拖动段落调整先后。每次只显示实际胜负路径；未使用的道具和未发生的住院会跳过。",
            "{combat_result}{injury_notice}{red_bull}{baton}",
            [],
        ],
        ENCOUNTER: ["事件开场", "所有事件都会显示，位于回执第一行。", "{player}遇到了{event}。", ["player", "event"]],
        DESCRIPTION: [
            "事件描述",
            "所有事件都会显示，位于回执第二行。未自定义时沿用数据库默认描述。",
            "水面下，藏着一次意想不到的相遇。",
            ["event", "player", "description"],
        ],
        MONEY_GAIN: [
            "获得金币",
            "抽到正数金额时显示；不会同时显示损失或零金额分支。",
            "你获得了{reward}金币。",
            ["reward", "change", "event"],
        ],
        MONEY_LOSS: [
            "损失金币",
            "抽到负数金额时显示；实际损失使用正数的{penalty}表示。",
            "这次损失了{penalty}金币。",
            ["penalty", "change", "event"],
        ],
        MONEY_ZERO: [
            "无金币变化",
            "恰好抽到0时显示，只发生剧情。金额区间与步长需能抽到0。",
            "虚惊一场，没有金币变化。",
            ["event", "player"],
        ],
        COMBAT_WIN: [
            "战斗获胜",
            "战斗胜利时显示奖金；若同时受伤或使用道具，继续追加对应提示。",
            "你制服了{event}，获得{reward}金币。",
            ["reward", "event"],
        ],
        COMBAT_LOSS: [
            "战斗失败",
            "战斗失败时显示，随后追加伤势与入院提示；医疗费出院另结。",
            "你没能制服{event}。",
            ["event", "player"],
        ],
        INJURY: [
            "受伤与入院",
            "战斗失败会受伤，胜利也可能受轻伤；只有实际受伤才追加，医疗费出院另结。",
            "伤势：{injury}，已送医，医疗费出院另结。",
            ["injury", "injury_score"],
        ],
        JAIL: [
            "调查／服刑",
            "触发此事件后显示，停留时间由上方时长参数决定。",
            "你被带走调查，需要等待{minutes}分钟。",
            ["minutes", "event"],
        ],
        DEBT: [
            "资金不足产生欠款",
            "金币损失超出可用资金、实际产生欠款时追加，不是每次扣款都出现。",
            "其中{borrowed}金币记为欠款。",
            ["borrowed", "penalty"],
        ],
        BOOST_RED_BULL: [
            "使用红牛",
            "仅战斗实际消耗1次已排队的红牛增益时追加。写这句文案不会自动使用或赠送道具。",
            "本次消耗1次红牛增益。",
            ["event", "player"],
        ],
        BOOST_BATON: [
            "使用电棍",
            "仅战斗实际使用电棍时追加，耗电20；剩余电量来自真实结算。",
            "本次电棍耗电20，剩余{baton_energy}/100。",
            ["baton_energy", "event"],
        ],
        BAIT_PURCHASE: [
            "自动购买鱼饵",
            "本竿实际自动购饵时追加；没有购买就不显示。",
            "本次自动购饵花费{bait_cost}金币。",
            ["bait_cost", "bait", "bait_color"],
        ],
        BAIT_EMPTY_DEFAULT: [
            "默认鱼饵耗尽",
            "默认鱼饵在本竿恰好耗尽时追加，不会每竿重复提示。",
            "默认{bait}已耗尽。",
            ["bait", "bait_color"],
        ],
        BAIT_EMPTY_OTHER: [
            "临时鱼饵耗尽",
            "本次指定的非默认鱼饵耗尽时追加。",
            "本次指定的{bait}已耗尽。",
            ["bait", "bait_color"],
        ],
        RECEIPT: [
            "四行回执布局",
            "控制最终四行的排版；必须保留开场、描述、结果、净收益四个占位符。",
            "{encounter}\n描述：{description}\n{outcome}\n收益：{net}金币",
            ["encounter", "description", "outcome", "net"],
        ],
    };
    for (const [scope, label] of [
        ["WIN", "成功"],
        ["LOSS", "失败"],
    ]) {
        for (const base of ["BOOST_RED_BULL", "BOOST_BATON", "INJURY"]) {
            const info = scenes[base];
            scenes[`${base}_${scope}`] = [
                `${label}分支 · ${base === "INJURY" ? "住院" : base === "BOOST_BATON" ? "电棍" : "红牛"}`,
                `只在战斗${label}路径实际发生时显示；未自定义时沿用通用文案。${base === "INJURY" ? "没有住院时跳过，医疗费出院另结。" : "没有实际使用此道具时跳过。"}`,
                info[2],
                info[3],
            ];
        }
        scenes[`COMBAT_${scope}_FLOW`] = [
            `${label}路径顺序`,
            `分叉后的文案仅对${label}结果生效。拖动此路径中的节点调整顺序，拖到“分叉前”则两条路径共用。`,
            scenes.COMBAT_FLOW[2],
            [],
        ];
    }
    const isFlow = (scene) => ["COMBAT_FLOW", "COMBAT_WIN_FLOW", "COMBAT_LOSS_FLOW"].includes(scene);
    const labels = Object.fromEntries(Object.entries(scenes).map(([key, info]) => [key, info[0]]));
    const kinds = { MONEY: "金币事件", COMBAT: "战斗事件", JAIL: "监狱事件" };
    const results = {
        MONEY: ["MONEY_GAIN", "MONEY_LOSS", "MONEY_ZERO"],
        COMBAT: ["COMBAT_WIN", "COMBAT_LOSS", "INJURY"],
        JAIL: ["JAIL"],
    };
    const common = ["ENCOUNTER", "DESCRIPTION"];
    const advanced = ["BAIT_PURCHASE", "BAIT_EMPTY_DEFAULT", "BAIT_EMPTY_OTHER", "RECEIPT"];
    const combatParts = {
        combat_result: "战斗结果",
        injury_notice: "受伤与入院",
        red_bull: "使用红牛",
        baton: "使用电棍",
    };
    const partScenes = { injury_notice: "INJURY", red_bull: "BOOST_RED_BULL", baton: "BOOST_BATON" };
    const sceneParts = { INJURY: "injury_notice", BOOST_RED_BULL: "red_bull", BOOST_BATON: "baton" };
    const defaults = () => ({
        code: "",
        name: "",
        kind: "MONEY",
        poolCode: "NEGATIVE",
        tickets: 10,
        amountMin: "20",
        amountMax: "80",
        amountStep: "1",
        jailMinutes: 10,
        strengthMin: 60,
        strengthMax: 100,
        winInjuryPercent: 20,
        lightPercent: 50,
        mediumPercent: 35,
        enabled: true,
        texts: [],
    });
    const node = (tag, className, text) => {
        const el = document.createElement(tag);
        if (className) el.className = className;
        if (text !== undefined) el.textContent = text;
        return el;
    };

    function create(root, event = null, lockedCode = false, onChange = () => {}) {
        root.replaceChildren(document.querySelector("#event-fields-template").content.cloneNode(true));
        const branches = root.querySelector(".event-branches"),
            inspector = root.querySelector(".event-inspector");
        inspector.hidden = false;
        const livePreview = node("aside", "event-live-preview");
        livePreview.setAttribute("aria-label", "当前路径的最终预览");
        branches.append(livePreview);
        const data = event || defaults();
        const field = (key) => root.querySelector(`[data-eventfield="${key}"]`);
        for (const [key, value] of Object.entries(data)) {
            const input = field(key);
            if (!input) continue;
            if (input.tagName === "SELECT" && ![...input.options].some((option) => option.value === value)) {
                const option = node("option", "", `${value}（已配置分组）`);
                option.value = value;
                input.append(option);
            }
            if (input.type === "checkbox") input.checked = Boolean(value);
            else input.value = value ?? "";
        }
        field("code").readOnly = lockedCode;
        root.querySelector(".event-parameters").open = !lockedCode;
        const list = root.querySelector(".event-texts"),
            navigation = root.querySelector(".branch-navigation");
        const panels = new Map(),
            buttons = new Map();
        let active = "DESCRIPTION",
            advancedOpen = false,
            lastText = null,
            fallbacks = {},
            availableResults = null,
            navigationSortScope = "common";
        const openPaths = { win: true, loss: false };
        function openInspector() {
            inspector.hidden = false;
        }
        function closeInspector() {}
        root.querySelector('[data-event-action="open-editor"]').addEventListener("click", openInspector);
        root.querySelector('[data-event-action="close-editor"]').addEventListener("click", closeInspector);
        root.onkeydown = (event) => {
            if (event.key === "Escape") closeInspector();
        };
        for (const scene of Object.keys(scenes)) {
            const panel = node("div", "branch-text-list");
            panel.dataset.scene = scene;
            panels.set(scene, panel);
            list.append(panel);
        }
        const rows = () => [...root.querySelectorAll(".event-text-row")];
        function allowed() {
            return [
                ...common,
                ...results[field("kind").value],
                ...(field("kind").value === "COMBAT"
                    ? [
                          "BOOST_RED_BULL",
                          "BOOST_BATON",
                          "COMBAT_FLOW",
                          "INJURY_WIN",
                          "INJURY_LOSS",
                          "BOOST_RED_BULL_WIN",
                          "BOOST_RED_BULL_LOSS",
                          "BOOST_BATON_WIN",
                          "BOOST_BATON_LOSS",
                          "COMBAT_WIN_FLOW",
                          "COMBAT_LOSS_FLOW",
                      ]
                    : []),
                ...(field("kind").value === "MONEY" ? ["DEBT"] : []),
                ...advanced,
            ];
        }
        function status(scene) {
            const entries = isFlow(scene)
                ? ["COMBAT_FLOW", "COMBAT_WIN_FLOW", "COMBAT_LOSS_FLOW"].flatMap((key) => [...panels.get(key).children])
                : [...panels.get(scene).children];
            const enabled = entries.filter((row) => row.querySelector('[data-textfield="enabled"]').checked);
            const positive = enabled.filter((row) => Number(row.querySelector('[data-textfield="weight"]').value) > 0);
            if (isFlow(scene))
                return positive.length ? "已自定义顺序" : enabled.length ? "顺序配置需调整" : "使用默认顺序";
            return positive.length ? `自定义 ${positive.length}句` : enabled.length ? "权重需调整" : "使用通用文案";
        }
        function allowsResultScene(scene, scope) {
            const base = scene.replace(/_(WIN|LOSS)$/, ""),
                part = sceneParts[base];
            return !part || FishingCombatTree.allowsResultText(read(root).texts, fallbacks, part, scope);
        }
        function updateStatus() {
            for (const row of rows()) {
                const enabled = row.querySelector('[data-textfield="enabled"]').checked;
                row.classList.toggle("disabled-text", !enabled);
                row.querySelector(".text-variant-label").textContent = enabled ? "随机候选文案" : "未启用的旧文案";
            }
            const moneyResult = { MONEY_GAIN: "获得金币", MONEY_LOSS: "损失金币", MONEY_ZERO: "无金币变化" };
            const unreachable = (scene) =>
                field("kind").value === "MONEY" &&
                availableResults &&
                moneyResult[scene] &&
                !availableResults[moneyResult[scene]];
            for (const [scene, button] of buttons) {
                button.querySelector("small").textContent = unreachable(scene) ? "当前金额不会触发" : status(scene);
                const resultScenes =
                    scene === "COMBAT_WIN"
                        ? [
                              ["COMBAT_WIN", "成功"],
                              ["COMBAT_LOSS", "失败"],
                          ]
                        : ["BOOST_RED_BULL", "BOOST_BATON", "INJURY"].includes(scene)
                          ? [
                                [scene + "_WIN", "成功"],
                                [scene + "_LOSS", "失败"],
                            ]
                          : [];
                const customized = resultScenes
                    .filter(([key]) => allowsResultScene(key, key.endsWith("_LOSS") ? "loss" : "win"))
                    .filter(([key]) =>
                        [...panels.get(key).children].some(
                            (row) =>
                                row.querySelector('[data-textfield="enabled"]').checked &&
                                Number(row.querySelector('[data-textfield="weight"]').value) > 0,
                        ),
                    )
                    .map(([, label]) => label);
                if (customized.length) button.querySelector("small").textContent = customized.join("／") + "已自定义";
                if (sceneParts[scene] && !allowsResultScene(scene, "win") && !allowsResultScene(scene, "loss"))
                    button.querySelector("small").textContent = "结果前 · 通用文案";
                const summary = button.querySelector(".canvas-node-summary");
                if (summary)
                    summary.textContent =
                        [...panels.get(scene).children]
                            .find(
                                (row) =>
                                    row.querySelector('[data-textfield="enabled"]').checked &&
                                    Number(row.querySelector('[data-textfield="weight"]').value) > 0,
                            )
                            ?.querySelector("textarea").value ||
                        fallbacks[scene]?.[0]?.template ||
                        "读取默认文案中…";
            }
            root.querySelector("[data-branch-condition]").textContent =
                scenes[active][1] +
                (unreachable(active) ? " 当前金额范围或步长不会触发此分支，调整参数后才会发生。" : "");
            const activeBase = active.replace(/_(WIN|LOSS)$/, "");
            if (sceneParts[activeBase]) {
                const win = allowsResultScene(activeBase, "win"),
                    loss = allowsResultScene(activeBase, "loss");
                root.querySelector("[data-branch-condition]").textContent +=
                    !win && !loss
                        ? " 当前在战斗结果之前，只使用通用文案；拖到结果之后才可设置成功／失败文案。"
                        : !win
                          ? " 成功路径中位于战斗结果之前，使用通用文案；失败路径可设置专属文案。"
                          : !loss
                            ? " 失败路径中位于战斗结果之前，使用通用文案；成功路径可设置专属文案。"
                            : " 当前在战斗结果之后，可分别定义成功／失败文案。";
            }
            const count = panels.get(active).children.length;
            root.querySelector("[data-branch-status]").textContent = count
                ? `${status(active)} · 此分支共${count}句。预览先展示第一句启用且权重大于0的文案。`
                : "尚未配置专属文案，当前使用通用文案。点击下方按钮添加即可覆盖此分支。";
            if (isFlow(active))
                root.querySelector("[data-branch-status]").textContent =
                    `${status(active)}。拖动分支会创建事件专属顺序，保存后生效。`;
            root.querySelector("[data-event-summary]").textContent =
                `${kinds[field("kind").value]} · ${field("name").value || "未命名"} · 权重${field("tickets").value}`;
            const fallback = root.querySelector("[data-branch-default]");
            fallback.replaceChildren();
            const custom = (
                isFlow(active)
                    ? ["COMBAT_FLOW", "COMBAT_WIN_FLOW", "COMBAT_LOSS_FLOW"].flatMap((key) => [
                          ...panels.get(key).children,
                      ])
                    : [...panels.get(active).children]
            ).some((row) => row.querySelector('[data-textfield="enabled"]').checked);
            fallback.hidden = custom || isFlow(active);
            root.querySelector('[data-event-action="copy-default"]').hidden = custom || isFlow(active);
            root.querySelector('[data-event-action="add-text"]').hidden = isFlow(active);
            list.hidden = isFlow(active);
            root.querySelector('[data-event-action="reset-default"]').hidden = !custom;
            if (!custom) {
                fallback.append(node("strong", "", isFlow(active) ? "当前使用默认顺序" : "当前使用默认文案"));
                const defaults = fallbacks[active] || [];
                if (!defaults.length) fallback.append(node("p", "hint", "正在读取数据库默认文案…"));
                for (const text of defaults) {
                    const box = node("div", "default-variant");
                    box.append(
                        node("small", "", `默认候选 · 权重${text.weight}`),
                        node(
                            "p",
                            "",
                            isFlow(active)
                                ? combatOrder()
                                      .map((part) => combatParts[part])
                                      .join(" → ")
                                : text.template,
                        ),
                    );
                    fallback.append(box);
                }
            }
            let orderSummary = root.querySelector(".combat-order-summary");
            if (!orderSummary) {
                orderSummary = node("div", "combat-order-summary");
                list.before(orderSummary);
            }
            orderSummary.hidden = !isFlow(active);
            if (isFlow(active)) {
                const tree = combatState();
                const describe = (parts) => (parts.length ? parts.map((part) => combatParts[part]).join(" → ") : "无");
                orderSummary.replaceChildren(
                    node("strong", "", "共用 → 战斗结果 → 成功／失败"),
                    node("p", "", `分叉前共用：${describe(tree.common)}`),
                    node("p", "", `成功路径：${describe(tree.win)}`),
                    node("p", "", `失败路径：${describe(tree.loss)}`),
                    node(
                        "p",
                        "hint",
                        "拖到“分叉前”即为两条路径共用；分叉后分别定义。恢复默认会同时恢复两条路径的顺序。",
                    ),
                );
                const orderList = node("div", "project-order-list");
                orderSummary.append(orderList);
                FishingCombatTree.render(orderList, tree, {
                    onlyScope: active === "COMBAT_WIN_FLOW" ? "win" : active === "COMBAT_LOSS_FLOW" ? "loss" : "common",
                    button: (scene) => {
                        const button = node("button", "branch-button", labels[scene]);
                        button.type = "button";
                        button.addEventListener("click", () => selectScene(scene));
                        return button;
                    },
                    editable,
                    change: changeTree,
                    openPaths,
                });
                if (active === "COMBAT_FLOW")
                    for (const part of ["red_bull", "baton", "injury_notice"])
                        if (!tree.common.includes(part)) {
                            const button = node("button", "text-button", `将${combatParts[part]}放入共用过程`);
                            button.type = "button";
                            button.addEventListener("click", () =>
                                changeTree(FishingCombatTree.move(tree, "win", part, "common"), "common"),
                            );
                            orderSummary.append(button);
                        }
            }
        }
        function selectScene(scene, notify = true) {
            if (!allowed().includes(scene)) return;
            if (
                (scene.endsWith("_WIN") || scene.endsWith("_LOSS")) &&
                !allowsResultScene(scene, scene.endsWith("_WIN") ? "win" : "loss")
            )
                scene = scene.replace(/_(WIN|LOSS)$/, "");
            active = scene;
            root.dataset.activeScene = scene;
            for (const [key, panel] of panels) panel.hidden = key !== scene;
            const navigationScene =
                scene === "COMBAT_LOSS"
                    ? "COMBAT_WIN"
                    : isFlow(scene)
                      ? "COMBAT_FLOW"
                      : scene.replace(/^(BOOST_RED_BULL|BOOST_BATON|INJURY)_(WIN|LOSS)$/, "$1");
            for (const [key, button] of buttons) {
                button.classList.toggle("active", key === navigationScene);
                button.setAttribute("aria-pressed", String(key === navigationScene));
            }
            const scopeChoices = root.querySelector(".editor-scope-choices") || node("div", "editor-scope-choices");
            root.querySelector("[data-branch-title]").after(scopeChoices);
            scopeChoices.replaceChildren();
            let choices = [];
            if (["BOOST_RED_BULL", "BOOST_BATON", "INJURY"].includes(navigationScene))
                choices = [
                    [navigationScene, "通用文案"],
                    ...(allowsResultScene(navigationScene, "win") ? [[navigationScene + "_WIN", "成功文案"]] : []),
                    ...(allowsResultScene(navigationScene, "loss") ? [[navigationScene + "_LOSS", "失败文案"]] : []),
                ];
            if (navigationScene === "COMBAT_WIN")
                choices = [
                    ["COMBAT_WIN", "成功文案"],
                    ["COMBAT_LOSS", "失败文案"],
                ];
            if (isFlow(scene))
                choices = [
                    ["COMBAT_FLOW", "共用顺序"],
                    ["COMBAT_WIN_FLOW", "成功顺序"],
                    ["COMBAT_LOSS_FLOW", "失败顺序"],
                ];
            for (const [key, label] of choices) {
                const button = node("button", "", label);
                button.type = "button";
                button.setAttribute("aria-pressed", String(key === scene));
                button.addEventListener("click", () => selectScene(key));
                scopeChoices.append(button);
            }
            scopeChoices.hidden = choices.length === 0;
            root.querySelector("[data-branch-title]").textContent = labels[scene];
            root.querySelector("[data-branch-path]").textContent =
                `${kinds[field("kind").value]} / 正在编辑 / ${labels[scene]}`;
            root.querySelector("[data-branch-condition]").textContent = scenes[scene][1];
            const placeholders = root.querySelector(".branch-placeholders");
            placeholders.replaceChildren();
            for (const key of scenes[scene][3]) {
                const button = node("button", "placeholder-chip", `{${key}}`);
                button.type = "button";
                button.addEventListener("click", () => {
                    const input =
                        lastText && panels.get(scene).contains(lastText)
                            ? lastText
                            : panels.get(scene).querySelector("textarea");
                    if (!input || input.disabled) return;
                    if (input.value.length + key.length + 2 > 4000) return;
                    input.setRangeText(`{${key}}`, input.selectionStart, input.selectionEnd, "end");
                    input.focus();
                    onChange();
                });
                placeholders.append(button);
            }
            updateStatus();
            if (notify) {
                openInspector();
                onChange({ scene });
            }
        }
        function navigationGroup(title, keys, target = navigation) {
            const group = node("div", "branch-group");
            group.append(node("p", "branch-group-title", title));
            for (const key of keys) {
                const button = sceneButton(key);
                group.append(button);
            }
            target.append(group);
        }
        function combatState() {
            return FishingCombatTree.read(read(root).texts, fallbacks);
        }
        function combatOrder() {
            const tree = combatState();
            return [...tree.common, ...tree.win];
        }
        function editable() {
            return root.dataset.readOnly !== "true" && !root.closest("fieldset")?.disabled;
        }
        function writeLayouts(layouts, disable = []) {
            if (!editable()) return false;
            const missing = Object.keys(layouts).filter((scene) => !panels.get(scene).children.length).length;
            if (rows().length + missing > 60) {
                root.querySelector("[data-branch-status]").textContent =
                    "专属文案最多60条，请先移除不需要的旧文案再调整顺序。";
                return false;
            }
            for (const scene of disable)
                panels
                    .get(scene)
                    .querySelectorAll('[data-textfield="enabled"]')
                    .forEach((input) => {
                        input.checked = false;
                    });
            for (const [scene, template] of Object.entries(layouts)) {
                const entries = [...panels.get(scene).children];
                let row =
                    entries.find((item) => item.querySelector('[data-textfield="enabled"]').checked) || entries[0];
                if (!row) row = addText({ code: null, scene, template, weight: 1, enabled: true });
                entries.forEach((item) => {
                    item.querySelector('[data-textfield="enabled"]').checked = false;
                });
                row.querySelector('[data-textfield="enabled"]').checked = true;
                row.querySelector('[data-textfield="weight"]').value = "1";
                row.querySelector("textarea").value = template;
            }
            return true;
        }
        function changeTree(tree, scope) {
            if (!writeLayouts(FishingCombatTree.templates(tree))) return;
            if (scope !== "common") openPaths[scope] = true;
            active = scope === "win" ? "COMBAT_WIN_FLOW" : scope === "loss" ? "COMBAT_LOSS_FLOW" : "COMBAT_FLOW";
            buildNavigation();
            selectScene(active);
        }
        function sceneButton(scene) {
            const button = node("button", "branch-button");
            button.type = "button";
            button.dataset.branch = scene;
            button.append(node("span", "", labels[scene]), node("small", "", status(scene)));
            if (!isFlow(scene)) button.append(node("p", "canvas-node-summary", "读取默认文案中…"));
            button.addEventListener("click", () => selectScene(scene));
            buttons.set(scene, button);
            return button;
        }
        function combatTree() {
            const tree = combatState();
            navigation.dataset.combatTreeSignature = JSON.stringify(tree);
            const group = node("div", "branch-group combat-sort-group");
            group.append(node("p", "branch-group-title", "② 战斗文案 · 拖动排序"));
            const scopes = node("div", "navigation-sort-scopes");
            scopes.setAttribute("role", "group");
            scopes.setAttribute("aria-label", "侧栏排序范围");
            for (const [scope, label] of [
                ["common", "同步"],
                ["win", "成功"],
                ["loss", "失败"],
            ]) {
                const button = node("button", "", label);
                button.type = "button";
                button.setAttribute("aria-pressed", String(scope === navigationSortScope));
                button.addEventListener("click", () => {
                    navigationSortScope = scope;
                    buildNavigation();
                });
                scopes.append(button);
            }
            group.append(
                scopes,
                node(
                    "p",
                    "navigation-sort-hint",
                    navigationSortScope === "common"
                        ? "拖动 ↕ 同步成功、失败顺序"
                        : `仅调整${navigationSortScope === "win" ? "成功" : "失败"}顺序`,
                ),
            );
            const order = FishingCombatTree.flatOrder(read(root).texts, fallbacks, navigationSortScope);
            const menu = node("div", "navigation-sort-list");
            let dragged = null;
            function reorder(next, focusPart = null) {
                const code =
                    navigationSortScope === "win"
                        ? "COMBAT_WIN_FLOW"
                        : navigationSortScope === "loss"
                          ? "COMBAT_LOSS_FLOW"
                          : "COMBAT_FLOW";
                const template =
                    (navigationSortScope === "common" ? "" : "{branch}") + next.map((part) => `{${part}}`).join("");
                if (
                    !writeLayouts(
                        { [code]: template },
                        navigationSortScope === "common" ? ["COMBAT_WIN_FLOW", "COMBAT_LOSS_FLOW"] : [],
                    )
                )
                    return;
                active = code;
                buildNavigation();
                selectScene(code);
                if (focusPart)
                    navigation.querySelector(`[data-sort-part="${focusPart}"] .navigation-drag-handle`)?.focus();
            }
            const partScenes = {
                combat_result: "COMBAT_WIN",
                injury_notice: "INJURY",
                red_bull: "BOOST_RED_BULL",
                baton: "BOOST_BATON",
            };
            order.forEach((part, index) => {
                const item = node("div", "navigation-sort-item");
                item.dataset.sortPart = part;
                const handle = node("span", "navigation-drag-handle", "↕");
                handle.draggable = editable();
                handle.tabIndex = 0;
                handle.setAttribute("role", "button");
                handle.setAttribute("aria-label", `拖动排序：${FishingCombatTree.names[part]}`);
                function step(delta) {
                    const to = index + delta;
                    if (to < 0 || to >= order.length || !editable()) return;
                    const next = [...order];
                    [next[index], next[to]] = [next[to], next[index]];
                    reorder(next, part);
                }
                handle.addEventListener("keydown", (event) => {
                    if (["ArrowUp", "ArrowDown"].includes(event.key)) {
                        event.preventDefault();
                        step(event.key === "ArrowUp" ? -1 : 1);
                    }
                });
                handle.addEventListener("dragstart", (event) => {
                    if (!editable()) {
                        event.preventDefault();
                        return;
                    }
                    dragged = part;
                    event.dataTransfer.effectAllowed = "move";
                    event.dataTransfer.setData("text/plain", part);
                    item.classList.add("dragging");
                });
                handle.addEventListener("dragend", () => {
                    dragged = null;
                    menu.querySelectorAll(".dragging,.drop-before,.drop-after").forEach((el) =>
                        el.classList.remove("dragging", "drop-before", "drop-after"),
                    );
                });
                item.addEventListener("dragover", (event) => {
                    if (!dragged || dragged === part || !editable()) return;
                    event.preventDefault();
                    event.dataTransfer.dropEffect = "move";
                    const after = event.clientY > item.getBoundingClientRect().top + item.offsetHeight / 2;
                    menu.querySelectorAll(".drop-before,.drop-after").forEach((el) =>
                        el.classList.remove("drop-before", "drop-after"),
                    );
                    item.classList.add(after ? "drop-after" : "drop-before");
                });
                item.addEventListener("drop", (event) => {
                    if (!dragged || dragged === part || !editable()) return;
                    event.preventDefault();
                    const next = order.filter((value) => value !== dragged),
                        at = next.indexOf(part);
                    next.splice(at + (item.classList.contains("drop-after") ? 1 : 0), 0, dragged);
                    reorder(next);
                    dragged = null;
                });
                const button = sceneButton(partScenes[part]);
                const tools = node("div", "navigation-sort-tools");
                for (const [symbol, label, delta] of [
                    ["↑", "上移", -1],
                    ["↓", "下移", 1],
                ]) {
                    const move = node("button", "", symbol);
                    move.type = "button";
                    move.dataset.eventAction = "sort-navigation";
                    move.setAttribute("aria-label", `${label}侧栏${FishingCombatTree.names[part]}`);
                    move.disabled = index + delta < 0 || index + delta >= order.length;
                    move.addEventListener("click", () => step(delta));
                    tools.append(move);
                }
                item.append(handle, button, tools);
                menu.append(item);
            });
            group.append(menu);
            navigation.append(group);
            navigationGroup("③ 叙述顺序", ["COMBAT_FLOW"]);
            buttons.get("COMBAT_WIN").querySelector("span").textContent = "战斗结果";
        }
        function buildNavigation() {
            const editing = !inspector.hidden;
            branches.insertBefore(inspector, livePreview);
            navigation.replaceChildren();
            buttons.clear();
            navigation.append(node("strong", "branch-tree-root", kinds[field("kind").value]));
            navigationGroup("① 每次遇到 · 依次显示", common);
            if (field("kind").value === "COMBAT") combatTree();
            else navigationGroup("② 结果分支 · 按实际结果显示", results[field("kind").value]);
            const details = node("details", "branch-advanced");
            details.open = advancedOpen;
            details.append(node("summary", "", "高级设置 · 鱼饵、欠款与回执"));
            details.addEventListener("toggle", () => {
                advancedOpen = details.open;
            });
            navigationGroup(
                "附加提示与排版",
                [...(field("kind").value === "MONEY" ? ["DEBT"] : []), ...advanced],
                details,
            );
            navigation.append(details);
            const inactive = rows().filter(
                (row) => !allowed().includes(row.querySelector('[data-textfield="scene"]').value),
            ).length;
            if (inactive)
                navigation.append(
                    node(
                        "p",
                        "hint branch-retained",
                        `另有${inactive}句其他类型文案已保留，当前类型不会触发。切回对应类型可继续编辑。`,
                    ),
                );
            selectScene(allowed().includes(active) ? active : "DESCRIPTION", false);
            if (editing) openInspector();
        }
        function addText(text) {
            const fragment = document.querySelector("#event-text-template").content.cloneNode(true),
                row = fragment.querySelector(".event-text-row");
            if (text.code) row.dataset.code = text.code;
            for (const [key, value] of Object.entries(text)) {
                const input = row.querySelector(`[data-textfield="${key}"]`);
                if (!input) continue;
                if (input.type === "checkbox") input.checked = Boolean(value);
                else input.value = value ?? "";
            }
            row.querySelector(".text-variant-label").textContent = "随机候选文案";
            row.querySelector("textarea").addEventListener("focus", (event) => {
                lastText = event.target;
            });
            row.querySelector('[data-event-action="remove-text"]').addEventListener("click", () => {
                row.remove();
                updateStatus();
                onChange();
            });
            panels.get(text.scene).append(fragment);
            return row;
        }
        data.texts.forEach(addText);
        root.querySelector('[data-event-action="add-text"]').addEventListener("click", () => {
            if (rows().length >= 60) return;
            const row = addText({ code: null, scene: active, template: scenes[active][2], weight: 1, enabled: true });
            updateStatus();
            onChange();
            row.querySelector("textarea").focus();
        });
        root.querySelector('[data-event-action="copy-default"]').addEventListener("click", () => {
            const originals = fallbacks[active] || [];
            if (!originals.length || rows().length + originals.length > 60) return;
            const copied = originals.map((text) => addText({ ...text, code: null, scene: active }));
            updateStatus();
            onChange();
            copied[0]?.querySelector("textarea")?.focus();
        });
        root.querySelector('[data-event-action="reset-default"]').addEventListener("click", () => {
            for (const scene of isFlow(active) ? ["COMBAT_FLOW", "COMBAT_WIN_FLOW", "COMBAT_LOSS_FLOW"] : [active])
                panels
                    .get(scene)
                    .querySelectorAll('[data-textfield="enabled"]')
                    .forEach((input) => {
                        input.checked = false;
                    });
            if (isFlow(active)) buildNavigation();
            updateStatus();
            onChange();
        });
        root.updateFishingDefaults = (value, receipts) => {
            fallbacks = value || {};
            availableResults = receipts;
            if (
                field("kind").value === "COMBAT" &&
                navigation.dataset.combatTreeSignature !== JSON.stringify(combatState())
            )
                buildNavigation();
            updateStatus();
        };
        function kindVisibility() {
            availableResults = null;
            root.querySelectorAll("[data-kind-section]").forEach((section) => {
                const visible = section.dataset.kindSection.split(" ").includes(field("kind").value);
                section.hidden = !visible;
                section.querySelectorAll("input,textarea,select").forEach((input) => {
                    input.disabled = !visible;
                });
            });
            buildNavigation();
        }
        field("kind").addEventListener("change", () => {
            if (!lockedCode) {
                if (field("kind").value === "COMBAT") {
                    field("poolCode").value = "COMBAT";
                    field("amountMin").value = "800";
                    field("amountMax").value = "2000";
                } else field("poolCode").value = "NEGATIVE";
            }
            kindVisibility();
            onChange();
        });
        root.oninput = () => {
            updateStatus();
            onChange();
        };
        root.onchange = () => {
            updateStatus();
            onChange();
        };
        if (root.fishingInvalidHandler) root.removeEventListener("invalid", root.fishingInvalidHandler, true);
        root.fishingInvalidHandler = (event) => {
            const row = event.target.closest(".event-text-row");
            if (row) {
                selectScene(row.querySelector('[data-textfield="scene"]').value);
            } else root.querySelector(".event-parameters").open = true;
        };
        root.addEventListener("invalid", root.fishingInvalidHandler, true);
        kindVisibility();
    }

    function read(root, preview = false) {
        const field = (key) => root.querySelector(`[data-eventfield="${key}"]`),
            value = { texts: [] };
        for (const key of ["code", "name", "kind", "poolCode", "amountMin", "amountMax", "amountStep"])
            value[key] = field(key).value.trim();
        for (const key of [
            "tickets",
            "jailMinutes",
            "strengthMin",
            "strengthMax",
            "winInjuryPercent",
            "lightPercent",
            "mediumPercent",
        ])
            value[key] = Number(field(key).value);
        value.enabled = field("enabled").checked;
        if (value.kind === "JAIL") {
            value.amountMin = "0";
            value.amountMax = "0";
            value.amountStep = "1";
        }
        if (preview) {
            value.code ||= "preview-event";
            value.name ||= "水下的惊喜";
        }
        root.querySelectorAll(".event-text-row").forEach((row) =>
            value.texts.push({
                code: row.dataset.code || null,
                scene: row.querySelector('[data-textfield="scene"]').value,
                template: row.querySelector('[data-textfield="template"]').value,
                weight: Number(row.querySelector('[data-textfield="weight"]').value),
                enabled: row.querySelector('[data-textfield="enabled"]').checked,
            }),
        );
        return value;
    }

    // One result choice plus two independent item switches replaces eight combined options.
    function resultPicker(root, onReceipt) {
        let receipts = {},
            kind = "",
            activeScene = "",
            base = "",
            redBull = false,
            baton = false;
        const title = node("p", "preview-context"),
            choices = node("div", "result-choices"),
            items = node("div", "preview-item-switches");
        choices.setAttribute("role", "group");
        choices.setAttribute("aria-label", "预览结果");
        const switches = {};
        for (const [key, label] of [
            ["redBull", "模拟使用红牛"],
            ["baton", "模拟使用电棍"],
        ]) {
            const wrapper = node("label", "check-line"),
                input = document.createElement("input");
            input.type = "checkbox";
            input.addEventListener("change", () => {
                if (key === "redBull") redBull = input.checked;
                else baton = input.checked;
                display();
            });
            wrapper.append(input, document.createTextNode(label));
            items.append(wrapper);
            switches[key] = input;
        }
        const hint = node("p", "hint", "仅模拟文案，不消耗道具。电棍示例为使用后剩余80/100。");
        const sample = node("div", "branch-sample"),
            sampleHeading = node("strong"),
            sampleText = node("p");
        sample.append(sampleHeading, sampleText);
        const note = node(
            "p",
            "preview-sample-note",
            "分支示例只展示该提示；下方完整回执按所选结果与道具模拟，实际触发取决于结算。",
        );
        root.replaceChildren(sample, note, choices, items, hint, title);
        let samples = {},
            unavailable = false;
        function display() {
            let key = base;
            if (kind === "COMBAT" && (redBull || baton))
                key += `（${redBull && baton ? "红牛＋电棍" : redBull ? "红牛" : "电棍"}）`;
            title.textContent = unavailable
                ? `当前配置不会触发：${labels[activeScene]}`
                : `完整回执 · ${key || "暂无可发生结果"}`;
            for (const button of choices.children) {
                button.classList.toggle("active", button.dataset.result === base);
                button.setAttribute("aria-pressed", String(button.dataset.result === base));
            }
            switches.redBull.checked = redBull;
            switches.baton.checked = baton;
            items.hidden = hint.hidden = kind !== "COMBAT";
            sampleHeading.textContent = `正在定义：${labels[activeScene] || "事件描述"}`;
            sampleText.textContent = samples[activeScene] || "请先补齐事件参数以生成分支示例。";
            onReceipt(
                unavailable
                    ? "当前金额范围或步长不会触发此分支。上方仅为文案示例；调整参数后可查看完整回执。"
                    : receipts[key] || "当前参数无法触发所选结果。",
            );
        }
        return {
            update(result, definition, scene) {
                receipts = result.receipts;
                samples = result.samples || {};
                if (kind !== definition.kind) {
                    kind = definition.kind;
                    redBull = baton = false;
                    base = "";
                    activeScene = "";
                }
                const bases = Object.keys(receipts).filter((key) => !key.includes("（"));
                if (!bases.includes(base)) base = bases[0] || "";
                if (scene !== activeScene) {
                    activeScene = scene;
                    const preferred = {
                        MONEY_GAIN: "获得金币",
                        MONEY_LOSS: "损失金币",
                        MONEY_ZERO: "无金币变化",
                        COMBAT_WIN: "战斗获胜",
                        COMBAT_LOSS: "战斗失败并入院",
                        INJURY: "战斗失败并入院",
                        INJURY_WIN: "战斗获胜并入院",
                        INJURY_LOSS: "战斗失败并入院",
                        BOOST_RED_BULL_WIN: "战斗获胜",
                        BOOST_RED_BULL_LOSS: "战斗失败并入院",
                        BOOST_BATON_WIN: "战斗获胜",
                        BOOST_BATON_LOSS: "战斗失败并入院",
                        COMBAT_WIN_FLOW: "战斗获胜",
                        COMBAT_LOSS_FLOW: "战斗失败并入院",
                        JAIL: "进入调查/监狱",
                    }[scene];
                    unavailable = Boolean(preferred && !bases.includes(preferred));
                    if (preferred && bases.includes(preferred)) base = preferred;
                    if (scene.startsWith("BOOST_BATON")) {
                        baton = true;
                        redBull = false;
                    }
                    if (scene.startsWith("BOOST_RED_BULL")) {
                        redBull = true;
                        baton = false;
                    }
                    if (isFlow(scene)) {
                        redBull = true;
                        baton = true;
                        base = scene === "COMBAT_LOSS_FLOW" ? "战斗失败并入院" : "战斗获胜";
                    }
                    const part = sceneParts[scene];
                    if (part && !FishingCombatTree.allowsResultText(definition.texts, result.fallbacks, part, "win"))
                        base = part === "injury_notice" ? "战斗获胜并入院" : "战斗获胜";
                    else if (
                        part &&
                        !FishingCombatTree.allowsResultText(definition.texts, result.fallbacks, part, "loss")
                    )
                        base = "战斗失败并入院";
                }
                if (kind === "MONEY") {
                    const preferred = { MONEY_GAIN: "获得金币", MONEY_LOSS: "损失金币", MONEY_ZERO: "无金币变化" }[
                        scene
                    ];
                    const wasUnavailable = unavailable;
                    unavailable = Boolean(preferred && !bases.includes(preferred));
                    if (wasUnavailable && preferred && !unavailable) base = preferred;
                }
                choices.replaceChildren();
                for (const key of bases) {
                    const button = node("button", "result-button", key);
                    button.type = "button";
                    button.dataset.result = key;
                    button.addEventListener("click", () => {
                        base = key;
                        unavailable = false;
                        display();
                    });
                    choices.append(button);
                }
                display();
            },
        };
    }
    window.FishingEventUI = { create, read, labels, defaults, resultPicker };
})();
