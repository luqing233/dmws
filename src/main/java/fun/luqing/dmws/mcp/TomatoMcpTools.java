package fun.luqing.dmws.mcp;

import fun.luqing.dmws.config.DmwConfig;
import fun.luqing.dmws.entity.dmw.tomato.TomatoBookContent;
import fun.luqing.dmws.entity.dmw.tomato.TomatoBookList;
import fun.luqing.dmws.entity.dmw.tomato.TomatoSubscriptionGroup;
import fun.luqing.dmws.service.dmw.tomato.TomatoBookChartService;
import fun.luqing.dmws.service.dmw.tomato.TomatoBookContentService;
import fun.luqing.dmws.service.dmw.tomato.TomatoBookListService;
import fun.luqing.dmws.service.dmw.tomato.TomatoSubscriptionGroupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 番茄业务 MCP 适配器，只调用业务 Service，不处理 HTTP 和其他领域工具。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TomatoMcpTools {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_SEARCH_RESULTS = 20;
    private static final int MAX_CHAPTERS_PER_PAGE = 200;

    private final DmwConfig dmwConfig;
    private final TomatoBookListService bookListService;
    private final TomatoBookContentService contentService;
    private final TomatoSubscriptionGroupService subscriptionService;
    private final TomatoBookChartService chartService;
    private final BookUpdateJobs bookUpdateJobs;
    private final BookSubscriptionJobs subscriptionJobs;
    private final fun.luqing.dmws.service.dmw.tomato.TomatoReadingService readingService;

    private final ReentrantLock writeLock = new ReentrantLock();

    public void execute(String tool, JsonNode args, ArrayNode content) {
        switch (tool) {
            case "dmw_get_update_job" -> {
                String id = requiredString(args, "job_id");
                addText(content, id.startsWith("sub-") ? subscriptionJobs.get(id) : bookUpdateJobs.get(id));
            }
            case "dmw_search_books" -> addText(content, searchBooks(args));
            case "dmw_get_book" -> addText(content, getBook(args));
            case "dmw_list_subscriptions" -> addText(content, listSubscriptions(args));
            case "dmw_list_chapters" -> addText(content, listChapters(args));
            case "dmw_get_chapters" -> addText(content, readChapters(args));
            case "dmw_check_chapters" -> addText(content, normalize(readingService.check(requiredString(args, "book_id"), itemIds(args, 50))));
            case "dmw_get_download_batch", "dmw_cancel_download_batch", "dmw_retry_download_batch" -> addText(content, downloadBatch(tool, args));
            case "dmw_get_downloader_diagnostics" -> {
                JsonNode diagnostic = readingService.diagnostics();
                if (diagnostic == null || diagnostic.path("mcp_is_error").asBoolean()) addText(content, normalize(diagnostic));
                else {
                    ObjectNode report = MAPPER.createObjectNode().put("ok", true).put("diagnostics_ok", diagnostic.path("ok").asBoolean());
                    report.set("diagnostics", diagnostic); addText(content, report);
                }
            }
            case "dmw_get_chapter_content" -> addText(content, getChapterContent(args));
            case "dmw_get_update_stats" -> addText(content, getUpdateStats(args));
            case "dmw_get_update_heatmap" -> addHeatmap(content, args);
            case "dmw_subscribe_book" -> addText(content, subscribeBook(args));
            case "dmw_unsubscribe_book" -> addText(content, unsubscribeBook(args));
            case "dmw_update_book" -> addText(content, updateBook(args));
            default -> throw new McpToolException("未知 DMW 工具: " + tool);
        }
    }
    private ObjectNode searchBooks(JsonNode args) {
        String query = requiredString(args, "q");
        String normalized = query.toLowerCase();
        List<TomatoBookList> books = bookListService.findAll().stream()
                .filter(book -> contains(book.getBookId(), normalized)
                        || contains(book.getBookName(), normalized))
                .limit(MAX_SEARCH_RESULTS)
                .toList();

        ArrayNode rows = MAPPER.createArrayNode();
        books.forEach(book -> rows.add(bookNode(book)));

        ObjectNode result = MAPPER.createObjectNode();
        result.put("scope", "dmw_local");
        result.put("query", query);
        result.set("books", rows);
        if (books.isEmpty()) {
            result.put("hint",
                    "No local DMW book matched. Use tnd_search_books on 8001 for global Fanqie search.");
        }
        return result;
    }

    private ObjectNode getBook(JsonNode args) {
        return bookNode(findBook(requiredString(args, "book_id")));
    }

    private ObjectNode listSubscriptions(JsonNode args) {
        long groupId = requiredLong(args, "group_id");
        ArrayNode rows = MAPPER.createArrayNode();
        for (TomatoSubscriptionGroup subscription : subscriptionService.findByGroupId(groupId)) {
            ObjectNode row = MAPPER.createObjectNode();
            row.put("subscription_id", subscription.getId());
            row.put("group_id", subscription.getGroupId());
            row.put("book_id", subscription.getBookId());
            row.put("operator", subscription.getOperator());
            row.put("at_all", subscription.isAtAll());
            row.put("enable", subscription.isEnable());
            bookListService.findByBookId(subscription.getBookId())
                    .ifPresent(book -> row.put("book_name", book.getBookName()));
            rows.add(row);
        }
        ObjectNode result = MAPPER.createObjectNode();
        result.put("group_id", groupId);
        result.put("count", rows.size());
        result.set("subscriptions", rows);
        return result;
    }

    private ObjectNode listChapters(JsonNode args) {
        String bookId = requiredString(args, "book_id");
        List<TomatoBookContent> chapters =
                contentService.findAllByBookIdOrderByRealChapterOrderAsc(bookId);
        int offset = Math.max(0, optionalInt(args, "offset", 0));
        int limit = Math.min(MAX_CHAPTERS_PER_PAGE,
                Math.max(1, optionalInt(args, "limit", 50)));
        int end = (int) Math.min(chapters.size(), (long) offset + limit);
        List<TomatoBookContent> page = offset >= chapters.size()
                ? List.of() : chapters.subList(offset, end);

        ArrayNode rows = MAPPER.createArrayNode();
        for (TomatoBookContent chapter : page) {
            ObjectNode row = MAPPER.createObjectNode();
            row.put("chapter_id", chapter.getChapterId());
            row.put("title", chapter.getTitle());
            row.put("order", chapter.getRealChapterOrder());
            row.put("word_count", chapter.getWordCount());
            row.put("update_time", chapter.getUpdateTime());
            row.put("has_content", chapter.getContent() != null && !chapter.getContent().isBlank());
            rows.add(row);
        }
        ObjectNode result = MAPPER.createObjectNode();
        result.put("book_id", bookId);
        result.put("total", chapters.size());
        result.put("offset", offset);
        result.put("limit", limit);
        result.put("has_more", end < chapters.size());
        result.set("chapters", rows);
        return result;
    }

    private ObjectNode getChapterContent(JsonNode args) {
        String bookId = requiredString(args, "book_id"), chapterId = optionalString(args, "chapter_id");
        int order = optionalInt(args, "order", 0), max = optionalInt(args, "max_chars", 0);
        var chapters = contentService.findAllByBookIdOrderByRealChapterOrderAsc(bookId);
        TomatoBookContent chapter = chapters.stream().filter(c -> !chapterId.isBlank() ? chapterId.equals(c.getChapterId())
                : order > 0 && c.getRealChapterOrder() != null && c.getRealChapterOrder() == order)
                .findFirst().orElseThrow(() -> new McpToolException("章节不在本地目录，请先更新目录"));
        ObjectNode result = readingService.read(bookId, List.of(chapter.getChapterId()), optionalBoolean(args,"download_missing",false), max, 1000000);
        result.put("chapter_id", chapter.getChapterId()).put("title", chapter.getTitle()).put("order", chapter.getRealChapterOrder())
                .put("word_count", chapter.getWordCount()).put("update_time", chapter.getUpdateTime());
        result.put("content", result.path("chapters").isEmpty() ? "" : result.path("chapters").get(0).path("content").asText());
        if (!result.path("chapters").isEmpty() && result.path("chapters").get(0).hasNonNull("character_count")) {
            result.set("word_count", result.path("chapters").get(0).path("character_count"));
        }
        return result;
    }

    private List<String> itemIds(JsonNode args, int limit) {
        JsonNode values = args.path("item_ids");
        if (!values.isArray() || values.isEmpty() || values.size() > limit) throw new McpToolException("item_ids 必须包含1到" + limit + "个数字字符串");
        var result = new java.util.ArrayList<String>();
        for (JsonNode id : values) {
            if (!id.isTextual() || !id.asText().matches("[0-9]{1,128}")) throw new McpToolException("章节ID必须为数字字符串");
            result.add(id.asText());
        }
        return result;
    }

    private ObjectNode readChapters(JsonNode args) {
        String bookId = requiredString(args, "book_id");
        ObjectNode result = readingService.read(bookId, itemIds(args, 20), optionalBoolean(args,"download_missing",false),
                optionalInt(args,"max_chars_per_chapter",0), optionalInt(args,"max_total_chars",100000));
        if (optionalBoolean(args,"include_forward_nodes",false)) {
            String name = bookListService.findByBookId(bookId).map(TomatoBookList::getBookName).orElse("");
            fun.luqing.dmws.service.dmw.tomato.TomatoForwardFormatter.attach(result, name);
        }
        return result;
    }

    private boolean optionalBoolean(JsonNode args, String field, boolean fallback) {
        if (!args.has(field)) return fallback;
        if (!args.path(field).isBoolean()) throw new McpToolException(field + " 必须是布尔值");
        return args.path(field).asBoolean();
    }

    private ObjectNode normalize(JsonNode payload) {
        if (payload == null) return MAPPER.createObjectNode().put("ok", false).put("code", "TRANSPORT_ERROR")
                .put("message", "下载器调用未获确认，不能认定成功；不要自动重复提交写操作");
        ObjectNode result = ((ObjectNode) payload).deepCopy();
        result.put("ok", !payload.path("mcp_is_error").asBoolean());
        return result;
    }

    private ObjectNode downloadBatch(String tool, JsonNode args) {
        boolean cancel = "dmw_cancel_download_batch".equals(tool), retry = "dmw_retry_download_batch".equals(tool);
        if (cancel || retry) { requireConfirmation(args); requireMaster(args); }
        String id = requiredString(args,"batch_id");
        String upstream = cancel ? "tnd_cancel_batch" : retry ? "tnd_retry_batch" : "tnd_get_batch_status";
        ObjectNode result = normalize(readingService.batch(upstream, id, optionalBoolean(args,"cancel_current",false)));
        if (!result.path("ok").asBoolean()) return result;
        String status = result.path("status").asText();
        String message = switch (status) {
            case "canceling" -> "取消请求已保存，尚未确认已停止";
            case "canceled" -> "批次已取消；已完成的正文会保留";
            case "done" -> "批次任务已结束，请按原章节ID列表重新读取核对正文";
            case "done_with_errors", "partial" -> "批次部分完成，请逐项检查错误，并重新读取原章节列表";
            case "failed" -> "批次失败，请检查错误；未确认正文完整";
            case "already_downloaded" -> "目标正文已存在，请按原章节列表重新读取；未创建新批次";
            case "nothing_to_retry" -> "没有可重试项，未创建新批次";
            default -> retry ? "重试请求已提交，请改用返回的新批次ID跟踪；尚未完成" : "批次尚未结束，请稍后按同一批次ID查询";
        };
        if (result.hasNonNull("cancel_error")) message += "；取消信号发生错误，不能认定已停止";
        result.put("display_text", message).put("content_verified", false).put("poll_after_seconds", 5);
        return result;
    }

    private ObjectNode getUpdateStats(JsonNode args) {
        String bookId = requiredString(args, "book_id");
        LocalDate start = optionalDate(args, "start_date");
        LocalDate end = optionalDate(args, "end_date");
        if (start != null && end != null && start.isAfter(end)) {
            throw new McpToolException("start_date 不能晚于 end_date");
        }

        ArrayNode days = MAPPER.createArrayNode();
        int totalChapters = 0;
        long totalWords = 0;
        int missingWords = 0;
        for (Object[] row : contentService.findStatsLast365Days(bookId)) {
            LocalDate date = LocalDate.parse(String.valueOf(row[0]));
            if (start != null && date.isBefore(start)) continue;
            if (end != null && date.isAfter(end)) continue;
            int chapterCount = ((Number) row[1]).intValue();
            long wordCount = ((Number) row[2]).longValue();
            int missing = row.length > 3 && row[3] != null ? ((Number) row[3]).intValue() : 0;
            missingWords += missing;
            totalChapters += chapterCount;
            totalWords += wordCount;
            ObjectNode day = MAPPER.createObjectNode();
            day.put("date", date.toString());
            day.put("chapter_count", chapterCount);
            day.put("word_count", wordCount);
            day.put("character_count", wordCount);
            day.put("missing_word_count_chapters", missing);
            days.add(day);
        }

        ObjectNode result = MAPPER.createObjectNode();
        result.put("book_id", bookId);
        result.put("total_chapters", totalChapters);
        result.put("total_words", totalWords);
        result.put("total_characters", totalWords);
        result.put("count_metric", "body_utf16_characters");
        result.put("missing_word_count_chapters", missingWords);
        result.put("data_complete", missingWords == 0);
        result.set("days", days);
        return result;
    }

    private void addHeatmap(ArrayNode content, JsonNode args) {
        String bookId = requiredString(args, "book_id");
        TomatoBookList book = findBook(bookId);
        var heatmap = chartService.generateHeatmap(bookId, book.getBookName());
        byte[] image = heatmap.image();
        ObjectNode summary = MAPPER.createObjectNode();
        summary.put("book_id", bookId);
        summary.put("book_name", book.getBookName());
        summary.put("mime_type", "image/png");
        summary.put("bytes", image.length);
        summary.put("data_complete", heatmap.dataComplete());
        summary.put("missing_word_count_chapters", heatmap.missingWordCountChapters());
        summary.put("source", "dmw_cached_statistics");
        summary.put("count_metric", "body_utf16_characters");
        addText(content, summary);
        ObjectNode imageNode = content.addObject();
        imageNode.put("type", "image");
        imageNode.put("mimeType", "image/png");
        imageNode.put("data", Base64.getEncoder().encodeToString(image));
    }

    private ObjectNode subscribeBook(JsonNode args) {
        requireConfirmation(args);
        long operator = requireMaster(args);
        long groupId = requiredLong(args, "group_id");
        String bookId = requiredString(args, "book_id");
        boolean atAll = args.path("at_all").asBoolean(false);

        return subscriptionJobs.submit(groupId, bookId, operator, atAll);
    }

    private ObjectNode unsubscribeBook(JsonNode args) {
        requireConfirmation(args);
        requireMaster(args);
        long groupId = requiredLong(args, "group_id");
        String bookId = requiredString(args, "book_id");

        writeLock.lock();
        try {
            TomatoSubscriptionGroup subscription =
                    subscriptionService.findByGroupIdAndBookId(groupId, bookId)
                            .orElseThrow(() -> new McpToolException("未找到对应订阅"));
            subscriptionService.deleteById(subscription.getId());
            ObjectNode result = MAPPER.createObjectNode();
            result.put("ok", true);
            result.put("message", "已取消订阅");
            result.put("group_id", groupId);
            result.put("book_id", bookId);
            return result;
        } finally {
            writeLock.unlock();
        }
    }

    private ObjectNode updateBook(JsonNode args) {
        requireConfirmation(args);
        requireMaster(args);
        String bookId = requiredString(args, "book_id");
        TomatoBookList book = findBook(bookId);

        return bookUpdateJobs.submit(bookId, book.getBookName());
    }

    private TomatoBookList findBook(String bookId) {
        return bookListService.findByBookId(bookId)
                .orElseThrow(() -> new McpToolException("书籍不存在: " + bookId));
    }

    private ObjectNode bookNode(TomatoBookList book) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("book_id", book.getBookId());
        node.put("book_name", book.getBookName());
        node.put("last_chapter_id", book.getLastChapterId());
        node.put("last_title", book.getLastTitle());
        node.put("last_time", book.getLastTime());
        node.put("total_words", book.getTotalWords());
        return node;
    }

    private void requireConfirmation(JsonNode args) {
        if (!args.path("confirm").asBoolean(false)) {
            throw new McpToolException("写操作需要 confirm=true");
        }
    }

    private long requireMaster(JsonNode args) {
        long operator = requiredLong(args, "operator");
        if (!dmwConfig.isMaster(operator)) {
            throw new McpToolException("该操作仅限 master");
        }
        return operator;
    }

    private String requiredString(JsonNode args, String name) {
        String value = args == null ? "" : args.path(name).asText("").trim();
        if (value.isBlank()) throw new McpToolException(name + " 不能为空");
        return value;
    }

    private String optionalString(JsonNode args, String name) {
        return args == null ? "" : args.path(name).asText("").trim();
    }

    private long requiredLong(JsonNode args, String name) {
        JsonNode node = args == null ? null : args.get(name);
        if (node == null || node.isNull() || node.asText("").isBlank()) {
            throw new McpToolException(name + " 不能为空");
        }
        try {
            return Long.parseLong(node.asText());
        } catch (NumberFormatException e) {
            throw new McpToolException(name + " 必须是整数");
        }
    }

    private int optionalInt(JsonNode args, String name, int fallback) {
        JsonNode node = args == null ? null : args.get(name);
        if (node == null || node.isNull() || node.asText("").isBlank()) return fallback;
        try {
            return Integer.parseInt(node.asText());
        } catch (NumberFormatException e) {
            throw new McpToolException(name + " 必须是整数");
        }
    }

    private LocalDate optionalDate(JsonNode args, String name) {
        String value = optionalString(args, name);
        if (value.isBlank()) return null;
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new McpToolException(name + " 格式应为 yyyy-MM-dd");
        }
    }

    private boolean contains(String value, String query) {
        return value != null && value.toLowerCase().contains(query);
    }

    private void addText(ArrayNode content, JsonNode data) {
        ObjectNode text = content.addObject();
        text.put("type", "text");
        text.put("text", data.toString());
    }

}
