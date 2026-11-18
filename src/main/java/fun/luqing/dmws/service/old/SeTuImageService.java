package fun.luqing.dmws.service.old;

import fun.luqing.dmws.common.utils.ImageDownloadUtil;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Service
@Slf4j
public class SeTuImageService {
    private final ExecutorService executor;
    private final Function<String, Path> downloader;
    private final Duration budget;

    @Autowired
    public SeTuImageService(@Qualifier("imageDownloadExecutor") ExecutorService executor) {
        this(executor, ImageDownloadUtil::getOrDownload, Duration.ofSeconds(45));
    }

    SeTuImageService(ExecutorService executor, Function<String, Path> downloader, Duration budget) {
        this.executor = executor;
        this.downloader = downloader;
        this.budget = budget;
    }

    public List<ImageResult> download(JSONArray data, List<String> sizes) {
        long deadline = System.nanoTime() + budget.toNanos();
        List<ImageResult> images = new ArrayList<>();
        List<Future<Path>> futures = new ArrayList<>();
        for (int i = 0; i < Math.min(data.length(), 5); i++) {
            JSONObject row = data.getJSONObject(i);
            String url = imageUrl(row.optJSONObject("urls"), sizes);
            ImageResult image = new ImageResult(row.optString("title", "未命名作品"),
                    row.optString("author", "未知作者"), row.optLong("pid"), url, null, "没有可用的图片地址");
            Future<Path> future = null;
            if (url != null) {
                try {
                    future = executor.submit(() -> downloader.apply(url));
                } catch (java.util.concurrent.RejectedExecutionException exception) {
                    image = image.withDownload(null, "下载繁忙，请稍后重试");
                }
            }
            images.add(image);
            futures.add(future);
        }
        try {
            for (int i = 0; i < images.size(); i++) {
                Future<Path> future = futures.get(i);
                if (future == null) continue;
                try {
                    Path path = future.isDone() ? future.get()
                            : future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                    images.set(i, images.get(i).withDownload(path, path == null ? "图片下载失败或超时" : null));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    images.set(i, images.get(i).withDownload(null, "下载已取消"));
                } catch (java.util.concurrent.TimeoutException exception) {
                    images.set(i, images.get(i).withDownload(null, "图片下载超时"));
                } catch (java.util.concurrent.ExecutionException exception) {
                    log.warn("涩图下载任务失败", exception.getCause());
                    images.set(i, images.get(i).withDownload(null, "图片下载失败"));
                }
            }
        } finally {
            for (Future<Path> future : futures) if (future != null && !future.isDone()) future.cancel(true);
        }
        return List.copyOf(images);
    }

    private static String imageUrl(JSONObject urls, List<String> sizes) {
        if (urls == null) return null;
        for (String size : sizes) if (!urls.optString(size).isBlank()) return urls.getString(size);
        for (String size : List.of("regular", "small", "original")) {
            if (!urls.optString(size).isBlank()) return urls.getString(size);
        }
        return null;
    }

    public record ImageResult(String title, String author, long pid, String imageUrl, Path path, String error) {
        ImageResult withDownload(Path path, String error) {
            return new ImageResult(title, author, pid, imageUrl, path, error);
        }

        public String source() {
            return pid > 0 ? "https://www.pixiv.net/artworks/" + pid : imageUrl;
        }
    }
}
