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
        try {
            String message = action.get();
            if (message != null && !message.isBlank()) {
                reply(bot, event, message);
            }
        } catch (EconomyException exception) {
            reply(bot, event, exception.getMessage());
        } catch (RuntimeException exception) {
            log.error("经济指令执行失败: user={}, group={}, message={}",
                    event.getUserId(), event.getGroupId(), plainText(event), exception);
            reply(bot, event, "操作失败，请稍后再试");
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
            bot.sendGroupForwardMsg(event.getGroupId(), forward);
        } catch (RuntimeException exception) {
            log.warn("合并转发发送失败，降级为普通消息: group={}", event.getGroupId(), exception);
            reply(bot, event, String.join("\n\n", nodes));
        }
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
        if (event.getSender() == null) {
            return String.valueOf(event.getUserId());
        }
        String nickname = event.getSender().getNickname();
        return nickname == null || nickname.isBlank()
                ? String.valueOf(event.getUserId())
                : nickname;
    }

    public static String groupMemberDisplayName(Bot bot, long groupId, long userId) {
        try {
            GroupMemberInfoResp member = bot.getGroupMemberInfo(groupId, userId, false).getData();
            if (member != null) {
                if (member.getCard() != null && !member.getCard().isBlank()) {
                    return member.getCard().trim();
                }
                if (member.getNickname() != null && !member.getNickname().isBlank()) {
                    return member.getNickname().trim();
                }
            }
        } catch (RuntimeException ignored) {
            // Fall back to the numeric user id when the OneBot implementation cannot resolve a card.
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
        return action + ":" + event.getMessageId();
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
