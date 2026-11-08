package fun.luqing.dmws.economy;

import fun.luqing.dmws.enums.EconomyFxPair;
import fun.luqing.dmws.service.dmw.economy.EconomyMarketRandom;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class EconomyMarketRandomTests {
    @Test void samePrivateSeedSequenceAndPairSurviveRetriesAndRestart() {
        var first = new EconomyMarketRandom(); var restarted = new EconomyMarketRandom();
        String testKey = "01".repeat(32);
        for (long seq = 1; seq <= 100; seq++) for (var pair : EconomyFxPair.values()) {
            BigDecimal movement = first.nextReturn(testKey, seq, pair);
            assertEquals(movement, restarted.nextReturn(testKey, seq, pair));
            assertTrue(movement.abs().compareTo(new BigDecimal(".03")) <= 0);
        }
        assertNotEquals(first.nextReturn(testKey, 7, EconomyFxPair.EUR_USD), first.nextReturn(testKey, 8, EconomyFxPair.EUR_USD));
        assertNotEquals(first.nextReturn(testKey, 7, EconomyFxPair.EUR_USD), first.nextReturn("02".repeat(32), 7, EconomyFxPair.EUR_USD));
        assertNotEquals(first.nextReturn(testKey, 7, EconomyFxPair.EUR_USD), first.nextReturn(testKey, 7, EconomyFxPair.GBP_USD));
    }
    @Test void invalidSeedNeverSilentlyReseedsTheMarket() {
        assertThrows(IllegalArgumentException.class, () -> new EconomyMarketRandom().nextReturn(null, 1, EconomyFxPair.EUR_USD));
        assertThrows(IllegalArgumentException.class, () -> new EconomyMarketRandom().nextReturn("bad", 1, EconomyFxPair.EUR_USD));
    }
}
