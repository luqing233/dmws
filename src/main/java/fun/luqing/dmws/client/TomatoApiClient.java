package fun.luqing.dmws.client;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import fun.luqing.dmws.config.ConfigManager;
import fun.luqing.dmws.config.McpConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

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

    private final ConfigManager configManager;
    private McpClient fanqie;
    private McpClient downloader;

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

        JsonNode root = fanqie().callTool("get_book_detail", args);
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
     * 章节正文在小说侧接口中是纯文本，直接返回内容与元数据。
     *
     * @param chapterId 章节ID
     * @return 章节正文与元数据；获取失败返回 null
     */
    public TomatoContentRecord getChapterContent(String chapterId) {
        ObjectNode args = MAPPER.createObjectNode();
        args.put("item_id", chapterId);

        JsonNode root = fanqie().callTool("get_chapter_content", args);
        if (root == null) return null;

        JsonNode content = root.path("content");
        if (content.isMissingNode() || content.isEmpty()) {
            log.warn("get_chapter_content 未返回章节数据: chapterId={}", chapterId);
            return null;
        }

        String text = content.path("content").asText("");
        String bookId = content.path("book_id").asText("");

        return new TomatoContentRecord(
                bookId,
                "",
                resolveChapterOrder(bookId, chapterId),
                content.path("title").asText(""),
                text,
                resolveChapterTime(bookId, chapterId),
                text.length()
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

            JsonNode root = fanqie().callTool("get_book_directory", args);
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

    private int resolveChapterOrder(String bookId, String chapterId) {
        for (TomatoChapterRecord rec : getChapters(bookId)) {
            if (chapterId.equals(rec.itemId())) {
                return rec.realChapterOrder();
            }
        }
        return 0;
    }

    private String resolveChapterTime(String bookId, String chapterId) {
        for (TomatoChapterRecord rec : getChapters(bookId)) {
            if (chapterId.equals(rec.itemId())) {
                return rec.firstPassTime();
            }
        }
        return "";
    }
}
