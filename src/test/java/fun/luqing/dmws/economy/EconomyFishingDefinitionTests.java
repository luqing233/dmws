package fun.luqing.dmws.economy;

import fun.luqing.dmws.common.economy.EconomyException;
import fun.luqing.dmws.config.EconomyFishingEventInitializer;
import fun.luqing.dmws.entity.dmw.economy.*;
import fun.luqing.dmws.enums.EconomyAccountScope;
import fun.luqing.dmws.repository.dmw.economy.*;
import fun.luqing.dmws.service.dmw.economy.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional(transactionManager = "dmwTransactionManager")
class EconomyFishingDefinitionTests {
    static final long USER = 9934001;
    static final String SEED = "12".repeat(32);
    @Autowired EconomyFishingService fishing;
    @Autowired EconomyFishingEventRepository events;
    @Autowired EconomyFishingTextRepository rows;
    @Autowired EconomyFishingTextService texts;
    @Autowired EconomyFishingEventInitializer initializer;
    @Autowired EconomyFishingCastRepository casts;
    @Autowired EconomyFishingAttemptRepository attempts;
    @Autowired EconomyUserProfileService profiles;
    @Autowired EconomyAdminService admin;
    @Autowired EconomyItemService items;
    @Autowired EconomyLedgerService ledger;
    @Autowired EconomyFishingQueue queue;
    @Autowired @Qualifier("dmwDataSource") DataSource dataSource;
    @MockitoBean EconomyFishingRandom random;
    @MockitoBean EconomyGameTime time;

    @BeforeEach void setup() {
        var now = LocalDateTime.of(2026, 11, 14, 12, 0);
        when(time.now()).thenReturn(now); when(time.today()).thenReturn(now.toLocalDate());
    }

    void seed() {
        admin.adjust(USER, USER, "钓鱼人", new BigDecimal("1000"), true, "definition-seed");
        profiles.getOrCreateForUpdate(USER, "钓鱼人").setFishingRodPurchased(true);
        items.addItem(USER, "fishing-bait-white", 1);
    }

    EconomyFishingEvent only(String code) {
        events.findAll().forEach(e -> e.setTickets(e.getCode().equals(code) ? 10000 : 0));
        return events.findById(code).orElseThrow();
    }

    void text(String code, String owner, String scene, String template, int weight) {
        var row = new EconomyFishingTextTemplate();
        row.setCode(code); row.setOwnerCode(owner); row.setScene(scene);
        row.setTemplate(template); row.setWeight(weight); rows.saveAndFlush(row);
    }

    @ParameterizedTest
    @CsvSource({"0,-0.02", "1,-0.01", "2,0.00", "3,0.01", "4,0.02"})
    void signedCentRangeIncludesBothEndpointsAndZero(long draw, String expected) {
        seed(); var event = only("boots");
        event.setAmountMin(new BigDecimal("-0.02")); event.setAmountMax(new BigDecimal("0.02"));
        event.setAmountStep(new BigDecimal("0.01")); when(random.weight(5)).thenReturn(draw);
        text("cent-gain", "boots", "MONEY_GAIN", "获得{reward}，变化{change}，净额{net}", 1);
        text("cent-loss", "boots", "MONEY_LOSS", "损失{penalty}，变化{change}，净额{net}", 1);
        text("cent-zero", "boots", "MONEY_ZERO", "恰好为零{amount}", 1);
        var result = FishingTestActions.fish(fishing, USER, "钓鱼人", "signed-cent");
        var amount = new BigDecimal(expected);
        assertEquals(amount, result.netProfit());
        assertEquals(new BigDecimal("1000.00").add(amount), ledger.balance(USER, EconomyAccountScope.WALLET).balance());
        assertEquals(amount.max(BigDecimal.ZERO).setScale(2), result.reward().setScale(2));
        assertEquals(amount.negate().max(BigDecimal.ZERO).setScale(2), result.penalty().setScale(2));
        assertEquals(result.receiptText(), attempts.findById("signed-cent").orElseThrow().getReceipt());
        assertEquals(4, result.receiptText().lines().count());
        assertTrue(result.receiptText().contains(amount.signum() > 0 ? "获得" : amount.signum() < 0 ? "损失" : "恰好为零"));
    }

