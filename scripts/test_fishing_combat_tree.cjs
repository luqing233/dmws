const test = require("node:test");
const assert = require("node:assert/strict");
const tree = require("../src/main/resources/static/combat-tree.js");
const row = (scene, template) => ({ scene, template, enabled: true, weight: 1 });

test("legacy order becomes a shared prefix and two complete independent branches", () => {
    const state = tree.read([row("COMBAT_FLOW", "{red_bull}{baton}{combat_result}{injury_notice}")]);
    assert.deepEqual(state, {
        common: ["red_bull", "baton"],
        win: ["combat_result", "injury_notice"],
        loss: ["combat_result", "injury_notice"],
    });
    assert.deepEqual(
        tree.read(Object.entries(tree.templates(state)).map(([scene, value]) => row(scene, value))),
        state,
    );
});
test("moving a node before the fork removes it from both branch paths", () => {
    const start = tree.read([]);
    const next = tree.move(start, "win", "red_bull", "common");
    assert.deepEqual(next.common, ["red_bull"]);
    assert(!next.win.includes("red_bull") && !next.loss.includes("red_bull"));
    assert(tree.valid(next));
    assert(tree.valid(start));
});
test("moving common wording into one branch keeps the other path complete", () => {
    const start = tree.read([row("COMBAT_FLOW", "{red_bull}{baton}{combat_result}{injury_notice}")]);
    const next = tree.move(start, "common", "baton", "loss", "combat_result", false);
    assert.deepEqual(next.common, ["red_bull"]);
    assert.deepEqual(next.loss, ["baton", "combat_result", "injury_notice"]);
    assert.deepEqual(next.win, ["baton", "combat_result", "injury_notice"]);
    const changed = tree.move(next, "loss", "injury_notice", "loss", "combat_result", false);
    assert.deepEqual(changed.win, next.win);
    assert.notDeepEqual(changed.loss, next.loss);
    assert(tree.valid(changed));
});
test("results cannot become common and unrelated branches cannot steal each others nodes", () => {
    const start = tree.read([]);
    assert.equal(tree.move(start, "win", "combat_result", "common"), null);
    assert.equal(tree.move(start, "win", "baton", "loss", "combat_result"), null);
});
test("explicit fork preserves branch-specific wording even before the outcome sentence", () => {
    const texts = [
        row("COMBAT_WIN_FLOW", "{red_bull}{branch}{combat_result}{baton}{injury_notice}"),
        row("COMBAT_LOSS_FLOW", "{red_bull}{branch}{baton}{combat_result}{injury_notice}"),
    ];
    const parsed = tree.read(texts);
    assert.deepEqual(parsed.common, ["red_bull"]);
    assert.deepEqual(parsed.win, ["combat_result", "baton", "injury_notice"]);
    assert.deepEqual(parsed.loss, ["baton", "combat_result", "injury_notice"]);
    assert.equal(tree.scene("baton", "common"), "BOOST_BATON");
    assert.equal(tree.scene("baton", "loss"), "BOOST_BATON_LOSS");
    assert.equal(tree.scene("injury_notice", "win"), "INJURY_WIN");
});

test("flat sidebar keeps every narrative component even around an explicit branch marker", () => {
    const texts = [row("COMBAT_FLOW", "{red_bull}{baton}{branch}{combat_result}{injury_notice}")];
    assert.deepEqual(tree.flatOrder(texts), ["red_bull", "baton", "combat_result", "injury_notice"]);
    for (const scope of ["common", "win", "loss"]) {
        const order = tree.flatOrder(texts, {}, scope);
        assert.equal(order.length, 4);
        assert.equal(new Set(order).size, 4);
    }
});

test("flat sidebar independently resolves successful failed and shared order definitions", () => {
    const texts = [
        row("COMBAT_FLOW", "{red_bull}{baton}{combat_result}{injury_notice}"),
        row("COMBAT_WIN_FLOW", "{branch}{red_bull}{injury_notice}{combat_result}{baton}"),
        row("COMBAT_LOSS_FLOW", "{branch}{baton}{red_bull}{combat_result}{injury_notice}"),
    ];
    assert.deepEqual(tree.flatOrder(texts, {}, "common"), ["red_bull", "baton", "combat_result", "injury_notice"]);
    assert.deepEqual(tree.flatOrder(texts, {}, "win"), ["red_bull", "injury_notice", "combat_result", "baton"]);
    assert.deepEqual(tree.flatOrder(texts, {}, "loss"), ["baton", "red_bull", "combat_result", "injury_notice"]);
    texts[1].enabled = false;
    assert.deepEqual(tree.flatOrder(texts, {}, "win"), tree.flatOrder(texts));
});

test("flat sidebar uses database fallback order when there are no owner definitions", () => {
    const fallbacks = { COMBAT_FLOW: [row("COMBAT_FLOW", "{baton}{red_bull}{combat_result}{injury_notice}")] };
    assert.deepEqual(tree.flatOrder([], fallbacks, "loss"), ["baton", "red_bull", "combat_result", "injury_notice"]);
});

test("result-specific editing begins at the combat result rather than the stored branch marker", () => {
    const texts = [row("COMBAT_FLOW", "{red_bull}{branch}{baton}{combat_result}{injury_notice}")];
    assert.equal(tree.allowsResultText(texts, {}, "red_bull", "win"), false);
    assert.equal(tree.allowsResultText(texts, {}, "baton", "loss"), false);
    assert.equal(tree.allowsResultText(texts, {}, "injury_notice", "win"), true);
});

test("success and failure may independently place a component before or after their result", () => {
    const texts = [
        row("COMBAT_WIN_FLOW", "{branch}{red_bull}{combat_result}{baton}{injury_notice}"),
        row("COMBAT_LOSS_FLOW", "{branch}{red_bull}{baton}{combat_result}{injury_notice}"),
    ];
    assert.equal(tree.allowsResultText(texts, {}, "baton", "win"), true);
    assert.equal(tree.allowsResultText(texts, {}, "baton", "loss"), false);
    texts[1].template = "{combat_result}{red_bull}{baton}{injury_notice}";
    assert.equal(tree.allowsResultText(texts, {}, "baton", "loss"), true);
});
