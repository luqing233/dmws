package fun.luqing.dmws.economy;

import fun.luqing.dmws.service.dmw.economy.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class EconomyFishingRulesTests {
    @Test void historicalLevelsHaveMeaningfulBoundedMultipliers() {
        assertEquals(new BigDecimal("0.50"), EconomyFishingRules.rodMultiplier(1));
        assertEquals(new BigDecimal("0.94"), EconomyFishingRules.rodMultiplier(45));
        assertEquals(new BigDecimal("1.49"), EconomyFishingRules.rodMultiplier(100));
        assertEquals(new BigDecimal("1.49"), EconomyFishingRules.rodMultiplier(Integer.MAX_VALUE));
        assertEquals(new BigDecimal("0.50"), EconomyFishingRules.rodMultiplier(-1));
        for (int level = 2; level <= 100; level++) {
            assertTrue(EconomyFishingRules.rodMultiplier(level).compareTo(EconomyFishingRules.rodMultiplier(level - 1)) > 0);
        }
    }

    @Test void actualSizeAndLevelDeterminePriceRatherThanAnUnrelatedRandomRoll() {
        assertEquals(new BigDecimal("70.50"), EconomyFishingRules.reward(100, new BigDecimal("10"), 10, 30, 45));
        assertEquals(new BigDecimal("94.00"), EconomyFishingRules.reward(100, new BigDecimal("20"), 10, 30, 45));
        assertEquals(new BigDecimal("117.50"), EconomyFishingRules.reward(100, new BigDecimal("30"), 10, 30, 45));
        assertEquals(new BigDecimal("931.25"), EconomyFishingRules.reward(500, new BigDecimal("80"), 30, 80, 100));
        assertEquals(new BigDecimal("94.00"), EconomyFishingRules.reward(100, new BigDecimal("20"), 20, 20, 45));
    }

    @Test void lowerSuccessCurveKeepsDifficultyAndRodProgressionMeaningful() {
        var fish=new fun.luqing.dmws.entity.dmw.fish.Fish();fish.setLevel(1);
        fish.setDifficulty(25);
        assertEquals(.66375,EconomyFishingRules.successChance(fish,1,EconomyBaitQuality.WHITE),1e-10);
        assertEquals(.864625,EconomyFishingRules.successChance(fish,100,EconomyBaitQuality.RED),1e-10);
        assertEquals(.82,EconomyFishingRules.successChance(fish,45,EconomyBaitQuality.RED),.005);
        fish.setDifficulty(45);
        assertEquals(.777125,EconomyFishingRules.successChance(fish,100,EconomyBaitQuality.RED),1e-10);
        fish.setDifficulty(80);
        assertEquals(.57,EconomyFishingRules.successChance(fish,100,EconomyBaitQuality.RED),1e-10);
        fish.setDifficulty(100);
        var hard=EconomyFishingRules.chanceBreakdown(fish,100,EconomyBaitQuality.RED);
        assertEquals(.25,hard.base(),1e-10);assertEquals(.15,hard.rodBonus(),1e-10);
        assertEquals(.50,hard.total(),1e-10);
        fish.setDifficulty(0);
        assertEquals(.90,EconomyFishingRules.successChance(fish,100,EconomyBaitQuality.RED),1e-10);
        assertEquals(EconomyFishingRules.successChance(fish,100,EconomyBaitQuality.RED),EconomyFishingRules.successChance(fish,Integer.MAX_VALUE,EconomyBaitQuality.RED));
        fish.setDifficulty(25);fish.setLevel(2);
        assertEquals(.51375,EconomyFishingRules.successChance(fish,1,EconomyBaitQuality.WHITE),1e-10);
        double[] bonuses={0,.02,.04,.06,.08,.10};int index=0;
        for(var q:EconomyBaitQuality.values()) assertEquals(bonuses[index++],q.successBonus,1e-10);
    }

    @Test void receiptsShowDescriptionAndSignedNetWithoutDisclosingWallet() {
        var caught = new EconomyFishingService.FishingResult(true, "鲤鱼", new BigDecimal("35"),
                new BigDecimal("37.60"), new BigDecimal("20"), new BigDecimal("123456.78"), 45, false,
                "力气不小的河鱼", new BigDecimal("1.88"));
        String text = EconomyFishingText.receipt(caught);
        assertTrue(text.contains("力气不小的河鱼")); assertTrue(text.contains("+17.60"));
        assertEquals(4,text.lines().count()); assertTrue(text.contains("大小：35.00厘米")); assertFalse(text.contains("123456.78")); assertFalse(text.contains("钱包"));
        var escaped = new EconomyFishingService.FishingResult(false, "鲤鱼", BigDecimal.ZERO,
                BigDecimal.ZERO, new BigDecimal("20"), BigDecimal.ZERO, 45, true, "描述", new BigDecimal("1.88"));
        text = EconomyFishingText.receipt(escaped);
        assertTrue(text.contains("-20.00")); assertFalse(text.contains("鱼饵增益"));
        assertTrue(text.contains("描述：描述")); assertTrue(text.contains("脱钩逃走")); assertEquals(4,text.lines().count());
    }
}