    @ParameterizedTest
    @CsvSource({"2,1,1", "0,1,0", "0,1,-1", "0,1,0.03", "0,10000001,1"})
    void malformedRangesAreRejected(String minimum, String maximum, String step) {
        seed(); var event = only("boots");
        event.setAmountMin(new BigDecimal(minimum)); event.setAmountMax(new BigDecimal(maximum));
        event.setAmountStep(new BigDecimal(step));
        var error = assertThrows(EconomyException.class,
                () -> FishingTestActions.fish(fishing, USER, "钓鱼人", "invalid-range"));
        assertEquals("INVALID_EVENT", error.getCode());
        assertFalse(attempts.existsById("invalid-range"));
    }

    @ParameterizedTest
    @CsvSource({"FINE,MONEY,-7.25,-7.25,NEGATIVE", "FUN,MONEY,12.00,24.00,FUN",
            "REWARD,MONEY,12.00,24.00,REWARD", "TEXT,MONEY,0.00,0.00,FUN",
            "CROCODILE,COMBAT,12.00,24.00,COMBAT", "COMBAT,COMBAT,12.00,24.00,COMBAT",
            "JAIL,JAIL,0.00,0.00,NEGATIVE"})
    void legacyDefinitionsAndCustomTemplatesMigrateOnce(String old, String kind, String min, String max, String pool) throws Exception {
        var event = new EconomyFishingEvent();
        event.setCode("legacy-test"); event.setKind(old); event.setName("旧奇遇");
        event.setDescription("{player}收获{reward}，{unknown}保持原样。");
        event.setEncounterTemplate("旧开场{fish}"); event.setPenalty(new BigDecimal("7.25"));
        event.setRewardMin(new BigDecimal("12")); event.setRewardMax(new BigDecimal("24"));
        event.setTickets(17); event.setEnabled(false); event = events.saveAndFlush(event);
        text("custom-legacy", event.getCode(), "ENCOUNTER", "管理员修改的开场", 7);
        rows.findById("custom-legacy").orElseThrow().setEnabled(false);
        var historical = new EconomyFishingAttempt(); historical.setOperationId("unchanged-history");
        historical.setUserId(USER); historical.setEventCode(old); historical.setReceipt("旧回执"); attempts.save(historical);
        long count = rows.count();
        initializer.run(null); initializer.run(null);
        assertEquals(kind, event.getKind()); assertEquals(old, event.getLegacyKind()); assertEquals(pool, event.getPoolCode());
        assertEquals(0, new BigDecimal(min).compareTo(event.getAmountMin()));
        assertEquals(0, new BigDecimal(max).compareTo(event.getAmountMax()));
        assertEquals(old.equals("FINE") ? new BigDecimal("0.01") : BigDecimal.ONE, event.getAmountStep());
        assertEquals(17, event.getTickets()); assertFalse(event.isEnabled());
        assertEquals(new BigDecimal("7.25"), event.getPenalty());
        assertEquals(count + 1, rows.count());
        var custom = rows.findById("custom-legacy").orElseThrow();
        assertFalse(custom.isEnabled()); assertEquals(7, custom.getWeight()); assertEquals("管理员修改的开场", custom.getTemplate());
        assertEquals(event.getDescription(), rows.findById("migrated.legacy-test.description").orElseThrow().getTemplate());
        assertEquals("旧回执", attempts.findById("unchanged-history").orElseThrow().getReceipt());
        assertEquals(old, attempts.findById("unchanged-history").orElseThrow().getEventCode());
    }

