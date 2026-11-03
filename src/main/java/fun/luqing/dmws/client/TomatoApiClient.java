package fun.luqing.dmws.client;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import fun.luqing.dmws.config.ConfigManager;
import fun.luqing.dmws.config.McpConfig;
import fun.luqing.dmws.plugin.tomatoplugin.util.TomatoLogContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 番茄小说客户端，通过 MCP 服务获取数据。
 * <p>
 * 在线小说接口（fanqie）负责书目、目录与章节正文；
 * 本地下载器（downloader）补充章节序号与发布时间。
 */
@Slf4j
@Component
public class TomatoApiClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 目录分页拉取时的单页大小 */
    private static final int DIRECTORY_PAGE_SIZE = 100;
    /** 目录最多翻页次数，避免异常情况下无限循环 */
    private static final int DIRECTORY_MAX_PAGES = 100;
    /** 下载器批量读取单次上限 */
    private static final int MAX_BATCH = 20;
    /** 批量下载的轮询次数与间隔（每次 0.5 秒，最多约 3 分钟） */
    private static final int BATCH_POLL_TIMES = 360;
    private static final long BATCH_POLL_INTERVAL_MS = 500L;

    private final ConfigManager configManager;
    private McpClient fanqie;
    private McpClient downloader;
    /** 最近一次正文获取失败的结构化错误码 */
    private volatile String lastContentErrorCode;

    public TomatoApiClient(ConfigManager configManager) {
        this.configManager = configManager;
    }

    private McpClient fanqie() {
        if (fanqie == null) {
            McpConfig.McpServer server = config().getFanqie();
            fanqie = new McpClient("番茄小说", server);
        }
        return fanqie;
    }

    private McpClient downloader() {
        if (downloader == null) {
            McpConfig.McpServer server = config().getDownloader();
            downloader = new McpClient("番茄下载", server);
        }
        return downloader;
    }

    private McpConfig config() {
        McpConfig mcp = configManager.getDmwConfig().getMcp();
        if (mcp == null) {
            mcp = new McpConfig();
            configManager.getDmwConfig().setMcp(mcp);
        }
        return mcp;
    }

    // ===============================
    //   对外能力
    // ===============================

    /**
     * 获取书籍最新更新信息。
     *
     * @param bookId 书籍ID
     * @return 书籍名称、最新章节等；获取失败返回 null
     */
    public TomatoUpdateRecord getBookDetail(String bookId) {
        ObjectNode args = MAPPER.createObjectNode();
        args.put("book_id", bookId);

        JsonNode root = fanqie().callTool("online_get_book_detail", args);
        if (root == null) return null;

        JsonNode detail = root.path("detail");
        if (detail.isMissingNode() || detail.isEmpty()) {
            log.warn("get_book_detail 未返回书目数据: bookId={}", bookId);
            return null;
        }

        String latestChapterId = detail.path("latest_chapter_id").asText("");
        return new TomatoUpdateRecord(
                detail.path("book_name").asText(""),
                detail.path("latest_chapter_title").asText(""),
                latestChapterId,
                fetchLatestPublishTime(bookId, latestChapterId),
                detail.path("word_count").asLong(0L)
        );
    }

    /**
     * 批量获取章节正文。
     * 先用离散批量下载补齐目标章节，再按 item_ids 批量读取正文。
     * 批量读取的 start_index/end_index 是下载器本地槽位，与书籍章节序号无关，
     * 因此这里只用 item_ids 模式。
     *
     * @param bookId  所属书籍ID
     * @param targets 待获取的章节
     * @return 按章节ID索引的正文结果；未取到的章节不会出现在返回值中
     */
    public Map<String, TomatoContentRecord> getChapterContents(String bookId,
                                                               List<ChapterTarget> targets) {
        Map<String, TomatoContentRecord> result = new LinkedHashMap<>();
        if (targets == null || targets.isEmpty()) return result;

        List<ChapterTarget> batch = targets.size() > MAX_BATCH
                ? targets.subList(0, MAX_BATCH) : targets;

        // 先按 item_ids 离散下载，只下缺的那几章，不会把中间章节当成连续区间
        downloadDiscrete(bookId, batch);
        readBatch(bookId, batch, result);
        return result;
    }

    /**
     * 触发离散批量下载并等待完成。
     * 下载失败不影响后续读取：已经在本地的章节照样能读到。
     */
    private void downloadDiscrete(String bookId, List<ChapterTarget> batch) {
        ObjectNode args = MAPPER.createObjectNode();
        args.put("book_id", bookId);
        var idsArray = MAPPER.createArrayNode();
        for (ChapterTarget t : batch) idsArray.add(t.chapterId());
        args.set("item_ids", idsArray);

        JsonNode started = downloader().callTool("tnd_start_discrete_downloads", args);
        if (started == null) return;

        JsonNode batchId = started.path("batch_id");
        if (batchId.isMissingNode() || batchId.isNull()) return;

        // 批量任务在后台逐章执行，这里轮询到结束
        for (int i = 0; i < BATCH_POLL_TIMES; i++) {
            ObjectNode statusArgs = MAPPER.createObjectNode();
            statusArgs.set("batch_id", batchId);
            JsonNode status = downloader().callTool("tnd_get_batch_status", statusArgs);
            if (status == null) return;

            String state = status.path("status").asText("");
            if ("done".equals(state) || "done_with_errors".equals(state) || "failed".equals(state)) {
                if (!"done".equals(state)) {
                    log.warn("批量下载未完全成功: batchId={} status={} errors={}",
                            batchId.asText(""), state, abbreviateJson(status.path("errors")));
                }
                return;
            }
            if ("waiting_input".equals(state)) {
                log.warn("批量下载需要人工选择书名或格式，已跳过: batchId={}", batchId.asText(""));
                return;
            }
            try {
                Thread.sleep(BATCH_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        log.warn("批量下载等待超时: batchId={}", batchId.asText(""));
    }

    private String abbreviateJson(JsonNode node) {
        String s = node == null ? "" : node.toString();
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }

    /**
     * 按 item_ids 批量读取正文并填充结果。
     * 响应中带有 chapter_order，可直接作为章节序号，无需再查目录。
     */
    private void readBatch(String bookId, List<ChapterTarget> batch,
                           Map<String, TomatoContentRecord> result) {
        ObjectNode args = MAPPER.createObjectNode();
        args.put("book_id", bookId);
        var idsArray = MAPPER.createArrayNode();
        for (ChapterTarget t : batch) idsArray.add(t.chapterId());
        args.set("item_ids", idsArray);

        JsonNode root = downloader().callTool("tnd_read_downloaded_chapters", args);
        if (root == null) return;

        JsonNode chapters = root.path("chapters");
        if (!chapters.isArray()) return;

        Map<String, ChapterTarget> byId = new LinkedHashMap<>();
        for (ChapterTarget t : batch) byId.put(t.chapterId(), t);

        for (JsonNode ch : chapters) {
            String itemId = ch.path("item_id").asText("");
            ChapterTarget target = byId.get(itemId);
            if (target == null) continue;

            String text = ch.path("content").asText("");
            if (text.isBlank()) continue;

            // chapter_order 是番茄目录中的真实章节号，缺省时退回调用方传入的序号
            int order = ch.path("chapter_order").asInt(target.order());

            result.put(itemId, new TomatoContentRecord(
                    bookId,
                    root.path("book_name").asText(""),
                    order,
                    ch.path("title").asText(""),
                    text,
                    resolveChapterTime(bookId, itemId),
                    countWords(text)
            ));
        }
    }

    /**
     * 批量获取时的章节定位信息。
     *
     * @param chapterId 章节ID
     * @param order     章节在书中的序号（1 起）
     */
    public record ChapterTarget(String chapterId, int order) {
    }

    /**
     * 最近一次正文获取失败的结构化错误码。
     * 取值见下载器工具：CHAPTER_NOT_DOWNLOADED / BOOK_NOT_FOUND /
     * UPSTREAM_LIMITED / UPSTREAM_ERROR 等；无法识别的失败返回 null。
     */
    public String getLastContentErrorCode() {
        return lastContentErrorCode;
    }

    /**
     * 按错误码判断是否值得继续重试。
     * 书不存在属于永久失败，重试再多次也不会有结果。
     */
    public static boolean isRetryable(String errorCode) {
        if (errorCode == null || errorCode.isBlank()) return true;
        return !"BOOK_NOT_FOUND".equals(errorCode) && !"INVALID_ARGUMENT".equals(errorCode);
    }

    /**
     * 获取章节正文。
     * <p>
     * 优先使用下载器：先读本地已下载内容，没有则触发单章下载并等待完成再读；
     * 下载器不可用时回退到在线接口。
     *
     * @param chapterId 章节ID
     * @return 章节正文与元数据；获取失败返回 null
     */
    public TomatoContentRecord getChapterContent(String chapterId) {
        TomatoContentRecord fromDownloader = fetchFromDownloader(chapterId);
        if (fromDownloader != null) return fromDownloader;

        TomatoLogContext.info(log, "下载器未能提供章节 {}, 回退到在线接口", chapterId);
        return fetchFromOnline(chapterId);
    }

    /**
     * 获取章节正文，调用方已知章节所属书籍与序号时使用，避免反查。
     *
     * @param bookId    所属书籍ID
     * @param chapterId 章节ID
     * @param order     章节序号（1 起）
     * @return 章节正文与元数据；获取失败返回 null
     */
    public TomatoContentRecord getChapterContent(String bookId, String chapterId, int order) {
        TomatoContentRecord fromDownloader = fetchFromDownloader(bookId, chapterId, order);
        if (fromDownloader != null) return fromDownloader;

        TomatoLogContext.info(log, "下载器未能提供章节 {}, 回退到在线接口", chapterId);
        return fetchFromOnline(chapterId);
    }

    /**
     * 从下载器获取章节正文：本地已有则直接读，否则触发单章下载后重读。
     */
    private TomatoContentRecord fetchFromDownloader(String chapterId) {
        ChapterRef ref = resolveChapterRef(chapterId);
        if (ref == null) return null;
        return fetchFromDownloader(ref.bookId(), chapterId, ref.order());
    }

    private TomatoContentRecord fetchFromDownloader(String bookId, String chapterId, int order) {
        // 先看本地是否已下载，已下载就不再触发下载
        JsonNode root = readDownloadedChapter(bookId, chapterId);
        if (root != null) {
            return buildContentRecord(bookId, chapterId, order, root);
        }

        if (!triggerChapterDownload(bookId, order)) {
            lastContentErrorCode = downloader().getLastToolErrorCode();
            return null;
        }

        // 下载完成后按 item_id 校验，只接受真正命中目标章节的结果
        root = readDownloadedChapter(bookId, chapterId);
        if (root == null) {
            lastContentErrorCode = downloader().getLastToolErrorCode();
            log.debug("章节 {} 下载后仍不可读（bookId={} order={}）", chapterId, bookId, order);
            return null;
        }
        lastContentErrorCode = null;
        return buildContentRecord(bookId, chapterId, order, root);
    }

    private TomatoContentRecord buildContentRecord(String bookId, String chapterId, int order, JsonNode root) {
        String text = root.path("content").asText("");
        if (text.isBlank()) return null;

        // 校验返回的确实是目标章节，避免下载器的本地槽位错位导致张冠李戴
        String gotItemId = root.path("item_id").asText("");
        if (!gotItemId.isEmpty() && !gotItemId.equals(chapterId)) {
            log.warn("下载器返回的章节与请求不符: 期望={} 实际={}", chapterId, gotItemId);
            return null;
        }

        return new TomatoContentRecord(
                bookId,
                root.path("book_name").asText(""),
                order,
                root.path("title").asText(""),
                text,
                resolveChapterTime(bookId, chapterId),
                countWords(text)
        );
    }

    /**
     * 全项目统一的正文字数口径：正文字符数。
     * 上游接口另有一个"官方字数"，它不含空白与标点，比字符数少约三分之一；
     * 两种口径混用会让图表上的更新字数失真，因此统一以字符数为准。
     */
    private int countWords(String text) {
        return text == null ? 0 : text.length();
    }

    /**
     * 按 item_id 读取已下载章节。
     * item_id 是唯一可靠的定位方式；响应中的 index 是下载器本地槽位，
     * 会随下载顺序变化，不能作为章节序号的依据。
     */
    private JsonNode readDownloadedChapter(String bookId, String chapterId) {
        ObjectNode args = MAPPER.createObjectNode();
        args.put("book_id", bookId);
        args.put("item_id", chapterId);

        return downloader().callTool("tnd_read_downloaded_chapter", args);
    }

    /**
     * 触发单章下载并等待任务结束。
     * 下载器的 index 取自目录接口的 order 字段（并非标题中的章号，两者可能不一致）。
     */
    private boolean triggerChapterDownload(String bookId, int order) {
        if (order <= 0) return false;

        ObjectNode args = MAPPER.createObjectNode();
        args.put("book_id", bookId);
        args.put("index", order);

        JsonNode started = downloader().callTool("tnd_start_chapter_download", args);
        if (started == null) return false;

        // 任务启动响应使用 id 字段表示 job_id
        JsonNode jobId = started.has("job_id") ? started.path("job_id") : started.path("id");
        if (jobId.isMissingNode() || jobId.isNull()) {
            return true;
        }

        ObjectNode waitArgs = MAPPER.createObjectNode();
        waitArgs.set("job_id", jobId);
        waitArgs.put("timeout_seconds", 120);

        JsonNode result = downloader().callTool("tnd_wait_job", waitArgs);
        if (result == null) {
            log.warn("等待章节下载任务失败: bookId={} order={}", bookId, order);
            return false;
        }
        return true;
    }

    /**
     * 在线接口取正文，作为下载器不可用时的兜底。
     */
    private TomatoContentRecord fetchFromOnline(String chapterId) {
        ObjectNode args = MAPPER.createObjectNode();
        args.put("item_id", chapterId);

        JsonNode root = fanqie().callTool("online_get_chapter_content", args);
        if (root == null) return null;

        JsonNode content = root.path("content");
        if (content.isMissingNode() || content.isEmpty()) {
            log.warn("online_get_chapter_content 未返回章节数据: chapterId={}", chapterId);
            return null;
        }

        String text = content.path("content").asText("");
        String bookId = content.path("book_id").asText("");
        ChapterRef ref = resolveChapterRef(chapterId);

        return new TomatoContentRecord(
                bookId,
                "",
                ref != null ? ref.order() : 0,
                content.path("title").asText(""),
                text,
                resolveChapterTime(bookId, chapterId),
                countWords(text)
        );
    }

    /**
     * 获取指定书籍的完整章节目录。
     * 在线接口按页返回且不含序号与付费标记，这里循环翻页并补齐缺失字段。
     *
     * @param bookId 书籍ID
     * @return 章节列表，按章节序号升序；获取失败返回空列表
     */
    public List<TomatoChapterRecord> getChapters(String bookId) {
        List<TomatoChapterRecord> list = new ArrayList<>();

        int offset = 0;
        for (int page = 0; page < DIRECTORY_MAX_PAGES; page++) {
            ObjectNode args = MAPPER.createObjectNode();
            args.put("book_id", bookId);
            args.put("offset", offset);
            args.put("limit", DIRECTORY_PAGE_SIZE);

            JsonNode root = fanqie().callTool("online_get_book_directory", args);
            if (root == null) break;

            JsonNode directory = root.path("directory");
            JsonNode chapters = directory.path("chapters");
            if (!chapters.isArray() || chapters.isEmpty()) break;

            int index = 0;
            for (JsonNode ch : chapters) {
                int order = offset + index + 1;
                list.add(new TomatoChapterRecord(
                        ch.path("item_id").asText(""),
                        ch.path("title").asText(""),
                        ch.path("volume").asText(""),
                        false,
                        false,
                        false,
                        0,
                        order,
                        ""
                ));
                index++;
            }

            if (!directory.path("has_more").asBoolean(false)) break;
            JsonNode next = directory.path("next_offset");
            if (!next.isNumber() && !next.isTextual()) break;
            offset = next.asInt(offset + index);
        }

        // 用下载器补齐序号、付费标记与发布时间
        enrichWithTimes(list, bookId);
        return list;
    }

    // ===============================
    //   补充字段
    // ===============================

    /**
     * 从下载器的章节时间接口补齐序号、付费标记与发布时间。
     * 该接口不可用时保留在线接口的推断值，不影响主流程。
     */
    private void enrichWithTimes(List<TomatoChapterRecord> list, String bookId) {
        if (list.isEmpty()) return;

        List<TomatoChapterRecord> enriched = new ArrayList<>(list.size());
        int offset = 0;
        boolean failed = false;

        for (int page = 0; page < DIRECTORY_MAX_PAGES && !failed; page++) {
            ObjectNode args = MAPPER.createObjectNode();
            args.put("book_id", bookId);
            args.put("offset", offset);
            args.put("limit", DIRECTORY_PAGE_SIZE);

            JsonNode root = downloader().callTool("tnd_get_chapter_times", args);
            if (root == null) {
                failed = true;
                break;
            }

            JsonNode chapters = root.path("chapters");
            if (!chapters.isArray() || chapters.isEmpty()) break;

            for (JsonNode ch : chapters) {
                enriched.add(new TomatoChapterRecord(
                        ch.path("item_id").asText(""),
                        ch.path("title").asText(""),
                        ch.path("volume").asText(""),
                        ch.path("locked").asBoolean(false),
                        false,
                        false,
                        ch.path("need_pay").asBoolean(false) ? 1 : 0,
                        ch.path("order").asInt(0),
                        ch.path("first_pass_time").path("beijing_time").asText("")
                ));
            }

            if (!root.path("has_more").asBoolean(false)) break;
            offset = root.path("next_offset").asInt(offset + chapters.size());
        }

        if (failed || enriched.isEmpty()) {
            log.warn("无法从下载器补齐章节时间，保留在线接口推断值: bookId={}", bookId);
            return;
        }

        list.clear();
        list.addAll(enriched);
    }

    private String fetchLatestPublishTime(String bookId, String latestChapterId) {
        if (latestChapterId == null || latestChapterId.isBlank()) return "";

        ObjectNode args = MAPPER.createObjectNode();
        args.put("book_id", bookId);
        args.put("item_id", latestChapterId);
        args.put("limit", 1);

        JsonNode root = downloader().callTool("tnd_get_chapter_times", args);
        if (root == null) return "";

        JsonNode chapters = root.path("chapters");
        if (!chapters.isArray() || chapters.isEmpty()) return "";

        return chapters.get(0).path("first_pass_time").path("beijing_time").asText("");
    }

    /**
     * 章节的定位信息：所属书籍、章节序号与发布时间。
     */
    private record ChapterRef(String bookId, int order) {
    }

    /**
     * 解析 chapterId 的定位信息。
     * 先通过在线正文接口拿到 bookId，再用下载器的单章时间接口取序号，
     * 避免为查一章而翻完整个目录。
     */
    private ChapterRef resolveChapterRef(String chapterId) {
        String bookId = resolveBookId(chapterId);
        if (bookId == null || bookId.isBlank()) {
            log.warn("无法解析章节 {} 所属的 bookId", chapterId);
            return null;
        }

        ObjectNode args = MAPPER.createObjectNode();
        args.put("book_id", bookId);
        args.put("item_id", chapterId);

        JsonNode root = downloader().callTool("tnd_get_chapter_times", args);
        if (root != null) {
            JsonNode chapters = root.path("chapters");
            if (chapters.isArray() && !chapters.isEmpty()) {
                return new ChapterRef(bookId, chapters.get(0).path("order").asInt(0));
            }
        }
        return new ChapterRef(bookId, 0);
    }

    /**
     * 查询单章的发布时间。
     */
    private String resolveChapterTime(String bookId, String chapterId) {
        ObjectNode args = MAPPER.createObjectNode();
        args.put("book_id", bookId);
        args.put("item_id", chapterId);

        JsonNode root = downloader().callTool("tnd_get_chapter_times", args);
        if (root == null) return "";

        JsonNode chapters = root.path("chapters");
        if (!chapters.isArray() || chapters.isEmpty()) return "";

        return chapters.get(0).path("first_pass_time").path("beijing_time").asText("");
    }

    /**
     * 通过在线正文接口反查章节所属的 bookId。
     */
    private String resolveBookId(String chapterId) {
        ObjectNode args = MAPPER.createObjectNode();
        args.put("item_id", chapterId);

        JsonNode root = fanqie().callTool("online_get_chapter_content", args);
        if (root == null) return null;

        return root.path("content").path("book_id").asText("");
    }
}
