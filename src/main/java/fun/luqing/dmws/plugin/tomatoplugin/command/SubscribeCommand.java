package fun.luqing.dmws.plugin.tomatoplugin.command;

import com.mikuac.shiro.annotation.GroupMessageHandler;
import com.mikuac.shiro.annotation.MessageHandlerFilter;

import com.mikuac.shiro.annotation.common.Shiro;
import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.dto.event.message.GroupMessageEvent;
import fun.luqing.dmws.client.TomatoApiClient;

import fun.luqing.dmws.entity.dmw.tomato.TomatoBookList;
import fun.luqing.dmws.entity.dmw.tomato.TomatoSubscriptionGroup;
import fun.luqing.dmws.service.tomatoservice.TomatoBookContentService;
import fun.luqing.dmws.service.tomatoservice.TomatoBookListService;
import fun.luqing.dmws.service.tomatoservice.TomatoSubscriptionGroupService;
import fun.luqing.dmws.common.annotation.CommandInfo;
import fun.luqing.dmws.plugin.tomatoplugin.util.TomatoMessageUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;



@Shiro
@Slf4j
@Component
@RequiredArgsConstructor
public class SubscribeCommand {

    private final TomatoBookListService bookListService;
    private final TomatoBookContentService contentService;
    private final TomatoSubscriptionGroupService subscriptionService;
    private final TomatoApiClient tomatoApiClient;

    @GroupMessageHandler
    @MessageHandlerFilter(startWith = "番茄订阅")
    @Async("taskExecutor")
    @CommandInfo(startWith = "番茄订阅", desc = "订阅指定id的书籍，注意，此处的id为bookId，可以使用番茄的分享功能分享链接获取，具体操作为：打开阅读界面，点击左上角从左往右数第二个的分享图标，点击系统分享，就可以找到链接中的bookId，例如")
    public void subscribeBook(Bot bot, GroupMessageEvent event) {
        long groupId = event.getGroupId();
        String raw = event.getMessage().trim();
        boolean atAll = raw.contains("全体") || raw.contains("all");

        String bookId = raw.replaceAll("(番茄订阅|全体|all)", "").trim();

        if (bookId.isEmpty() || !bookId.matches("\\d+")) {
            TomatoMessageUtil.sendGroupMessage(bot, groupId, "请指定书籍ID，例如：\n番茄订阅 123456789");
            return;
        }

        TomatoBookList book = bookListService.findByBookId(bookId).orElseGet(() -> initializeBook(bot, groupId, bookId));
        if (book == null) return;

        if (subscriptionService.findByGroupIdAndBookId(groupId, bookId).isPresent()) {
            TomatoMessageUtil.sendGroupMessage(bot, groupId, "该书已订阅，无需重复。");
            return;
        }

        TomatoSubscriptionGroup sub = new TomatoSubscriptionGroup();
        sub.setGroupId(groupId);
        sub.setBookId(bookId);
        sub.setOperator(event.getUserId());
        sub.setAtAll(atAll);
        sub.setEnable(true);

        subscriptionService.addSubscription(sub);
        TomatoMessageUtil.sendGroupMessage(bot, groupId,
                "订阅成功：\n《" + book.getBookName() + "》\n最新章节：" + book.getLastTitle() + "\n更新时间：" + book.getLastTime());
    }

    private TomatoBookList initializeBook(Bot bot, long groupId, String bookId) {
        var rec = tomatoApiClient.getBookDetail(bookId);
        if (rec == null) {
            TomatoMessageUtil.sendGroupMessage(bot, groupId, "无法获取书籍信息，请检查ID是否正确。");
            return null;
        }

        TomatoBookList book = new TomatoBookList();
        book.setBookId(bookId);
        book.setBookName(rec.bookName());
        book.setLastTitle(rec.lastChapterTitle());
        book.setLastChapterId(rec.lastChapterId());
        book.setLastTime(rec.lastPublishTime());
        book.setTotalWords(rec.totalWords());
        bookListService.addBook(book);

        var updateResult = contentService.updateChapterList(bookId);
        TomatoMessageUtil.sendGroupMessage(bot, groupId,
                "已初始化《" + book.getBookName() + "》章节目录，共 " + updateResult.total() + " 章。");

        return book;
    }
}
