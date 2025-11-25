package fun.luqing.dmws.plugin.tomatoplugin.command;

import com.mikuac.shiro.annotation.GroupMessageHandler;
import com.mikuac.shiro.annotation.MessageHandlerFilter;
import com.mikuac.shiro.annotation.common.Shiro;
import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.dto.event.message.GroupMessageEvent;
import fun.luqing.dmws.entity.dmw.TomatoBookContent;
import fun.luqing.dmws.entity.dmw.TomatoBookList;
import fun.luqing.dmws.entity.dmw.TomatoSubscriptionGroup;
import fun.luqing.dmws.service.TomatoBookContentService;
import fun.luqing.dmws.service.TomatoBookListService;
import fun.luqing.dmws.service.TomatoSubscriptionGroupService;
import fun.luqing.dmws.common.annotation.CommandInfo;
import fun.luqing.dmws.plugin.tomatoplugin.util.TomatoMessageUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Map;

import static com.mikuac.shiro.common.utils.ShiroUtils.generateForwardMsg;

@Shiro
@Slf4j
@Component
@RequiredArgsConstructor
public class ChapterListCommand {

    private final TomatoBookContentService contentService;
    private final TomatoBookListService bookListService;
    private final TomatoSubscriptionGroupService subscriptionService;

    @GroupMessageHandler
    @MessageHandlerFilter(startWith = "获取目录")
    @Async("taskExecutor")
    @CommandInfo(startWith = "获取目录", desc = "查看指定订阅书籍的目录")
    public void getChapterList(Bot bot, GroupMessageEvent event) {
        long groupId = event.getGroupId();
        String param = event.getMessage().replace("获取目录", "").trim();

        if (param.isEmpty()) {
            TomatoMessageUtil.sendGroupMessage(bot, groupId, "请输入订阅序号，例如：获取目录 1");
            return;
        }

        long subId;
        try {
            subId = Long.parseLong(param);
        } catch (NumberFormatException e) {
            TomatoMessageUtil.sendGroupMessage(bot, groupId, "格式错误，请输入数字序号。");
            return;
        }

        Optional<TomatoSubscriptionGroup> subOpt = subscriptionService.findById(subId);
        if (subOpt.isEmpty() || subOpt.get().getGroupId() != groupId) {
            TomatoMessageUtil.sendGroupMessage(bot, groupId, "未找到对应订阅。");
            return;
        }

        TomatoSubscriptionGroup sub = subOpt.get();
        String bookId = sub.getBookId();
        TomatoBookList book = bookListService.findByBookId(bookId).orElse(null);
        if (book == null) {
            TomatoMessageUtil.sendGroupMessage(bot, groupId, "书籍不存在。");
            return;
        }

        List<TomatoBookContent> chapters = contentService.getChapterList(bookId);
        if (chapters == null) {
            TomatoMessageUtil.sendGroupMessage(bot, groupId, "获取目录失败");
            return;
        }

        List<String> messageList = new ArrayList<>();
        messageList.add("《" + book.getBookName() + "》目录：");
        for (TomatoBookContent c : chapters) {
            messageList.add(c.getRealChapterOrder() + ".   " + c.getTitle());
        }

        List<Map<String, Object>> forwardMsg = generateForwardMsg(
                1945927750L,
                "猫猫02号机",
                messageList
        );
        bot.sendGroupForwardMsg(groupId, forwardMsg);
    }
}
