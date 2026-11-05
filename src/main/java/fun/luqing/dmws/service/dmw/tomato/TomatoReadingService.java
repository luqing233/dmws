package fun.luqing.dmws.service.dmw.tomato;

import fun.luqing.dmws.client.TomatoApiClient;
import fun.luqing.dmws.entity.dmw.tomato.TomatoBookContent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.util.*;

/** Interactive reads never wait for the download worker or infer order from item IDs. */
@Service
@RequiredArgsConstructor
public class TomatoReadingService {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final TomatoApiClient client;
    private final TomatoBookContentService contentService;

    public ObjectNode read(String bookId, List<String> requested, boolean downloadMissing, int perChapter, int total) {
        TomatoApiClient.validateIds(bookId, requested, 20);
        if (perChapter < 0 || perChapter > 200000 || total < 1 || total > 1000000) {
            throw new IllegalArgumentException("正文预算超出范围（每章0–200000，总计1–1000000）");
        }
        List<String> ids = new ArrayList<>(new LinkedHashSet<>(requested));
        Map<String, TomatoBookContent> metadata = new HashMap<>();
        for (var chapter : contentService.findAllByBookIdOrderByRealChapterOrderAsc(bookId)) metadata.put(chapter.getChapterId(), chapter);
        Map<String, JsonNode> available = new HashMap<>();
        for (String id : ids) {
            var cached = metadata.get(id);
            if (cached != null && TomatoApiClient.hasBody(cached.getContent())
                    && (cached.getWordCount() == null || cached.getWordCount() <= cached.getContent().length())) {
                available.put(id, MAPPER.createObjectNode().put("content", cached.getContent()).put("title", cached.getTitle()).put("truncated", false));
            }
        }
        List<String> missing = ids.stream().filter(id -> !available.containsKey(id)).toList();
        JsonNode fetched = null;
        if (!missing.isEmpty()) {
            fetched = client.requestChapters(bookId, missing, downloadMissing, 0, 1000000);
            if (fetched != null && fetched.path("refresh_required").asBoolean()) {
                JsonNode refreshed = client.requestChapters(bookId, missing, false, 0, 1000000);
                if (refreshed != null) fetched = refreshed;
            }
            if (fetched != null) {
                for (JsonNode row : fetched.path("chapters")) {
                    String id = row.path("item_id").asText();
                    String body = row.path("content").asText("");
                    if (!missing.contains(id) || (!TomatoApiClient.hasBody(body) && !(row.path("truncated").asBoolean() && row.path("total_chars").asInt() > 0))) continue;
                    available.put(id, row);
                    var cached = metadata.get(id);
                    boolean complete = !row.path("truncated").asBoolean()
                            && (!fetched.path("truncated").asBoolean() || row.has("truncated"));
                    if (cached != null && complete) {
                        cached.setContent(body); cached.setWordCount(body.length());
                        contentService.saveOrUpdate(cached); // Only a complete body may enter the chart/statistics cache.
                    }
                }
            }
        }
        ObjectNode result = MAPPER.createObjectNode().put("ok", true).put("book_id", bookId).put("requested_count", ids.size());
        if (fetched != null) {
            for (String key : List.of("batch_id", "download", "download_error", "refresh_required", "errors")) {
                if (fetched.hasNonNull(key)) result.set(key, fetched.get(key));
            }
            if (fetched.path("mcp_is_error").asBoolean()) {
                result.set("download_error", MAPPER.createObjectNode().put("code", fetched.path("code").asText("TOOL_ERROR"))
                        .put("message", fetched.path("message").asText("正文接口返回错误")));
            }
        } else if (!missing.isEmpty()) {
            result.set("download_error", MAPPER.createObjectNode().put("code", "TRANSPORT_ERROR").put("message", "正文接口暂不可用，未确认下载是否提交"));
        }
        var rows = result.putArray("chapters"); var absent = result.putArray("missing_item_ids");
        int remaining = total, returned = 0, count = 0; boolean truncated = false;
        for (String id : ids) {
            JsonNode source = available.get(id);
            if (source == null) { absent.add(id); continue; }
            count++;
            String full = source.path("content").asText();
            int end = Math.min(full.length(), Math.min(remaining, perChapter == 0 ? full.length() : perChapter));
            if (end > 0 && end < full.length() && Character.isHighSurrogate(full.charAt(end - 1))) end--;
            boolean cut = end < full.length() || source.path("truncated").asBoolean();
            if (fetched != null && fetched.path("truncated").asBoolean() && !source.has("truncated")) cut = true;
            ObjectNode row = rows.addObject().put("item_id", id).put("chapter_id", id)
                    .put("title", source.path("title").asText("")).put("content", full.substring(0, end))
                    .put("returned_chars", end).put("truncated", cut);
            if (!source.path("truncated").asBoolean()) row.put("character_count", full.length());
            else row.putNull("character_count");
            var cached = metadata.get(id);
            if (cached != null) {
                if (cached.getRealChapterOrder() != null && cached.getRealChapterOrder() > 0) row.put("order", cached.getRealChapterOrder());
                row.put("update_time", cached.getUpdateTime());
                if (cached.getTitle() != null) row.put("title", cached.getTitle());
            }
            remaining -= end; returned += end; truncated |= cut;
        }
        String state = absent.isEmpty() ? "ready" : fetched != null && "downloading".equals(fetched.path("status").asText()) ? "downloading" : "partial";
        boolean complete = absent.isEmpty() && !truncated && !result.hasNonNull("download_error") && !result.path("refresh_required").asBoolean();
        result.put("count_metric", "body_utf16_characters").put("status", state).put("available_count", count).put("returned_count", rows.size()).put("returned_chars", returned)
                .put("truncated", truncated).put("complete", complete);
        String message = "可读 " + count + "/" + ids.size() + " 章";
        if (!absent.isEmpty()) message += "，缺失 " + absent.size() + " 章";
        if ("downloading".equals(state)) message += "；缺失正文已排队，尚未下载完成";
        if (result.hasNonNull("download_error")) message += "；下载未确认成功：" + result.path("download_error").path("message").asText();
        if (result.path("refresh_required").asBoolean()) message += "；请重新读取本组章节以刷新快照";
        if (truncated) message += "；本次返回已截断，并非完整正文";
        if ("downloading".equals(state)) result.put("poll_after_seconds", 5);
        result.put("display_text", message);
        return result;
    }

    public JsonNode check(String bookId, List<String> ids) { return client.checkChapters(bookId, ids); }
    public JsonNode batch(String tool, String id, boolean cancelCurrent) { return client.downloadBatch(tool, id, cancelCurrent); }
    public JsonNode diagnostics() { return client.downloaderDiagnostics(); }
}
