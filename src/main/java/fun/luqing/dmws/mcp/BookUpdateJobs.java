package fun.luqing.dmws.mcp;

import fun.luqing.dmws.service.dmw.tomato.TomatoBookContentService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class BookUpdateJobs {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final TomatoBookContentService contentService;
    private final LinkedHashMap<String, ObjectNode> jobs = new LinkedHashMap<>();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0,
            TimeUnit.SECONDS, new ArrayBlockingQueue<>(10), runnable -> {
                Thread thread = new Thread(runnable, "mcp-book-update");
                thread.setDaemon(true);
                return thread;
            });

    public synchronized ObjectNode submit(String bookId, String bookName) {
        for (ObjectNode job : jobs.values()) {
            if (bookId.equals(job.path("book_id").asText()) && !job.path("done").asBoolean()) {
                return job.deepCopy();
            }
        }
        if (executor.getQueue().remainingCapacity() == 0) {
            throw new McpToolException("更新队列已满，请稍后重试");
        }
        while (jobs.size() >= 100) {
            String oldest = jobs.entrySet().stream().filter(e -> e.getValue().path("done").asBoolean())
                    .map(java.util.Map.Entry::getKey).findFirst().orElseThrow();
            jobs.remove(oldest);
        }
        String id = UUID.randomUUID().toString();
        ObjectNode job = MAPPER.createObjectNode();
        job.put("job_id", id).put("book_id", bookId).put("book_name", bookName)
                .put("status", "queued").put("done", false).put("submitted_at", Instant.now().toString())
                .put("poll_after_seconds", 5);
        jobs.put(id, job);
        executor.execute(() -> run(id, bookId));
        return job.deepCopy();
    }

    public synchronized ObjectNode get(String id) {
        ObjectNode job = jobs.get(id);
        if (job == null) throw new McpToolException("任务不存在或已过期（重启后任务记录不保留）");
        return job.deepCopy();
    }

    private void run(String id, String bookId) {
        synchronized (this) { jobs.get(id).put("status", "running"); }
        try {
            var result = contentService.updateAllChapters(bookId, false, progress -> update(id, progress));
            update(id, result);
            synchronized (this) {
                jobs.get(id).put("status", result.fail() == 0 ? "completed" : "partial");
            }
        } catch (Exception e) {
            log.error("MCP 更新任务失败: {}", id, e);
            synchronized (this) {
                jobs.get(id).put("status", "failed").put("error", "更新失败，请检查服务日志后重试");
            }
        } finally {
            synchronized (this) {
                jobs.get(id).put("done", true).put("finished_at", Instant.now().toString());
            }
        }
    }

    private synchronized void update(String id, TomatoBookContentService.UpdateResult result) {
        jobs.get(id).set("missing_item_ids", MAPPER.valueToTree(result.missingItemIds()));
        jobs.get(id).put("total", result.total()).put("success", result.success())
                .put("fail", result.fail()).put("exist", result.exist())
                .put("processed", result.success() + result.fail() + result.exist());
    }

    @PreDestroy
    public void close() { executor.shutdownNow(); }
}
