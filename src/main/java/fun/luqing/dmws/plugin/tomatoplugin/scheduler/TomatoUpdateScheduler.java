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

                boolean changed = !Objects.equals(book.getLastChapterId(), info.lastChapterId())
                        && DateUtil.earlierOrEqual(book.getLastTime(), info.lastPublishTime());

                if (changed) {
                    book.setLastChapterId(info.lastChapterId());
                    book.setLastTitle(info.lastChapterTitle());
                    book.setLastTime(info.lastPublishTime());
                    book.setTotalWords(info.totalWords());
                    bookListService.saveOrUpdate(book);
                }

                // 每轮都补全缺失章节，而不只是在检测到更新时
                int filled = backfillMissingChapters(book);

                if (changed) {
                    notifyGroups(book, bot);
                } else if (filled > 0) {
                    log.info("《{}》补全了 {} 章历史内容", book.getBookName(), filled);
                }
            }
        } catch (Exception e) {
            log.error("更新任务异常: {}", e.getMessage(), e);
        }
    }

    /** 每轮每本书最多补全的章节数，避免单轮任务过长 */
    private static final int BACKFILL_LIMIT = 10;

    /**
     * 补全该书缺失的章节内容。
     * 先同步目录（缺失的章节会以空正文入库），再逐章补抓正文，
     * 每轮最多处理 {@value #BACKFILL_LIMIT} 章，避免拖垮定时任务。
     *
     * @return 本轮成功补全的章节数
     */
    private int backfillMissingChapters(TomatoBookList book) {
        try {
            contentService.updateChapterList(book.getBookId());
        } catch (Exception e) {
            log.warn("同步目录失败: bookId={} err={}", book.getBookId(), e.getMessage());
        }

        List<TomatoBookContent> missing = contentService
                .findAllByBookIdOrderByRealChapterOrderAsc(book.getBookId())
                .stream()
                .filter(c -> c.getContent() == null || c.getContent().isBlank())
                .limit(BACKFILL_LIMIT)
                .toList();

        int filled = 0;
        for (TomatoBookContent chapter : missing) {
            try {
                TomatoContentRecord record = tomatoApiClient.getChapterContent(
                        chapter.getBookId(), chapter.getChapterId(), chapter.getRealChapterOrder());
                if (record == null || record.content() == null || record.content().isBlank()) {
                    continue;
                }
                chapter.setContent(record.content());
                chapter.setWordCount(record.wordCount());
                if (record.updateTime() != null && !record.updateTime().isBlank()) {
                    chapter.setUpdateTime(record.updateTime());
                }
                contentService.saveOrUpdate(chapter);
                filled++;
            } catch (Exception e) {
                log.warn("补全章节失败: bookId={} chapterId={} err={}",
                        book.getBookId(), chapter.getChapterId(), e.getMessage());
            }
        }
        return filled;
    }

    private void notifyGroups(TomatoBookList book, Bot bot) {
        List<TomatoSubscriptionGroup> groups = subscriptionService.findByBookId(book.getBookId());
        String msg = "📢 《" + book.getBookName() + "》更新啦！\n最新章节：" + book.getLastTitle() + "\n更新时间：" + book.getLastTime();
        for (TomatoSubscriptionGroup g : groups) {
            TomatoMessageUtil.sendGroupMessage(bot, g.getGroupId(), msg);
        }
    }
}
