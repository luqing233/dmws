package fun.luqing.dmws.economy;

import fun.luqing.dmws.config.EconomyCatalogInitializer;
import fun.luqing.dmws.entity.dmw.economy.EconomyFishSubmission.Status;
import fun.luqing.dmws.repository.dmw.economy.*;
import fun.luqing.dmws.service.dmw.economy.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static fun.luqing.dmws.service.dmw.economy.FishReviewModels.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest @ActiveProfiles("test") @Transactional(transactionManager = "dmwTransactionManager")
class FishingWebEditingTests {
    @Autowired EconomyFishReviewService reviews;
    @Autowired EconomyFishingEventEditor editor;
    @Autowired EconomyFishSubmissionRepository submissions;
    @Autowired EconomyFishRepository fish;
    @Autowired EconomyFishingEventRepository events;
    @Autowired EconomyFishingTextRepository texts;
    @Autowired EconomyCatalogInitializer initializer;
    @Autowired EconomyFishingTextService narration;
    @Autowired fun.luqing.dmws.config.EconomyFishingEventInitializer eventInitializer;
    String uuid() { return UUID.randomUUID().toString(); }
    FishingEventEditorModels.Event proposal(String code, String kind, String min, String max) {
        return new FishingEventEditorModels.Event(code, "网页河灯", kind, "NEGATIVE", 10, min, max, "1", 5, 40, 70, 20, 50, 35, true,
                List.of(new FishingEventEditorModels.Text(null, "ENCOUNTER", "{player}捞起了{event}。", 3, true),
                        new FishingEventEditorModels.Text(null, "DESCRIPTION", "故事中的金额是{change}金币。", 1, true)));
    }
    @Test void eventProposalsStayPendingThenPublishDefinitionsAndOwnerTextsAtomically() {
        long before = events.count(); var event = proposal("web-lantern", "MONEY", "-20", "80");
        var command = new Submit(uuid(), "事件投稿人", null, event);
        var receipt = reviews.submit(command); assertEquals("EVENT", receipt.type()); assertEquals(before, events.count());
        assertEquals(receipt, reviews.submit(command));
        var row = submissions.findByRequestId(receipt.requestId()).orElseThrow();
        var decision = new Decision(uuid(), row.getVersion(), null, "事件通过", event);
        var approved = reviews.decide(row.getId(), true, decision, "管理员");
        assertEquals(Status.APPROVED, approved.status()); assertEquals("web-lantern", approved.publishedEventCode());
        assertEquals(before + 1, events.count()); assertEquals(2, texts.findByOwnerCodeOrderByCodeAsc("web-lantern").size());
        assertEquals(approved, reviews.decide(row.getId(), true, decision, "管理员"));
        assertTrue(reviews.queue("APPROVED", "网页河灯", 0).items().getFirst().reviewedEvent().amountMin().startsWith("-20"));
    }
    @Test void adminCanRenameDisableAndRepriceExistingFishWithoutRecreatingItOnRestart() throws Exception {
        var original = reviews.managedFish().stream().filter(f -> f.fish().name().equals("鲤鱼")).findFirst().orElseThrow();
        long before = fish.count(); var d = original.fish();
        var changed = new Draft("管理员改名鲤鱼", "新介绍{reward}", 999, 500, d.difficulty(), d.dimensionsMin(), d.dimensionsMax(), d.special());
        var command = new FishEdit(uuid(), original.revision(), changed, false, "测试保留ID");
        var result = reviews.editFish(original.id(), command, "管理员");
        assertEquals(original.id(), result.publishedFishId()); assertEquals(original.fish(), result.fish()); assertEquals(changed, result.reviewedFish());
        assertFalse(fish.findById(original.id()).orElseThrow().isEnabled()); assertEquals(999, fish.findById(original.id()).orElseThrow().getLevel());
        assertEquals(result, reviews.editFish(original.id(), command, "管理员"));
        assertThrows(FishReviewException.class, () -> reviews.editFish(original.id(), new FishEdit(uuid(), original.revision(), changed, true, "旧版本覆盖"), "管理员"));
        initializer.run(null); initializer.run(null);
        assertEquals(before, fish.count()); assertFalse(fish.existsByName("鲤鱼"));
        assertEquals("管理员改名鲤鱼", fish.findById(original.id()).orElseThrow().getName());
    }
    @Test void editingCrocodileKeepsLegacyColumnsAndGlobalNarrationWhileAuditingTheChange() {
        var original = editor.entry("crocodile"); var e = original.event(); var legacy = events.findById("crocodile").orElseThrow().getRewardMin();
        var cast = texts.findById("global.cast.1").orElseThrow().getTemplate();
        var lines = new ArrayList<>(e.texts()); lines.add(new FishingEventEditorModels.Text(null, "COMBAT_WIN", "制服{event}，赏金{reward}金币。", 7, true));
        var updated = new FishingEventEditorModels.Event(e.code(), "尼罗鳄·管理员版", e.kind(), e.poolCode(), e.tickets(), "900", "2100", "1", e.jailMinutes(),
                e.strengthMin(), e.strengthMax(), 15, 55, 30, e.enabled(), lines);
        var edit = new FishingEventEditorModels.Edit(uuid(), original.revision(), updated, "调整奖金和战斗文案");
        var result = reviews.editEvent(e.code(), edit, "管理员");
        assertEquals("crocodile", result.sourceEventCode()); assertEquals(original.event(), result.event()); assertEquals("尼罗鳄·管理员版", editor.entry("crocodile").event().name());
        assertEquals(legacy, events.findById("crocodile").orElseThrow().getRewardMin());
        assertEquals(cast, texts.findById("global.cast.1").orElseThrow().getTemplate());
        assertEquals(result, reviews.editEvent(e.code(), edit, "管理员"));
        assertThrows(FishReviewException.class, () -> reviews.editEvent(e.code(), new FishingEventEditorModels.Edit(uuid(), original.revision(), updated, "过期版本"), "管理员"));
    }
    @Test void invalidBudgetsRangesWeightsAndOwnershipAreRejected() {
        assertThrows(FishReviewException.class, () -> editor.validate(proposal("wrong-range", "MONEY", "80", "20")));
        assertThrows(FishReviewException.class, () -> editor.validate(proposal("wrong-battle", "COMBAT", "-1", "2")));
        var e = editor.entry("crocodile"); var d = e.event();
        var tooHeavy = new FishingEventEditorModels.Event(d.code(), d.name(), d.kind(), d.poolCode(), 10000, d.amountMin(), d.amountMax(), d.amountStep(),
                d.jailMinutes(), d.strengthMin(), d.strengthMax(), d.winInjuryPercent(), d.lightPercent(), d.mediumPercent(), d.enabled(), d.texts());
        assertThrows(FishReviewException.class, () -> reviews.editEvent(d.code(), new FishingEventEditorModels.Edit(uuid(), e.revision(), tooHeavy, ""), "管理员"));
        var zero = proposal("zero-text", "MONEY", "0", "0");
        var disabledDescription = new FishingEventEditorModels.Event(zero.code(), zero.name(), zero.kind(), zero.poolCode(), zero.tickets(), zero.amountMin(), zero.amountMax(), zero.amountStep(),
                zero.jailMinutes(), zero.strengthMin(), zero.strengthMax(), zero.winInjuryPercent(), zero.lightPercent(), zero.mediumPercent(), true,
                List.of(new FishingEventEditorModels.Text(null, "DESCRIPTION", "描述", 0, true)));
        assertThrows(FishReviewException.class, () -> editor.validate(disabledDescription));
    }
    @Test void eventPreviewSupportsSignedMoneyCombatAndJailWithoutPublishing() {
        long before = events.count();
        var money = editor.preview(proposal("preview-money", "MONEY", "-20", "80"));
        assertEquals(Set.of("获得金币", "损失金币", "无金币变化"), money.receipts().keySet());
        assertTrue(money.receipts().get("损失金币").contains("-20.00"));
        assertTrue(editor.preview(proposal("preview-combat", "COMBAT", "100", "300")).receipts().get("战斗失败并入院").contains("医疗费出院另结"));
        assertTrue(editor.preview(proposal("preview-jail", "JAIL", "0", "0")).receipts().values().iterator().next().contains("5分钟"));
        assertEquals(before, events.count());
    }

