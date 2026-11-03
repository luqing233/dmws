package fun.luqing.dmws.mcp;

import fun.luqing.dmws.config.DmwConfig;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 最小 Streamable HTTP MCP 服务，暴露 DMW 业务工具。
 */
@RestController
@RequestMapping("/mcp")
@RequiredArgsConstructor
public class DmwMcpController {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PROTOCOL_VERSION = "2024-11-05";

    private final DmwMcpTools tools;
    private final DmwConfig dmwConfig;
    private final Set<String> sessions = ConcurrentHashMap.newKeySet();

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> handle(
            @RequestBody JsonNode request,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "mcp-session-id", required = false) String sessionId,
            HttpServletResponse response) {

        if (!dmwConfig.isMcpServerEnabled()) {
            return ResponseEntity.notFound().build();
        }
        if (!isAuthorized(authorization)) {
            return ResponseEntity.status(401).build();
        }

        String method = request.path("method").asText("");
        JsonNode id = request.path("id");

        if ("initialize".equals(method)) {
            String newSessionId = UUID.randomUUID().toString();
            sessions.add(newSessionId);
            response.setHeader("mcp-session-id", newSessionId);

            ObjectNode result = MAPPER.createObjectNode();
            result.put("protocolVersion", PROTOCOL_VERSION);
            result.putObject("capabilities").putObject("tools");
            result.putObject("serverInfo")
                    .put("name", "dmw-mcp")
                    .put("version", "1.1.1");
            return ResponseEntity.ok(rpcResult(id, result));
        }

        if (sessionId == null || !sessions.contains(sessionId)) {
            return ResponseEntity.badRequest().body(jsonRpcError(id, -32000, "MCP session 无效"));
        }
        if ("notifications/initialized".equals(method)) {
            return ResponseEntity.accepted().build();
        }
        if ("tools/list".equals(method)) {
            ObjectNode result = MAPPER.createObjectNode();
            result.set("tools", toolDefinitions());
            return ResponseEntity.ok(rpcResult(id, result));
        }
        if ("tools/call".equals(method)) {
            String toolName = request.path("params").path("name").asText("");
            JsonNode args = request.path("params").path("arguments");
            ObjectNode result = tools.execute(toolName, args);
            return ResponseEntity.ok(rpcResult(id, result));
        }

        return ResponseEntity.badRequest()
                .body(jsonRpcError(id, -32601, "不支持的方法: " + method));
    }

    private boolean isAuthorized(String authorization) {
        String token = dmwConfig.getMcpServerToken();
        return token != null && !token.isBlank() && token.equals(authorization);
    }

    private ObjectNode rpcResult(JsonNode id, JsonNode result) {
        ObjectNode response = MAPPER.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        response.set("result", result);
        return response;
    }

    private ObjectNode jsonRpcError(JsonNode id, int code, String message) {
        ObjectNode response = MAPPER.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        ObjectNode error = response.putObject("error");
        error.put("code", code);
        error.put("message", message);
        return response;
    }

    private ArrayNode toolDefinitions() {
        ArrayNode tools = MAPPER.createArrayNode();

        tools.add(tool("dmw_search_books",
                "Search only books already present in DMW's local database (known/subscribed books) "
                        + "by book_id or book_name. This does NOT call Fanqie and does NOT search the "
                        + "global catalog. For global Fanqie search, use tnd_search_books on 8001.",
                objectSchema(List.of("q"),
                        property("q", "string", "Book title or local book ID"))));

        tools.add(tool("dmw_get_book",
                "Read one book already registered in DMW's local database.",
                objectSchema(List.of("book_id"),
                        property("book_id", "string", "DMW book ID"))));

        tools.add(tool("dmw_list_subscriptions",
                "List DMW subscriptions for one QQ group.",
                objectSchema(List.of("group_id"),
                        property("group_id", "integer", "QQ group ID"))));

        tools.add(tool("dmw_list_chapters",
                "List chapter metadata already stored in DMW. It does not return chapter text.",
                objectSchema(List.of("book_id"),
                        property("book_id", "string", "DMW book ID"),
                        property("offset", "integer", "Pagination offset"),
                        property("limit", "integer", "Page size, max 200"))));

        tools.add(tool("dmw_get_chapter_content",
                "Read one chapter from DMW. If the body is missing, DMW tries to fetch it from the "
                        + "local downloader before returning it.",
                objectSchema(List.of("book_id"),
                        property("book_id", "string", "DMW book ID"),
                        property("chapter_id", "string", "Chapter item ID"),
                        property("order", "integer", "1-based chapter order"),
                        property("max_chars", "integer", "Maximum returned characters; 0 means full text"))));

        tools.add(tool("dmw_get_update_stats",
                "Read daily chapter and word-count statistics from DMW's local database. "
                        + "This is a read-only query and does not download chapters.",
                objectSchema(List.of("book_id"),
                        property("book_id", "string", "DMW book ID"),
                        property("start_date", "string", "Optional start date, yyyy-MM-dd"),
                        property("end_date", "string", "Optional end date, yyyy-MM-dd"))));

        tools.add(tool("dmw_get_update_heatmap",
                "Generate a GitHub-style update heatmap from DMW's database and return image/png "
                        + "base64. It may backfill missing chapter word counts before rendering.",
                objectSchema(List.of("book_id"),
                        property("book_id", "string", "DMW book ID"))));

        tools.add(tool("dmw_subscribe_book",
                "Subscribe a book to one QQ group. book_id should come from tnd_search_books on 8001 "
                        + "or an existing DMW book. This is a write operation and requires confirm=true "
                        + "and a master operator.",
                objectSchema(List.of("group_id", "book_id", "operator", "confirm"),
                        property("group_id", "integer", "QQ group ID"),
                        property("book_id", "string", "Book ID"),
                        property("operator", "integer", "QQ user ID of the master"),
                        property("at_all", "boolean", "Whether to mention all members"),
                        property("confirm", "boolean", "Must be true"))));

        tools.add(tool("dmw_unsubscribe_book",
                "Remove a book subscription from one QQ group. Requires confirm=true and a master operator.",
                objectSchema(List.of("group_id", "book_id", "operator", "confirm"),
                        property("group_id", "integer", "QQ group ID"),
                        property("book_id", "string", "Book ID"),
                        property("operator", "integer", "QQ user ID of the master"),
                        property("confirm", "boolean", "Must be true"))));

        tools.add(tool("dmw_update_book",
                "Update directory and chapter text for one DMW book. This is a synchronous write "
                        + "operation, can be slow, and requires confirm=true and a master operator.",
                objectSchema(List.of("book_id", "operator", "confirm"),
                        property("book_id", "string", "DMW book ID"),
                        property("operator", "integer", "QQ user ID of the master"),
                        property("confirm", "boolean", "Must be true"))));

        tools.add(tool("dmw_economy_transfer",
                "Transfer wallet coins to a member of the QQ group. If target_name uniquely and "
                        + "exactly matches one group card or nickname, the transfer executes directly. "
                        + "If the name is ambiguous or only fuzzy-matched, the tool returns candidates "
                        + "without executing. After the user chooses, retry with target_user_id and "
                        + "confirm=true. request_id is the idempotency key.",
                objectSchema(List.of("group_id", "amount", "request_id"),
                        property("group_id", "integer", "QQ group ID"),
                        property("target_user_id", "integer", "Resolved target QQ user ID"),
                        property("target_name", "string", "Target group card or nickname"),
                        property("amount", "string", "Amount, for example 100 or 1.5万"),
                        property("request_id", "string", "Idempotency key, max 50 safe characters"),
                        property("confirm", "boolean",
                                "Set true only after the user resolves an ambiguous target"))));

        tools.add(tool("dmw_economy_rob",
                "Attempt to rob a member of the QQ group. A unique exact target name or an explicit "
                        + "group member target_user_id executes directly. Ambiguous or fuzzy matches "
                        + "return candidates without executing; retry with target_user_id and "
                        + "confirm=true after the user chooses. request_id is the idempotency key.",
                objectSchema(List.of("group_id", "request_id"),
                        property("group_id", "integer", "QQ group ID"),
                        property("target_user_id", "integer", "Resolved target QQ user ID"),
                        property("target_name", "string", "Target group card or nickname"),
                        property("request_id", "string", "Idempotency key, max 50 safe characters"),
                        property("confirm", "boolean",
                                "Set true only after the user resolves an ambiguous target"))));

        return tools;
    }

    private ObjectNode tool(String name, String description, ObjectNode schema) {
        ObjectNode tool = MAPPER.createObjectNode();
        tool.put("name", name);
        tool.put("description", description);
        tool.set("inputSchema", schema);
        return tool;
    }

    private ObjectNode objectSchema(List<String> requiredFields, ObjectNode... properties) {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        for (ObjectNode property : properties) {
            props.set(property.path("name").asText(), property.path("definition"));
        }
        ArrayNode required = schema.putArray("required");
        requiredFields.forEach(required::add);
        return schema;
    }

    private ObjectNode property(String name, String type, String description) {
        ObjectNode wrapper = MAPPER.createObjectNode();
        wrapper.put("name", name);
        ObjectNode definition = wrapper.putObject("definition");
        definition.put("type", type);
        definition.put("description", description);
        return wrapper;
    }
}
