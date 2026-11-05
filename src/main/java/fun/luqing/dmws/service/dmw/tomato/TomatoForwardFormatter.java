package fun.luqing.dmws.service.dmw.tomato;

import org.jsoup.Jsoup;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Builds literal text nodes only; sending remains an explicit transport operation. */
public final class TomatoForwardFormatter {
    private TomatoForwardFormatter() { }
    public static void attach(ObjectNode result, String bookName) {
        var nodes = new ObjectMapper().createArrayNode();
        String title = "《" + (bookName == null || bookName.isBlank() ? result.path("book_id").asText() : bookName) + "》";
        nodes.add(title.substring(0, Math.min(200, title.length())));
        int remaining = 49000 - nodes.get(0).asText().length(); boolean cut = false;
        for (JsonNode row : result.path("chapters")) {
            String text = row.path("content").asText("");
            if (text.isBlank()) { cut |= row.path("truncated").asBoolean(); continue; }
            String heading = row.path("title").asText("");
            String body = heading + "\n" + Jsoup.parse(text).body().wholeText();
            int offset = 0;
            while (offset < body.length() && remaining > 0 && nodes.size() < 100) {
                int end = Math.min(body.length(), offset + Math.min(3000, remaining));
                if (end < body.length() && end > offset && Character.isHighSurrogate(body.charAt(end - 1))) end--;
                if (end == offset) break;
                nodes.add(body.substring(offset, end)); remaining -= end - offset; offset = end;
            }
            cut |= offset < body.length() || row.path("truncated").asBoolean();
        }
        result.set("forward_nodes", nodes.size() == 1 ? new ObjectMapper().createArrayNode() : nodes);
        result.put("forward_truncated", cut).put("forward_complete", result.path("complete").asBoolean() && !cut && nodes.size() > 1);
    }
}
