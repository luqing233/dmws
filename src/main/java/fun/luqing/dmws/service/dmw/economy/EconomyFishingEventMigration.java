package fun.luqing.dmws.service.dmw.economy;

import fun.luqing.dmws.entity.dmw.economy.EconomyFishingEvent;
import java.math.BigDecimal;

/** Convert definitions only. Historical attempts, receipts and ledger entries are never rewritten. */
public final class EconomyFishingEventMigration {
    private EconomyFishingEventMigration() { }

    public static void normalize(EconomyFishingEvent event) {
        String old = event.getKind();
        if (event.getAmountMin() != null && event.getAmountMax() != null
                && java.util.Set.of("MONEY", "COMBAT", "JAIL").contains(old)) return;
        BigDecimal minimum;
        BigDecimal maximum;
        switch (old) {
            case "FINE" -> { minimum = event.getPenalty().negate(); maximum = minimum; }
            case "FUN", "REWARD", "CROCODILE", "COMBAT" -> {
                minimum = event.getRewardMin(); maximum = event.getRewardMax();
            }
            case "TEXT", "JAIL" -> { minimum = BigDecimal.ZERO; maximum = BigDecimal.ZERO; }
            case "MONEY" -> throw new IllegalStateException("Missing signed money range: " + event.getCode());
            default -> throw new IllegalStateException("Unknown legacy fishing event: " + event.getCode());
        }
        if (event.getLegacyKind() == null) event.setLegacyKind(old);
        event.setKind(java.util.Set.of("CROCODILE", "COMBAT").contains(old) ? "COMBAT"
                : old.equals("JAIL") ? "JAIL" : "MONEY");
        event.setAmountMin(minimum);
        event.setAmountMax(maximum);
        event.setAmountStep(minimum.stripTrailingZeros().scale() <= 0 && maximum.stripTrailingZeros().scale() <= 0
                ? BigDecimal.ONE : new BigDecimal("0.01"));
    }
}
