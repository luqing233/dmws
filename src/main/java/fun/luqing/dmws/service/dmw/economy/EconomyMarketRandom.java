package fun.luqing.dmws.service.dmw.economy;

import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import fun.luqing.dmws.enums.EconomyFxPair;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

@Component
public class EconomyMarketRandom {
    /** A failed tick must retry the SAME draw, even after restart or a later wall-clock slot. */
    public BigDecimal nextReturn(String secret, long sequence, EconomyFxPair pair) {
        try {
            if (secret == null || !secret.matches("[0-9a-f]{64}") || sequence <= 0 || pair == null)
                throw new IllegalArgumentException("Invalid market entropy context");
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(HexFormat.of().parseHex(secret), "HmacSHA256"));
            ByteBuffer bytes = ByteBuffer.wrap(mac.doFinal(("fx-v1:" + sequence + ":" + pair.name()).getBytes(StandardCharsets.UTF_8)));
            double u1 = uniform(bytes.getLong()), u2 = uniform(bytes.getLong());
            double shockChance = uniform(bytes.getLong()), shockSize = uniform(bytes.getLong());
            double move = StrictMath.sqrt(-2 * StrictMath.log(u1)) * StrictMath.cos(2 * StrictMath.PI * u2) * .001;
            if (shockChance < .01) move += (shockSize * 2 - 1) * .02;
            return BigDecimal.valueOf(Math.max(-.03, Math.min(.03, move)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Cannot derive simulated quote", e);
        }
    }
    private double uniform(long bits) { return ((bits >>> 11) + 1.0) / (0x1.0p53 + 2.0); }
}
