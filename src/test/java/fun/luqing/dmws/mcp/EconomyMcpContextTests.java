package fun.luqing.dmws.mcp;

import fun.luqing.dmws.config.DmwConfig;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.*;

class EconomyMcpContextTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private final String secret = "s".repeat(64);
    private final DmwConfig config = config();

    private DmwConfig config() {
        DmwConfig value = new DmwConfig(); value.setBotId(1970243451L);
        value.setMaster(new long[]{3253912136L});
        value.getMcp().getEconomy().setContextSecret(secret); return value;
    }

    private ObjectNode context(String tool, long issued) {
        return mapper.createObjectNode().put("group_id", 706372110L).put("bot_id", 1970243451L)
                .put("sender_id", 1329158513L).put("message_id", -1295435687L).put("issued_at", issued).put("tool", tool);
    }

    private ObjectNode signed(String tool, ObjectNode context) throws Exception {
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(context.toString().getBytes(StandardCharsets.UTF_8));
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return mapper.createObjectNode().put("_trusted_context", payload + "." + HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII))));
    }

    @Test void defaultsToBotForOwnActionsAndRequesterForPurchases() throws Exception {
        for (String tool : EconomyMcpContext.WRITES) {
            var input = signed(tool, context(tool, Instant.now().getEpochSecond())).put("_source_message_id", 123);
            var actual = new EconomyMcpContext(config).authorize(tool, input);
            assertEquals(tool.equals("dmw_economy_buy") ? 1329158513L : 1970243451L, actual.path("user_id").asLong());
            assertEquals(-1295435687L, actual.path("_source_message_id").asLong());
            assertFalse(actual.has("_trusted_context"));
        }
    }

    @Test void rejectsMissingForgedExpiredOrWrongToolContext() throws Exception {
        var guard = new EconomyMcpContext(config);
        assertThrows(McpToolException.class, () -> guard.authorize("dmw_economy_buy", mapper.createObjectNode()));
        var valid = signed("dmw_economy_buy", context("dmw_economy_buy", Instant.now().getEpochSecond()));
        var forged = valid.deepCopy().put("_trusted_context", valid.path("_trusted_context").asText() + "0");
        assertThrows(McpToolException.class, () -> guard.authorize("dmw_economy_buy", forged));
        assertThrows(McpToolException.class, () -> guard.authorize("dmw_economy_rob", valid));
        var expired = signed("dmw_economy_buy", context("dmw_economy_buy", Instant.now().getEpochSecond() - 301));
        assertThrows(McpToolException.class, () -> guard.authorize("dmw_economy_buy", expired));
    }

    @Test void cannotSelectAdministratorThirdPartyOtherGroupOrOtherBot() throws Exception {
        var guard = new EconomyMcpContext(config);
        for (long owner : new long[]{3253912136L, 999999L, 0, -1}) {
            var input = signed("dmw_economy_buy", context("dmw_economy_buy", Instant.now().getEpochSecond())).put("user_id", owner);
            assertThrows(McpToolException.class, () -> guard.authorize("dmw_economy_buy", input));
        }
        var wrongGroup = signed("dmw_economy_buy", context("dmw_economy_buy", Instant.now().getEpochSecond())).put("group_id", 123);
        assertThrows(McpToolException.class, () -> guard.authorize("dmw_economy_buy", wrongGroup));
        var wrongBot = signed("dmw_economy_buy", context("dmw_economy_buy", Instant.now().getEpochSecond()).put("bot_id", 3253912136L));
        assertThrows(McpToolException.class, () -> guard.authorize("dmw_economy_buy", wrongBot));
    }

    @Test void permitsExplicitRequesterAndDoesNotRequireSignatureForReadOnlyTools() throws Exception {
        var input = signed("dmw_economy_use_item", context("dmw_economy_use_item", Instant.now().getEpochSecond())).put("user_id", 1329158513L);
        assertEquals(1329158513L, new EconomyMcpContext(config).authorize("dmw_economy_use_item", input).path("user_id").asLong());
        var query = mapper.createObjectNode();
        assertSame(query, new EconomyMcpContext(config).authorize("dmw_economy_shop", query));
    }
}
