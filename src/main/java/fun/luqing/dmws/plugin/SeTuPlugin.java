package fun.luqing.dmws.plugin;

import com.mikuac.shiro.annotation.AnyMessageHandler;
import com.mikuac.shiro.annotation.MessageHandlerFilter;
import com.mikuac.shiro.annotation.common.Shiro;
import com.mikuac.shiro.common.utils.MsgUtils;
import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.dto.event.message.AnyMessageEvent;
import fun.luqing.dmws.common.annotation.CommandInfo;
import fun.luqing.dmws.common.utils.ImageDownloadUtil;
import fun.luqing.dmws.service.old.SeTuService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Shiro
@Component
@Slf4j
@RequiredArgsConstructor
public class SeTuPlugin {
    private final SeTuService seTuService;

    private static final int MAX_IMAGES = 5;
    private static final int DOWNLOAD_TIMEOUT_SECONDS = 45;
    private static final int SEND_INTERVAL_MS = 500;

    @AnyMessageHandler
    @MessageHandlerFilter(startWith = "涩图 ")
    @Async("taskExecutor")
    @CommandInfo(startWith = "涩图 ", desc = "获取指定tag的涩图")
    public void getSeTu(Bot bot, AnyMessageEvent event) {
        try {
            String msgText = event.getMessage().trim();
            if (!msgText.startsWith("涩图 tag=")) {
                bot.sendMsg(event, "请使用格式：涩图 tag=标签1 标签2 [参数]", false);
                return;
            }

            String content = msgText.substring("涩图 tag=".length()).trim();
            if (content.isEmpty()) {
                bot.sendMsg(event, "请提供标签，例如：涩图 tag=猫 芙宁娜 num=2 r18=1 size=original", false);
                return;
            }

            // 分离标签和参数
            String[] parts = content.split(" (?=[a-zA-Z]+\\=)");
            String tagsPart = parts[0];
            String paramsPart = parts.length > 1 ? parts[1] : "";

            List<String> tagList = List.of(tagsPart.trim().split("\\s+"));

            // 解析参数
            int num = 1;
            int r18 = 0;
            List<String> sizeList = List.of("original");
            String proxy = null;
            Boolean excludeAI = false;

            if (!paramsPart.isEmpty()) {
                String[] paramPairs = paramsPart.split("[,，]");
                for (String pair : paramPairs) {
                    String[] kv = pair.split("=");
                    if (kv.length != 2) continue;
                    String key = kv[0].trim().toLowerCase();
                    String value = kv[1].trim();
                    switch (key) {
                        case "num":
                            try { num = Math.min(Math.max(Integer.parseInt(value), 1), MAX_IMAGES); } catch (Exception ignored) {}
                            break;
                        case "r18":
                            try { r18 = Integer.parseInt(value); } catch (Exception ignored) {}
                            break;
                        case "size":
                            sizeList = List.of(value.split("[,，]"));
                            break;
                        case "proxy":
                            proxy = value;
                            break;
                        case "excludeai":
                            excludeAI = Boolean.parseBoolean(value);
                            break;
                    }
                }
            }

            // 调用 Lolicon API v2 获取图片信息
            JSONArray res = seTuService.getLoliconImageByKeyword(tagList, null, r18, num, sizeList, proxy, excludeAI);

            if (res.isEmpty()) {
                bot.sendMsg(event, "没有获取到图片", false);
                return;
            }

            JSONObject first = res.getJSONObject(0);
            if (first.has("error")) {
                bot.sendMsg(event, "获取失败：" + first.getString("error"), false);
                return;
            }

            int total = res.length();
            bot.sendMsg(event, String.format("已找到 %d 张图片，正在下载...", total), false);

            // 并行下载图片到本地
            List<ImageResult> results = downloadImages(res, sizeList);

            // 统计结果
            long successCount = results.stream().filter(r -> r.path != null).count();
            long failCount = results.stream().filter(r -> r.path == null).count();

            if (successCount == 0) {
                bot.sendMsg(event, "所有图片下载失败，请稍后重试", false);
                // 发送文本信息作为降级
                sendTextFallback(bot, event, results);
                return;
            }

            if (failCount > 0) {
                bot.sendMsg(event, String.format("下载完成：%d 张成功，%d 张失败", successCount, failCount), false);
            }

            // 逐张发送图片
            int sent = 0;
            for (ImageResult result : results) {
                if (result.path == null) continue;

                try {
                    String msg = buildImageMessage(result);
                    bot.sendMsg(event, msg, false);
                    sent++;

                    // 发送间隔，避免被风控
                    if (sent < successCount) {
                        Thread.sleep(SEND_INTERVAL_MS);
                    }
                } catch (Exception e) {
                    log.error("发送图片失败: {}", result.title, e);
                }
            }

            log.info("涩图发送完成: {}/{} 张成功", sent, total);

        } catch (Exception e) {
            log.error("获取涩图失败", e);
            bot.sendMsg(event, "获取涩图出错：" + e.getMessage(), false);
        }
    }

