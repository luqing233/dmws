package fun.luqing.dmws.client;

import fun.luqing.dmws.config.McpConfig;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/** Serialized Streamable HTTP calls with correlated JSON/SSE responses, stateful or stateless. */
@Slf4j
public class McpClient {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PROTOCOL_VERSION = "2024-11-05";
    private final String name;
    private final McpConfig.McpServer server;
    private final HttpClient httpClient;
    private final AtomicLong requestId = new AtomicLong();
    private final ThreadLocal<String> lastToolErrorCode = new ThreadLocal<>();
    private String sessionId;
    private boolean initialized;
    private boolean expiredSession;

    public McpClient(String name, McpConfig.McpServer server) {
        this.name = name;
        this.server = server;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(Math.max(1, server.getTimeout()))).build();
    }

    public String getLastToolErrorCode() { return lastToolErrorCode.get(); }

    /** Compatibility API: business errors are null. Use callToolResult to preserve error details. */
    public synchronized JsonNode callTool(String tool, ObjectNode args) {
        JsonNode payload = callToolResult(tool, args);
        return payload != null && !payload.path("mcp_is_error").asBoolean() ? payload : null;
    }

    /** Payload including business errors. Never replay a possibly executed write after a timeout. */
    public synchronized JsonNode callToolResult(String tool, ObjectNode args) {
        lastToolErrorCode.remove();
        JsonNode payload = invoke(tool, args);
        if (payload == null && expiredSession) {
            resetSession();
            payload = invoke(tool, args); // 404 explicitly rejected the old session before executing the call.
        }
        return payload;
    }

    private JsonNode invoke(String tool, ObjectNode args) {
        expiredSession = false;
        lastToolErrorCode.remove();
        if (!initialized && !initialize()) return null;
        ObjectNode params = MAPPER.createObjectNode().put("name", tool);
        params.set("arguments", args == null ? MAPPER.createObjectNode() : args);
        JsonNode response = rpc("tools/call", params, server.getReadTimeout());
        if (response == null) return null;
        if (response.has("error")) {
            lastToolErrorCode.set("RPC_ERROR");
            return null;
        }
        JsonNode result = response.path("result");
        JsonNode payload = result.path("structuredContent");
        if (!payload.isObject()) {
            payload = null;
            for (JsonNode block : result.path("content")) {
                if (!"text".equals(block.path("type").asText())) continue;
                try { payload = MAPPER.readTree(block.path("text").asText()); break; }
                catch (RuntimeException ignored) { }
            }
        }
        if (payload == null || !payload.isObject()) {
            lastToolErrorCode.set("INVALID_TOOL_RESULT");
            return null;
        }
        if (result.path("isError").asBoolean()) {
            lastToolErrorCode.set(payload.path("code").asText("TOOL_ERROR"));
            ((ObjectNode) payload).put("mcp_is_error", true);
        }
        return payload;
    }

    private boolean initialize() {
        ObjectNode params = MAPPER.createObjectNode().put("protocolVersion", PROTOCOL_VERSION);
        params.putObject("capabilities");
        params.putObject("clientInfo").put("name", "dmws").put("version", "1.1.1");
        JsonNode response = rpc("initialize", params, server.getTimeout());
        if (response == null || response.has("error") || !response.path("result").isObject()) return false;
        initialized = true; // An absent session header is valid for a stateless server.
        try {
            ObjectNode notification = MAPPER.createObjectNode().put("jsonrpc", "2.0").put("method", "notifications/initialized");
            httpClient.send(builder(notification, server.getTimeout()).build(), HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.debug("[{}] initialized notification failed", name);
        }
        return true;
    }

    private HttpRequest.Builder builder(ObjectNode payload, int timeout) {
        var builder = HttpRequest.newBuilder(URI.create(server.getUrl()))
                .timeout(Duration.ofSeconds(Math.max(1, timeout)))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", PROTOCOL_VERSION)
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()));
        if (sessionId != null && !sessionId.isBlank()) builder.header("mcp-session-id", sessionId);
        if (server.getToken() != null && !server.getToken().isBlank()) builder.header("Authorization", server.getToken());
        return builder;
    }

    private JsonNode rpc(String method, ObjectNode params, int timeout) {
        long id = requestId.incrementAndGet();
        ObjectNode payload = MAPPER.createObjectNode().put("jsonrpc", "2.0").put("id", id).put("method", method);
        payload.set("params", params);
        try {
            var response = httpClient.send(builder(payload, timeout).build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                expiredSession = response.statusCode() == 404 && sessionId != null && !"initialize".equals(method);
                lastToolErrorCode.set("HTTP_" + response.statusCode());
                return null;
            }
            JsonNode parsed = parseBody(response.body(), id);
            if (parsed == null) lastToolErrorCode.set("RPC_ID_MISMATCH");
            if ("initialize".equals(method) && parsed != null) sessionId = response.headers().firstValue("mcp-session-id").orElse(null);
            return parsed;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            lastToolErrorCode.set("TRANSPORT_ERROR");
            log.warn("[{}] {} transport failed: {}", name, method, e.getClass().getSimpleName());
            return null;
        }
    }

    private JsonNode parseBody(String body, long id) {
        if (body == null || body.isBlank()) return null;
        for (String line : body.split("\n")) {
            if (!line.stripLeading().startsWith("data:")) continue;
            try {
                JsonNode node = MAPPER.readTree(line.stripLeading().substring(5).trim());
                if (node.path("id").isIntegralNumber() && node.path("id").asLong() == id) return node;
            } catch (RuntimeException ignored) { }
        }
        try {
            JsonNode node = MAPPER.readTree(body);
            return node.path("id").isIntegralNumber() && node.path("id").asLong() == id ? node : null;
        } catch (RuntimeException ignored) { return null; }
    }

    public synchronized void resetSession() { initialized = false; sessionId = null; expiredSession = false; }
}