    FishingEventEditorModels.Event customBoostEvent() {
        var e = proposal("web-custom-boost", "COMBAT", "100", "300");
        var lines = new ArrayList<>(e.texts());
        lines.add(new FishingEventEditorModels.Text(null, "BOOST_RED_BULL", "喝掉红牛，勇气加倍！", 1, true));
        lines.add(new FishingEventEditorModels.Text(null, "BOOST_BATON", "挥动电棍，余电{baton_energy}/100。", 1, true));
        return new FishingEventEditorModels.Event(e.code(), e.name(), e.kind(), e.poolCode(), e.tickets(), e.amountMin(), e.amountMax(), e.amountStep(),
                e.jailMinutes(), e.strengthMin(), e.strengthMax(), e.winInjuryPercent(), e.lightPercent(), e.mediumPercent(), e.enabled(), lines);
    }

    @Test void combatPreviewShowsCustomAndFallbackItemTextForActualExampleCombinations() {
        long count = events.count();
        var receipts = editor.preview(customBoostEvent()).receipts();
        assertEquals(12, receipts.size());
        assertFalse(receipts.get("战斗获胜").contains("挥动电棍"));
        assertFalse(receipts.get("战斗获胜（红牛）").contains("挥动电棍"));
        assertTrue(receipts.get("战斗获胜（红牛）").contains("喝掉红牛"));
        assertFalse(receipts.get("战斗获胜（电棍）").contains("喝掉红牛"));
        assertTrue(receipts.get("战斗获胜（电棍）").contains("余电80/100"));
        String both = receipts.get("战斗失败并入院（红牛＋电棍）");
        assertTrue(both.contains("喝掉红牛")); assertTrue(both.contains("余电80/100"));
        assertTrue(both.contains("医疗费出院另结"));
        receipts.values().forEach(receipt -> { assertEquals(4, receipt.lines().count()); assertFalse(receipt.contains("{baton_energy}")); });
        assertTrue(editor.preview(proposal("fallback-boost", "COMBAT", "100", "300")).receipts().get("战斗获胜（电棍）").contains("本次电棍耗电20，剩余80/100"));
        assertEquals(count, events.count());
    }

