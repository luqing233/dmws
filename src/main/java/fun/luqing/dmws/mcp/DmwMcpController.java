package fun.luqing.dmws.mcp;

import fun.luqing.dmws.config.DmwConfig;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
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
    private final McpToolCatalog catalog;
    private final EconomyMcpContext economyContext;
    private final Set<String> sessions = ConcurrentHashMap.newKeySet();

    @GetMapping
    public ResponseEntity<Void> noEventStream() {
        // Explicitly decline optional SSE without Spring logging a protocol probe as an exception.
        return ResponseEntity.status(405).build();
    }

    @DeleteMapping
    public ResponseEntity<Void> closeSession(@RequestHeader(value = "Authorization", required = false) String authorization,
                                            @RequestHeader(value = "mcp-session-id", required = false) String sessionId) {
        if (!isAuthorized(authorization)) return ResponseEntity.status(401).build();
        if (sessionId == null || !sessions.remove(sessionId)) return ResponseEntity.notFound().build();
        return ResponseEntity.noContent().build();
    }

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
            return ResponseEntity.status(404).body(jsonRpcError(id, -32000, "MCP session 无效，请重新初始化"));
        }
        if ("notifications/initialized".equals(method)) {
            return ResponseEntity.accepted().build();
        }
        if ("ping".equals(method)) {
            return ResponseEntity.ok(rpcResult(id, MAPPER.createObjectNode()));
        }
        if ("tools/list".equals(method)) {
            ObjectNode result = MAPPER.createObjectNode();
            result.set("tools", catalog.definitions());
            return ResponseEntity.ok(rpcResult(id, result));
        }
        if ("tools/call".equals(method)) {
            String toolName = request.path("params").path("name").asText("");
            JsonNode args = request.path("params").path("arguments");
            ObjectNode result;
            try {
                result = tools.execute(toolName, economyContext.authorize(toolName, args));
            } catch (McpToolException e) {
                result = MAPPER.createObjectNode().put("isError", true);
                result.putArray("content").addObject().put("type", "text").put("text",
                        MAPPER.createObjectNode().put("ok", false).put("code", "UNTRUSTED_CONTEXT")
                                .put("message", e.getMessage()).toString());
            }
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

}
