package fun.luqing.dmws.common.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Slf4j
public class ImageFileServer {

    private final HttpServer server;
    private final ExecutorService executor;
    @Getter
    private final int port;

    public ImageFileServer(String host, int port) throws IOException {
        this.port = port;
        this.server = HttpServer.create(new InetSocketAddress(host, port), 0);
        this.server.createContext("/", this::handle);
        // 守护线程：即使 stop() 未被调用也不会阻塞 JVM 退出
        this.executor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "image-file-server");
            thread.setDaemon(true);
            return thread;
        });
        this.server.setExecutor(executor);
    }

    public void start() {
        server.start();
        log.info("图片文件服务器已启动: http://{}:{}/", server.getAddress().getHostString(), port);
    }

    public void stop() {
        server.stop(0);
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
                log.warn("图片文件服务器线程池未能及时终止");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("图片文件服务器已停止");
    }

    private void handle(HttpExchange exchange) throws IOException {
        String filename = exchange.getRequestURI().getPath().substring(1);
        if (!isSafeFilename(filename)) {
            exchange.sendResponseHeaders(400, -1);
            exchange.close();
            return;
        }

        Path file;
        try {
            Path base = Path.of("temp/images").toAbsolutePath().normalize();
            file = base.resolve(filename).normalize();
            // 归一化后必须仍位于缓存目录内，防止路径穿越
            if (!file.startsWith(base)) {
                exchange.sendResponseHeaders(400, -1);
                exchange.close();
                return;
            }
        } catch (InvalidPathException e) {
            exchange.sendResponseHeaders(400, -1);
            exchange.close();
            return;
        }

        if (!Files.exists(file) || !Files.isReadable(file) || !Files.isRegularFile(file)) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }

        byte[] bytes = Files.readAllBytes(file);
        String contentType = Files.probeContentType(file);
        exchange.getResponseHeaders().set("Content-Type", contentType != null ? contentType : "image/jpeg");
        exchange.getResponseHeaders().set("Cache-Control", "public, max-age=86400");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private boolean isSafeFilename(String filename) {
        return !filename.isEmpty()
                && !filename.contains("..")
                && !filename.contains("/")
                && !filename.contains("\\")
                && !filename.contains(":")
                && !filename.contains("\0");
    }
}