    @Test void publishedCustomBoostTextUsesOnlyConsumedItemsAndRealRemainingBattery() {
        var event = customBoostEvent();
        var receipt = reviews.submit(new Submit(uuid(), "道具文案投稿", null, event));
        var row = submissions.findByRequestId(receipt.requestId()).orElseThrow();
        reviews.decide(row.getId(), true, new Decision(uuid(), row.getVersion(), null, "通过", event), "管理员");
        var definition = events.findById(event.code()).orElseThrow();
        var zero = java.math.BigDecimal.ZERO;
        var result = new EconomyFishingService.FishingResult(true, event.name(), zero, new java.math.BigDecimal("300"), zero,
                zero, 10, false, "战斗描述", EconomyFishingRules.rodMultiplier(10));
        for (int boosts = 0; boosts < 4; boosts++) {
            boolean redBull = (boosts & 1) != 0, baton = (boosts & 2) != 0;
            var formatted = narration.format(result, "钓鱼人", "custom-item-text", new EconomyFishingTextService.Narration(
                    definition, null, null, null, zero, 0, redBull, baton ? 37 : null));
            String text = formatted.receiptText();
            assertEquals(redBull, text.contains("喝掉红牛"));
            assertEquals(baton, text.contains("挥动电棍，余电37/100"));
            assertEquals(4, text.lines().count());
            assertFalse(text.contains("{baton_energy}"));
        }
    }

