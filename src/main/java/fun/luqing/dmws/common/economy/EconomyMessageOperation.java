package fun.luqing.dmws.common.economy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** One QQ request has one durable operation per actor/action/item, regardless of transport. */
public final class EconomyMessageOperation {
    private EconomyMessageOperation() { }

    public static String id(String action, long groupId, long messageId, long userId, String detail) {
        String source = action + "\n" + groupId + "\n" + messageId + "\n" + userId + "\n" + detail;
        try {
            return "qq:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