    /**
     * 并行下载图片到本地临时文件
     */
    private List<ImageResult> downloadImages(JSONArray imageData, List<String> sizeList) {
        List<CompletableFuture<ImageResult>> futures = new ArrayList<>();

        for (int i = 0; i < imageData.length(); i++) {
            JSONObject setu = imageData.getJSONObject(i);
            String title = setu.optString("title", "未知标题");
            String author = setu.optString("author", "未知作者");
            JSONObject urls = setu.optJSONObject("urls");
            String imageUrl = extractImageUrl(urls, sizeList);

            if (imageUrl == null) {
                futures.add(CompletableFuture.completedFuture(
                        new ImageResult(title, author, null, null, "无可用URL")));
                continue;
            }

            futures.add(CompletableFuture.supplyAsync(() -> {
                Path localPath = ImageDownloadUtil.getOrDownload(imageUrl);
                if (localPath != null) {
                    return new ImageResult(title, author, localPath, imageUrl, null);
                } else {
                    return new ImageResult(title, author, null, imageUrl, "下载失败");
                }
            }));
        }

        // 等待所有下载完成（最多等 DOWNLOAD_TIMEOUT_SECONDS 秒）
        return futures.stream()
                .map(f -> {
                    try {
                        return f.get(DOWNLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        log.warn("图片下载超时或被中断: {}", e.getMessage());
                        return new ImageResult("未知", "未知", null, null, "超时");
                    }
                })
                .toList();
    }

    /**
     * 从 urls JSON 中提取图片 URL
     */
    private String extractImageUrl(JSONObject urls, List<String> sizeList) {
        if (urls == null) return null;
        for (String size : sizeList) {
            if (urls.has(size)) {
                return urls.getString(size);
            }
        }
        // 取第一个可用的URL
        for (String key : urls.keySet()) {
            return urls.getString(key);
        }
        return null;
    }

    /**
     * 构建包含图片和信息的消息
     */
    private String buildImageMessage(ImageResult result) {
        // 使用 file:// 协议发送本地图片，QQ客户端无需访问外网
        String filePath = result.path.toAbsolutePath().toString().replace("\\", "/");
        return MsgUtils.builder()
                .text("「" + result.title + "」 / " + result.author)
                .text("\n")
                .img("file:///" + filePath)
                .build();
    }

    /**
     * 发送文本降级信息
     */
    private void sendTextFallback(Bot bot, AnyMessageEvent event, List<ImageResult> results) {
        StringBuilder sb = new StringBuilder("图片信息如下：\n");
        for (int i = 0; i < results.size(); i++) {
            ImageResult r = results.get(i);
            sb.append(String.format("%d. 《%s》 - %s\n", i + 1, r.title, r.author));
            if (r.imageUrl != null) {
                sb.append("   链接: ").append(r.imageUrl).append("\n");
            }
        }
        bot.sendMsg(event, sb.toString(), false);
    }

    /**
     * 图片下载结果
     */
    private record ImageResult(
            String title,
            String author,
            Path path,
            String imageUrl,
            String error
    ) {}
}
