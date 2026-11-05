package fun.luqing.dmws.service.dmw.economy;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Raw historical levels are never rewritten; the reward curve alone is bounded. */
public final class EconomyFishingRules {
    public static final int MAX_LEVEL = 100;
    private static final BigDecimal LEVEL_INCREMENT = new BigDecimal("0.02");
    private EconomyFishingRules() { }

    public static int effectiveLevel(int rawLevel) {
        return Math.max(1, Math.min(MAX_LEVEL, rawLevel));
    }

    public static BigDecimal rodMultiplier(int rawLevel) {
        return BigDecimal.ONE.add(LEVEL_INCREMENT.multiply(BigDecimal.valueOf(effectiveLevel(rawLevel) - 1))).setScale(2);
    }

    public static BigDecimal upgradeCost(int rawLevel) {
        return BigDecimal.valueOf(Math.max(1, rawLevel)).multiply(new BigDecimal("1000.00"));
    }

    /** A fixed-size fish uses its neutral size factor; the actual measured size determines price. */
    public static BigDecimal sizeMultiplier(BigDecimal size, int minimum, int maximum) {
        int min = Math.max(1, minimum), max = Math.max(min, maximum);
        if (max == min) return BigDecimal.ONE;
        BigDecimal position = size.subtract(BigDecimal.valueOf(min)).max(BigDecimal.ZERO)
                .min(BigDecimal.valueOf(max - min)).divide(BigDecimal.valueOf(max - min), 12, RoundingMode.HALF_UP);
        return new BigDecimal("0.75").add(new BigDecimal("0.50").multiply(position));
    }

    public static BigDecimal reward(int basePrice, BigDecimal size, int min, int max, int rawLevel) {
        return BigDecimal.valueOf(basePrice).multiply(sizeMultiplier(size, min, max))
                .multiply(rodMultiplier(rawLevel)).setScale(2, RoundingMode.HALF_UP);
    }
}
