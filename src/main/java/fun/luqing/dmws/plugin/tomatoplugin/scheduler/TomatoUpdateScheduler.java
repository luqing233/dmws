package fun.luqing.dmws.plugin.tomatoplugin.scheduler;

import com.mikuac.shiro.core.Bot;
import fun.luqing.dmws.client.TomatoContentRecord;
import fun.luqing.dmws.client.TomatoUpdateRecord;
import fun.luqing.dmws.entity.dmw.tomato.TomatoBookContent;
import fun.luqing.dmws.entity.dmw.tomato.TomatoBookList;
import fun.luqing.dmws.entity.dmw.tomato.TomatoSubscriptionGroup;
import fun.luqing.dmws.plugin.tomatoplugin.util.DateUtil;
import fun.luqing.dmws.plugin.tomatoplugin.util.TomatoMessageUtil;
import fun.luqing.dmws.service.tomatoservice.TomatoBookContentService;
import fun.luqing.dmws.service.tomatoservice.TomatoBookListService;
import fun.luqing.dmws.service.tomatoservice.TomatoSubscriptionGroupService;
import fun.luqing.dmws.client.TomatoApiClient;
import fun.luqing.dmws.config.ConfigManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Random;

@Component
@Slf4j
@RequiredArgsConstructor
public class TomatoUpdateScheduler {

    private final TomatoBookListService bookListService;
    private final TomatoBookContentService contentService;
    private final TomatoSubscriptionGroupService subscriptionService;
    private final TomatoApiClient tomatoApiClient;
    private final ConfigManager configManager;

    @Scheduled(fixedDelay = 1000 * 60 * 4, initialDelay = 1000 * 30)
    public void updateSubscribedBooks() {
        try {
            List<TomatoBookList> books = bookListService.findAll();
            if (books.isEmpty()) return;

            Bot bot = configManager.getBotContainer().robots.get(configManager.getDmwConfig().getBot_id());
            if (bot == null) {
                log.warn("bot_id={} 尚未连接，跳过本次更新",
                        configManager.getDmwConfig().getBot_id());
                return;
            }

            for (TomatoBookList book : books) {
                int delay = 5 + new Random().nextInt(11);
                Thread.sleep(delay * 1000L);

                TomatoUpdateRecord info = tomatoApiClient.getBookDetail(book.getBookId());
                if (info == null) continue;

                if (!Objects.equals(book.getLastChapterId(), info.lastChapterId())
                        && DateUtil.earlierOrEqual(book.getLastTime(), info.lastPublishTime())) {

                    book.setLastChapterId(info.lastChapterId());
                    book.setLastTitle(info.lastChapterTitle());
                    book.setLastTime(info.lastPublishTime());
                    book.setTotalWords(info.totalWords());
                    bookListService.saveOrUpdate(book);

                    TomatoContentRecord recode = tomatoApiClient.getChapterContent(info.lastChapterId());
                    if (recode != null) {
                        TomatoBookContent chapter = new TomatoBookContent(
                                null,
                                recode.bookId(),
                                info.lastChapterId(),
                                recode.realChapterOrder(),
                                recode.title(),
                                recode.content(),
                                recode.updateTime(),
                                recode.wordCount()
                        );
                        contentService.saveOrUpdate(chapter);
                    }

                    notifyGroups(book, bot);
                }
            }
        } catch (Exception e) {
            log.error("更新任务异常: {}", e.getMessage(), e);
        }
    }

    private void notifyGroups(TomatoBookList book, Bot bot) {
        List<TomatoSubscriptionGroup> groups = subscriptionService.findByBookId(book.getBookId());
        String msg = "📢 《" + book.getBookName() + "》更新啦！\n最新章节：" + book.getLastTitle() + "\n更新时间：" + book.getLastTime();
        for (TomatoSubscriptionGroup g : groups) {
            TomatoMessageUtil.sendGroupMessage(bot, g.getGroupId(), msg);
        }
    }
}