    @Test void previewOnlyOffersScenesForItsEventKindAndIncludesDatabaseFallbackTemplates() {
        var money = editor.preview(proposal("tree-money", "MONEY", "-20", "80"));
        assertFalse(money.samples().containsKey("BOOST_BATON"));
        assertFalse(money.fallbacks().containsKey("BOOST_RED_BULL"));
        assertTrue(money.samples().get("MONEY_LOSS").contains("20.00"));
        var combat = editor.preview(customBoostEvent());
        assertTrue(combat.samples().get("BOOST_BATON").contains("余电80/100"));
        assertTrue(combat.fallbacks().get("BOOST_BATON").getFirst().template().contains("{baton_energy}"));
        assertFalse(combat.fallbacks().containsKey("MONEY_GAIN"));
        var jail = editor.preview(proposal("tree-jail", "JAIL", "0", "0"));
        assertTrue(jail.samples().get("JAIL").contains("5分钟"));
        assertFalse(jail.samples().containsKey("COMBAT_WIN"));
        assertFalse(jail.fallbacks().containsKey("BOOST_BATON"));
        assertTrue(jail.fallbacks().containsKey("ENCOUNTER"));
    }

    @Test void unmodifiedBranchesInheritDatabaseDefaultsWithoutPublishingCopiedOwnerRows() throws Exception {
        var e = proposal("inherited-defaults", "MONEY", "20", "80");
        var inherited = new FishingEventEditorModels.Event(e.code(), e.name(), e.kind(), e.poolCode(), e.tickets(), e.amountMin(), e.amountMax(), e.amountStep(),
                e.jailMinutes(), e.strengthMin(), e.strengthMax(), e.winInjuryPercent(), e.lightPercent(), e.mediumPercent(), e.enabled(), List.of());
        var submitted = reviews.submit(new Submit(uuid(), "默认文案投稿", null, inherited));
        var row = submissions.findByRequestId(submitted.requestId()).orElseThrow();
        reviews.decide(row.getId(), true, new Decision(uuid(), row.getVersion(), null, "沿用默认", inherited), "管理员");
        assertTrue(texts.findByOwnerCodeOrderByCodeAsc(e.code()).isEmpty());
        eventInitializer.run(null); eventInitializer.run(null);
        assertTrue(texts.findByOwnerCodeOrderByCodeAsc(e.code()).isEmpty(), "Restart must preserve inherited narration");
        var preview = editor.preview(editor.entry(e.code()).event());
        assertTrue(preview.receipts().get("获得金币").contains("80.00"));
        String previous = preview.fallbacks().get("MONEY_GAIN").getFirst().template();
        var global = texts.findByOwnerCodeAndSceneAndEnabledTrueOrderByCodeAsc("*", "MONEY_GAIN").getFirst();
        global.setTemplate("新版默认提示：收入{reward}金币。");
        var refreshed = editor.preview(editor.entry(e.code()).event());
        assertNotEquals(previous, refreshed.fallbacks().get("MONEY_GAIN").getFirst().template());
        assertTrue(refreshed.receipts().get("获得金币").contains("新版默认提示：收入80.00金币。"));
    }

    FishingEventEditorModels.Event orderedCombat() {
        var e = customBoostEvent(); var lines = new ArrayList<>(e.texts());
        lines.add(new FishingEventEditorModels.Text(null, "COMBAT_FLOW", "{red_bull}{baton}{combat_result}{injury_notice}", 1, true));
        return new FishingEventEditorModels.Event(e.code(), e.name(), e.kind(), e.poolCode(), e.tickets(), e.amountMin(), e.amountMax(), e.amountStep(),
                e.jailMinutes(), e.strengthMin(), e.strengthMax(), e.winInjuryPercent(), e.lightPercent(), e.mediumPercent(), e.enabled(), lines);
    }

    @Test void orderedCombatPreviewPlacesActualItemNarrationBeforeEitherResult() {
        var receipts = editor.preview(orderedCombat()).receipts();
        for (String key : List.of("战斗获胜（红牛＋电棍）", "战斗失败并入院（红牛＋电棍）")) {
            String receipt = receipts.get(key);
            int redBull = receipt.indexOf("喝掉红牛"), baton = receipt.indexOf("挥动电棍"), result = receipt.indexOf(key.startsWith("战斗获胜") ? "你成功制服" : "你没能制服");
            assertTrue(redBull >= 0 && redBull < baton && baton < result, receipt);
            assertEquals(4, receipt.lines().count());
        }
        assertFalse(receipts.get("战斗获胜").contains("喝掉红牛"));
        assertFalse(receipts.get("战斗获胜").contains("挥动电棍"));
    }

