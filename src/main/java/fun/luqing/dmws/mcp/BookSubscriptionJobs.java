package fun.luqing.dmws.mcp;

import fun.luqing.dmws.client.TomatoApiClient;
import fun.luqing.dmws.entity.dmw.tomato.TomatoBookList;
import fun.luqing.dmws.entity.dmw.tomato.TomatoSubscriptionGroup;
import fun.luqing.dmws.service.dmw.tomato.TomatoBookListService;
import fun.luqing.dmws.service.dmw.tomato.TomatoSubscriptionGroupService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Subscription registration never keeps an HTTP request waiting on the downloader. */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookSubscriptionJobs {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final TomatoBookListService books;
    private final TomatoSubscriptionGroupService subscriptions;
    private final TomatoApiClient client;
    private final BookUpdateJobs updates;
    private final LinkedHashMap<String, ObjectNode> jobs = new LinkedHashMap<>();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(10), runnable -> {
                Thread thread = new Thread(runnable, "mcp-book-subscribe");
                thread.setDaemon(true);
                return thread;
            });

    public synchronized ObjectNode submit(long groupId, String bookId, long operator, boolean atAll) {
        for (var job : jobs.values()) {
            if (!job.path("done").asBoolean() && job.path("group_id").asLong() == groupId
                    && bookId.equals(job.path("book_id").asText())) return job.deepCopy();
        }
        var existing = subscriptions.findByGroupIdAndBookId(groupId, bookId);
        if (existing.isPresent()) {
            return MAPPER.createObjectNode().put("ok", true).put("done", true).put("status", "completed")
                    .put("already_subscribed", true).put("subscription_id", existing.get().getId())
                    .put("enable", existing.get().isEnable()).put("group_id", groupId).put("book_id", bookId);
        }
        if (executor.isShutdown() || executor.getQueue().remainingCapacity() == 0) {
            throw new McpToolException("订阅任务队列暂不可用，请稍后重试");
        }
        while (jobs.size() >= 100) {
            String oldest = jobs.entrySet().stream().filter(e -> e.getValue().path("done").asBoolean())
                    .map(java.util.Map.Entry::getKey).findFirst().orElseThrow();
            jobs.remove(oldest);
        }
        String id = "sub-" + UUID.randomUUID();
        ObjectNode job = MAPPER.createObjectNode().put("job_id", id).put("job_type", "subscription")
                .put("group_id", groupId).put("book_id", bookId).put("status", "queued").put("done", false)
                .put("next_tool", "dmw_get_update_job").put("poll_after_seconds", 5)
                .put("message", "订阅任务已提交，尚未订阅成功；请查询任务结果");
        jobs.put(id, job);
        executor.execute(() -> run(id, groupId, bookId, operator, atAll));
        return job.deepCopy();
    }

    public synchronized ObjectNode get(String id) {
        var job = jobs.get(id);
        if (job == null) throw new McpToolException("订阅任务不存在或已过期，请查询本群订阅后再决定是否重试");
        return job.deepCopy();
    }

    private void run(String id, long groupId, String bookId, long operator, boolean atAll) {
        synchronized (this) { jobs.get(id).put("status", "running"); }
        try {
            TomatoBookList book = books.findByBookId(bookId).orElse(null);
            boolean newBook = book == null;
            if (newBook) {
                var detail = client.getBookDetail(bookId);
                if (detail == null) throw new McpToolException("未能获取书籍信息，订阅尚未创建");
                book = new TomatoBookList();
                book.setBookId(bookId);
                book.setBookName(detail.bookName());
                book.setLastTitle(detail.lastChapterTitle());
                book.setLastChapterId(detail.lastChapterId());
                book.setLastTime(detail.lastPublishTime());
                book.setTotalWords(detail.totalWords());
                book = books.addBook(book);
            }
            var subscription = subscriptions.findByGroupIdAndBookId(groupId, bookId).orElse(null);
            if (subscription == null) {
                subscription = new TomatoSubscriptionGroup();
                subscription.setGroupId(groupId);
                subscription.setBookId(bookId);
                subscription.setOperator(operator);
                subscription.setAtAll(atAll);
                subscription.setEnable(true);
                subscription = subscriptions.addSubscription(subscription);
            }
            synchronized (this) {
                jobs.get(id).put("subscription_id", subscription.getId()).put("book_name", book.getBookName())
                        .put("enable", subscription.isEnable()).put("ok", true).put("status", "completed")
                        .put("message", "订阅成功；章节数据可能仍在后台补全");
            }
            if (newBook) {
                try {
                    var update = updates.submit(bookId, book.getBookName());
                    synchronized (this) { jobs.get(id).put("data_job_id", update.path("job_id").asText()); }
                } catch (RuntimeException e) {
                    log.warn("订阅成功，但后台补全未提交: bookId={}", bookId, e);
                    synchronized (this) { jobs.get(id).put("data_update_required", true); }
                }
            }
        } catch (Exception e) {
            log.error("订阅任务失败: jobId={} bookId={}", id, bookId, e);
            synchronized (this) {
                jobs.get(id).put("ok", false).put("status", "failed")
                        .put("error", e instanceof McpToolException ? e.getMessage() : "订阅失败，请查询本群订阅并检查服务日志");
            }
        } finally {
            synchronized (this) { jobs.get(id).put("done", true); }
        }
    }

    @PreDestroy
    public void close() { executor.shutdownNow(); }
}
