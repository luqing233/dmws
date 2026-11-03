package fun.luqing.dmws.client;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import fun.luqing.dmws.config.McpConfig;
import fun.luqing.dmws.plugin.tomatoplugin.util.TomatoLogContext;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * MCP（Model Context Protocol）Streamable HTTP 客户端。
 * 只实现本项目的需要：initialize 建会话、tools/call 调工具。
 * <p>
 * 握手流程：POST initialize 拿到 mcp-session-id，之后每次请求携带该头，
 * 并在会话建立后发送 notifications/initialized 通知。
 */
@Slf4j
public class McpClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PROTOCOL_VERSION = "2024-11-05";

    private final String name;
    private final McpConfig.McpServer server;
    private final HttpClient httpClient;

    private volatile String sessionId;
    private volatile boolean initialized;
    /** 最近一次调用是否因传输层问题失败（用于决定是否需要重建会话） */
    private volatile boolean sessionBroken;
    /** 最近一次工具调用返回的结构化错误码，成功时为 null */
    private volatile String lastToolErrorCode;

    /**
     * 返回最近一次工具调用的结构化错误码（如 CHAPTER_NOT_DOWNLOADED），
     * 未出错时返回 null。
     */
    public String getLastToolErrorCode() {
        return lastToolErrorCode;
    }

    /**
     * 从工具错误内容中提取 code 字段，兼容 JSON 与纯文本两种情况。
     */
    private String extractErrorCode(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            JsonNode node = MAPPER.readTree(text.trim());
            JsonNode code = node.path("code");
            return code.isMissingNode() || code.isNull() ? null : code.asText();
        } catch (Exception ignored) {
            return null;
        }
    }

    public McpClient(String name, McpConfig.McpServer server) {
        this.name = name;
        this.server = server;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, server.getTimeout())))
                .build();
    }

    /**
     * 调用一个 MCP 工具，返回工具输出的 JSON 对象。
     *
     * @param tool  工具名
     * @param args  工具参数，可为 null
     * @return 工具返回的 JSON；调用失败返回 null
     */
    public JsonNode callTool(String tool, ObjectNode args) {
        JsonNode result = callToolInternal(tool, args);
        // 只有真正的传输层失败（非工具业务错误）才重建会话重试
        if (result == null && sessionBroken) {
            sessionBroken = false;
            resetSession();
            result = callToolInternal(tool, args);
        }
        return result;
    }

    private JsonNode callToolInternal(String tool, ObjectNode args) {
        if (!ensureSession()) {
            sessionBroken = true;
            return null;
        }

        ObjectNode params = MAPPER.createObjectNode();
        params.put("name", tool);
        params.set("arguments", args == null ? MAPPER.createObjectNode() : args);

        JsonNode response = rpc("tools/call", params, server.getReadTimeout());
        if (response == null) {
            // rpc 返回 null 代表传输失败或 HTTP 非 2xx，视为会话可能失效
            sessionBroken = true;
            return null;
        }

        if (response.has("error")) {
            log.warn("[{}] 调用 {} 失败: {}", name, tool, abbreviate(response.get("error").toString()));
            return null;
        }

        JsonNode result = response.path("result");
        if (result.path("isError").asBoolean(false)) {
            String raw = extractText(result);
            // 工具业务错误（如"章节未下载"）属于预期内的状态，降为 debug 避免刷屏
            log.debug("[{}] 工具 {} 返回错误: {}", name, tool, abbreviate(raw));
            lastToolErrorCode = extractErrorCode(raw);
            return null;
        }

        lastToolErrorCode = null;

        String text = extractText(result);
        if (text == null || text.isBlank()) {
            log.debug("[{}] 工具 {} 返回空内容", name, tool);
            return null;
        }
        try {
            return MAPPER.readTree(text);
        } catch (Exception e) {
            log.error("[{}] 工具 {} 返回内容不是合法 JSON: {}", name, tool, abbreviate(text));
            return null;
        }
    }

    /**
     * 从 tools/call 的 result.content 中取出第一段文本。
     */
    private String extractText(JsonNode result) {
        JsonNode content = result.path("content");
        if (content.isArray()) {
            for (JsonNode node : content) {
                if ("text".equals(node.path("type").asText())) {
                    return node.path("text").asText();
                }
            }
        }
        return null;
    }

    private synchronized boolean ensureSession() {
        if (initialized && sessionId != null) return true;
        return initialize();
    }

    private boolean initialize() {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.set("capabilities", MAPPER.createObjectNode());
        ObjectNode clientInfo = MAPPER.createObjectNode();
        clientInfo.put("name", "dmws");
        clientInfo.put("version", "1.0");
        params.set("clientInfo", clientInfo);

        try {
            ObjectNode payload = MAPPER.createObjectNode();
            payload.put("jsonrpc", "2.0");
            payload.put("id", 1);
            payload.put("method", "initialize");
            payload.set("params", params);

            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(server.getUrl()))
                    .timeout(Duration.ofSeconds(server.getTimeout()))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(payload)));
            if (server.getToken() != null && !server.getToken().isBlank()) {
                builder.header("Authorization", server.getToken());
            }

            HttpResponse<String> resp = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                log.error("[{}] MCP 初始化失败: HTTP {}", name, resp.statusCode());
                return false;
            }

            String sid = resp.headers().firstValue("mcp-session-id").orElse(null);
            if (sid == null || sid.isBlank()) {
                log.error("[{}] MCP 初始化未返回 mcp-session-id", name);
                return false;
            }
            sessionId = sid;
            sendInitializedNotification();
            initialized = true;
            TomatoLogContext.info(log, "[{}] MCP 会话已建立: {}", name, abbreviate(sid));
            return true;
        } catch (Exception e) {
            log.error("[{}] MCP 初始化异常: {}", name, e.getMessage());
            return false;
        }
    }

    /**
     * 会话建立后按协议要求发送 notifications/initialized。
     * 通知没有响应体，失败也不阻断后续调用。
     */
    private void sendInitializedNotification() {
        try {
            ObjectNode payload = MAPPER.createObjectNode();
            payload.put("jsonrpc", "2.0");
            payload.put("method", "notifications/initialized");

            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(server.getUrl()))
                    .timeout(Duration.ofSeconds(server.getTimeout()))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .header("mcp-session-id", sessionId)
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(payload)));
            if (server.getToken() != null && !server.getToken().isBlank()) {
                builder.header("Authorization", server.getToken());
            }
            httpClient.send(builder.build(), HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            log.debug("[{}] 发送 initialized 通知失败: {}", name, e.getMessage());
        }
    }

    private JsonNode rpc(String method, ObjectNode params, int timeoutSeconds) {
        try {
            ObjectNode payload = MAPPER.createObjectNode();
            payload.put("jsonrpc", "2.0");
            payload.put("id", 2);
            payload.put("method", method);
            payload.set("params", params);

            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(server.getUrl()))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .header("mcp-session-id", sessionId)
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(payload)));
            if (server.getToken() != null && !server.getToken().isBlank()) {
                builder.header("Authorization", server.getToken());
            }

            HttpResponse<String> resp = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                log.warn("[{}] {} 返回 HTTP {}: {}", name, method, resp.statusCode(), abbreviate(resp.body()));
                return null;
            }
            return parseBody(resp.body());
        } catch (Exception e) {
            log.error("[{}] {} 请求异常: {}", name, method, e.getMessage());
            return null;
        }
    }

    /**
     * 响应体可能是直接的 JSON，也可能是 SSE 的 data: 行，两种都要支持。
     */
    private JsonNode parseBody(String body) {
        if (body == null || body.isBlank()) return null;

        for (String line : body.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("data:")) {
                String json = trimmed.substring(5).trim();
                if (!json.isEmpty()) {
                    try {
                        return MAPPER.readTree(json);
                    } catch (Exception ignored) {
                        // 继续找下一条 data 行
                    }
                }
            }
        }

        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            log.warn("[{}] 无法解析响应体: {}", name, abbreviate(body));
            return null;
        }
    }

    public synchronized void resetSession() {
        initialized = false;
        sessionId = null;
    }

    private String abbreviate(String text) {
        if (text == null) return "";
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }
}
