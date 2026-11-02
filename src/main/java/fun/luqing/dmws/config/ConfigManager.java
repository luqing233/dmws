package fun.luqing.dmws.config;

import com.mikuac.shiro.core.BotContainer;
import fun.luqing.dmws.common.server.ImageFileServer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;

@Component
@Slf4j
@Getter
@RequiredArgsConstructor
public class ConfigManager {

    private static final String CONFIG_PATH = "config/config.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final BotContainer botContainer;

    private DmwConfig dmwConfig;
    private ImageFileServer imageFileServer;

    @PostConstruct
    public void initConfig() {
        try {
            File file = new File(CONFIG_PATH);

            if (!file.exists()) {
                createDefaultConfig(file);
            } else {
                loadConfig(file);
            }
            startFileServer();
        } catch (IOException e) {
            log.error("初始化配置失败", e);
            dmwConfig = new DmwConfig();
        }
    }

    private void startFileServer() {
        try {
            int port = dmwConfig.getFile_server_port();
            String host = dmwConfig.getFile_server_host();
            imageFileServer = new ImageFileServer(host, port);
            imageFileServer.start();
        } catch (IOException e) {
            log.error("图片文件服务器启动失败: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void destroy() {
        if (imageFileServer != null) {
            imageFileServer.stop();
        }
    }

    private void createDefaultConfig(File file) throws IOException {
        file.getParentFile().mkdirs();
        dmwConfig = new DmwConfig();
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(file, dmwConfig);
        log.info("默认配置已写入: {}", CONFIG_PATH);
    }

    private void loadConfig(File file) throws IOException {
        dmwConfig = MAPPER.readValue(file, DmwConfig.class);
        log.info("读取配置成功: {}", CONFIG_PATH);
    }

}