    @Test void canonicalSignedDefinitionsAreNotRemigrated() throws Exception {
        var event = events.findById("boots").orElseThrow();
        event.setAmountMin(new BigDecimal("-0.05")); event.setAmountMax(new BigDecimal("0.10"));
        event.setAmountStep(new BigDecimal("0.05"));
        initializer.run(null); initializer.run(null);
        assertEquals(new BigDecimal("-0.05"), event.getAmountMin()); assertEquals(new BigDecimal("0.05"), event.getAmountStep());
        assertEquals(java.util.Set.of("MONEY", "COMBAT", "JAIL"), events.findAll().stream()
                .map(EconomyFishingEvent::getKind).collect(java.util.stream.Collectors.toSet()));
    }

    @Test void templateDrawsRespectWeightsOverridesAndDisabledRowsWithoutUsingGameplayRandom() {
        text("weighted-a", "test", "MONEY_GAIN", "A", 1);
        text("weighted-b", "test", "MONEY_GAIN", "B", 9);
        text("weighted-zero", "test", "MONEY_GAIN", "ZERO", 0);
        int a = 0, b = 0;
        for (int i = 0; i < 200; i++) {
            String seed = String.format("%064x", i);
            String value = texts.render("test", "MONEY_GAIN", Map.of(), seed);
            assertEquals(value, texts.render("test", "MONEY_GAIN", Map.of(), seed));
            if (value.equals("A")) a++; else if (value.equals("B")) b++; else fail(value);
        }
        assertTrue(a > 0); assertTrue(b > a * 3);
        rows.findById("weighted-b").orElseThrow().setEnabled(false);
        assertEquals("A", texts.render("test", "MONEY_GAIN", Map.of(), SEED));
        rows.findById("weighted-a").orElseThrow().setEnabled(false);
        assertThrows(EconomyException.class, () -> texts.render("test", "MONEY_GAIN", Map.of(), SEED));
        rows.findById("weighted-zero").orElseThrow().setEnabled(false);
        assertEquals("发现藏着的金币，获得12金币。", texts.render("test", "MONEY_GAIN", Map.of("reward", "12"), SEED));
        verifyNoInteractions(random);
    }

    @Test void placeholdersAreLiteralSinglePassAndReceiptLayoutIsValidated() {
        assertEquals("船长$1{reward} / 5.00 / {unknown}", EconomyFishingText.render("{player} / {reward} / {unknown}",
                Map.of("player", "船长$1{reward}", "reward", "5.00")));
        seed(); only("boots");
        text("bad-receipt", "boots", "RECEIPT", "我拿到了{net}", 1);
        assertThrows(EconomyException.class, () -> FishingTestActions.fish(fishing, USER, "玩家", "bad-layout"));
        assertFalse(attempts.existsById("bad-layout"));
    }

    @Test void narrationChangesDoNotAdvanceTheEconomicRandomStream() {
        var actual = new EconomyFishingRandom();
        long expected = EconomyFishingRandom.withSeed(SEED, () -> actual.weight(100000));
        long afterNarration = EconomyFishingRandom.withSeed(SEED, () -> {
            texts.message("CAST", Map.of(), SEED);
            texts.message("MONEY_GAIN", Map.of("reward", "12"), SEED);
            return actual.weight(100000);
        });
        assertEquals(expected, afterNarration);
        rows.findById("global.cast.1").orElseThrow().setWeight(1000);
        assertEquals(expected, EconomyFishingRandom.withSeed(SEED, () -> {
            texts.message("CAST", Map.of(), SEED);
            return actual.weight(100000);
        }));
    }

