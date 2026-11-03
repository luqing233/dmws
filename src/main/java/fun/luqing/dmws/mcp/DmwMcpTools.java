package fun.luqing.dmws.mcp;

import fun.luqing.dmws.client.TomatoApiClient;
import fun.luqing.dmws.client.TomatoUpdateRecord;
import fun.luqing.dmws.config.DmwConfig;
import fun.luqing.dmws.entity.dmw.tomato.TomatoBookContent;
import fun.luqing.dmws.entity.dmw.tomato.TomatoBookList;
import fun.luqing.dmws.entity.dmw.tomato.TomatoSubscriptionGroup;
import fun.luqing.dmws.service.tomatoservice.TomatoBookChartService;
import fun.luqing.dmws.service.tomatoservice.TomatoBookContentService;
import fun.luqing.dmws.service.tomatoservice.TomatoBookListService;
import fun.luqing.dmws.service.tomatoservice.TomatoSubscriptionGroupService;
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
 * DMW 业务 MCP 工具。工具只调用现有 Service，不直接访问数据库。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DmwMcpTools {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_SEARCH_RESULTS = 20;
    private static final int MAX_CHAPTERS_PER_PAGE = 200;

    private final DmwConfig dmwConfig;
    private final TomatoBookListService bookListService;
    private final TomatoBookContentService contentService;
    private final TomatoSubscriptionGroupService subscriptionService;
    private final TomatoBookChartService chartService;
    private final TomatoApiClient tomatoApiClient;
    private final EconomyMcpTools economyMcpTools;

    private final ReentrantLock writeLock = new ReentrantLock();

    public ObjectNode execute(String tool, JsonNode args) {
        ObjectNode result = MAPPER.createObjectNode();
        ArrayNode content = result.putArray("content");
        try {
            switch (tool) {
                case "dmw_search_books" -> addText(content, searchBooks(args));
                case "dmw_get_book" -> addText(content, getBook(args));
                case "dmw_list_subscriptions" -> addText(content, listSubscriptions(args));
                case "dmw_list_chapters" -> addText(content, listChapters(args));
                case "dmw_get_chapter_content" -> addText(content, getChapterContent(args));
                case "dmw_get_update_stats" -> addText(content, getUpdateStats(args));
                case "dmw_get_update_heatmap" -> addHeatmap(content, args);
                case "dmw_subscribe_book" -> addText(content, subscribeBook(args));
                case "dmw_unsubscribe_book" -> addText(content, unsubscribeBook(args));
                case "dmw_update_book" -> addText(content, updateBook(args));
                case "dmw_economy_transfer" -> addText(content, economyMcpTools.transfer(args));
                case "dmw_economy_rob" -> addText(content, economyMcpTools.rob(args));
                default -> throw new McpToolException("未知 DMW 工具: " + tool);
            }
        } catch (McpToolException e) {
            return error(e.getMessage());
        } catch (Exception e) {
            log.error("DMW MCP 工具 {} 执行失败", tool, e);
            return error("工具执行失败: " + e.getMessage());
        }
        return result;
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
        int end = Math.min(chapters.size(), offset + limit);
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
        String bookId = requiredString(args, "book_id");
        String chapterId = optionalString(args, "chapter_id");
        int order = optionalInt(args, "order", 0);
        if (chapterId.isBlank() && order <= 0) {
            throw new McpToolException("chapter_id 或 order 至少提供一个");
        }

        writeLock.lock();
        try {
            List<TomatoBookContent> chapters =
                    contentService.findAllByBookIdOrderByRealChapterOrderAsc(bookId);
            TomatoBookContent chapter = chapters.stream()
                    .filter(item -> !chapterId.isBlank()
                            ? chapterId.equals(item.getChapterId())
                            : item.getRealChapterOrder() != null
                            && item.getRealChapterOrder() == order)
                    .findFirst()
                    .orElseThrow(() -> new McpToolException("章节不存在"));

            if (chapter.getContent() == null || chapter.getContent().isBlank()) {
                contentService.updateChapter(chapter, false);
            }
            if (chapter.getContent() == null || chapter.getContent().isBlank()) {
                throw new McpToolException("章节正文暂不可用");
            }

            int maxChars = Math.max(0, optionalInt(args, "max_chars", 0));
            String text = chapter.getContent();
            boolean truncated = maxChars > 0 && text.length() > maxChars;
            if (truncated) text = text.substring(0, maxChars);

            ObjectNode result = MAPPER.createObjectNode();
            result.put("book_id", bookId);
            result.put("chapter_id", chapter.getChapterId());
            result.put("title", chapter.getTitle());
            result.put("order", chapter.getRealChapterOrder());
            result.put("word_count", chapter.getWordCount());
            result.put("update_time", chapter.getUpdateTime());
            result.put("truncated", truncated);
            result.put("content", text);
            return result;
        } finally {
            writeLock.unlock();
        }
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
        for (Object[] row : contentService.findStatsLast365Days(bookId)) {
            LocalDate date = LocalDate.parse(String.valueOf(row[0]));
            if (start != null && date.isBefore(start)) continue;
            if (end != null && date.isAfter(end)) continue;
            int chapterCount = ((Number) row[1]).intValue();
            long wordCount = ((Number) row[2]).longValue();
            totalChapters += chapterCount;
            totalWords += wordCount;
            ObjectNode day = MAPPER.createObjectNode();
            day.put("date", date.toString());
            day.put("chapter_count", chapterCount);
            day.put("word_count", wordCount);
            days.add(day);
        }

        ObjectNode result = MAPPER.createObjectNode();
        result.put("book_id", bookId);
        result.put("total_chapters", totalChapters);
        result.put("total_words", totalWords);
        result.set("days", days);
        return result;
    }

    private void addHeatmap(ArrayNode content, JsonNode args) {
        String bookId = requiredString(args, "book_id");
        TomatoBookList book = findBook(bookId);
        writeLock.lock();
        try {
            byte[] image = chartService.generateBookUpdateHeatmap(bookId, book.getBookName());
            ObjectNode summary = MAPPER.createObjectNode();
            summary.put("book_id", bookId);
            summary.put("book_name", book.getBookName());
            summary.put("mime_type", "image/png");
            summary.put("bytes", image.length);
            addText(content, summary);

            ObjectNode imageNode = content.addObject();
            imageNode.put("type", "image");
            imageNode.put("mimeType", "image/png");
            imageNode.put("data", Base64.getEncoder().encodeToString(image));
        } finally {
            writeLock.unlock();
        }
    }

    private ObjectNode subscribeBook(JsonNode args) {
        requireConfirmation(args);
        long operator = requireMaster(args);
        long groupId = requiredLong(args, "group_id");
        String bookId = requiredString(args, "book_id");
        boolean atAll = args.path("at_all").asBoolean(false);

        writeLock.lock();
        try {
            if (subscriptionService.findByGroupIdAndBookId(groupId, bookId).isPresent()) {
                throw new McpToolException("该书已订阅，无需重复");
            }

            TomatoBookList book = bookListService.findByBookId(bookId).orElse(null);
            if (book == null) {
                TomatoUpdateRecord record = tomatoApiClient.getBookDetail(bookId);
                if (record == null) {
                    throw new McpToolException("无法从下载器获取书籍信息");
                }
                book = new TomatoBookList();
                book.setBookId(bookId);
                book.setBookName(record.bookName());
                book.setLastTitle(record.lastChapterTitle());
                book.setLastChapterId(record.lastChapterId());
                book.setLastTime(record.lastPublishTime());
                book.setTotalWords(record.totalWords());
                bookListService.addBook(book);
                contentService.updateChapterList(bookId);
            }

            TomatoSubscriptionGroup subscription = new TomatoSubscriptionGroup();
            subscription.setGroupId(groupId);
            subscription.setBookId(bookId);
            subscription.setOperator(operator);
            subscription.setAtAll(atAll);
            subscription.setEnable(true);
            subscriptionService.addSubscription(subscription);

            ObjectNode result = MAPPER.createObjectNode();
            result.put("ok", true);
            result.put("message", "订阅成功");
            result.set("book", bookNode(book));
            result.put("subscription_id", subscription.getId());
            return result;
        } finally {
            writeLock.unlock();
        }
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

        writeLock.lock();
        try {
            TomatoBookContentService.UpdateResult result =
                    contentService.updateAllChapters(bookId, false);
            ObjectNode response = MAPPER.createObjectNode();
            response.put("ok", true);
            response.put("book_id", bookId);
            response.put("book_name", book.getBookName());
            response.put("total", result.total());
            response.put("success", result.success());
            response.put("fail", result.fail());
            response.put("exist", result.exist());
            return response;
        } finally {
            writeLock.unlock();
        }
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

    private ObjectNode error(String message) {
        ObjectNode result = MAPPER.createObjectNode();
        ArrayNode content = result.putArray("content");
        ObjectNode text = content.addObject();
        text.put("type", "text");
        text.put("text", message);
        result.put("isError", true);
        return result;
    }

}
