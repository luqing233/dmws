package fun.luqing.dmws.common.utils;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
public class ImageDownloadUtil {

    private static final int MAX_RETRIES = 3;
    private static final int BASE_TIMEOUT_SECONDS = 15;
    private static final Path CACHE_DIR = Path.of("temp/images");

    static {
        try {
            Files.createDirectories(CACHE_DIR);
        } catch (IOException e) {
            log.error("创建图片缓存目录失败: {}", e.getMessage());
        }
    }

    private ImageDownloadUtil() {}

    /**
     * 获取或下载图片。如果本地已缓存则直接返回，避免重复下载。
     *
     * @param imageUrl 图片URL
     * @return 本地缓存文件路径，失败返回 null
     */
    public static Path getOrDownload(String imageUrl) {
        Path cached = getCached(imageUrl);
        if (cached != null) {
            log.debug("命中缓存: {} -> {}", imageUrl, cached.getFileName());
            return cached;
        }
        return download(imageUrl, MAX_RETRIES);
    }

    /**
     * 检查是否已有缓存
     */
    private static Path getCached(String imageUrl) {
        String hash = sha256(imageUrl);
        // 尝试常见扩展名
        for (String ext : new String[]{".jpg", ".png", ".gif", ".webp", ".bmp"}) {
            Path file = CACHE_DIR.resolve(hash + ext);
            if (Files.exists(file) && Files.isReadable(file)) {
                return file;
            }
        }
        return null;
    }

    private static Path download(String imageUrl, int maxRetries) {
        String hash = sha256(imageUrl);
        String fileExt = extractFileExtension(imageUrl);
        Exception lastException = null;

        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try {
                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(BASE_TIMEOUT_SECONDS))
                        .followRedirects(HttpClient.Redirect.ALWAYS)
                        .build();

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(imageUrl))
                        .timeout(Duration.ofSeconds(30))
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .header("Referer", "https://www.pixiv.net/")
                        .build();

                HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

                if (response.statusCode() != 200) {
                    log.warn("图片下载返回非200状态码 [{}]: {} (尝试 {}/{})",
                            response.statusCode(), imageUrl, attempt + 1, maxRetries);
                    if (attempt < maxRetries - 1) {
                        sleepWithBackoff(attempt);
                    }
                    continue;
                }

                String contentType = response.headers().firstValue("Content-Type").orElse("");
                if (fileExt.isEmpty() && !contentType.isEmpty()) {
                    fileExt = extractExtensionFromContentType(contentType);
                }
                if (fileExt.isEmpty()) {
                    fileExt = ".jpg";
                }

                Path cacheFile = CACHE_DIR.resolve(hash + fileExt);
                try (InputStream is = response.body()) {
                    Files.copy(is, cacheFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }

                log.debug("图片下载成功 [{}] -> {}", imageUrl, cacheFile.getFileName());
                return cacheFile;

            } catch (IOException e) {
                lastException = e;
                log.warn("图片下载失败 [{}] (尝试 {}/{}): {}", imageUrl, attempt + 1, maxRetries, e.getMessage());
                if (attempt < maxRetries - 1) {
                    sleepWithBackoff(attempt);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("图片下载被中断: {}", imageUrl);
                return null;
            }
        }

        log.error("图片下载最终失败 [{}]: {}",
                imageUrl, lastException != null ? lastException.getMessage() : "unknown");
        return null;
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            // fallback: use hashCode as hex
            return String.format("%08x", input.hashCode());
        }
    }

    private static String extractFileExtension(String url) {
        String path = URI.create(url).getPath();
        if (path != null && path.contains(".")) {
            String ext = path.substring(path.lastIndexOf('.'));
            if (ext.length() <= 5 && ext.matches("\\.[a-zA-Z]+")) {
                return ext.toLowerCase();
            }
        }
        return "";
    }

    private static String extractExtensionFromContentType(String contentType) {
        return switch (contentType.toLowerCase()) {
            case String s when s.contains("jpeg") || s.contains("jpg") -> ".jpg";
            case String s when s.contains("png") -> ".png";
            case String s when s.contains("gif") -> ".gif";
            case String s when s.contains("webp") -> ".webp";
            case String s when s.contains("bmp") -> ".bmp";
            default -> "";
        };
    }

    private static void sleepWithBackoff(int attempt) {
        try {
            long baseMs = 1000L;
            long jitter = ThreadLocalRandom.current().nextLong(500);
            long delay = baseMs * (1L << attempt) + jitter;
            Thread.sleep(Math.min(delay, 10_000L));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