    @Test void publishedOrderSurvivesInitializationAndActualNarrationSkipsUnconsumedParts() throws Exception {
        var event = orderedCombat(); var submitted = reviews.submit(new Submit(uuid(), "顺序投稿", null, event));
        var row = submissions.findByRequestId(submitted.requestId()).orElseThrow();
        reviews.decide(row.getId(), true, new Decision(uuid(), row.getVersion(), null, "通过", event), "管理员");
        eventInitializer.run(null);
        assertEquals("{red_bull}{baton}{combat_result}{injury_notice}", editor.entry(event.code()).event().texts().stream().filter(t -> t.scene().equals("COMBAT_FLOW")).findFirst().orElseThrow().template());
        var zero = java.math.BigDecimal.ZERO; var definition = events.findById(event.code()).orElseThrow();
        for (boolean win : List.of(false, true)) for (int boosts = 0; boosts < 4; boosts++) {
            boolean redBull = (boosts & 1) != 0, baton = (boosts & 2) != 0;
            var result = new EconomyFishingService.FishingResult(win, event.name(), zero, win ? new java.math.BigDecimal("300") : zero, zero, zero, 10, false, "", EconomyFishingRules.rodMultiplier(10));
            String text = narration.format(result, "钓鱼人", "ordered-items", new EconomyFishingTextService.Narration(definition, null, null, null, zero, win ? 0 : 40, redBull, baton ? 17 : null)).receiptText();
            int outcome = text.indexOf(win ? "你成功制服" : "你没能制服");
            assertEquals(redBull, text.contains("喝掉红牛")); assertEquals(baton, text.contains("余电17/100"));
            if (redBull) assertTrue(text.indexOf("喝掉红牛") < outcome, text);
            if (baton) assertTrue(text.indexOf("挥动电棍") < outcome, text);
            if (!win) assertTrue(text.indexOf("医疗费出院另结") > outcome, text);
            assertEquals(4, text.lines().count()); assertFalse(text.contains("{combat_result}"));
        }
    }

    @Test void invalidOrderCannotDropOrDuplicateResultsItemsOrInjuryNotices() {
        assertTrue(EconomyFishingText.validCombatFlow("{red_bull}{baton}{combat_result}{injury_notice}"));
        for (String template : List.of("{red_bull}{combat_result}{injury_notice}", "{red_bull}{baton}{combat_result}{combat_result}{injury_notice}", "{red_bull}{baton}{combat_result}{injury_notice}{unknown}")) {
            assertFalse(EconomyFishingText.validCombatFlow(template));
            var e = orderedCombat(); var lines = new ArrayList<>(e.texts());
            lines.removeIf(t -> t.scene().equals("COMBAT_FLOW")); lines.add(new FishingEventEditorModels.Text(null, "COMBAT_FLOW", template, 1, true));
            var invalid = new FishingEventEditorModels.Event(e.code(), e.name(), e.kind(), e.poolCode(), e.tickets(), e.amountMin(), e.amountMax(), e.amountStep(), e.jailMinutes(), e.strengthMin(), e.strengthMax(), e.winInjuryPercent(), e.lightPercent(), e.mediumPercent(), e.enabled(), lines);
            assertThrows(FishReviewException.class, () -> editor.validate(invalid));
        }
    }

    FishingEventEditorModels.Event binaryCombat() {
        var e = customBoostEvent(); var lines = new ArrayList<>(e.texts());
        lines.add(new FishingEventEditorModels.Text(null, "COMBAT_WIN_FLOW", "{red_bull}{branch}{combat_result}{baton}{injury_notice}", 1, true));
        lines.add(new FishingEventEditorModels.Text(null, "COMBAT_LOSS_FLOW", "{red_bull}{branch}{baton}{combat_result}{injury_notice}", 1, true));
        lines.add(new FishingEventEditorModels.Text(null, "BOOST_RED_BULL_WIN", "成功专属红牛。", 1, true));
        lines.add(new FishingEventEditorModels.Text(null, "BOOST_BATON_WIN", "成功电棍余电{baton_energy}。", 1, true));
        lines.add(new FishingEventEditorModels.Text(null, "BOOST_BATON_LOSS", "失败电棍余电{baton_energy}。", 1, true));
        lines.add(new FishingEventEditorModels.Text(null, "INJURY_WIN", "赢了也住院：{injury}（{injury_score}），医疗费出院另结。", 1, true));
        lines.add(new FishingEventEditorModels.Text(null, "INJURY_LOSS", "输了住院：{injury}（{injury_score}），医疗费出院另结。", 1, true));
        return new FishingEventEditorModels.Event(e.code(), e.name(), e.kind(), e.poolCode(), e.tickets(), e.amountMin(), e.amountMax(), e.amountStep(), e.jailMinutes(), e.strengthMin(), e.strengthMax(), e.winInjuryPercent(), e.lightPercent(), e.mediumPercent(), e.enabled(), lines);
    }

