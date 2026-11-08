package fun.luqing.dmws.mcp;

import fun.luqing.dmws.config.DmwConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;

/** Only the trusted chat adapter, never the LLM, supplies the identity envelope. */
@Component
@RequiredArgsConstructor
public class EconomyMcpContext {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    public static final Set<String> WRITES = Set.of("dmw_economy_buy", "dmw_economy_use_item",
            "dmw_economy_transfer", "dmw_economy_rob", "dmw_economy_discharge", "dmw_economy_bail",
            "dmw_economy_fx_open", "dmw_economy_fx_close", "dmw_economy_fx_repay");
    private final DmwConfig config;

    public JsonNode authorize(String tool, JsonNode args) {
        if (!WRITES.contains(tool)) return args;
        try {
            String secret = config.getMcp().getEconomy().getContextSecret();
            if (secret == null || secret.length() < 32) throw new IllegalArgumentException();
            String envelope = args.path("_trusted_context").asText();
            String[] parts = envelope.split("\\.", -1);
            if (parts.length != 2 || envelope.length() > 2048) throw new IllegalArgumentException();
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal(parts[0].getBytes(StandardCharsets.US_ASCII));
            if (!MessageDigest.isEqual(expected, HexFormat.of().parseHex(parts[1]))) throw new IllegalArgumentException();
            JsonNode context = MAPPER.readTree(Base64.getUrlDecoder().decode(parts[0]));
            long group = positive(context, "group_id"), bot = positive(context, "bot_id"), sender = positive(context, "sender_id");
            if (config.getBotId() <= 0 || bot != config.getBotId()) throw new IllegalArgumentException();
            JsonNode message = context.path("message_id");
            if (!message.isIntegralNumber() || !message.canConvertToLong() || message.asLong() == 0) throw new IllegalArgumentException();
            long now = Instant.now().getEpochSecond(), issued = positive(context, "issued_at");
            if (issued > now + 30 || issued < now - 300 || !tool.equals(context.path("tool").asText())) throw new IllegalArgumentException();
            if (args.has("group_id") && (!args.path("group_id").isIntegralNumber() || args.path("group_id").asLong() != group)) throw new IllegalArgumentException();
            long owner = "dmw_economy_buy".equals(tool) ? sender : bot;
            if (args.has("user_id")) owner = positive(args, "user_id");
            if (owner != sender && owner != bot) throw new IllegalArgumentException();
            ObjectNode verified = ((ObjectNode) args).deepCopy();
            verified.remove("_trusted_context");
            verified.put("group_id", group).put("user_id", owner).put("_source_message_id", message.asLong());
            return verified;
        } catch (Exception e) {
            throw new McpToolException("经济操作未获可信消息授权，未执行；只能操作本次消息发送者或机器人自己的账户");
        }
    }

    private long positive(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() <= 0) throw new IllegalArgumentException();
        return value.asLong();
    }
}
