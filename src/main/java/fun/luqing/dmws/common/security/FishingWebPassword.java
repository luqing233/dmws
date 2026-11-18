package fun.luqing.dmws.common.security;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.util.Base64;

/** Independent web credentials; never reuse a OneBot/MCP token or the economy message HMAC key. */
public final class FishingWebPassword {
    private FishingWebPassword() { }
    public static boolean configured(String hash) {
        try { parse(hash); return true; } catch (RuntimeException invalid) { return false; }
    }
    public static boolean matches(String password, String encoded) {
        if (password == null || password.length() > 256) return false;
        PBEKeySpec spec = null;
        try {
            var parts = parse(encoded);
            spec = new PBEKeySpec(password.toCharArray(), Base64.getUrlDecoder().decode(parts[2]), Integer.parseInt(parts[1]), 256);
            byte[] actual = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return MessageDigest.isEqual(actual, Base64.getUrlDecoder().decode(parts[3]));
        } catch (Exception invalid) { return false; }
        finally { if (spec != null) spec.clearPassword(); }
    }
    private static String[] parse(String value) {
        if (value == null || value.length() > 300) throw new IllegalArgumentException();
        String[] parts = value.split("\\$", -1);
        if (parts.length != 4 || !parts[0].equals("pbkdf2-sha256")) throw new IllegalArgumentException();
        int rounds = Integer.parseInt(parts[1]);
        if (rounds < 100000 || rounds > 2000000 || Base64.getUrlDecoder().decode(parts[2]).length != 16
                || Base64.getUrlDecoder().decode(parts[3]).length != 32) throw new IllegalArgumentException();
        return parts;
    }
}