    @Test void binaryPreviewSeparatesWinHospitalLossHospitalAndSharedPrefix() {
        var preview = editor.preview(binaryCombat()).receipts();
        String win = preview.get("战斗获胜并入院（红牛＋电棍）"), loss = preview.get("战斗失败并入院（红牛＋电棍）");
        assertTrue(win.contains("赢了也住院：轻伤（1）"), win); assertFalse(win.contains("输了住院"));
        assertTrue(loss.contains("输了住院：中伤（40）"), loss); assertFalse(loss.contains("赢了也住院"));
        assertTrue(win.indexOf("你成功制服") < win.indexOf("成功电棍"), win);
        assertTrue(loss.indexOf("挥动电棍") < loss.indexOf("你没能制服"), loss);
        assertFalse(loss.contains("失败电棍"));
        assertFalse(win.contains("成功专属红牛")); assertTrue(win.contains("喝掉红牛"));
        assertFalse(preview.get("战斗获胜").contains("住院"));
    }

    @Test void actualBinaryNarrationUsesOnlyItsOwnBranchAndActualHospitalState() {
        var event = binaryCombat(); var submitted = reviews.submit(new Submit(uuid(), "二叉树投稿", null, event));
        var row = submissions.findByRequestId(submitted.requestId()).orElseThrow();
        reviews.decide(row.getId(), true, new Decision(uuid(), row.getVersion(), null, "通过", event), "管理员");
        var zero = java.math.BigDecimal.ZERO; var definition = events.findById(event.code()).orElseThrow();
        for (boolean win : List.of(false, true)) for (int injury : List.of(0, 1, 75)) {
            var result = new EconomyFishingService.FishingResult(win, event.name(), zero, zero, zero, zero, 10, false, "", EconomyFishingRules.rodMultiplier(10));
            String text = narration.format(result, "钓鱼人", "binary-hospital", new EconomyFishingTextService.Narration(definition, null, null, null, zero, injury, true, 37)).receiptText();
            assertTrue(text.contains(win ? "成功电棍余电37" : "挥动电棍，余电37/100"), text);
            assertFalse(text.contains("失败电棍")); if (!win) assertFalse(text.contains("成功电棍"));
            assertEquals(injury > 0, text.contains(win ? "赢了也住院" : "输了住院"));
            assertFalse(text.contains(win ? "输了住院" : "赢了也住院"));
            assertFalse(text.contains("成功专属红牛")); assertTrue(text.contains("喝掉红牛"));
            assertEquals(4, text.lines().count()); assertFalse(text.contains("{branch}"));
        }
        texts.findByOwnerCodeOrderByCodeAsc(event.code()).stream().filter(t -> t.getScene().equals("INJURY_WIN")).forEach(t -> t.setEnabled(false));
        assertFalse(editor.preview(editor.entry(event.code()).event()).receipts().get("战斗获胜并入院").contains("赢了也住院"));
    }

