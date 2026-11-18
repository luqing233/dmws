package fun.luqing.dmws.common.utils;

import lombok.extern.slf4j.Slf4j;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledExecutorService;

@Slf4j
public final class ImageDownloadUtil {
    private static final Path CACHE_DIR = Path.of("temp/images");
    private static final Duration DOWNLOAD_BUDGET = Duration.ofSeconds(35);
    private static final int MAX_BYTES = 20 * 1024 * 1024;
    private static final ScheduledExecutorService DEADLINES = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "image-download-deadline");
        thread.setDaemon(true);
        return thread;
    });

    private ImageDownloadUtil() {}

    /** 完整响应体也受期限约束；只有完整图片可以成为可见缓存。 */
    public static Path getOrDownload(String imageUrl) {
        return getOrDownload(imageUrl, CACHE_DIR, DOWNLOAD_BUDGET, MAX_BYTES);
    }

    static Path getOrDownload(String imageUrl, Path directory, Duration budget, int maxBytes) {
        long deadline = System.nanoTime() + budget.toNanos();
        try {
            URI uri = URI.create(imageUrl);
            if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
                throw new IOException("不支持的图片协议");
            }
            Files.createDirectories(directory);
            List<URI> sources = downloadSources(uri);
            String hash = sha256(imageUrl);
            for (String ext : new String[]{".jpg", ".png", ".gif", ".webp", ".bmp"}) {
                Path cached = directory.resolve(hash + ext);
                if (Files.isRegularFile(cached) && Files.size(cached) > 0 && Files.size(cached) <= maxBytes
                        && Files.isReadable(cached)) return cached;
            }
            for (int attempt = 0; attempt < 3; attempt++) {
                if (Thread.currentThread().isInterrupted()) return null;
                Path temporary = null;
                HttpURLConnection connection = null;
                ScheduledFuture<?> expiry = null;
                URI source = sources.get(attempt % sources.size());
                long attemptDeadline = sources.size() > 1 && attempt == 0
                        ? Math.min(deadline, System.nanoTime() + Duration.ofSeconds(12).toNanos()) : deadline;
                try {
                    connection = (HttpURLConnection) source.toURL().openConnection();
                    HttpURLConnection active = connection;
                    expiry = DEADLINES.schedule(active::disconnect, Math.max(1, attemptDeadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                    connection.setConnectTimeout(remainingMillis(attemptDeadline, 5_000));
                    connection.setReadTimeout(remainingMillis(attemptDeadline, 8_000));
                    connection.setRequestProperty("User-Agent", "Mozilla/5.0");
                    connection.setRequestProperty("Referer", "https://www.pixiv.net/");
                    int status = connection.getResponseCode();
                    if (status != 200) throw new IOException("HTTP " + status);
                    String extension = extension(connection.getContentType());
                    if (extension == null) throw new IOException("响应不是受支持的图片");
                    long expected = connection.getContentLengthLong();
                    if (expected > maxBytes) throw new IOException("图片超过大小限制");
                    temporary = Files.createTempFile(directory, hash + "-", ".part");
                    long count = 0;
                    try (InputStream input = connection.getInputStream(); OutputStream output = Files.newOutputStream(temporary)) {
                        byte[] buffer = new byte[16 * 1024];
                        while (true) {
                            if (Thread.currentThread().isInterrupted()) throw new IOException("下载已取消");
                            connection.setReadTimeout(remainingMillis(attemptDeadline, 8_000));
                            int length = input.read(buffer);
                            if (length < 0) break;
                            count += length;
                            if (count > maxBytes) throw new IOException("图片超过大小限制");
                            output.write(buffer, 0, length);
                        }
                    }
                    remainingMillis(attemptDeadline, 8_000);
                    if (count == 0 || (expected >= 0 && count != expected)) throw new IOException("图片下载不完整");
                    Path target = directory.resolve(hash + extension);
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    log.debug("图片下载完成: file={}, bytes={}", target.getFileName(), count);
                    return target;
                } catch (IOException exception) {
                    log.warn("图片下载未完成: host={}, attempt={}, reason={}", source.getHost(), attempt + 1, exception.getMessage());
                    if (System.nanoTime() >= deadline || Thread.currentThread().isInterrupted()) return null;
                } finally {
                    if (expiry != null) expiry.cancel(false);
                    if (connection != null) connection.disconnect();
                    if (temporary != null) Files.deleteIfExists(temporary);
                }
            }
        } catch (IOException | IllegalArgumentException exception) {
            log.warn("图片下载失败: {}", exception.getMessage());
        }
        return null;
    }

    // 仅识别默认 Pixiv 代理的标准图片路径，不改写其他自定义代理。
    static List<URI> downloadSources(URI uri) {
        String path = uri.getRawPath();
        if ("i.pixiv.re".equalsIgnoreCase(uri.getHost()) && uri.getUserInfo() == null && uri.getPort() == -1
                && path != null && (path.startsWith("/img-original/") || path.startsWith("/img-master/") || path.startsWith("/c/"))) {
            String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
            return List.of(URI.create("https://i.pximg.net" + path + query), uri);
        }
        return List.of(uri);
    }

    private static int remainingMillis(long deadline, int maximum) throws IOException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new IOException("图片下载超时");
        return (int) Math.max(1, Math.min(maximum, TimeUnit.NANOSECONDS.toMillis(remaining)));
    }

    private static String extension(String contentType) {
        if (contentType == null) return null;
        return switch (contentType.toLowerCase(Locale.ROOT).split(";", 2)[0].trim()) {
            case "image/jpeg", "image/jpg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            case "image/bmp" -> ".bmp";
            default -> null;
        };
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
