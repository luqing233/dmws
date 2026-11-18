"use strict";

const $ = (selector, root = document) => root.querySelector(selector);
const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];
const statusNames = { PENDING: "待审核", APPROVED: "已通过", REJECTED: "已驳回" };
const state = {
    session: null,
    catalogPage: 0,
    reviewPage: 0,
    reviewStatus: "PENDING",
    selected: null,
    submission: null,
    previewSequence: 0,
    previewTimer: null,
    toastTimer: null,
    queue: null,
    createType: "FISH",
    adminSection: "moderation",
    fishEntries: [],
    eventEntries: [],
    managerPage: 0,
    managerSelected: null,
};
const eventKinds = { MONEY: "金币事件", COMBAT: "战斗事件", JAIL: "监狱 / 调查" };
const eventPreviewPicker = FishingEventUI.resultPicker($("#event-preview-selector"), (receipt) => {
    $("#preview-receipt").textContent = receipt;
});
const previewColumn = $(".preview-column"),
    previewHome = $(".submission-layout");
function mountEventPreview() {
    const eventMode = state.createType === "EVENT";
    previewHome.classList.toggle("event-layout", eventMode);
    if (eventMode) $("#event-type-fields .event-live-preview").append(previewColumn);
    else previewHome.append(previewColumn);
    $("#preview-disclosure").open = eventMode || !window.matchMedia("(max-width: 760px)").matches;
}

function element(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined && text !== null) node.textContent = String(text);
    return node;
}

function notice(message, error = false) {
    const toast = $("#toast");
    toast.textContent = message;
    toast.classList.toggle("error", error);
    toast.hidden = false;
    clearTimeout(state.toastTimer);
    state.toastTimer = setTimeout(
        () => {
            toast.hidden = true;
        },
        error ? 7000 : 4000,
    );
}

async function api(path, options = {}) {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 15000);
    const headers = { Accept: "application/json", ...options.headers };
    if (options.body !== undefined) headers["Content-Type"] = "application/json";
    if (options.method && options.method !== "GET" && state.session?.csrfToken)
        headers["X-CSRF-Token"] = state.session.csrfToken;
    try {
        const response = await fetch(`/api/fishing${path}`, {
            ...options,
            headers,
            credentials: "same-origin",
            signal: controller.signal,
        });
        const data = await response.json().catch(() => ({}));
        if (!response.ok) {
            const error = new Error(
                data.message ||
                    (response.status === 404 ? "页面服务暂未开放，请联系管理员。" : "操作未完成，请稍后重试。"),
            );
            error.code = data.code || `HTTP_${response.status}`;
            error.status = response.status;
            throw error;
        }
        return data;
    } catch (error) {
        if (error.status) throw error;
        const network = new Error("暂时未收到服务器结果，请检查连接后查询进度，避免重复提交。");
        network.code = "NETWORK";
        throw network;
    } finally {
        clearTimeout(timeout);
    }
}