    @Test void synchronizedSidebarOrderPersistsAndDisablesPriorPathOrdersWithoutLosingTheirText() throws Exception {
        var proposal = binaryCombat();
        var submitted = reviews.submit(new Submit(uuid(), "侧栏排序投稿", null, proposal));
        var pending = submissions.findByRequestId(submitted.requestId()).orElseThrow();
        reviews.decide(pending.getId(), true, new Decision(uuid(), pending.getVersion(), null, "通过", proposal), "管理员");
        var original = editor.entry(proposal.code()); var e = original.event();
        var updatedTexts = new ArrayList<>(e.texts().stream().map(t -> new FishingEventEditorModels.Text(t.code(), t.scene(), t.template(), t.weight(),
                List.of("COMBAT_WIN_FLOW", "COMBAT_LOSS_FLOW").contains(t.scene()) ? false : t.enabled())).toList());
        updatedTexts.add(new FishingEventEditorModels.Text(null, "COMBAT_FLOW", "{red_bull}{baton}{combat_result}{injury_notice}", 1, true));
        var updated = new FishingEventEditorModels.Event(e.code(), e.name(), e.kind(), e.poolCode(), e.tickets(), e.amountMin(), e.amountMax(), e.amountStep(),
                e.jailMinutes(), e.strengthMin(), e.strengthMax(), e.winInjuryPercent(), e.lightPercent(), e.mediumPercent(), e.enabled(), updatedTexts);
        reviews.editEvent(e.code(), new FishingEventEditorModels.Edit(uuid(), original.revision(), updated, "侧栏同步排序"), "管理员");
        eventInitializer.run(null);
        var saved = editor.entry(e.code()).event();
        assertEquals(2, saved.texts().stream().filter(t -> List.of("COMBAT_WIN_FLOW", "COMBAT_LOSS_FLOW").contains(t.scene()) && !t.enabled()).count());
        assertTrue(saved.texts().stream().anyMatch(t -> t.scene().equals("BOOST_BATON_LOSS") && t.enabled() && t.template().contains("失败电棍")));
        for (String key : List.of("战斗获胜（红牛＋电棍）", "战斗失败并入院（红牛＋电棍）")) {
            String receipt = editor.preview(saved).receipts().get(key);
            int baton = receipt.indexOf("挥动电棍");
            int outcome = receipt.indexOf(key.startsWith("战斗获胜") ? "你成功制服" : "你没能制服");
            assertTrue(baton >= 0 && baton < outcome, receipt); assertEquals(4, receipt.lines().count());
            assertFalse(receipt.contains("成功电棍")); assertFalse(receipt.contains("失败电棍"));
        }
        var current = editor.entry(e.code()); var c = current.event();
        var moved = c.texts().stream().map(t -> t.scene().equals("COMBAT_FLOW") ? new FishingEventEditorModels.Text(t.code(), t.scene(), "{red_bull}{combat_result}{baton}{injury_notice}", t.weight(), t.enabled()) : t).toList();
        var movedEvent = new FishingEventEditorModels.Event(c.code(), c.name(), c.kind(), c.poolCode(), c.tickets(), c.amountMin(), c.amountMax(), c.amountStep(), c.jailMinutes(), c.strengthMin(), c.strengthMax(), c.winInjuryPercent(), c.lightPercent(), c.mediumPercent(), c.enabled(), moved);
        reviews.editEvent(c.code(), new FishingEventEditorModels.Edit(uuid(), current.revision(), movedEvent, "电棍移到结果之后"), "管理员");
        eventInitializer.run(null);
        var restored = editor.preview(editor.entry(c.code()).event()).receipts();
        assertTrue(restored.get("战斗获胜（红牛＋电棍）").contains("成功电棍余电80"));
        assertTrue(restored.get("战斗失败并入院（红牛＋电棍）").contains("失败电棍余电80"));
    }

    @Test void onlyTheActualCombatResultPositionSplitsCommonAndResultSpecificText() {
        String before = "{red_bull}{branch}{baton}{combat_result}{injury_notice}";
        assertEquals("BOOST_RED_BULL", EconomyFishingText.combatPartScene(before, "red_bull", true));
        assertEquals("BOOST_BATON", EconomyFishingText.combatPartScene(before, "baton", false));
        assertEquals("INJURY_WIN", EconomyFishingText.combatPartScene(before, "injury_notice", true));
        String after = "{combat_result}{red_bull}{baton}{injury_notice}";
        assertEquals("BOOST_RED_BULL_WIN", EconomyFishingText.combatPartScene(after, "red_bull", true));
        assertEquals("BOOST_BATON_LOSS", EconomyFishingText.combatPartScene(after, "baton", false));
    }
}
