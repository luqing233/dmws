package fun.luqing.dmws.economy;

import fun.luqing.dmws.service.dmw.economy.EconomyFishingRandom;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EconomyFishingRandomTests {
    @Test
    void retriesAndNewInstancesReplayTheSameDrawAndFailureClosesItsScope() {
        String seed = EconomyFishingRandom.newSeed();
        var random = new EconomyFishingRandom();
        var expected = EconomyFishingRandom.withSeed(seed, () -> sequence(random));
        assertEquals(expected, EconomyFishingRandom.withSeed(seed, () -> sequence(new EconomyFishingRandom())));
        assertThrows(IllegalStateException.class, () -> EconomyFishingRandom.withSeed(seed, () -> {
            assertEquals(expected.getFirst(), random.weight(10000));
            throw new IllegalStateException("after drawing");
        }));
        assertEquals(expected, EconomyFishingRandom.withSeed(seed, () -> sequence(random)));
        assertEquals(0, random.weight(1));
        assertThrows(IllegalArgumentException.class, () -> EconomyFishingRandom.withSeed(seed, () -> random.weight(0)));
    }

    private List<Object> sequence(EconomyFishingRandom random) {
        return List.of(random.weight(10000), random.weight(Long.MAX_VALUE), random.fraction(), random.chance(.5));
    }

    @Test
    void nestedDrawRestoresTheOuterSequence() {
        var random = new EconomyFishingRandom();
        String seed = EconomyFishingRandom.newSeed();
        var expected = EconomyFishingRandom.withSeed(seed, () -> List.of(random.weight(10000), random.weight(10000)));
        var actual = EconomyFishingRandom.withSeed(seed, () -> {
            long first = random.weight(10000);
            EconomyFishingRandom.withSeed(EconomyFishingRandom.newSeed(), () -> random.weight(10000));
            return List.of(first, random.weight(10000));
        });
        assertEquals(expected, actual);
    }
}
