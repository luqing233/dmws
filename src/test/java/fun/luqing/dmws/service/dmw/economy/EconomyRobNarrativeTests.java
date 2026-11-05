package fun.luqing.dmws.service.dmw.economy;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;

class EconomyRobNarrativeTests {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 4, 20, 58);

    @Test
    void counterAttackHidesBalancesAndIdsAndUsesReadableDeadline() {
        var result = result(false, false, false, true, false);
        for (int variant = 0; variant < 3; variant++) {
            String message = EconomyRobNarrative.format(result, "·.·^花予汐^", NOW, variant);
            assertTrue(message.contains("重伤（89/100）"));
            assertTrue(message.contains("明天 20:58"));
            assertTrue(message.contains("本人发送“出院”"));
            assertFalse(message.contains("1845"));
            assertFalse(message.contains("9528"));
            assertFalse(message.contains("3676311786"));
            assertFalse(message.contains("2026-11-05T"));
            assertFalse(message.contains("082291787"));
            assertFalse(message.contains("钱包"));
            assertFalse(message.contains("余额"));
        }
    }

    @Test
    void humorousVariantsKeepEveryOutcomeAndInjuryRecipientCorrect() {
        for (int variant = 0; variant < 3; variant++) {
            String success = EconomyRobNarrative.format(result(true, false, false, false, false), "目标", NOW, variant);
            assertTrue(success.contains("123.45"));
            assertFalse(success.contains("出院"));
            String assault = EconomyRobNarrative.format(result(false, true, true, false, true), "目标", NOW, variant);
            assertTrue(assault.contains("目标的伤势"));
            assertTrue(assault.contains("释放时间：今天 21:08"));
            assertTrue(assault.contains("罚款：50.00"));
            String caught = EconomyRobNarrative.format(result(false, false, true, false, false), "目标", NOW, variant);
            assertTrue(caught.contains("保释费用"));
            String empty = EconomyRobNarrative.format(result(false, true, false, false, false), "目标", NOW, variant);
            assertFalse(empty.contains("罚款"));
            assertFalse(empty.contains("伤势"));
            String escaped = EconomyRobNarrative.format(result(false, false, false, false, false), "目标", NOW, variant);
            assertFalse(escaped.contains("123.45"));
            assertTrue(escaped.contains("犯罪值：5/100"));
        }
    }

    @Test
    void friendlyDatesHandleMidnightAndNewYearWithoutLosingContext() {
        assertEquals("今天 20:58", EconomyGameText.time(NOW, NOW));
        assertEquals("明天 20:58", EconomyGameText.time(NOW.plusDays(1), NOW));
        assertEquals("11月6日 20:58", EconomyGameText.time(NOW.plusDays(2), NOW));
        assertEquals("2027年1月1日 00:02", EconomyGameText.time(LocalDateTime.of(2027,1,1,0,2), NOW));
        assertEquals("明天 00:02", EconomyGameText.time(LocalDateTime.of(2027,1,1,0,2), LocalDateTime.of(2026,12,31,23,58)));
    }

    private EconomyRobService.RobResult result(boolean success, boolean poor, boolean caught, boolean countered, boolean assault) {
        boolean injured = countered || assault;
        return new EconomyRobService.RobResult(success, poor, caught, countered, false, 123L,
                success ? new BigDecimal("123.45") : BigDecimal.ZERO, caught ? new BigDecimal("50.00") : BigDecimal.ZERO,
                new BigDecimal("1845.00"), new BigDecimal("9528.00"), assault, injured ? 3676311786L : 0,
                injured ? 89 : 0, injured ? NOW.plusDays(1).withNano(82291787) : null,
                caught ? NOW.plusMinutes(10) : null, 5, false, null);
    }
}
