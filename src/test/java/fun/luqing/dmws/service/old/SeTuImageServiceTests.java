package fun.luqing.dmws.service.old;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class SeTuImageServiceTests {
    private JSONObject image(long pid, String url) {
        return new JSONObject().put("title", "作品" + pid).put("author", "画师" + pid)
                .put("pid", pid).put("urls", new JSONObject().put("regular", url));
    }

    @Test void aSlowImageDoesNotLoseMetadataOrBlockCompletedImages() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch cancelled = new CountDownLatch(1);
            var service = new SeTuImageService(executor, url -> {
                if (url.equals("fast")) return Path.of("fast.jpg");
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException exception) { cancelled.countDown(); Thread.currentThread().interrupt(); }
                return null;
            }, Duration.ofMillis(200));
            var result = assertTimeoutPreemptively(Duration.ofSeconds(2), () -> service.download(
                    new JSONArray().put(image(10, "slow")).put(image(20, "fast")), List.of("regular")));
            assertEquals("作品10", result.get(0).title());
            assertEquals("https://www.pixiv.net/artworks/10", result.get(0).source());
            assertEquals("图片下载超时", result.get(0).error());
            assertEquals(Path.of("fast.jpg"), result.get(1).path());
            assertTrue(cancelled.await(1, TimeUnit.SECONDS));
        }
    }

    @Test void rejectedAndMissingDownloadsRemainUsableResults() {
        var executor = Executors.newSingleThreadExecutor();
        executor.shutdown();
        var service = new SeTuImageService(executor);
        var result = service.download(new JSONArray().put(image(1, "url")).put(new JSONObject().put("pid", 2)), List.of("regular"));
        assertTrue(result.get(0).error().contains("繁忙"));
        assertEquals("没有可用的图片地址", result.get(1).error());
    }
}
