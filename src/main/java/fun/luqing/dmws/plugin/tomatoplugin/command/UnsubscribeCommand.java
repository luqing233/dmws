package fun.luqing.dmws.plugin.tomatoplugin.command;

import com.mikuac.shiro.annotation.GroupMessageHandler;
import com.mikuac.shiro.annotation.MessageHandlerFilter;

import com.mikuac.shiro.annotation.common.Shiro;
import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.dto.event.message.GroupMessageEvent;
import fun.luqing.dmws.entity.dmw.tomato.TomatoSubscriptionGroup;
import fun.luqing.dmws.service.tomatoservice.TomatoSubscriptionGroupService;
import fun.luqing.dmws.plugin.tomatoplugin.util.TomatoMessageUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Shiro
@Slf4j
@Component
@RequiredArgsConstructor
public class UnsubscribeCommand {

    private final TomatoSubscriptionGroupService subscriptionService;

    @GroupMessageHandler
    @MessageHandlerFilter(startWith = "取消订阅")
    @Async("taskExecutor")
    public void unsubscribeById(Bot bot, GroupMessageEvent event) {
        long groupId = event.getGroupId();
        Matcher matcher = Pattern.compile("取消订阅\\s*(\\d+)").matcher(event.getMessage());
        if (!matcher.find()) {
            TomatoMessageUtil.sendGroupMessage(bot, groupId, "请使用：取消订阅 <ID>，例如：取消订阅 1");
            return;
        }

        try {
            Long id = Long.parseLong(matcher.group(1));
            Optional<TomatoSubscriptionGroup> optional = subscriptionService.findById(id);
            if (optional.isEmpty()) {
                TomatoMessageUtil.sendGroupMessage(bot, groupId, "未找到 ID 为 " + id + " 的订阅。");
                return;
            }

            TomatoSubscriptionGroup sub = optional.get();
            if (sub.getGroupId() != groupId) {
                TomatoMessageUtil.sendGroupMessage(bot, groupId, "该订阅不属于本群，无法删除。");
                return;
            }

            subscriptionService.deleteById(id);
            TomatoMessageUtil.sendGroupMessage(bot, groupId, "已取消订阅：" + sub.getBookId());
        } catch (Exception e) {
            log.error("取消订阅异常: {}", e.getMessage(), e);
            TomatoMessageUtil.sendGroupMessage(bot, groupId, "删除订阅失败：" + e.getMessage());
        }
    }
}
