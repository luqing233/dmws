"use strict";

(() => {
    const parts = ["combat_result", "injury_notice", "red_bull", "baton"];
    const names = { combat_result: "战斗结果", injury_notice: "住院", red_bull: "红牛", baton: "电棍" };
    const flowScenes = { win: "COMBAT_WIN_FLOW", loss: "COMBAT_LOSS_FLOW" };
    const flow = "{combat_result}{injury_notice}{red_bull}{baton}";
    const baseScene = { injury_notice: "INJURY", red_bull: "BOOST_RED_BULL", baton: "BOOST_BATON" };
    function scene(part, scope) {
        if (part === "combat_result") return scope === "win" ? "COMBAT_WIN" : "COMBAT_LOSS";
        return baseScene[part] + (scope === "common" ? "" : scope === "win" ? "_WIN" : "_LOSS");
    }
    function parse(template) {
        const order = [...template.matchAll(/\{(combat_result|injury_notice|red_bull|baton)\}/g)].map(
            (match) => match[1],
        );
        if (order.length !== 4 || new Set(order).size !== 4) return parse(flow);
        const at = template.includes("{branch}") ? template.indexOf("{branch}") : template.indexOf("{combat_result}");
        const common = order.filter((part) => template.indexOf(`{${part}}`) < at);
        return { common, branch: order.filter((part) => !common.includes(part)) };
    }
    function read(texts, fallbacks = {}) {
        const first = (code) => texts.find((text) => text.scene === code && text.enabled && text.weight > 0)?.template;
        const source = (key) =>
            first(flowScenes[key]) ||
            first("COMBAT_FLOW") ||
            fallbacks[flowScenes[key]]?.[0]?.template ||
            fallbacks.COMBAT_FLOW?.[0]?.template ||
            flow;
        const win = parse(source("win")),
            loss = parse(source("loss"));
        let length = 0;
        while (length < win.common.length && win.common[length] === loss.common[length]) length++;
        const common = win.common.slice(0, length);
        return {
            common,
            win: [...win.common.slice(length), ...win.branch],
            loss: [...loss.common.slice(length), ...loss.branch],
        };
    }
    function flatOrder(texts, fallbacks = {}, scope = "common") {
        const first = (code) => texts.find((text) => text.scene === code && text.enabled && text.weight > 0)?.template;
        const code = flowScenes[scope] || "COMBAT_FLOW";
        const template =
            first(code) ||
            first("COMBAT_FLOW") ||
            fallbacks[code]?.[0]?.template ||
            fallbacks.COMBAT_FLOW?.[0]?.template ||
            flow;
        const parsed = parse(template);
        return [...parsed.common, ...parsed.branch];
    }
    function allowsResultText(texts, fallbacks, part, scope) {
        const order = flatOrder(texts, fallbacks, scope);
        return order.indexOf(part) > order.indexOf("combat_result");
    }
    function valid(tree) {
        if (tree.common.includes("combat_result")) return false;
        return ["win", "loss"].every((scope) => {
            const order = [...tree.common, ...tree[scope]];
            return order.length === 4 && new Set(order).size === 4 && order.every((part) => parts.includes(part));
        });
    }
    function templates(tree) {
        if (!valid(tree)) throw new Error("Invalid combat tree");
        const prefix = tree.common.map((part) => `{${part}}`).join("") + "{branch}";
        return Object.fromEntries(
            ["win", "loss"].map((scope) => [
                flowScenes[scope],
                prefix + tree[scope].map((part) => `{${part}}`).join(""),
            ]),
        );
    }
    function move(tree, from, part, to, target = null, after = false) {
        if (!["common", "win", "loss"].includes(from) || !["common", "win", "loss"].includes(to)) return null;
        if (
            !valid(tree) ||
            !tree[from].includes(part) ||
            part === target ||
            (to === "common" && part === "combat_result")
        )
            return null;
        if (from !== "common" && to !== "common" && from !== to) return null;
        const next = { common: [...tree.common], win: [...tree.win], loss: [...tree.loss] };
        if (to === "common")
            for (const scope of ["common", "win", "loss"]) next[scope] = next[scope].filter((value) => value !== part);
        else {
            next[from] = next[from].filter((value) => value !== part);
            if (from === "common") {
                const other = to === "win" ? "loss" : "win";
                next[other].unshift(part); // Keep the other possible path complete, inheriting its common wording.
            }
        }
        next[to] = next[to].filter((value) => value !== part);
        const at = target ? next[to].indexOf(target) : next[to].length;
        if (at < 0) return null;
        next[to].splice(at + (target && after ? 1 : 0), 0, part);
        return valid(next) ? next : null;
    }
    const element = (tag, className, text) => {
        const node = document.createElement(tag);
        if (className) node.className = className;
        if (text !== undefined) node.textContent = text;
        return node;
    };
    function render(container, tree, options) {
        let dragging = null;
        const group = element("div", "branch-group combat-sequence-group binary-combat-tree");
        group.append(
            options.button("COMBAT_FLOW"),
            element("p", "hint", "分叉前共用；分叉后只在对应结果下出现。拖动 ↕ 或用上下按钮调整。"),
        );
        function apply(next, scope, part) {
            if (next && options.editable()) options.change(next, scope, part);
        }
        function zone(scope, title) {
            const section = element("section", "combat-scope");
            section.dataset.combatScope = scope;
            section.setAttribute("aria-label", title);
            const header = element("p", "combat-scope-title", title);
            section.append(header);
            section.addEventListener("dragover", (event) => {
                if (!dragging || event.target.closest("[data-combat-part]")) return;
                if (move(tree, dragging.scope, dragging.part, scope)) {
                    event.preventDefault();
                    section.classList.add("scope-drop");
                }
            });
            section.addEventListener("dragleave", (event) => {
                if (!section.contains(event.relatedTarget)) section.classList.remove("scope-drop");
            });
            section.addEventListener("drop", (event) => {
                if (!dragging || event.target.closest("[data-combat-part]")) return;
                event.preventDefault();
                apply(move(tree, dragging.scope, dragging.part, scope), scope, dragging.part);
                dragging = null;
            });
            const nodes = element("div", "combat-order-tree");
            section.append(nodes);
            if (!tree[scope].length)
                nodes.append(element("p", "combat-empty-drop", "把红牛、电棍等拖到这里，成为分叉前的共用文案"));
            tree[scope].forEach((part, index) => {
                const item = element("div", "combat-part");
                item.dataset.combatPart = part;
                item.dataset.scope = scope;
                const handle = element("span", "combat-drag-handle", `↕ ${index + 1}`);
                handle.draggable = true;
                handle.tabIndex = 0;
                handle.setAttribute("role", "button");
                handle.setAttribute(
                    "aria-label",
                    `拖动${scope === "common" ? "共用" : scope === "win" ? "成功" : "失败"}${names[part]}`,
                );
                function step(delta) {
                    const destination = tree[scope][index + delta];
                    if (destination) apply(move(tree, scope, part, scope, destination, delta > 0), scope, part);
                }
                handle.addEventListener("keydown", (event) => {
                    if (["ArrowUp", "ArrowDown"].includes(event.key)) {
                        event.preventDefault();
                        step(event.key === "ArrowUp" ? -1 : 1);
                    }
                });
                handle.addEventListener("dragstart", (event) => {
                    if (!options.editable()) {
                        event.preventDefault();
                        return;
                    }
                    dragging = { scope, part };
                    options.dragStart?.();
                    event.dataTransfer.effectAllowed = "move";
                    event.dataTransfer.setData("text/plain", part);
                    item.classList.add("dragging");
                });
                handle.addEventListener("dragend", () => {
                    dragging = null;
                    group
                        .querySelectorAll(".dragging,.drop-before,.drop-after,.scope-drop")
                        .forEach((node) =>
                            node.classList.remove("dragging", "drop-before", "drop-after", "scope-drop"),
                        );
                });
                item.addEventListener("dragover", (event) => {
                    if (!dragging) return;
                    const after = event.clientY > item.getBoundingClientRect().top + item.offsetHeight / 2;
                    if (!move(tree, dragging.scope, dragging.part, scope, part, after)) return;
                    event.preventDefault();
                    event.stopPropagation();
                    group
                        .querySelectorAll(".drop-before,.drop-after")
                        .forEach((node) => node.classList.remove("drop-before", "drop-after"));
                    item.classList.add(after ? "drop-after" : "drop-before");
                });
                item.addEventListener("drop", (event) => {
                    if (!dragging) return;
                    event.preventDefault();
                    event.stopPropagation();
                    apply(
                        move(tree, dragging.scope, dragging.part, scope, part, item.classList.contains("drop-after")),
                        scope,
                        dragging.part,
                    );
                    dragging = null;
                });
                const tools = element("div", "combat-move-tools");
                for (const [symbol, word, delta] of [
                    ["↑", "上移", -1],
                    ["↓", "下移", 1],
                ]) {
                    const button = element("button", "", symbol);
                    button.type = "button";
                    button.dataset.eventAction = "move-part";
                    button.setAttribute(
                        "aria-label",
                        `${word}${scope === "common" ? "共用" : scope === "win" ? "成功" : "失败"}${names[part]}`,
                    );
                    button.disabled = index + delta < 0 || index + delta >= tree[scope].length;
                    button.addEventListener("click", () => step(delta));
                    tools.append(button);
                }
                item.append(handle, tools, options.button(scene(part, scope)));
                if (part !== "combat_result") {
                    const actions = element("div", "canvas-scope-actions");
                    for (const target of scope === "common" ? ["win", "loss"] : ["common"]) {
                        const button = element(
                            "button",
                            "",
                            target === "common" ? "设为共用" : target === "win" ? "移入成功路径" : "移入失败路径",
                        );
                        button.type = "button";
                        button.dataset.eventAction = "move-part";
                        button.setAttribute(
                            "aria-label",
                            `${target === "common" ? "设为共用" : target === "win" ? "移入成功路径" : "移入失败路径"}${names[part]}`,
                        );
                        button.addEventListener("click", () =>
                            apply(
                                move(tree, scope, part, target, target === "common" ? null : tree[target][0], false),
                                target,
                                part,
                            ),
                        );
                        actions.append(button);
                    }
                    item.append(actions);
                }
                nodes.append(item);
            });
            return section;
        }
        if (options.onlyScope) {
            const scope = options.onlyScope;
            group.append(
                zone(scope, scope === "common" ? "共用过程" : scope === "win" ? "成功路径顺序" : "失败路径顺序"),
            );
            container.append(group);
            return;
        }
        group.append(zone("common", "分叉前 · 共用文案"));
        const fork = element("div", "combat-fork");
        fork.append(element("strong", "", "战斗结果 · 二选一"));
        const switcher = element("div", "combat-path-switch");
        switcher.setAttribute("role", "tablist");
        switcher.setAttribute("aria-label", "战斗结果路径");
        const paths = new Map(),
            tabs = new Map();
        for (const scope of ["win", "loss"]) {
            const tab = element("button", "", scope === "win" ? "成功分支" : "失败分支");
            tab.type = "button";
            tab.setAttribute("role", "tab");
            tab.addEventListener("click", () => {
                for (const key of ["win", "loss"]) {
                    options.openPaths[key] = key === scope;
                    paths.get(key).hidden = key !== scope;
                    tabs.get(key).setAttribute("aria-selected", String(key === scope));
                }
                options.branchChanged?.(scope);
            });
            tabs.set(scope, tab);
            switcher.append(tab);
            const branch = element("section", "combat-path");
            branch.setAttribute("aria-label", scope === "win" ? "成功路径" : "失败路径");
            branch.dataset.path = scope;
            branch.hidden = !options.openPaths[scope];
            tab.setAttribute("aria-selected", String(options.openPaths[scope]));
            const summary = element("p", "path-heading", scope === "win" ? "成功后的独立文案" : "失败后的独立文案");
            branch.append(
                summary,
                options.button(flowScenes[scope]),
                zone(scope, scope === "win" ? "只在战斗成功时执行" : "只在战斗失败时执行"),
            );
            paths.set(scope, branch);
            fork.append(branch);
        }
        fork.insertBefore(switcher, fork.children[1]);
        group.append(fork);
        container.append(group);
    }
    const api = { read, flatOrder, allowsResultText, templates, move, valid, render, scene, parts, names };
    if (typeof module !== "undefined" && module.exports) module.exports = api;
    else window.FishingCombatTree = api;
})();
