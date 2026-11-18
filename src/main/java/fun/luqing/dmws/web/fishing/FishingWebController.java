package fun.luqing.dmws.web.fishing;

import fun.luqing.dmws.service.dmw.economy.*;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import java.util.Map;
import java.util.List;
import static fun.luqing.dmws.service.dmw.economy.FishReviewModels.*;

@RestController
@RequestMapping("/api/fishing")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "dmws.fishing-web", name = "enabled", havingValue = "true")
public class FishingWebController {
    private final EconomyFishReviewService reviews;
    private final FishingWebAccess access;
    private final EconomyFishingEventEditor events;

    @GetMapping("/catalog") public Catalog catalog(@RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "0") int page) { return reviews.catalog(q, page); }
    @PostMapping(value = "/preview", consumes = "application/json")
    public Preview preview(@RequestBody JsonNode draft, @RequestParam(defaultValue = "10") int rodLevel) { return reviews.preview(draft(draft), rodLevel); }
    @PostMapping(value = "/submissions", consumes = "application/json")
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public Receipt submit(@RequestBody JsonNode command, HttpServletRequest request) {
        access.submissionLimit(request);
        return reviews.submit(new Submit(text(command, "requestId"), text(command, "author"),
                command.hasNonNull("fish") ? draft(command.path("fish")) : null, command.hasNonNull("event") ? event(command.path("event")) : null));
    }
    @GetMapping("/submissions/{requestId}") public Receipt progress(@PathVariable String requestId) { return reviews.progress(requestId); }
    @GetMapping("/admin/session") public Map<String, Object> session(HttpServletRequest request) { return access.session(request); }
    public record Login(String username, String password) { }
    @PostMapping(value = "/admin/login", consumes = "application/json")
    public Map<String, Object> login(@RequestBody Login login, HttpServletRequest request) { return access.login(request, login.username(), login.password()); }
    @PostMapping("/admin/logout") public Map<String, Boolean> logout(HttpServletRequest request) { access.logout(request); return Map.of("authenticated", false); }
    @GetMapping("/admin/submissions")
    public Queue queue(@RequestParam(defaultValue = "PENDING") String status, @RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "0") int page) { return reviews.queue(status, q, page); }
    @GetMapping("/event-options") public Map<String, Object> options() {
        return Map.of("scenes", EconomyFishingEventEditor.SCENES, "pools", List.of("NEGATIVE", "COMBAT", "FUN", "REWARD"));
    }
    @PostMapping(value = "/event-preview", consumes = "application/json")
    public FishingEventEditorModels.Preview eventPreview(@RequestBody JsonNode body) { return events.preview(event(body)); }
    @GetMapping("/admin/fish") public List<FishEntry> managedFish() { return reviews.managedFish(); }
    @PostMapping(value = "/admin/fish-preview", consumes = "application/json")
    public Preview previewExisting(@RequestBody JsonNode value, @RequestParam(defaultValue = "10") int rodLevel) { return reviews.previewExisting(draft(value), rodLevel); }
    @PostMapping(value = "/admin/fish/{id}", consumes = "application/json")
    public Submission editFish(@PathVariable int id, @RequestBody JsonNode command, HttpServletRequest request) {
        if (!command.path("enabled").isBoolean()) throw badInput();
        return reviews.editFish(id, new FishEdit(text(command, "requestId"), text(command, "revision"), draft(command.path("fish")), command.path("enabled").booleanValue(), text(command, "note")), access.user(request));
    }
    @GetMapping("/admin/events") public List<FishingEventEditorModels.Entry> managedEvents() { return events.list(); }
    @PostMapping(value = "/admin/events/{code}", consumes = "application/json")
    public Submission editEvent(@PathVariable String code, @RequestBody JsonNode command, HttpServletRequest request) {
        return reviews.editEvent(code, new FishingEventEditorModels.Edit(text(command, "requestId"), text(command, "revision"), event(command.path("event")), text(command, "note")), access.user(request));
    }
    @PostMapping(value = "/admin/submissions/{id}/approve", consumes = "application/json")
    public Submission approve(@PathVariable long id, @RequestBody JsonNode command, HttpServletRequest request) { return reviews.decide(id, true, decision(command, true), access.user(request)); }
    @PostMapping(value = "/admin/submissions/{id}/reject", consumes = "application/json")
    public Submission reject(@PathVariable long id, @RequestBody JsonNode command, HttpServletRequest request) { return reviews.decide(id, false, decision(command, false), access.user(request)); }

    private Decision decision(JsonNode value, boolean approve) {
        var version = value.path("version");
        if (!version.isIntegralNumber() || !version.canConvertToLong()) throw badInput();
        return new Decision(text(value, "requestId"), version.longValue(), approve && value.hasNonNull("fish") ? draft(value.path("fish")) : null,
                text(value, "note"), approve && value.hasNonNull("event") ? event(value.path("event")) : null);
    }
    private FishingEventEditorModels.Event event(JsonNode node) {
        if (!node.isObject() || !node.path("enabled").isBoolean() || !node.path("texts").isArray() || node.path("texts").size() > 60) throw badInput();
        var lines = new java.util.ArrayList<FishingEventEditorModels.Text>();
        for (var line : node.path("texts")) {
            if (!line.isObject() || !line.path("enabled").isBoolean()) throw badInput();
            lines.add(new FishingEventEditorModels.Text(text(line, "code"), text(line, "scene"), text(line, "template"), number(line, "weight"), line.path("enabled").booleanValue()));
        }
        return new FishingEventEditorModels.Event(text(node, "code"), text(node, "name"), text(node, "kind"), text(node, "poolCode"), number(node, "tickets"),
                text(node, "amountMin"), text(node, "amountMax"), text(node, "amountStep"), optionalNumber(node, "jailMinutes", 10),
                optionalNumber(node, "strengthMin", 60), optionalNumber(node, "strengthMax", 100), optionalNumber(node, "winInjuryPercent", 20),
                optionalNumber(node, "lightPercent", 50), optionalNumber(node, "mediumPercent", 35), node.path("enabled").booleanValue(), lines);
    }
    private Integer optionalNumber(JsonNode node, String key, int fallback) { return node.hasNonNull(key) ? number(node, key) : fallback; }
    private Draft draft(JsonNode value) {
        if (!value.isObject() || !value.path("special").isBoolean()) throw badInput();
        return new Draft(text(value, "name"), text(value, "description"), number(value, "level"), number(value, "price"),
                number(value, "difficulty"), number(value, "dimensionsMin"), number(value, "dimensionsMax"), value.path("special").booleanValue());
    }
    private Integer number(JsonNode value, String field) {
        var node = value.path(field); if (!node.isIntegralNumber() || !node.canConvertToInt()) throw badInput(); return node.intValue();
    }
    private String text(JsonNode value, String field) {
        var node = value.path(field); if (node.isMissingNode() || node.isNull()) return null; if (!node.isTextual()) throw badInput(); return node.textValue();
    }
    private FishReviewException badInput() { return new FishReviewException("INVALID_INPUT", "字段格式不正确；价格、等级、难度和尺寸必须为整数。", 400); }
}
