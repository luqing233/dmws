package fun.luqing.dmws.economy;

import fun.luqing.dmws.service.dmw.economy.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class EconomyFishingRulesTests {
    @Test void historicalLevelsHaveMeaningfulBoundedMultipliers() {
        assertEquals(new BigDecimal("1.00"), EconomyFishingRules.rodMultiplier(1));
        assertEquals(new BigDecimal("1.88"), EconomyFishingRules.rodMultiplier(45));
        assertEquals(new BigDecimal("2.98"), EconomyFishingRules.rodMultiplier(100));
        assertEquals(new BigDecimal("2.98"), EconomyFishingRules.rodMultiplier(Integer.MAX_VALUE));
        assertEquals(new BigDecimal("1.00"), EconomyFishingRules.rodMultiplier(-1));
        for (int level = 2; level <= 100; level++) {
            assertTrue(EconomyFishingRules.rodMultiplier(level).compareTo(EconomyFishingRules.rodMultiplier(level - 1)) > 0);
        }
    }

    @Test void actualSizeAndLevelDeterminePriceRatherThanAnUnrelatedRandomRoll() {
        assertEquals(new BigDecimal("141.00"), EconomyFishingRules.reward(100, new BigDecimal("10"), 10, 30, 45));
        assertEquals(new BigDecimal("188.00"), EconomyFishingRules.reward(100, new BigDecimal("20"), 10, 30, 45));
        assertEquals(new BigDecimal("235.00"), EconomyFishingRules.reward(100, new BigDecimal("30"), 10, 30, 45));
        assertEquals(new BigDecimal("1862.50"), EconomyFishingRules.reward(500, new BigDecimal("80"), 30, 80, 100));
        assertEquals(new BigDecimal("188.00"), EconomyFishingRules.reward(100, new BigDecimal("20"), 20, 20, 45));
    }

    @Test void receiptsShowDescriptionAndSignedNetWithoutDisclosingWallet() {
        var caught = new EconomyFishingService.FishingResult(true, "鲤鱼", new BigDecimal("35"),
                new BigDecimal("37.60"), new BigDecimal("20"), new BigDecimal("123456.78"), 45, false,
                "力气不小的河鱼", new BigDecimal("1.88"));
        String text = EconomyFishingText.receipt(caught);
        assertTrue(text.contains("力气不小的河鱼")); assertTrue(text.contains("+17.60"));
        assertTrue(text.contains("×1.88")); assertFalse(text.contains("123456.78")); assertFalse(text.contains("钱包"));
        var escaped = new EconomyFishingService.FishingResult(false, "鲤鱼", BigDecimal.ZERO,
                BigDecimal.ZERO, new BigDecimal("20"), BigDecimal.ZERO, 45, true, "描述", new BigDecimal("1.88"));
        text = EconomyFishingText.receipt(escaped);
        assertTrue(text.contains("-20.00")); assertTrue(text.contains("未计道具购入成本"));
        assertFalse(text.contains("描述"));
    }
}
