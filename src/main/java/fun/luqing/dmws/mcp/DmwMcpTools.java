package fun.luqing.dmws.mcp;

import fun.luqing.dmws.common.economy.EconomyException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** MCP 工具路由及统一结果、异常封装。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DmwMcpTools {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final TomatoMcpTools tomatoMcpTools;
    private final EconomyMcpTools economyMcpTools;
    private final ForwardMcpTools forwardMcpTools;
    private final EconomyConditionMcpTools conditionMcpTools;
    private final EconomyMarketMcpTools marketMcpTools;

    public ObjectNode execute(String tool, JsonNode args) {
        ObjectNode result = MAPPER.createObjectNode();
        ArrayNode content = result.putArray("content");
        try {
            switch (tool) {
                case "dmw_economy_fx_market", "dmw_economy_fx_positions", "dmw_economy_fx_history",
                     "dmw_economy_fx_open", "dmw_economy_fx_close", "dmw_economy_fx_repay", "dmw_economy_fx_add_margin" ->
                        addText(content, marketMcpTools.execute(tool, args));
                case "dmw_economy_shop" -> addText(content, economyMcpTools.shop(args));
                case "dmw_economy_balance" -> addText(content, economyMcpTools.balance(args));
                case "dmw_economy_backpack" -> addText(content, economyMcpTools.backpack(args));
                case "dmw_send_forward_message" -> addText(content, forwardMcpTools.send(args));
                case "dmw_economy_transfer" -> addText(content, economyMcpTools.transfer(args));
                case "dmw_economy_rob" -> addText(content, economyMcpTools.rob(args));
                case "dmw_economy_buy" -> addText(content, economyMcpTools.buy(args));
                case "dmw_economy_use_item" -> {
                    ObjectNode response = economyMcpTools.useItem(args);
                    addText(content, response);
                    if (!response.path("ok").asBoolean()) result.put("isError", true);
                }
                case "dmw_economy_status", "dmw_economy_get_location", "dmw_economy_discharge", "dmw_economy_bail" -> {
                    ObjectNode response = conditionMcpTools.execute(tool, args);
                    addText(content, response);
                    if (!response.path("ok").asBoolean()) result.put("isError", true);
                }
                default -> tomatoMcpTools.execute(tool, args, content);
            }
        } catch (McpToolException e) {
            return error(MAPPER.createObjectNode().put("ok", false).put("code", "INVALID_REQUEST")
                    .put("message", e.getMessage()).toString());
        } catch (EconomyException e) {
            return error(MAPPER.createObjectNode().put("ok", false).put("code", e.getCode())
                    .put("message", e.getMessage()).toString());
        } catch (Exception e) {
            log.error("DMW MCP 工具 {} 执行失败", tool, e);
            return error("工具执行失败: " + e.getMessage());
        }
        // All economy adapters use the same error contract, including returned business errors.
        for (JsonNode block : content) {
            if (!"text".equals(block.path("type").asText())) continue;
            try {
                JsonNode payload = MAPPER.readTree(block.path("text").asText());
                if (payload.isObject() && payload.has("ok")) {
                    result.set("structuredContent", payload);
                    if (!payload.path("ok").asBoolean()) result.put("isError", true);
                }
            } catch (RuntimeException ignored) {
                // Tomato adapters may deliberately return ordinary text.
            }
        }
        return result;
    }

    private void addText(ArrayNode content, JsonNode data) {
        ObjectNode text = content.addObject();
        text.put("type", "text");
        text.put("text", data.toString());
    }

    private ObjectNode error(String message) {
        ObjectNode result = MAPPER.createObjectNode();
        ArrayNode content = result.putArray("content");
        ObjectNode text = content.addObject();
        text.put("type", "text");
        text.put("text", message);
        try { result.set("structuredContent", MAPPER.readTree(message)); } catch (RuntimeException ignored) { }
        result.put("isError", true);
        return result;
    }

}
