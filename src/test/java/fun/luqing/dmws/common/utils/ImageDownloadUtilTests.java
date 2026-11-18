package fun.luqing.dmws.common.utils;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ImageDownloadUtilTests {
    @TempDir Path directory;
    HttpServer server;
    ExecutorService executor;
    final byte[] png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aN1sAAAAASUVORK5CYII=");

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.start();
    }
    @AfterEach void stop() {
        server.stop(0);
        executor.shutdownNow();
    }
    String url(String path) { return "http://127.0.0.1:" + server.getAddress().getPort() + path; }

    @Test void permitsOriginFallbackOnlyForTheKnownDefaultProxyAndPreservesCustomSources() {
        URI proxy = URI.create("https://i.pixiv.re/img-master/img/a.jpg?x=1");
        assertEquals(java.util.List.of(URI.create("https://i.pximg.net/img-master/img/a.jpg?x=1"), proxy),
                ImageDownloadUtil.downloadSources(proxy));
        for (String source : new String[]{"https://custom.example/img-master/a.jpg", "https://i.pixiv.re.evil.test/img-master/a.jpg",
                "https://i.pixiv.re/not-an-image", "https://i.pixiv.re:1234/img-master/a.jpg"}) {
            URI uri = URI.create(source);
            assertEquals(java.util.List.of(uri), ImageDownloadUtil.downloadSources(uri));
        }
    }

    @Test void publishesCompleteFileAtomicallyAndReusesCache() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/image", exchange -> {
            calls.incrementAndGet();
            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, png.length);
            try (var output = exchange.getResponseBody()) { output.write(png); }
        });
        Path path = ImageDownloadUtil.getOrDownload(url("/image"), directory, Duration.ofSeconds(2), 1024);
        assertNotNull(path);
        assertArrayEquals(png, Files.readAllBytes(path));
        assertEquals(path, ImageDownloadUtil.getOrDownload(url("/image"), directory, Duration.ofSeconds(2), 1024));
        assertEquals(1, calls.get());
        try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
    }

    @Test void aSlowResponseBodyCannotOutliveTheDownloadBudget() throws Exception {
        server.createContext("/slow", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) {
                for (int i = 0; i < 100; i++) {
                    output.write(1); output.flush();
                    try { Thread.sleep(50); } catch (InterruptedException exception) { return; }
                }
            } catch (java.io.IOException ignored) { }
        });
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertNull(
                ImageDownloadUtil.getOrDownload(url("/slow"), directory, Duration.ofMillis(250), 1024)));
        try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
    }

    @Test void rejectsNonImagesOversizedAndTruncatedBodiesWithoutCachingThem() throws Exception {
        server.createContext("/html", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, 1);
            try (var output = exchange.getResponseBody()) { output.write(1); }
        });
        server.createContext("/large", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) { output.write(new byte[2048]); }
        });
        server.createContext("/partial", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, 100);
            try (var output = exchange.getResponseBody()) { output.write(1); } catch (java.io.IOException ignored) { }
            exchange.close();
        });
        for (String path : new String[]{"/html", "/large", "/partial"}) {
            assertNull(ImageDownloadUtil.getOrDownload(url(path), directory, Duration.ofMillis(300), 1024), path);
        }
        try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
    }
}
