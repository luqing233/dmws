package fun.luqing.dmws.config;

import com.mikuac.shiro.core.BotContainer;
import com.mikuac.shiro.properties.WebSocketClientProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.Set;

/**
 * Emits explicit database and OneBot connection state without exposing credentials.
 */
@Slf4j
@Component
public class ConnectionStatusLogger {

    private static final long DISCONNECTED_WARNING_INTERVAL_MS = 30_000L;

    private final DataSource dataSource;
    private final DataSourceProperties dataSourceProperties;
    private final WebSocketClientProperties webSocketClientProperties;
    private final DmwConfig dmwConfig;
    private final BotContainer botContainer;

    private volatile Boolean lastConnected;
    private volatile long lastDisconnectedWarningAt;

    public ConnectionStatusLogger(
            @Qualifier("dmwDataSource") DataSource dataSource,
            @Qualifier("dmwDataSourceProperties") DataSourceProperties dataSourceProperties,
            WebSocketClientProperties webSocketClientProperties,
            DmwConfig dmwConfig,
            BotContainer botContainer
    ) {
        this.dataSource = dataSource;
        this.dataSourceProperties = dataSourceProperties;
        this.webSocketClientProperties = webSocketClientProperties;
        this.dmwConfig = dmwConfig;
        this.botContainer = botContainer;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logStartupConnectionState() {
        try {
            Integer result = new JdbcTemplate(dataSource).queryForObject("select 1", Integer.class);
            log.info("数据库连接成功: url={}, validation={}", safeJdbcUrl(), result);
        } catch (RuntimeException exception) {
            log.error("数据库连接检查失败: url={}", safeJdbcUrl(), exception);
        }

        if (!Boolean.TRUE.equals(webSocketClientProperties.getEnable())) {
            log.info("OneBot WebSocket 客户端未启用");
            return;
        }
        log.info("OneBot WebSocket 正在连接: url={}, expectedBotId={}",
                webSocketClientProperties.getUrl(), dmwConfig.getBotId());
    }

    @Scheduled(fixedDelay = 5_000L, initialDelay = 5_000L)
    public void monitorOneBotConnection() {
        if (!Boolean.TRUE.equals(webSocketClientProperties.getEnable())) {
            return;
        }

        boolean connected = !botContainer.robots.isEmpty();
        if (lastConnected == null) {
            lastConnected = connected;
            if (connected) {
                logConnected();
            } else {
                lastDisconnectedWarningAt = System.currentTimeMillis();
            }
            return;
        }
        if (connected != lastConnected) {
            lastConnected = connected;
            if (connected) {
                logConnected();
            } else {
                log.warn("OneBot WebSocket 已断开，客户端将自动重连: url={}",
                        webSocketClientProperties.getUrl());
                lastDisconnectedWarningAt = System.currentTimeMillis();
            }
            return;
        }

        long now = System.currentTimeMillis();
        if (!connected && now - lastDisconnectedWarningAt >= DISCONNECTED_WARNING_INTERVAL_MS) {
            log.warn("OneBot WebSocket 尚未连接，继续重试: url={}, expectedBotId={}",
                    webSocketClientProperties.getUrl(), dmwConfig.getBotId());
            lastDisconnectedWarningAt = now;
        }
    }

    private void logConnected() {
        Set<Long> botIds = Set.copyOf(botContainer.robots.keySet());
        log.info("OneBot WebSocket 连接成功: botIds={}", botIds);
    }

    private String safeJdbcUrl() {
        String url = dataSourceProperties.getUrl();
        if (url == null) {
            return "<not-configured>";
        }
        int queryIndex = url.indexOf('?');
        return queryIndex >= 0 ? url.substring(0, queryIndex) : url;
    }
}
