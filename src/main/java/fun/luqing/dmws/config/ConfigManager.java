package fun.luqing.dmws.config;

import com.mikuac.shiro.core.BotContainer;
import fun.luqing.dmws.common.server.ImageFileServer;
import fun.luqing.dmws.common.server.ImageFileServerLifecycle;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 兼容现有调用的配置门面；文件服务器生命周期由独立组件管理。 */
@Component
@Getter
@RequiredArgsConstructor
public class ConfigManager {
    private final BotContainer botContainer;
    private final DmwConfig dmwConfig;
    private final ImageFileServerLifecycle fileServerLifecycle;

    public ImageFileServer getImageFileServer() {
        return fileServerLifecycle.getImageFileServer();
    }

    public void initFileServer() {
        fileServerLifecycle.initFileServer();
    }

    public void destroy() {
        fileServerLifecycle.destroy();
    }
}