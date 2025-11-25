package fun.luqing.dmws.plugin.tomatoplugin.command;

import com.mikuac.shiro.annotation.GroupMessageHandler;
import com.mikuac.shiro.annotation.MessageHandlerFilter;
import com.mikuac.shiro.annotation.common.Shiro;
import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.dto.event.message.GroupMessageEvent;
import fun.luqing.dmws.entity.dmw.TomatoBookList;
import fun.luqing.dmws.entity.dmw.TomatoSubscriptionGroup;
import fun.luqing.dmws.service.TomatoBookContentService;
import fun.luqing.dmws.service.TomatoBookListService;
import fun.luqing.dmws.service.TomatoSubscriptionGroupService;
import fun.luqing.dmws.plugin.tomatoplugin.util.TomatoMessageUtil;
import fun.luqing.dmws.common.annotation.CommandInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Shiro
@Slf4j
@Component
@RequiredArgsConstructor
public class UpdateChapterCommand {

    private final TomatoBookContentService contentService;
    private final TomatoBookListService bookListService;
    private final TomatoSubscriptionGroupService subscriptionService;

    @GroupMessageHandler
    @MessageHandlerFilter(startWith = "更新章节内容")
    @Async("taskExecutor")
    @CommandInfo(startWith = "更新章节内容", desc = "更新数据库中指定书籍的所有章节内容")
    public void updateAllChapterContents(Bot bot, GroupMessageEvent event) {
        long groupId = event.getGroupId();
        String param = event.getMessage().replace("更新章节内容", "").trim();

        if (param.isEmpty()) {
            TomatoMessageUtil.sendGroupMessage(bot, groupId, "请输入订阅ID，例如：更新章节内容 1");
            return;
        }

        long subId;
        try {
            subId = Long.parseLong(param);
        } catch (NumberFormatException e) {
            TomatoMessageUtil.sendGroupMessage(bot, groupId, "格式错误，请输入数字ID。");
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

        TomatoBookContentService.UpdateResult updateResult = contentService.updateAllChapters(bookId,false);
        String resultMsg = String.format("《%s》章节内容更新完成。\n成功：%d 章\n失败：%d 章",
                book.getBookName(), updateResult.success(), updateResult.fail());
        TomatoMessageUtil.sendGroupMessage(bot, groupId, resultMsg);
    }
}
