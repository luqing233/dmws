package fun.luqing.dmws.plugin;

import com.mikuac.shiro.annotation.AnyMessageHandler;
import com.mikuac.shiro.annotation.MessageHandlerFilter;
import com.mikuac.shiro.annotation.common.Shiro;
import com.mikuac.shiro.common.utils.MsgUtils;
import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.dto.event.message.AnyMessageEvent;
import fun.luqing.dmws.annotation.CommandInfo;
import fun.luqing.dmws.config.ConfigManager;
import fun.luqing.dmws.service.SeTuService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.mikuac.shiro.common.utils.ShiroUtils.generateForwardMsg;

@Shiro
@Component
@Slf4j
@RequiredArgsConstructor
public class SeTuPlugin {
    private final ConfigManager configManager;
    private final SeTuService seTuService;

    @AnyMessageHandler
    @MessageHandlerFilter(startWith = "涩图 ")
    @Async("taskExecutor")
    @CommandInfo(startWith = "涩图 ", desc = "获取指定tag的涩图")
    public void getSeTu(Bot bot, AnyMessageEvent event){
        try {
            String msgText = event.getMessage().trim();
            // 确认前缀
            if (!msgText.startsWith("涩图 tag=")) {
                bot.sendMsg(event, "请使用格式：涩图 tag=标签1 标签2 [参数]", false);
                return;
            }

            // 移除前缀 "涩图 tag="
            String content = msgText.substring("涩图 tag=".length()).trim();
            if (content.isEmpty()) {
                bot.sendMsg(event, "请提供标签，例如：涩图 tag=猫 芙宁娜 num=2 r18=1 size=original", false);
                return;
            }

            // 分离标签和参数
            String[] parts = content.split(" (?=[a-zA-Z]+\\=)");
            String tagsPart = parts[0];
            String paramsPart = parts.length > 1 ? parts[1] : "";

            // 标签列表
            List<String> tagList = List.of(tagsPart.trim().split("\\s+"));

            // 默认参数
            int num = 1;
            int r18 = 0;
            List<String> sizeList = List.of("original");
            String proxy = null;
            Boolean excludeAI = false;

            // 解析参数
            if (!paramsPart.isEmpty()) {
                String[] paramPairs = paramsPart.split("[,，]");
                for (String pair : paramPairs) {
                    String[] kv = pair.split("=");
                    if (kv.length != 2) continue;
                    String key = kv[0].trim().toLowerCase();
                    String value = kv[1].trim();
                    switch (key) {
                        case "num":
                            try { num = Math.min(Math.max(Integer.parseInt(value),1),20); } catch (Exception ignored) {}
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

            // 调用 Lolicon API v2
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

            // 构建合并转发消息
            List<String> messageList = new ArrayList<>();
            for (int i = 0; i < res.length(); i++) {
                JSONObject setu = res.getJSONObject(i);
                String title = setu.optString("title", "未知标题");
                String author = setu.optString("author", "未知作者");
                JSONObject urls = setu.optJSONObject("urls"); // 获取 urls 对象
                if (urls == null) continue;

                // 取第一个 size
                String imageUrl = urls.has(sizeList.get(0)) ? urls.getString(sizeList.get(0)) : urls.keys().hasNext() ? urls.getString(urls.keys().next()) : null;
                if (imageUrl == null) continue;

                messageList.add("标题：" + title);
                messageList.add("作者：" + author);
                messageList.add("链接：" + imageUrl);
                messageList.add(MsgUtils.builder().img(imageUrl).build());
            }

            if (messageList.isEmpty()) {
                bot.sendMsg(event, "没有获取到可用图片", false);
                return;
            }

            // 生成并发送合并转发消息
            List<Map<String, Object>> forwardMsg = generateForwardMsg(
                    configManager.getDmwConfig().getBot_id(),
                    configManager.getDmwConfig().getBot_name(),
                    messageList
            );
            bot.sendForwardMsg(event, forwardMsg);
            log.info("发送合并转发图片成功，共 {} 张", messageList.size() / 4);

        } catch (Exception e) {
            log.error("获取涩图失败", e);
            bot.sendMsg(event, "获取涩图出错：" + e.getMessage(), false);
        }
    }


}