const post = (path, body) => api(path, { method: "POST", body: JSON.stringify(body) });
function uuid() {
    if (globalThis.crypto?.randomUUID) return crypto.randomUUID();
    const bytes = crypto.getRandomValues(new Uint8Array(16));
    bytes[6] = (bytes[6] & 15) | 64;
    bytes[8] = (bytes[8] & 63) | 128;
    const hex = [...bytes].map((value) => value.toString(16).padStart(2, "0")).join("");
    return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

function draft(form) {
    const field = (name) => form.elements.namedItem(name);
    return {
        name: field("name").value.trim(),
        description: field("description").value.trim(),
        level: Number(field("level").value),
        price: Number(field("price").value),
        difficulty: Number(field("difficulty").value),
        dimensionsMin: Number(field("dimensionsMin").value),
        dimensionsMax: Number(field("dimensionsMax").value),
        special: field("special").checked,
    };
}

function fillDraft(form, fish) {
    for (const [name, value] of Object.entries(fish)) {
        const input = form.elements.namedItem(name);
        if (!input) continue;
        if (input.type === "checkbox") input.checked = Boolean(value);
        else input.value = value ?? "";
    }
}

function validateSize(form) {
    const minimum = form.elements.namedItem("dimensionsMin");
    const maximum = form.elements.namedItem("dimensionsMax");
    maximum.setCustomValidity(Number(maximum.value) < Number(minimum.value) ? "最大尺寸不能小于最小尺寸。" : "");
}

function showError(node, message = "") {
    node.textContent = message;
    node.hidden = !message;
}

function schedulePreview() {
    state.previewSequence++;
    clearTimeout(state.previewTimer);
    state.previewTimer = setTimeout(refreshPreview, 400);
}

async function refreshPreview() {
    if (state.createType === "EVENT") return refreshEventPreview();
    const sequence = ++state.previewSequence;
    const form = $("#submission-form");
    validateSize(form);
    const fish = draft(form);
    const example = !fish.name;
    fish.name ||= "月光鳑鲏";
    fish.description ||= "细碎的银光，藏在水草之间。";
    $("#preview-name").textContent = fish.name;
    $("#preview-mode").textContent = example ? "示例预览" : "实时预览";
    if (fish.dimensionsMax < fish.dimensionsMin) {
        $("#preview-receipt").textContent = "最大尺寸不能小于最小尺寸，请调整后查看预览。";
        $("#preview-reward").textContent = "—";
        $("#preview-chance").textContent = "—";
        return;
    }
    try {
        const result = await post(`/preview?rodLevel=${$("#rod-level").value}`, fish);
        if (sequence !== state.previewSequence) return;
        $("#preview-receipt").textContent = result.receipt;
        $("#preview-reward").textContent = `${result.minimumReward}～${result.maximumReward}`;
        $("#preview-chance").textContent = `${result.successPercent}%`;
        $("#preview-unlock").textContent = result.unlocked
            ? `当前鱼竿已解锁 · 最低需要${result.requiredRodLevel}级鱼竿`
            : `需要${result.requiredRodLevel}级鱼竿 · 当前预览等级尚未解锁`;
    } catch (error) {
        if (sequence !== state.previewSequence) return;
        $("#preview-receipt").textContent = error.message;
        $("#preview-reward").textContent = "—";
        $("#preview-chance").textContent = "—";
    }
}

$("#submission-form").addEventListener("input", () => {
    validateSize($("#submission-form"));
    schedulePreview();
});
$$("[data-create-type]").forEach((button) =>
    button.addEventListener("click", () => {
        if (state.submission && !$("#submission-success").hidden) return notice("请先选择继续投稿。");
        if (state.submission) return notice("上次提交尚未确认，请先查询进度。", true);
        state.createType = button.dataset.createType;
        $$("[data-create-type]").forEach((item) => item.classList.toggle("active", item === button));
        $("#fish-type-fields").hidden = state.createType !== "FISH";
        $("#fish-type-fields").disabled = state.createType !== "FISH";
        $("#event-type-fields").hidden = state.createType !== "EVENT";
        $("#event-type-fields").disabled = state.createType !== "EVENT";
        $("#rod-control").hidden = state.createType === "EVENT";
        $("#event-preview-selector").hidden = state.createType !== "EVENT";
        $(".preview-card").classList.toggle("event-preview", state.createType === "EVENT");
        mountEventPreview();
        $(".preview-caption").textContent =
            state.createType === "EVENT" ? "所选分支的示例与完整回执" : "假设成功钓获时的群聊回执";
        const metrics = $$(".preview-metrics>div");
        $("span", metrics[0]).textContent = state.createType === "EVENT" ? "事件金额" : "售价区间";
        $("span", metrics[1]).textContent = state.createType === "EVENT" ? "事件权重" : "白饵钓获率";
        $("small", metrics[0]).textContent = state.createType === "EVENT" ? "金币 / 或调查分钟" : "金币 / 成功钓获";
        $("small", metrics[1]).textContent = state.createType === "EVENT" ? "不是固定中奖百分比" : "遭遇该鱼之后";
        $(".preview-footnote").textContent =
            state.createType === "EVENT"
                ? "示例只预览文案和参数，不会产生金币、战斗或入狱效果。"
                : "按已有白饵估算，不含购饵支出。预览不会实际抛竿、扣款或生成钓获记录。";
        refreshPreview();
    }),
);
async function refreshEventPreview() {
    const sequence = ++state.previewSequence;
    const definition = FishingEventUI.read($("#event-type-fields"), true);
    $("#preview-name").textContent = definition.name;
    $("#preview-mode").textContent = "事件示例";
    try {
        const result = await post("/event-preview", definition);
        if (sequence !== state.previewSequence) return;
        $("#event-type-fields").updateFishingDefaults(result.fallbacks, result.receipts);
        eventPreviewPicker.update(result, definition, $("#event-type-fields").dataset.activeScene);
        $("#preview-reward").textContent =
            definition.kind === "JAIL"
                ? `${definition.jailMinutes}分钟`
                : `${definition.amountMin}～${definition.amountMax}`;
        $("#preview-chance").textContent = `${definition.tickets}票`;
        $("#preview-unlock").textContent =
            `${eventKinds[definition.kind]} · ${definition.poolCode}分组 · ${definition.enabled ? "审核后启用" : "审核后仍停用"}`;
    } catch (error) {
        if (sequence === state.previewSequence) $("#preview-receipt").textContent = error.message;
    }
}
$("#rod-level").addEventListener("input", (event) => {
    $("#rod-value").textContent = `${event.target.value}级`;
    schedulePreview();
});
$$("[data-placeholder]").forEach((button) =>
    button.addEventListener("click", () => {
        const input = $("#submission-form textarea[name=description]");
        if (input.value.length + button.dataset.placeholder.length > 255) return notice("描述最多255字。", true);
        input.setRangeText(button.dataset.placeholder, input.selectionStart, input.selectionEnd, "end");
        input.focus();
        schedulePreview();
    }),
);

function showReceipt(receipt) {
    $("#submission-success").hidden = false;
    $("#success-summary").textContent =
        `「${receipt.name}」已提交，当前状态：${statusNames[receipt.status] || receipt.status}。`;
    $("#success-code").textContent = receipt.requestId;
    $("#progress-code").value = receipt.requestId;
    $("#submission-fields").disabled = true;
    sessionStorage.setItem("fishing-submission", receipt.requestId);
    $("#submission-success").scrollIntoView({ behavior: "smooth", block: "center" });
}

$("#submission-form").addEventListener("submit", async (event) => {
    event.preventDefault();
    const form = event.currentTarget;
    validateSize(form);
    if (!form.reportValidity()) return;
    const content = {
        author: form.elements.namedItem("author").value.trim(),
        ...(state.createType === "EVENT"
            ? { event: FishingEventUI.read($("#event-type-fields")) }
            : { fish: draft(form) }),
    };
    const signature = JSON.stringify(content);
    if (state.submission && state.submission.signature !== signature) {
        showError($("#submit-error"), "上次提交结果尚未确认。请先用下方查询码查询，确认未提交后再修改内容。");
        $("#progress-panel").open = true;
        $("#progress-code").value = state.submission.requestId;
        return;
    }
    state.submission ||= { requestId: uuid(), signature };
    const button = $("#submit-button");
    button.disabled = true;
    button.textContent = "正在提交…";
    showError($("#submit-error"));
    try {
        showReceipt(await post("/submissions", { ...content, requestId: state.submission.requestId }));
    } catch (error) {
        showError($("#submit-error"), error.message);
        $("#progress-code").value = state.submission.requestId;
        if (error.code !== "NETWORK") state.submission = null;
    } finally {
        button.disabled = false;
        button.textContent = "提交审核 ↗";
    }
});

$("#another-submission").addEventListener("click", () => {
    state.submission = null;
    $("#submission-fields").disabled = false;
    $("#submission-form").reset();
    previewHome.append(previewColumn);
    FishingEventUI.create($("#event-type-fields"), null, false, schedulePreview);
    mountEventPreview();
    $("#submission-success").hidden = true;
    showError($("#submit-error"));
    refreshPreview();
    $(
        state.createType === "EVENT"
            ? '#event-type-fields [data-eventfield="name"]'
            : "#submission-form input[name=name]",
    )?.focus();
});
$("#copy-tracking").addEventListener("click", async () => {
    const code = $("#success-code").textContent;
    const url = new URL(location.href);
    url.searchParams.set("submission", code);
    url.hash = "submit";
    try {
        await navigator.clipboard.writeText(url.href);
        notice("进度链接已复制。");
    } catch {
        notice(`请保留查询码：${code}`);
    }
});
$("#progress-form").addEventListener("submit", async (event) => {
    event.preventDefault();
    const node = $("#progress-result");
    node.hidden = false;
    node.textContent = "正在查询…";
    const request = $("#progress-code").value.trim();
    try {
        const receipt = await api(`/submissions/${encodeURIComponent(request)}`);
        node.textContent =
            `「${receipt.name}」\n状态：${statusNames[receipt.status]}\n提交时间：${receipt.createdAt}` +
            (receipt.reviewedAt ? `\n审核时间：${receipt.reviewedAt}` : "") +
            (receipt.note ? `\n审核意见：${receipt.note}` : "") +
            (receipt.status === "APPROVED"
                ? receipt.type === "EVENT"
                    ? "\n事件已发布，按启用状态与鱼饵分组参与钓鱼。"
                    : "\n已经进入正式鱼池，可以在鱼池图鉴中查看。"
                : "");
    } catch (error) {
        node.textContent = error.message;
        if (error.status === 404 && state.submission?.requestId === request) {
            state.submission = null;
            node.textContent += "\n未找到上次投稿，可以修改资料后重新提交。";
        }
    }
});

function pagination(node, page, pages, onChange) {
    node.replaceChildren();
    if (pages <= 1) return;
    const previous = element("button", "button small", "← 上一页");
    previous.type = "button";
    previous.disabled = page === 0;
    previous.addEventListener("click", () => onChange(page - 1));
    const next = element("button", "button small", "下一页 →");
    next.type = "button";
    next.disabled = page + 1 >= pages;
    next.addEventListener("click", () => onChange(page + 1));
    node.append(previous, element("span", "", `${page + 1} / ${pages}`), next);
}

function empty(node, title, description) {
    const box = element("div", "empty-state");
    box.append(element("span", "", "≈"), element("h2", "", title), element("p", "", description));
    node.replaceChildren(box);
}

async function loadCatalog(page = 0) {
    const node = $("#catalog-grid");
    state.catalogPage = page;
    try {
        const data = await api(`/catalog?page=${page}&q=${encodeURIComponent($("#catalog-query").value.trim())}`);
        $("#catalog-total").textContent = data.allCount;
        $("#catalog-special").textContent = data.specialCount;
        node.replaceChildren();
        for (const { id, fish, displayDescription } of data.items) {
            const card = element("article", "panel fish-card");
            const header = element("div", "fish-card-header");
            header.append(element("span", "fish-number", `No.${String(id).padStart(3, "0")}`));
            if (fish.special) header.append(element("span", "tag", "彩蛋鱼种"));
            card.append(
                header,
                element("h2", "", fish.name),
                element("p", "fish-card-description", displayDescription || "暂无介绍"),
            );
            const stats = element("div", "fish-card-stats");
            for (const [label, value] of [
                ["鱼种等级", `${fish.level}级`],
                ["基础价格", `${Number(fish.price).toLocaleString()}金币`],
                ["尺寸范围", `${fish.dimensionsMin}～${fish.dimensionsMax}cm`],
            ]) {
                const item = element("div", "", label);
                item.append(element("b", "", value));
                stats.append(item);
            }
            card.append(stats);
            node.append(card);
        }
        if (!data.items.length) empty(node, "还没有找到这条鱼", "换个名称搜索，或者将你的灵感投进鱼池。");
        pagination($("#catalog-pagination"), data.page, data.pages, loadCatalog);
    } catch (error) {
        empty(node, "图鉴暂时无法读取", error.message);
    }
}
$("#catalog-search").addEventListener("submit", (event) => {
    event.preventDefault();
    loadCatalog(0);
});

function sessionView() {
    const authenticated = Boolean(state.session?.authenticated);
    $("#login-panel").hidden = authenticated;
    $("#review-workspace").hidden = !authenticated;
    $("#admin-identity").hidden = !authenticated;
    $("#admin-name").textContent = authenticated ? `审核员 · ${state.session.username}` : "";
    $("#login-config").hidden = state.session?.loginEnabled !== false;
    $("#login-button").disabled = state.session?.loginEnabled === false;
    if (!authenticated) {
        for (const selector of [
            "#review-list",
            "#review-detail",
            "#fish-manager-list",
            "#fish-manager-detail",
            "#event-manager-list",
            "#event-manager-detail",
        ])
            $(selector).replaceChildren();
        state.fishEntries = [];
        state.eventEntries = [];
    }
}
async function loadSession() {
    try {
        state.session = await api("/admin/session");
        sessionView();
    } catch (error) {
        showError($("#login-error"), error.message);
    }
}
$("#login-form").addEventListener("submit", async (event) => {
    event.preventDefault();
    const form = event.currentTarget;
    const button = $("#login-button");
    button.disabled = true;
    button.textContent = "正在登录…";
    showError($("#login-error"));
    try {
        state.session = await post("/admin/login", {
            username: form.elements.namedItem("username").value,
            password: form.elements.namedItem("password").value,
        });
        sessionView();
        await adminSection(state.adminSection);
        notice("已进入审核工作台。");
    } catch (error) {
        showError($("#login-error"), error.message);
    } finally {
        form.elements.namedItem("password").value = "";
        button.disabled = state.session?.loginEnabled === false;
        button.textContent = "进入审核工作台 →";
    }
});
$("#logout-button").addEventListener("click", async () => {
    try {
        await post("/admin/logout", {});
        state.session = { authenticated: false, loginEnabled: true };
        state.selected = null;
        sessionView();
        notice("已退出审核账号。");
    } catch (error) {
        notice(error.message, true);
    }
});

async function loadQueue(page = 0) {
    if (!state.session?.authenticated) return;
    state.reviewPage = page;
    try {
        const data = await api(
            `/admin/submissions?status=${state.reviewStatus}&page=${page}&q=${encodeURIComponent($("#review-query").value.trim())}`,
        );
        if (page > 0 && !data.items.length && data.total > 0) return loadQueue(page - 1);
        state.queue = data;
        $("#count-pending").textContent = data.pending;
        $("#count-approved").textContent = data.approved;
        $("#count-rejected").textContent = data.rejected;
        const node = $("#review-list");
        node.replaceChildren();
        for (const row of data.items) {
            const button = element("button", "submission-card");
            button.type = "button";
            button.setAttribute("aria-label", `查看${row.fish.name}的投稿`);
            button.append(
                element("span", `tag ${row.status.toLowerCase()}`, statusNames[row.status]),
                element("h3", "", row.fish.name),
                element("p", "mini-meta", `投稿人 · ${row.author}`),
            );
            const stats = element("div", "draft-stats");
            if (row.type === "EVENT")
                stats.append(
                    element("span", "", eventKinds[row.event.kind]),
                    element("span", "", `${row.event.tickets}票`),
                    element("span", "", row.event.poolCode),
                );
            else
                stats.append(
                    element("span", "", `${row.fish.level}级`),
                    element("span", "", `${row.fish.price}金币`),
                    element("span", "", row.fish.special ? "彩蛋" : "普通鱼种"),
                );
            button.append(stats, element("time", "draft-time", row.createdAt));
            button.addEventListener("click", () => {
                state.selected = row;
                renderDetail(row);
                selectCard(row.id);
            });
            button.dataset.id = row.id;
            node.append(button);
        }
        pagination($("#review-pagination"), data.page, data.pages, loadQueue);
        if (!data.items.length) {
            empty(
                node,
                state.reviewStatus === "PENDING" ? "没有待审核的投稿" : "暂无记录",
                "新的灵感到来时，会显示在这里。",
            );
            empty($("#review-detail"), "暂时可以歇一竿", "切换审核状态，查看已经处理的投稿。");
            state.selected = null;
        } else {
            const selected = data.items.find((row) => row.id === state.selected?.id) || data.items[0];
            state.selected = selected;
            renderDetail(selected);
            selectCard(selected.id);
        }
    } catch (error) {
        if (error.status === 401) {
            state.session = { authenticated: false, loginEnabled: true };
            sessionView();
        }
        notice(error.message, true);
    }
}
function selectCard(id) {
    $$(".submission-card").forEach((button) => {
        const selected = button.dataset.id === String(id);
        button.classList.toggle("selected", selected);
        button.setAttribute("aria-pressed", String(selected));
    });
}
$$("[data-status]").forEach((button) =>
    button.addEventListener("click", () => {
        state.reviewStatus = button.dataset.status;
        state.selected = null;
        $$("[data-status]").forEach((item) => item.classList.toggle("active", item === button));
        loadQueue(0);
    }),
);
$("#review-search").addEventListener("submit", (event) => {
    event.preventDefault();
    state.selected = null;
    loadQueue(0);
});

function renderDetail(row, existing = null) {
    if (row.type === "EVENT") return renderEventDetail(row, existing);
    const fragment = $("#review-form-template").content.cloneNode(true);
    const form = $("form", fragment);
    const detail = (name) => $(`[data-detail="${name}"]`, form);
    detail("name").textContent = row.fish.name;
    detail("meta").textContent = existing
        ? `鱼种 #${existing.id} · 保留原ID和历史钓获记录`
        : `投稿人：${row.author} · ${row.createdAt}`;
    detail("status").textContent = existing ? (existing.enabled ? "已启用" : "已停用") : statusNames[row.status];
    detail("status").classList.add(existing ? "approved" : row.status.toLowerCase());
    const f = row.fish;
    detail("original").textContent =
        `${f.name}\n${f.description}\n等级：${f.level} · 基础价格：${f.price}金币 · 难度：${f.difficulty}\n尺寸：${f.dimensionsMin}～${f.dimensionsMax}厘米 · ${f.special ? "彩蛋鱼种" : "普通鱼种"}`;
    fillDraft(form, row.reviewedFish || row.fish);
    form.elements.namedItem("note").value = row.note || "";
    const pending = Boolean(existing) || row.status === "PENDING";
    let enabled = null;
    if (existing) {
        $(".section-divider>span", form).textContent = "编辑现有鱼种";
        $(".section-divider small", form).textContent = "保存后生效，原始资料和修改记录保留";
        $('[data-action="reject"]', form).hidden = true;
        $('[data-action="approve"]', form).textContent = "保存鱼种修改 ↗";
        form.elements.namedItem("name").maxLength = 255;
        for (const key of ["level", "price", "difficulty", "dimensionsMin", "dimensionsMax"])
            form.elements.namedItem(key).max = 2147483647;
        const line = element("label", "check-line manager-enabled");
        enabled = element("input");
        enabled.type = "checkbox";
        enabled.checked = existing.enabled;
        line.append(enabled, document.createTextNode("启用此鱼种（停用保留历史）"));
        $(".review-actions", form).before(line);
        form.elements.namedItem("note").placeholder = "修改原因（可选，将保留在审核记录中）";
    }
    if (!pending) {
        $$("input,textarea", form).forEach((input) => {
            input.disabled = true;
        });
        $(".review-actions", form).hidden = true;
        detail("reviewed").hidden = false;
        detail("reviewed").textContent =
            `由 ${row.reviewer} 于 ${row.reviewedAt} 审核` +
            (row.sourceFishId
                ? ` · 修改鱼种 #${row.sourceFishId} · ${row.reviewedEnabled ? "启用" : "停用"}`
                : row.publishedFishId
                  ? ` · 已入池 #${row.publishedFishId}`
                  : "");
    }
    let decision = null;
    async function decide(approve) {
        const note = form.elements.namedItem("note").value.trim();
        if (!approve && !note) {
            showError(detail("error"), "请填写驳回原因，帮助投稿人改进。");
            form.elements.namedItem("note").focus();
            return;
        }
        if (approve) {
            validateSize(form);
            if (!form.reportValidity()) return;
        }
        const content = {
            ...(existing ? { revision: existing.revision, enabled: enabled.checked } : { version: row.version }),
            fish: approve ? draft(form) : null,
            note,
        };
        const signature = JSON.stringify({ ...content, approve });
        if (decision && decision.signature !== signature) {
            showError(detail("error"), "上次审核结果未确认，请先刷新列表核对，再调整操作。");
            return;
        }
        decision ||= { requestId: uuid(), signature };
        $$(".review-actions button", form).forEach((button) => {
            button.disabled = true;
        });
        showError(detail("error"));
        try {
            await post(
                existing
                    ? `/admin/fish/${existing.id}`
                    : `/admin/submissions/${row.id}/${approve ? "approve" : "reject"}`,
                { ...content, requestId: decision.requestId },
            );
            notice(
                existing
                    ? "鱼种修改已保存，历史钓获记录保持原样。"
                    : approve
                      ? "审核通过，鱼种已进入正式鱼池。"
                      : "已驳回，审核意见已保存。",
            );
            state.selected = null;
            if (existing) await loadFishManager();
            else await loadQueue(state.reviewPage);
            loadCatalog(state.catalogPage);
        } catch (error) {
            showError(detail("error"), error.message);
            if (error.code !== "NETWORK") decision = null;
            if (error.status === 401) {
                state.session = { authenticated: false, loginEnabled: true };
                sessionView();
                notice("登录已过期，请重新登录。", true);
            }
        } finally {
            $$(".review-actions button", form).forEach((button) => {
                button.disabled = false;
            });
        }
    }
    form.addEventListener("input", () => validateSize(form));
    form.addEventListener("submit", (event) => {
        event.preventDefault();
        decide(true);
    });
    $('[data-action="reject"]', form).addEventListener("click", () => decide(false));
    $('[data-action="preview"]', form).addEventListener("click", async () => {
        try {
            detail("preview").textContent = (
                await post(existing ? "/admin/fish-preview?rodLevel=10" : "/preview?rodLevel=10", draft(form))
            ).receipt;
        } catch (error) {
            detail("preview").textContent = error.message;
        }
    });
    $(existing ? "#fish-manager-detail" : "#review-detail").replaceChildren(fragment);
}

function route() {
    const view = ["submit", "catalog", "review"].includes(location.hash.slice(1)) ? location.hash.slice(1) : "submit";
    $$(".view").forEach((section) => {
        section.hidden = section.id !== `view-${view}`;
    });
    $$("[data-nav]").forEach((link) => {
        const active = link.dataset.nav === view;
        link.classList.toggle("active", active);
        if (active) link.setAttribute("aria-current", "page");
        else link.removeAttribute("aria-current");
    });
    if (view === "catalog") loadCatalog(state.catalogPage);
    if (view === "review") {
        sessionView();
        if (state.session?.authenticated) adminSection(state.adminSection);
    }
}
window.addEventListener("hashchange", route);

$$("[data-admin-section]").forEach((button) =>
    button.addEventListener("click", () => {
        state.managerSelected = null;
        state.managerPage = 0;
        adminSection(button.dataset.adminSection);
    }),
);
async function adminSection(section) {
    state.adminSection = section;
    $("#moderation-panel").hidden = section !== "moderation";
    $("#fish-manager-panel").hidden = section !== "fish";
    $("#event-manager-panel").hidden = section !== "events";
    $$("[data-admin-section]").forEach((button) =>
        button.classList.toggle("active", button.dataset.adminSection === section),
    );
    if (!state.session?.authenticated) return;
    if (section === "fish") return loadFishManager();
    if (section === "events") return loadEventManager();
    return loadQueue(state.reviewPage);
}

async function loadFishManager(page = state.managerPage) {
    try {
        state.fishEntries = await api("/admin/fish");
        const query = $("#fish-manager-query").value.trim().toLowerCase();
        const entries = state.fishEntries.filter((entry) => entry.fish.name.toLowerCase().includes(query));
        const pages = Math.max(1, Math.ceil(entries.length / 18));
        state.managerPage = Math.min(page, pages - 1);
        const current = entries.slice(state.managerPage * 18, state.managerPage * 18 + 18);
        const list = $("#fish-manager-list");
        list.replaceChildren();
        for (const entry of current) {
            const button = element("button", "submission-card");
            button.type = "button";
            button.append(
                element("span", "tag", entry.enabled ? "启用" : "停用"),
                element("h3", "", entry.fish.name),
                element("p", "mini-meta", `#${entry.id} · ${entry.fish.level}级 · ${entry.fish.price}金币`),
            );
            button.addEventListener("click", () => {
                state.managerSelected = entry.id;
                show(entry);
            });
            list.append(button);
        }
        function show(entry) {
            renderDetail({ fish: entry.fish, type: "FISH", status: "CURRENT", reviewedFish: null, note: "" }, entry);
            [...list.children].forEach((button, index) =>
                button.classList.toggle("selected", current[index]?.id === entry.id),
            );
        }
        if (current.length) show(current.find((entry) => entry.id === state.managerSelected) || current[0]);
        else {
            empty(list, "没有找到鱼种", "可按名称查找，也可以先投稿新鱼。");
            empty($("#fish-manager-detail"), "选择一条现有鱼种", "修改会保留鱼种ID和历史渔获。");
        }
        pagination($("#fish-manager-pagination"), state.managerPage, pages, loadFishManager);
    } catch (error) {
        adminError(error);
    }
}
$("#fish-manager-search").addEventListener("submit", (event) => {
    event.preventDefault();
    state.managerSelected = null;
    loadFishManager(0);
});

async function loadEventManager() {
    try {
        state.eventEntries = await api("/admin/events");
        const query = $("#event-manager-query").value.trim().toLowerCase();
        const entries = state.eventEntries.filter((entry) =>
            `${entry.event.name} ${entry.event.code}`.toLowerCase().includes(query),
        );
        const list = $("#event-manager-list");
        list.replaceChildren();
        for (const entry of entries) {
            const button = element("button", "submission-card");
            button.type = "button";
            button.append(
                element("span", "tag", entry.event.enabled ? "启用" : "停用"),
                element("h3", "", entry.event.name),
                element("p", "mini-meta", `${eventKinds[entry.event.kind]} · ${entry.event.code}`),
                element("p", "draft-stats", `${entry.event.poolCode} · 权重 ${entry.event.tickets}`),
            );
            button.addEventListener("click", () => {
                state.managerSelected = entry.event.code;
                show(entry);
            });
            list.append(button);
        }
        function show(entry) {
            renderEventDetail({ type: "EVENT", event: entry.event, status: "CURRENT" }, entry);
            [...list.children].forEach((button, index) =>
                button.classList.toggle("selected", entries[index]?.event.code === entry.event.code),
            );
        }
        if (entries.length) show(entries.find((entry) => entry.event.code === state.managerSelected) || entries[0]);
        else {
            empty(list, "没有找到事件", "可按名称或编号搜索。");
            empty($("#event-manager-detail"), "选择一个特殊事件", "金币、战斗和监狱事件都可以编辑。");
        }
    } catch (error) {
        adminError(error);
    }
}
$("#event-manager-search").addEventListener("submit", (event) => {
    event.preventDefault();
    state.managerSelected = null;
    loadEventManager();
});
function adminError(error) {
    if (error.status === 401) {
        state.session = { authenticated: false, loginEnabled: true };
        sessionView();
    }
    notice(error.message, true);
}

function renderEventDetail(row, existing = null) {
    const definition = existing?.event || row.reviewedEvent || row.event;
    const target = $(existing ? "#event-manager-detail" : "#review-detail");
    const form = element("form", "event-review-form");
    const heading = element("div", "event-manager-intro");
    heading.append(
        element("p", "eyebrow", existing ? "编辑特殊事件 / EVENT CONFIGURATION" : "特殊事件投稿"),
        element("h2", "", definition.name),
        element(
            "p",
            "hint",
            existing
                ? `编号 ${definition.code} · 修改后生效，保留历史事件记录`
                : `投稿人：${row.author} · ${row.createdAt} · ${statusNames[row.status]}`,
        ),
    );
    const original = element("details", "original-draft");
    original.append(element("summary", "", "查看原始事件资料"));
    const source = row.event;
    original.append(
        element(
            "p",
            "",
            `${source.name}（${source.code}）\n${eventKinds[source.kind]} · ${source.poolCode} · 权重${source.tickets}\n金额：${source.amountMin}～${source.amountMax}，步长${source.amountStep}\n` +
                source.texts
                    .map(
                        (text) =>
                            `[${FishingEventUI.labels[text.scene] || text.scene}] ${text.template}（权重${text.weight}，${text.enabled ? "启用" : "停用"}）`,
                    )
                    .join("\n"),
        ),
    );
    const editor = element("div");
    let previewTimer = null,
        previewSequence = 0;
    function scheduleEventDetailPreview() {
        previewSequence++;
        clearTimeout(previewTimer);
        previewTimer = setTimeout(refreshEventDetailPreview, 400);
    }
    FishingEventUI.create(editor, definition, true, scheduleEventDetailPreview);
    const pending = Boolean(existing) || row.status === "PENDING";
    editor.dataset.readOnly = String(!pending);
    if (!pending)
        editor
            .querySelectorAll(
                'input,textarea,select,[data-event-action]:not([data-event-action="open-editor"]):not([data-event-action="close-editor"]),.placeholder-chip',
            )
            .forEach((input) => {
                input.disabled = true;
            });
    const preview = element("div", "review-preview"),
        previewButton = element("button", "text-button", "预览随分支自动更新 · 手动刷新 ↻");
    previewButton.type = "button";
    const results = element("div", "event-results");
    preview.append(previewButton, results);
    const controls = element("div", "event-preview-selector"),
        receipt = element("pre", "receipt", "正在生成预览…");
    receipt.setAttribute("aria-live", "polite");
    results.append(controls, receipt);
    const picker = FishingEventUI.resultPicker(controls, (text) => {
        receipt.textContent = text;
    });
    async function refreshEventDetailPreview() {
        if (!target.contains(form)) return;
        const sequence = ++previewSequence;
        try {
            const value = FishingEventUI.read(editor, true),
                result = await post("/event-preview", value);
            if (sequence !== previewSequence || !target.contains(form)) return;
            editor.updateFishingDefaults(result.fallbacks, result.receipts);
            picker.update(result, value, editor.dataset.activeScene);
        } catch (error) {
            if (sequence === previewSequence) receipt.textContent = error.message;
        }
    }
    previewButton.addEventListener("click", refreshEventDetailPreview);
    const noteLabel = element("label", "field", existing ? "修改说明" : "审核意见");
    const note = element("textarea");
    note.rows = 3;
    note.maxLength = 500;
    note.placeholder = "通过 / 保存时可选；驳回时请填写原因。";
    note.value = row.note || "";
    note.disabled = !pending;
    noteLabel.append(note);
    const errorNode = element("p", "inline-error");
    errorNode.hidden = true;
    errorNode.setAttribute("role", "alert");
    const buttons = element("div", "review-actions");
    const reject = element("button", "button danger", "驳回投稿");
    reject.type = "button";
    reject.hidden = Boolean(existing);
    const save = element("button", "button primary", existing ? "保存事件配置 ↗" : "通过并发布事件 ↗");
    save.type = "submit";
    buttons.append(reject, save);
    buttons.hidden = !pending;
    form.append(heading, original, editor, noteLabel, errorNode, buttons);
    editor.querySelector(".event-live-preview").append(preview);
    if (!pending)
        form.append(
            element(
                "p",
                "hint",
                `由 ${row.reviewer} 于 ${row.reviewedAt} 审核${row.sourceEventCode ? " · 管理员修改记录" : ""}`,
            ),
        );
    let decision = null;
    async function decide(approve) {
        if (!approve && !note.value.trim()) {
            showError(errorNode, "请填写驳回原因。");
            note.focus();
            return;
        }
        if (approve && !form.reportValidity()) return;
        const event = approve ? FishingEventUI.read(editor) : null;
        const content = {
            ...(existing ? { revision: existing.revision } : { version: row.version }),
            event,
            note: note.value.trim(),
        };
        const signature = JSON.stringify({ ...content, approve });
        if (decision && decision.signature !== signature)
            return showError(errorNode, "上次结果尚未确认，请刷新列表核对后再修改。");
        decision ||= { requestId: uuid(), signature };
        reject.disabled = true;
        save.disabled = true;
        showError(errorNode);
        try {
            await post(
                existing
                    ? `/admin/events/${encodeURIComponent(definition.code)}`
                    : `/admin/submissions/${row.id}/${approve ? "approve" : "reject"}`,
                { ...content, requestId: decision.requestId },
            );
            notice(existing ? "事件及随机文案已保存。" : approve ? "事件已通过审核并发布。" : "事件投稿已驳回。");
            if (existing) await loadEventManager();
            else {
                state.selected = null;
                await loadQueue(state.reviewPage);
            }
        } catch (error) {
            showError(errorNode, error.message);
            if (error.code !== "NETWORK") decision = null;
            if (error.status === 401) adminError(error);
        } finally {
            reject.disabled = false;
            save.disabled = false;
        }
    }
    form.addEventListener("submit", (event) => {
        event.preventDefault();
        decide(true);
    });
    reject.addEventListener("click", () => decide(false));
    target.replaceChildren(form);
    scheduleEventDetailPreview();
}

async function initialize() {
    const mobile = window.matchMedia("(max-width: 760px)");
    $("#preview-disclosure").open = !mobile.matches;
    mobile.addEventListener("change", (event) => {
        $("#preview-disclosure").open = !event.matches;
    });
    FishingEventUI.create($("#event-type-fields"), null, false, schedulePreview);
    route();
    refreshPreview();
    await loadSession();
    route();
    const tracking =
        new URL(location.href).searchParams.get("submission") || sessionStorage.getItem("fishing-submission");
    if (tracking) {
        $("#progress-code").value = tracking;
        $("#progress-panel").open = true;
    }
}
initialize();
