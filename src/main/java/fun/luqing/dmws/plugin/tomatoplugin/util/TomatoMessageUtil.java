package fun.luqing.dmws.plugin.tomatoplugin.util;

import com.mikuac.shiro.core.Bot;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TomatoMessageUtil {

    public static void sendGroupMessage(Bot bot, long groupId, String message) {
        try {
            bot.sendGroupMsg(groupId, message, false);
        } catch (Exception e) {
            log.error("发送群消息失败: {}", e.getMessage(), e);
        }
    }

    public static String formatWordsToWan(long words) {
        double wan = words / 10000.0;
        return String.format("%.1f万字", wan);
    }
}
