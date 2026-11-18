package fun.luqing.dmws.economy;

import fun.luqing.dmws.entity.dmw.economy.EconomyFishingAttempt;
import fun.luqing.dmws.repository.dmw.economy.EconomyFishingAttemptRepository;
import fun.luqing.dmws.repository.dmw.economy.EconomyFishRepository;
import fun.luqing.dmws.service.dmw.economy.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional(transactionManager = "dmwTransactionManager")
class EconomyFishingNarrationTests {
    private static final long PLAYER = 9932001;
    private static final long GROUP = 9932;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 13, 18, 0);
    @Autowired EconomyFishingService fishing;
    @Autowired EconomyFishingAttemptRepository attempts;
    @Autowired fun.luqing.dmws.repository.dmw.economy.EconomyFishingTextRepository textRows;
    @Autowired EconomyFishRepository fish;
    @Autowired EconomyUserProfileService profiles;
    @Autowired EconomyAdminService admin;
    @MockitoBean EconomyFishingRandom random;
    @MockitoBean EconomyGameTime time;

    @BeforeEach
    void setup() {
        when(time.now()).thenReturn(NOW);
        when(time.today()).thenReturn(NOW.toLocalDate());
        when(random.weight(10000)).thenReturn(9999L);
        admin.adjust(PLAYER, PLAYER, "本人", new BigDecimal("1000"), true, "narration-fund");
        profiles.getOrCreateForUpdate(PLAYER, "本人").setFishingRodPurchased(true);
    }

    private void prior(String operation, long player, long group, LocalDateTime at, String name) {
        profiles.getOrCreateForUpdate(player, name);
        var row = new EconomyFishingAttempt();
        row.setOperationId(operation);
        row.setUserId(player);
        row.setGroupId(group);
        row.setOccurredAt(at);
        row.setEventCode("ESCAPED");
        attempts.saveAndFlush(row);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void lastOtherFisherInThisGroupProvidesAQuoteInsideTheFishValueRange(int variant) {
        prior("older-other", 9932002, GROUP, NOW.minusHours(2), "旧钓友");
        prior("latest-other", 9932003, GROUP, NOW.minusMinutes(1), "最近的钓友");
        prior("own-attempt", PLAYER, GROUP, NOW, "本人");
        prior("other-group", 9932004, GROUP + 1, NOW, "其他群钓友");
        prior("future-attempt", 9932005, GROUP, NOW.plusMinutes(2), "未来钓友");
        when(random.weight(501)).thenReturn(250L);
        for (int i = 1; i <= 2; i++) textRows.findById("global.escape_estimate." + i).orElseThrow().setEnabled(i == variant + 1);
        var result = FishingTestActions.fish(fishing, PLAYER, "本人", "narration-cast", GROUP, "鱼塘");
        String expected = variant == 0 ? "附近的钓鱼佬最近的钓友说，这条鱼至少值10.00金币。"
                : "这条鱼起码值10.00金币，钓鱼佬最近的钓友如是说。";
        String receipt = EconomyFishingText.receipt(result);
        assertTrue(receipt.contains(expected), receipt);
        assertEquals(4, receipt.lines().count());
        assertEquals(receipt, attempts.findById("narration-cast").orElseThrow().getReceipt());
        assertFalse(receipt.contains("情报人员"));
        assertFalse(receipt.contains("其他群钓友"));
        assertFalse(receipt.contains("未来钓友"));
    }

    @Test
    void onlySelfOrFishersOutsideTheTwelveHourWindowUseTheNoWitnessLine() {
        prior("own-only", PLAYER, GROUP, NOW.minusMinutes(1), "本人");
        prior("expired-other", 9932006, GROUP, NOW.minusHours(12).minusSeconds(1), "昨天的钓友");
        var result = FishingTestActions.fish(fishing, PLAYER, "本人", "no-witness", GROUP, "鱼塘");
        assertTrue(EconomyFishingText.receipt(result).contains("你四周张望，应该没有人看到吧"));
        verify(random, never()).weight(2);
    }

    @Test
    void twelveHourBoundaryAndFixedSizeQuoteAreSupported() {
        textRows.findById("global.escape_estimate.2").orElseThrow().setEnabled(false);
        var ticket = fishing.cast(PLAYER, "本人", "fixed-quote", 0, GROUP, "鱼塘", null, null);
        prior("boundary-other", 9932007, GROUP, ticket.dueAt().minusHours(12), "边界钓友");
        var carp = fish.findAll().stream().filter(row -> row.getName().equals("鲤鱼")).findFirst().orElseThrow();
        carp.setDimensionsMin(32); carp.setDimensionsMax(32);
        var result = fishing.reel(PLAYER, "fixed-quote", ticket.dueAt());
        assertTrue(EconomyFishingText.receipt(result).contains("附近的钓鱼佬边界钓友说，这条鱼至少值10.00金币。"));
        verify(random).weight(1);
    }

    @Test
    void castPromptsMatchTheThreeRequestedLines() {
        assertEquals(java.util.Set.of("这个池子里面真的有鱼吗？", "抛得挺远，鱼能看见吗？", "这次谁来咬钩？"),
                textRows.findByOwnerCodeAndSceneAndEnabledTrueOrderByCodeAsc("*", "CAST").stream()
                        .map(row -> row.getTemplate()).collect(java.util.stream.Collectors.toSet()));
    }
}
