package fun.luqing.dmws.config;

import lombok.Getter;
import lombok.Setter;

/**
 * 番茄小说 MCP 服务配置。
 * 只连接本地下载器（downloader）的 Streamable HTTP 服务。
 * 真实的 url 与 token 请在 application.yaml 的 dmws.mcp 下配置，不要写进源码。
 */
@Getter
@Setter
public class McpConfig {

    private McpServer downloader = new McpServer("http://localhost:8001/mcp");

    /**
     * 单个 MCP 服务端点。
     */
    @Getter
    @Setter
    public static class McpServer {
        /** 服务地址，例如 http://localhost:8000/mcp */
        private String url;
        /** Authorization 头的完整取值，例如 "Bearer xxxx" */
        private String token;
        /** 普通请求超时（秒） */
        private int timeout = 30;
        /** 长时间读取超时（秒），批量取正文时使用 */
        private int readTimeout = 300;

        public McpServer() {
        }

        public McpServer(String url) {
            this.url = url;
        }

        public boolean isConfigured() {
            return url != null && !url.isBlank();
        }
    }
}
