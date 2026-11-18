package fun.luqing.dmws.plugin.economy;

import com.mikuac.shiro.common.utils.MsgUtils;
import com.mikuac.shiro.common.utils.ShiroUtils;
import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.dto.action.response.GroupMemberInfoResp;
import com.mikuac.shiro.dto.action.response.GroupInfoResp;
import com.mikuac.shiro.dto.event.message.GroupMessageEvent;
import fun.luqing.dmws.common.economy.EconomyException;
import fun.luqing.dmws.common.economy.EconomyMoney;
import fun.luqing.dmws.config.DmwConfig;
import fun.luqing.dmws.service.dmw.economy.EconomyPlayerNameService;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

@Slf4j
public final class EconomyCommandSupport {

    private EconomyCommandSupport() {
    }

    public static void execute(Bot bot, GroupMessageEvent event, Supplier<String> action) {
        String message;
        try {
            message = action.get();
        } catch (EconomyException exception) {
            message = exception.getMessage();
        } catch (RuntimeException exception) {
            log.error("经济指令执行失败: user={}, group={}, message={}",
                    event.getUserId(), event.getGroupId(), plainText(event), exception);
            message = "操作失败，请稍后再试";
        }
        if (message != null && !message.isBlank()) {
            try {
                reply(bot, event, message);
            } catch (RuntimeException exception) {
                log.warn("经济指令回执发送结果不明: user={}, group={}", event.getUserId(), event.getGroupId(), exception);
            }
        }
    }

    public static void reply(Bot bot, GroupMessageEvent event, String message) {
        bot.sendGroupMsg(
                event.getGroupId(),
                MsgUtils.builder()
                        .reply(event.getMessageId())
                        .text(message)
                        .build(),
                false
        );
    }

    public static void sendTextQuery(Bot bot, GroupMessageEvent event, DmwConfig config, String title, String text) {
        if (text.length() <= 600 && text.lines().count() <= 10) {
            reply(bot, event, ShiroUtils.escape(text));
            return;
        }
        List<String> pages = EconomyDisplayFormatter.textPages(title, text).stream().map(ShiroUtils::escape).toList();
        try {
            var response = bot.sendGroupForwardMsg(event.getGroupId(),
                    ShiroUtils.generateForwardMsg(config.getBotId(), config.getBotName(), pages));
            if (response != null && Integer.valueOf(0).equals(response.getRetCode()) && response.getData() != null) return;
        } catch (RuntimeException exception) {
            log.warn("查询合并转发发送失败: group={}", event.getGroupId(), exception);
        }
        for (String page : pages) reply(bot, event, page);
    }

    public static void sendForward(
            Bot bot,
            GroupMessageEvent event,
            DmwConfig config,
            List<String> nodes
    ) {
        try {
            List<Map<String, Object>> forward = ShiroUtils.generateForwardMsg(
                    config.getBotId(),
                    config.getBotName(),
                    nodes
            );
            var response = bot.sendGroupForwardMsg(event.getGroupId(), forward);
            if (response == null || !Integer.valueOf(0).equals(response.getRetCode())
                    || response.getData() == null) {
                reply(bot, event, String.join("\n\n", nodes));
            }
        } catch (RuntimeException exception) {
            log.warn("合并转发发送失败，降级为普通消息: group={}", event.getGroupId(), exception);
            reply(bot, event, String.join("\n\n", nodes));
        }
    }

    public static void sendForward(Bot bot, long groupId, DmwConfig config, List<String> nodes) {
        try {
            var response = bot.sendGroupForwardMsg(groupId,
                    ShiroUtils.generateForwardMsg(config.getBotId(), config.getBotName(), nodes));
            if (response != null && Integer.valueOf(0).equals(response.getRetCode()) && response.getData() != null) return;
        } catch (RuntimeException exception) {
            log.warn("合并转发发送失败，降级为普通消息: group={}", groupId, exception);
        }
        bot.sendGroupMsg(groupId, String.join("\n\n", nodes), false);
    }

    public static String plainText(GroupMessageEvent event) {
        String text = event.getPlainText();
        if (text == null) {
            text = event.getMessage();
        }
        return text == null ? "" : text.replace('\u00A0', ' ').trim();
    }

    public static String argument(GroupMessageEvent event, String... prefixes) {
        String text = plainText(event);
        for (String prefix : prefixes) {
            if (text.regionMatches(true, 0, prefix, 0, prefix.length())) {
                return text.substring(prefix.length()).trim();
            }
        }
        return text;
    }

    public static String[] arguments(GroupMessageEvent event, String... prefixes) {
        String argument = argument(event, prefixes);
        if (argument.isBlank()) {
            return new String[0];
        }
        return argument.split("\\s+");
    }

    public static BigDecimal amount(GroupMessageEvent event, String... prefixes) {
        String argument = argument(event, prefixes);
        if (argument.isBlank()) {
            throw new EconomyException("INVALID_AMOUNT", "请提供金额");
        }
        return EconomyMoney.parse(argument);
    }

    public static boolean requestsAll(String raw) {
        String normalized = raw == null
                ? ""
                : raw.replace('！', '!').replaceAll("\\s+", "").trim();
        while (normalized.startsWith("!")) {
            normalized = normalized.substring(1);
        }
        return normalized.isBlank()
                || Set.of("全部", "所有", "全存", "全取", "all").contains(
                normalized.toLowerCase(Locale.ROOT)
        );
    }

    public static int quantity(String raw, int defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException exception) {
            throw new EconomyException("INVALID_QUANTITY", "数量格式不正确");
        }
    }

    public static Long mentionedUserId(GroupMessageEvent event) {
        List<Long> users = ShiroUtils.getAtList(event.getArrayMsg());
        return users.isEmpty() ? null : users.getFirst();
    }

    public static String nickname(GroupMessageEvent event) {
        String name = event.getSender() == null ? "" : EconomyPlayerNameService.visible(
                event.getSender().getCard(), event.getSender().getNickname(), event.getUserId());
        return name.isBlank() ? "这位玩家" : name;
    }

    public static String groupMemberDisplayName(Bot bot, long groupId, long userId) {
        try {
            GroupMemberInfoResp member = bot.getGroupMemberInfo(groupId, userId, false).getData();
            if (member != null) {
                String name = EconomyPlayerNameService.visible(member.getCard(), member.getNickname(), userId);
                if (!name.isBlank()) return name;
            }
        } catch (RuntimeException ignored) {
            // Human replies use a neutral label when QQ cannot resolve a name.
        }
        return "对方";
    }

    public static String groupName(Bot bot, long groupId) {
        try {
            GroupInfoResp group = bot.getGroupInfo(groupId, false).getData();
            return group == null || group.getGroupName() == null ? "" : group.getGroupName().trim();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    public static String operationId(String action, GroupMessageEvent event) {
        return operationId(action, event, "");
    }

    public static String operationId(String action, GroupMessageEvent event, String detail) {
        return fun.luqing.dmws.common.economy.EconomyMessageOperation.id(action, event.getGroupId(),
                event.getMessageId(), event.getUserId(), detail);
    }

    public static boolean isExact(String text, String... commands) {
        String normalized = text.trim().toLowerCase(Locale.ROOT);
        for (String command : commands) {
            if (normalized.equals(command.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

}
