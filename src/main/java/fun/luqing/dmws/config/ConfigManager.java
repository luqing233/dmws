package fun.luqing.dmws.config;

import com.mikuac.shiro.core.BotContainer;
import fun.luqing.dmws.common.server.ImageFileServer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@Slf4j
@Getter
@RequiredArgsConstructor
public class ConfigManager {

    private final BotContainer botContainer;
    private final DmwConfig dmwConfig;

    private ImageFileServer imageFileServer;

    @PostConstruct
    public void initFileServer() {
        startFileServer();
    }

    private void startFileServer() {
        try {
            int port = dmwConfig.getFileServerPort();
            String host = dmwConfig.getFileServerHost();
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

}
