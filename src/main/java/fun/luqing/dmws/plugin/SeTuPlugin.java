package fun.luqing.dmws.plugin;

import com.mikuac.shiro.annotation.AnyMessageHandler;
import com.mikuac.shiro.annotation.MessageHandlerFilter;
import com.mikuac.shiro.annotation.common.Shiro;
import com.mikuac.shiro.common.utils.MsgUtils;
import com.mikuac.shiro.common.utils.ShiroUtils;
import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.dto.action.response.GroupMemberInfoResp;
import com.mikuac.shiro.dto.event.message.AnyMessageEvent;
import fun.luqing.dmws.common.annotation.CommandInfo;
import fun.luqing.dmws.common.utils.ImageDownloadUtil;
import fun.luqing.dmws.config.ConfigManager;
import fun.luqing.dmws.service.old.SeTuService;
import jakarta.annotation.Resource;
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
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

@Shiro
@Component
@Slf4j
@RequiredArgsConstructor
public class SeTuPlugin {
    private final SeTuService seTuService;
    private final ConfigManager configManager;

    @Resource(name = "taskExecutor")
    private Executor executor;

    private static final int MAX_IMAGES = 5;
    private static final int DOWNLOAD_TIMEOUT_SECONDS = 45;

    @AnyMessageHandler
    @MessageHandlerFilter(startWith = "涩图 ")
    @Async("taskExecutor")
    @CommandInfo(startWith = "涩图 ", desc = "获取指定tag的涩图，格式：涩图 tag=标签1 标签2 [num=1-5] [r18=0/1/2] [size=original] [proxy=域名] [excludeai=true]")
    public void getSeTu(Bot bot, AnyMessageEvent event) {
        try {
            String msgText = event.getMessage().trim();
            if (!msgText.startsWith("涩图 tag=")) {
                reply(bot, event,
                        "请使用格式：涩图 tag=标签1 标签2 [参数]");
                return;
            }

            String content = msgText.substring("涩图 tag=".length()).trim();
            if (content.isEmpty()) {
                reply(bot, event,
                        "请提供标签，例如：涩图 tag=猫 芙宁娜 num=2");
                return;
            }

            // 分离标签和参数：参数可多组，如 "猫 芙宁娜 num=2 size=original,regular"
            String[] parts = content.split(" (?=[a-zA-Z]+=)");
            String tagsPart = parts[0];
            List<String> paramsParts = new ArrayList<>();
            for (int i = 1; i < parts.length; i++) {
                paramsParts.add(parts[i]);
            }

            List<String> tagList = List.of(tagsPart.trim().split("\\s+"));

            // 解析参数
            int num = 1;
            int r18 = 0;
            List<String> sizeList = List.of("original");
            String proxy = null;
            boolean excludeAI = false;

            for (String paramsPart : paramsParts) {
                if (paramsPart.isEmpty()) continue;
                String[] paramPairs = paramsPart.split("[,，]");
                for (String pair : paramPairs) {
                    String[] kv = pair.split("=", 2);
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

            // R18 属于高风控内容，仅允许主人或群管理员触发
            if (r18 != 0 && !isPrivileged(bot, event)) {
                reply(bot, event, "R18 模式仅限主人或本群管理员使用");
                return;
            }

            // 调用 Lolicon API v2 获取图片信息
            JSONArray res = seTuService.getLoliconImageByKeyword(tagList, null, r18, num, sizeList, proxy, excludeAI);

            if (res.isEmpty()) {
                reply(bot, event, "没有获取到图片");
                return;
            }

            JSONObject first = res.getJSONObject(0);
            if (first.has("error")) {
                reply(bot, event, "获取失败：" + first.getString("error"));
                return;
            }

            int total = res.length();
            reply(bot, event, String.format("已找到 %d 张图片，正在下载...", total));

            // 并行下载图片到本地
            List<ImageResult> results = downloadImages(res, sizeList);

            // 统计结果
            long successCount = results.stream().filter(r -> r.path != null).count();
            long failCount = results.stream().filter(r -> r.path == null).count();

            if (successCount == 0) {
                reply(bot, event, "所有图片下载失败，请稍后重试");
                // 发送文本信息作为降级
                sendTextFallback(bot, event, results);
                return;
            }

            if (failCount > 0) {
                reply(bot, event, String.format("下载完成：%d 张成功，%d 张失败", successCount, failCount));
            }

            // 构建合并转发消息
            String baseUrl = "http://" + configManager.getDmwConfig().getFileServerHost()
                    + ":" + configManager.getDmwConfig().getFileServerPort() + "/";


            List<String> messageList = new ArrayList<>();

            for (ImageResult result : results) {

                // 标题
                messageList.add("标题：" + result.title);

                // 作者
                messageList.add("作者：" + result.author);

                if (result.path == null) {

                    if (result.imageUrl != null) {
                        messageList.add("原图链接：" + result.imageUrl);
                    }

                    messageList.add("状态：下载失败");

                    continue;
                }

                String imageUrl = baseUrl + result.path.getFileName();

                // 图片链接
                messageList.add("图片链接：\n" + result.imageUrl);

                // 图片单独一个节点
                messageList.add(
                        MsgUtils.builder()
                                .img(imageUrl)
                                .build()
                );
            }

            var forwardMsg = ShiroUtils.generateForwardMsg(
                    configManager.getDmwConfig().getBotId(),
                    configManager.getDmwConfig().getBotName(),
                    messageList
            );


            boolean forwardSuccess = false;
            try {
                var forwardResult = bot.sendForwardMsg(event, forwardMsg);
                forwardSuccess = forwardResult != null
                        && forwardResult.getRetCode() == 0
                        && forwardResult.getData() != null;
            } catch (Exception e) {
                log.warn("合并转发发送异常，回退为发送链接: {}", e.getMessage());
            }

            if (forwardSuccess) {
                log.info("涩图合并转发发送完成: {} 张", results.size());
                return;
            }

            log.warn("合并转发发送失败，回退为发送链接");
            sendLinkFallback(bot, event, results, baseUrl);

        } catch (Exception e) {
            log.error("获取涩图失败", e);
            reply(bot, event, "获取涩图出错：" + e.getMessage());
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
            }, executor));
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
     * 判断消息发送者是否为主人、群主或管理员
     */
    private boolean isPrivileged(Bot bot, AnyMessageEvent event) {
        long userId = event.getUserId();
        if (configManager.getDmwConfig().isMaster(userId)) {
            return true;
        }
        String role = event.getSender() == null ? null : event.getSender().getRole();
        if ("owner".equalsIgnoreCase(role) || "admin".equalsIgnoreCase(role)) {
            return true;
        }
        // 部分实现不返回 role，兜底查询群成员信息
        try {
            GroupMemberInfoResp member = bot.getGroupMemberInfo(event.getGroupId(), userId, false).getData();
            if (member == null) return false;
            String memberRole = member.getRole();
            return "owner".equalsIgnoreCase(memberRole) || "admin".equalsIgnoreCase(memberRole);
        } catch (Exception e) {
            log.warn("获取群成员信息失败: {}", e.getMessage());
            return false;
        }
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
        reply(bot, event, sb.toString());
    }

    private void sendLinkFallback(Bot bot, AnyMessageEvent event, List<ImageResult> results, String baseUrl) {
        List<String> linkMessages = new ArrayList<>();
        for (ImageResult result : results) {
            String url;
            if (result.path != null) {
                url = baseUrl + result.path.getFileName();
            } else if (result.imageUrl != null) {
                url = result.imageUrl;
            } else {
                url = "[无可用链接]";
            }
            // 图片链接节点单独发送，客户端可直接预览；纯文本节点保留链接便于复制
            linkMessages.add(url);
            if (result.path != null) {
                linkMessages.add(MsgUtils.builder().img(url).build());
            }
            linkMessages.add("「" + result.title + "」 / " + result.author);
        }

        var linkForwardMsg = ShiroUtils.generateForwardMsg(
                configManager.getDmwConfig().getBotId(),
                configManager.getDmwConfig().getBotName(),
                linkMessages
        );
        bot.sendForwardMsg(event, linkForwardMsg);
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

    private void reply(Bot bot, AnyMessageEvent event, String msg) {
        bot.sendMsg(
                event,
                MsgUtils.builder()
                        .reply(event.getMessageId())
                        .text(msg)
                        .build(),
                false
        );
    }

    private void reply(Bot bot, AnyMessageEvent event, String format, Object... args) {
        reply(bot, event, String.format(format, args));
    }
}

