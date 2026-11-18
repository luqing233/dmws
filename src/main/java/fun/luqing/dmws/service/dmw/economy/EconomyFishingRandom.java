package fun.luqing.dmws.service.dmw.economy;

import org.springframework.stereotype.Component;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.nio.ByteBuffer;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

@Component
public class EconomyFishingRandom {
    private static final ThreadLocal<Draw> CURRENT = new ThreadLocal<>();

    public static String newSeed() {
        byte[] seed = new byte[32];
        new java.security.SecureRandom().nextBytes(seed);
        return HexFormat.of().formatHex(seed);
    }

    public static <T> T withSeed(String seed, Supplier<T> action) {
        Draw previous = CURRENT.get();
        CURRENT.set(new Draw(seed));
        try {
            return action.get();
        } finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }

    public long weight(long bound) {
        Draw draw = CURRENT.get();
        if (draw == null) return ThreadLocalRandom.current().nextLong(bound);
        if (bound <= 0) throw new IllegalArgumentException("Random bound must be positive");
        long bits, value;
        do {
            bits = draw.nextLong() >>> 1;
            value = bits % bound;
        } while (bits - value + (bound - 1) < 0);
        return value;
    }

    public double fraction() {
        Draw draw = CURRENT.get();
        return draw == null ? ThreadLocalRandom.current().nextDouble() : (draw.nextLong() >>> 11) * 0x1.0p-53;
    }

    public boolean chance(double probability) { return fraction() < probability; }

    private static final class Draw {
        private final Mac mac;
        private long sequence;

        private Draw(String seed) {
            try {
                mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(HexFormat.of().parseHex(seed), "HmacSHA256"));
            } catch (java.security.GeneralSecurityException e) {
                throw new IllegalStateException("Cannot initialize fishing draw", e);
            }
        }

        private long nextLong() {
            return ByteBuffer.wrap(mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(sequence++).array())).getLong();
        }
    }
}
