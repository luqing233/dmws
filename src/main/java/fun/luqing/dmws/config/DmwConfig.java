package fun.luqing.dmws.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "dmws")
public class DmwConfig {
    private long botId;
    private String botName;
    private long[] master;
    private int fileServerPort = 8765;
    private String fileServerHost = "127.0.0.1";
    private boolean mcpServerEnabled = false;
    private String mcpServerToken;

    /**
     * MCP 经济操作者及本地番茄下载器配置。
     */
    private McpConfig mcp = new McpConfig();

    /**
     * 判断指定ID是否为主人
     * @param id 要检查的ID
     * @return 如果是主人返回true，否则返回false
     */
    public boolean isMaster(long id) {
        if (master == null) {
            return false;
        }
        for (long masterId : master) {
            if (masterId == id) {
                return true;
            }
        }
        return false;
    }
}
