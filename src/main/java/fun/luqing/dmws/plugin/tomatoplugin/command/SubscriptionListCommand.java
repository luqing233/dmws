package fun.luqing.dmws.plugin.tomatoplugin.command;

import com.mikuac.shiro.annotation.GroupMessageHandler;
import com.mikuac.shiro.annotation.MessageHandlerFilter;
import com.mikuac.shiro.annotation.common.Shiro;
import com.mikuac.shiro.core.Bot;
import fun.luqing.dmws.entity.dmw.TomatoSubscriptionGroup;
import fun.luqing.dmws.service.TomatoBookListService;
import fun.luqing.dmws.service.TomatoSubscriptionGroupService;
import fun.luqing.dmws.plugin.tomatoplugin.util.TomatoMessageUtil;
import fun.luqing.dmws.common.annotation.CommandInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.mikuac.shiro.common.utils.ShiroUtils.generateForwardMsg;

@Shiro
@Slf4j
@Component
@RequiredArgsConstructor
public class SubscriptionListCommand {

    private final TomatoSubscriptionGroupService subscriptionService;
    private final TomatoBookListService bookListService;

    @GroupMessageHandler
    @MessageHandlerFilter(cmd = "订阅列表")
    @Async("taskExecutor")
    @CommandInfo(cmd = "订阅列表", desc = "查看当前群的番茄小说订阅列表")
    public void selectAllBook(Bot bot, com.mikuac.shiro.dto.event.message.GroupMessageEvent event) {
        long groupId = event.getGroupId();
        List<TomatoSubscriptionGroup> subscriptions = subscriptionService.findByGroupId(groupId);
        if (subscriptions.isEmpty()) {
            TomatoMessageUtil.sendGroupMessage(bot, groupId, "本群暂无订阅的书籍哦~");
            return;
        }

        List<String> messageList = new ArrayList<>();
        messageList.add("📚 本群订阅列表：");

        for (TomatoSubscriptionGroup sub : subscriptions) {
            bookListService.findByBookId(sub.getBookId())
                    .ifPresent(book -> {
                        String words = TomatoMessageUtil.formatWordsToWan(book.getTotalWords());
                        messageList.add("ID: " + sub.getId() +
                                " 《" + book.getBookName() + "》\n" +
                                words);
                    });
        }

        List<Map<String, Object>> forwardMsg = generateForwardMsg(
                1945927750L,
                "猫猫02号机",
                messageList
        );
        bot.sendGroupForwardMsg(groupId, forwardMsg);
    }
}
