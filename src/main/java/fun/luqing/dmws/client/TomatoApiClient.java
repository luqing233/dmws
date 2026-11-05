package fun.luqing.dmws.client;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import fun.luqing.dmws.config.ConfigManager;
import fun.luqing.dmws.config.McpConfig;
import fun.luqing.dmws.common.logging.TomatoLogContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 番茄小说客户端，只通过本地下载器 MCP 获取数据。
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
    /** 批量下载的轮询次数与间隔（每次 2 秒，最多约 3 分钟） */
    private static final int BATCH_POLL_TIMES = 90;
    private static final long BATCH_POLL_INTERVAL_MS = 2000L;

    private final ConfigManager configManager;
    private volatile McpClient downloader;
    /** 最近一次正文获取失败的结构化错误码 */
    private final ThreadLocal<String> lastContentErrorCode = new ThreadLocal<>();
    private final Map<String, Map<String, TomatoChapterRecord>> directoryCache = new java.util.concurrent.ConcurrentHashMap<>();

    public TomatoApiClient(ConfigManager configManager) {
        this.configManager = configManager;
    }

    private synchronized McpClient downloader() {
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

        JsonNode root = downloader().callTool("tnd_preview_book", args);
        if (root == null) return null;

        String bookName = root.path("book_name").asText("");
        if (bookName.isBlank()) {
            log.warn("tnd_preview_book 未返回书名: bookId={}", bookId);
            return null;
        }

        String latestChapterTitle = root.path("last_chapter_title").asText("");
        String latestChapterId = "";
        String latestPublishTime = "";
        int chapterCount = root.path("chapter_count").asInt(0);

        if (chapterCount > 0) {
            ObjectNode latestArgs = MAPPER.createObjectNode();
            latestArgs.put("book_id", bookId);
            latestArgs.put("offset", Math.max(0, chapterCount - 1));
            latestArgs.put("limit", 1);

            JsonNode directory = downloader().callTool("tnd_get_chapter_times", latestArgs);
            if (directory != null) {
                JsonNode chapters = directory.path("chapters");
                if (chapters.isArray() && !chapters.isEmpty()) {
                    JsonNode latest = chapters.get(0);
                    latestChapterId = latest.path("item_id").asText("");
                    String title = latest.path("title").asText("");
                    if (!title.isBlank()) latestChapterTitle = title;
                    latestPublishTime = latest.path("first_pass_time").path("beijing_time").asText("");
                }
            }
        }

        return new TomatoUpdateRecord(
                bookName,
                latestChapterTitle,
                latestChapterId,
                latestPublishTime,
                root.path("word_count").asLong(0L)
        );
    }

    /** Unified nonblocking v0.3 API. Downloads are opt-in and target item IDs only. */
    public JsonNode requestChapters(String bookId, List<String> itemIds, boolean downloadMissing,
                                    int perChapter, int total) {
        validateIds(bookId, itemIds, 20);
        if (perChapter < 0 || perChapter > 200000 || total < 1 || total > 1000000) {
            throw new IllegalArgumentException("正文预算超出范围（每章0–200000，总计1–1000000）");
        }
        ObjectNode args = MAPPER.createObjectNode().put("book_id", bookId)
                .put("download_missing", downloadMissing).put("max_chars_per_chapter", perChapter).put("max_total_chars", total);
        args.set("item_ids", MAPPER.valueToTree(itemIds));
        JsonNode payload = downloader().callToolResult("tnd_get_chapters", args);
        if (payload != null && payload.hasNonNull("book_id") && !bookId.equals(payload.path("book_id").asText())) {
            return MAPPER.createObjectNode().put("mcp_is_error", true).put("code", "BOOK_ID_MISMATCH")
                    .put("message", "下载器返回了不同书籍的正文，已拒绝使用");
        }
        return payload;
    }

    public JsonNode checkChapters(String bookId, List<String> itemIds) {
        validateIds(bookId, itemIds, 50);
        ObjectNode args = MAPPER.createObjectNode().put("book_id", bookId);
        args.set("item_ids", MAPPER.valueToTree(itemIds));
        return downloader().callToolResult("tnd_check_chapters", args);
    }

    public JsonNode downloadBatch(String tool, String batchId, boolean cancelCurrent) {
        if (!List.of("tnd_get_batch_status", "tnd_cancel_batch", "tnd_retry_batch").contains(tool)
                || batchId == null || batchId.isBlank() || batchId.length() > 200) {
            throw new IllegalArgumentException("必须提供明确的下载批次ID");
        }
        ObjectNode args = MAPPER.createObjectNode().put("batch_id", batchId);
        if ("tnd_cancel_batch".equals(tool)) args.put("cancel_current", cancelCurrent);
        return downloader().callToolResult(tool, args);
    }

    public JsonNode downloaderDiagnostics() {
        return downloader().callToolResult("tnd_get_diagnostics", MAPPER.createObjectNode());
    }

    public static void validateIds(String bookId, List<String> itemIds, int limit) {
        if (bookId == null || !bookId.matches("[0-9]{1,128}") || itemIds == null || itemIds.isEmpty()
                || itemIds.size() > limit || itemIds.stream().anyMatch(id -> id == null || !id.matches("[0-9]{1,128}"))) {
            throw new IllegalArgumentException("book_id及章节ID必须是数字字符串，每次最多" + limit + "章");
        }
    }

    /** Background backfill may wait on ONE batch. Chat reads use requestChapters directly. */
    public Map<String, TomatoContentRecord> getChapterContents(String bookId, List<ChapterTarget> targets) {
        Map<String, TomatoContentRecord> result = new LinkedHashMap<>();
        lastContentErrorCode.remove();
        if (targets == null || targets.isEmpty()) return result;
        List<String> ids = targets.stream().map(ChapterTarget::chapterId).distinct().toList();
        JsonNode root = requestChapters(bookId, ids, true, 0, 1000000);
        if (root == null) { lastContentErrorCode.set(downloader().getLastToolErrorCode()); return result; }
        if (root.path("mcp_is_error").asBoolean()) { lastContentErrorCode.set(root.path("code").asText("TOOL_ERROR")); return result; }
        if (root.hasNonNull("batch_id") && "downloading".equals(root.path("status").asText())) {
            String batchId = root.path("batch_id").asText();
            for (int i = 0; i < BATCH_POLL_TIMES; i++) {
                JsonNode status = downloadBatch("tnd_get_batch_status", batchId, false);
                if (status == null || status.path("mcp_is_error").asBoolean()) break;
                String state = status.path("status").asText();
                if (List.of("done", "done_with_errors", "failed", "canceled", "canceling", "waiting_input").contains(state)) break;
                try { Thread.sleep(BATCH_POLL_INTERVAL_MS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
            root = requestChapters(bookId, ids, false, 0, 1000000);
        } else if (root.path("refresh_required").asBoolean()) {
            root = requestChapters(bookId, ids, false, 0, 1000000);
        }
        if (root == null) { lastContentErrorCode.set(downloader().getLastToolErrorCode()); return result; }
        if (root.path("mcp_is_error").asBoolean()) { lastContentErrorCode.set(root.path("code").asText("TOOL_ERROR")); return result; }
        if (root.hasNonNull("download_error")) lastContentErrorCode.set(root.path("download_error").path("code").asText("DOWNLOAD_ERROR"));
        else lastContentErrorCode.remove();
        Map<String, JsonNode> rows = new LinkedHashMap<>();
        for (JsonNode row : root.path("chapters")) rows.put(row.path("item_id").asText(), row);
        for (ChapterTarget target : targets) {
            JsonNode row = rows.get(target.chapterId());
            if (row == null || row.path("truncated").asBoolean()
                    || (root.path("truncated").asBoolean() && !row.has("truncated"))) continue;
            String text = row.path("content").asText("");
            if (!hasBody(text)) continue;
            var metadata = directoryCache.getOrDefault(bookId, Map.of()).get(target.chapterId());
            result.put(target.chapterId(), new TomatoContentRecord(bookId, root.path("book_name").asText(""),
                    target.order(), row.path("title").asText(""), text,
                    metadata == null ? "" : metadata.firstPassTime(), text.length()));
        }
        return result;
    }

    public record ChapterTarget(String chapterId, int order) { }
    public String getLastContentErrorCode() { return lastContentErrorCode.get(); }
    public static boolean isRetryable(String code) {
        return code == null || !List.of("BOOK_NOT_FOUND", "INVALID_ARGUMENT", "BATCH_BUSY", "BATCH_ACTIVE", "BATCH_NOT_FOUND").contains(code);
    }
    public TomatoContentRecord getChapterContent(String bookId, String chapterId, int order) {
        return getChapterContents(bookId, List.of(new ChapterTarget(chapterId, order))).get(chapterId);
    }
    public static boolean hasBody(String text) {
        if (text == null || text.isEmpty()) return false;
        String decoded = org.jsoup.parser.Parser.unescapeEntities(text, false);
        return decoded.codePoints().anyMatch(c -> !Character.isWhitespace(c) && !Character.isSpaceChar(c) && c != 0x200b && c != 0xfeff);
    }

    /**
     * 获取指定书籍的完整章节目录。
     * 目录、序号、付费标记与发布时间全部来自下载器 MCP。
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

            JsonNode root = downloader().callTool("tnd_get_chapter_times", args);
            if (root == null) break;

            JsonNode chapters = root.path("chapters");
            if (!chapters.isArray() || chapters.isEmpty()) break;

            for (JsonNode ch : chapters) {
                list.add(new TomatoChapterRecord(
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
            JsonNode next = root.path("next_offset");
            if (!next.isNumber() && !next.isTextual()) break;
            offset = next.asInt(offset + chapters.size());
        }

        Map<String, TomatoChapterRecord> metadata = new LinkedHashMap<>();
        list.forEach(row -> metadata.put(row.itemId(), row));
        if (directoryCache.size() > 200) directoryCache.clear();
        directoryCache.put(bookId, Map.copyOf(metadata));
        return list;
    }

}
