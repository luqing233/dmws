package fun.luqing.dmws;

import fun.luqing.dmws.common.server.ImageFileServerLifecycle;
import fun.luqing.dmws.config.ConfigManager;
import fun.luqing.dmws.config.DmwConfig;
import com.mikuac.shiro.core.BotContainer;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class ImageFileServerLifecycleTests {
    @Test
    void lifecycleStartsOnceServesRequestsAndCanStopAndRestart() throws Exception {
        DmwConfig config = new DmwConfig();
        config.setFileServerHost("127.0.0.1");
        config.setFileServerPort(0);
        var lifecycle = new ImageFileServerLifecycle(config);
        var manager = new ConfigManager(new BotContainer(), config, lifecycle);
        try (var client = HttpClient.newHttpClient()) {
            lifecycle.initFileServer();
            var first = lifecycle.getImageFileServer();
            assertTrue(first.getPort() > 0);
            manager.initFileServer();
            assertSame(first, manager.getImageFileServer());
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + first.getPort()
                    + "/missing-lifecycle-test.png")).timeout(Duration.ofSeconds(3)).build();
            assertEquals(404, client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode());
            manager.destroy();
            lifecycle.destroy();
            assertNull(manager.getImageFileServer());
            lifecycle.initFileServer();
            assertNotSame(first, manager.getImageFileServer());
        } finally {
            lifecycle.destroy();
        }
    }
}