    @Test void customCombatLossAndInjuryTemplatesUseActualSettlementFields() {
        seed(); only("crocodile"); when(random.chance(anyDouble())).thenReturn(false);
        text("custom-combat-loss", "crocodile", "COMBAT_LOSS", "{player}败给了{event}。", 1);
        rows.findById("crocodile.injury.1").orElseThrow().setEnabled(false);
        text("custom-injury", "crocodile", "INJURY", "伤势{injury}，分值{injury_score}；医疗另结。", 1);
        var result = FishingTestActions.fish(fishing, USER, "船长$1{reward}", "custom-combat-loss-operation");
        assertTrue(result.receiptText().contains("船长$1{reward}败给了尼罗鳄。伤势轻伤，分值1；医疗另结。"));
        assertFalse(result.receiptText().contains("{injury}"));
    }

    @Test void negativeTemplateWeightsReportTheDatabaseConfiguredError() {
        text("bad-weight", "test", "ENCOUNTER", "坏权重", -1);
        rows.findById("global.invalid_text.1").orElseThrow().setTemplate("文案需要维护。");
        assertEquals("文案需要维护。", assertThrows(EconomyException.class,
                () -> texts.render("test", "ENCOUNTER", Map.of(), SEED)).getMessage());
        rows.findById("global.invalid_text.1").orElseThrow().setWeight(-1);
        assertTrue(assertThrows(EconomyException.class,
                () -> texts.render("test", "ENCOUNTER", Map.of(), SEED)).getMessage().contains("文案配置无效"));
    }

    @Test void castBusyAndQueueMessagesComeFromDatabaseAndCastPromptIsSaved() {
        seed(); rows.findByOwnerCodeAndSceneAndEnabledTrueOrderByCodeAsc("*", "CAST").forEach(r -> r.setEnabled(false));
        text("custom-cast", "*", "CAST", "{player}抛出了鱼线。", 1);
        rows.findById("global.busy.1").orElseThrow().setTemplate("鱼线还在水里。");
        rows.findById("global.waiting.1").orElseThrow().setTemplate("请等收竿。");
        var ticket = fishing.cast(USER, "新钓友", "custom-cast-operation", 0, null, null, null, null);
        assertEquals("新钓友抛出了鱼线。", ticket.message());
        assertEquals(ticket.message(), casts.findById("custom-cast-operation").orElseThrow().getCastReceipt());
        assertEquals("请等收竿。", queue.status(USER));
        assertEquals("鱼线还在水里。", assertThrows(EconomyException.class,
                () -> fishing.cast(USER, "新钓友", "busy-operation", 0, null, null, null, null)).getMessage());
    }

    @Test void documentedSqlExamplesExecuteTwiceAndAllThreeKindsSettleWithCustomText() throws Exception {
        String sql = Files.readString(Path.of("scripts/fishing_event_examples.sql"), StandardCharsets.UTF_8)
                .replace("START TRANSACTION;", "").replace("COMMIT;", "");
        var resource = new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8));
        var connection = DataSourceUtils.getConnection(dataSource);
        try { ScriptUtils.executeSqlScript(connection, resource); ScriptUtils.executeSqlScript(connection, resource); }
        finally { DataSourceUtils.releaseConnection(connection, dataSource); }
        seed(); items.addItem(USER, "fishing-bait-red", 1);
        only("river-purse"); when(random.weight(101)).thenReturn(100L);
        var money = FishingTestActions.fish(fishing, USER, "玩家", "example-money", null, null, "红");
        assertEquals(new BigDecimal("80.00"), money.reward()); assertTrue(money.receiptText().contains("你清点了钱袋"));
        only("river-crab"); when(random.chance(anyDouble())).thenReturn(true, false);
        var combat = FishingTestActions.fish(fishing, USER, "玩家", "example-combat");
        assertEquals(new BigDecimal("100.00"), combat.reward()); assertTrue(combat.receiptText().contains("你制服了巨型河蟹"));
        only("river-evidence"); var jail = FishingTestActions.fish(fishing, USER, "玩家", "example-jail");
        assertTrue(jail.receiptText().contains("配合调查5分钟")); assertEquals(new BigDecimal("0.00"), jail.netProfit());
    }
}
