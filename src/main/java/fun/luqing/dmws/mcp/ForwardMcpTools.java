package fun.luqing.dmws.mcp;

import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.core.BotContainer;
import fun.luqing.dmws.config.DmwConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class ForwardMcpTools {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final BotContainer botContainer;
    private final DmwConfig config;

    public ObjectNode send(JsonNode args) {
        boolean group = args.hasNonNull("group_id");
        if (group == args.hasNonNull("user_id")) {
            throw new McpToolException("group_id 和 user_id 必须且只能提供一个");
        }
        long target;
        try {
            target = Long.parseLong(args.path(group ? "group_id" : "user_id").asText());
            if (target <= 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            throw new McpToolException("目标 ID 必须为正整数");
        }
        JsonNode nodes = args.path("nodes");
        if (!nodes.isArray() || nodes.isEmpty() || nodes.size() > 100) {
            throw new McpToolException("nodes 必须包含 1 到 100 条文本");
        }
        Bot bot = botContainer.robots.get(config.getBotId());
        if (bot == null) throw new McpToolException("配置的 OneBot 尚未连接");
        List<Map<String, Object>> messages = new ArrayList<>();
        int total = 0;
        for (JsonNode node : nodes) {
            if (!node.isTextual() || node.asText().isBlank()) {
                throw new McpToolException("nodes 每项必须为非空文本");
            }
            total += node.asText().length();
            if (total > 50000) throw new McpToolException("合并转发总文本不能超过 50000 字符");
            messages.add(Map.of("type", "node", "data", Map.of(
                    "uin", String.valueOf(bot.getSelfId()), "name", config.getBotName(),
                    "content", List.of(Map.of("type", "text", "data", Map.of("text", node.asText()))))));
        }
        var response = group ? bot.sendGroupForwardMsg(target, messages)
                : bot.sendPrivateForwardMsg(target, messages);
        if (response == null || !Integer.valueOf(0).equals(response.getRetCode()) || response.getData() == null) {
            throw new McpToolException("合并转发未确认发送成功，请检查 OneBot 状态；不要自动重试以免重复发送");
        }
        ObjectNode result = MAPPER.createObjectNode();
        result.put("ok", true);
        result.put(group ? "group_id" : "user_id", target);
        result.put("node_count", messages.size());
        result.set("receipt", MAPPER.valueToTree(response.getData()));
        return result;
    }
}
