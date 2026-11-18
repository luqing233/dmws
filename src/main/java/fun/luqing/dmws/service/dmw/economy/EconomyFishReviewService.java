package fun.luqing.dmws.service.dmw.economy;

import fun.luqing.dmws.entity.dmw.economy.EconomyFishSubmission;
import fun.luqing.dmws.entity.dmw.economy.EconomyFishSubmission.Status;
import fun.luqing.dmws.entity.dmw.fish.Fish;
import fun.luqing.dmws.repository.dmw.economy.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import static fun.luqing.dmws.service.dmw.economy.FishReviewModels.*;

@Service
@RequiredArgsConstructor
@Transactional(transactionManager = "dmwTransactionManager", isolation = Isolation.READ_COMMITTED)
public class EconomyFishReviewService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private final EconomyFishRepository fish;
    private final EconomyFishSubmissionRepository submissions;
    private final EconomyFishReviewLockRepository locks;
    private final EconomyGameTime time;
    private final EconomyFishingTextService texts;
    private final EconomyFishingBaitRuleRepository baitRules;
    private final EconomyFishingEventEditor eventEditor;
    private final EconomyFishingEventRepository eventDefinitions;

    public Receipt submit(Submit command) {
        if (command == null) throw invalid("请填写鱼种资料。");
        String request = uuid(command.requestId());
        String author = shortText(command.author(), "投稿人", 40, false);
        if (command.event() != null) return submitEvent(command, request, author);
        Draft draft = validate(command.fish());
        String hash = digest(List.of(author, draft));
        lock();
        var existing = submissions.findByRequestId(request);
        if (existing.isPresent()) {
            if (!hash.equals(existing.get().getPayloadHash())) throw conflict("提交内容与上次请求不同，请先查询上次提交进度。");
            return receipt(existing.get());
        }
        ensureNewName(draft.name());
        if (submissions.existsByNameIgnoreCaseAndStatus(draft.name(), Status.PENDING))
            throw conflict("已有同名鱼种正在审核，请等待审核结果。");
        var row = new EconomyFishSubmission();
        row.setRequestId(request); row.setPayloadHash(hash); row.setAuthor(author);
        row.setName(draft.name()); row.setDescription(draft.description()); row.setLevel(draft.level());
        row.setPrice(draft.price()); row.setDifficulty(draft.difficulty()); row.setDimensionsMin(draft.dimensionsMin());
        row.setDimensionsMax(draft.dimensionsMax()); row.setSpecial(draft.special()); row.setCreatedAt(time.now());
        return receipt(submissions.saveAndFlush(row));
    }

    private Receipt submitEvent(Submit command, String request, String author) {
        if (command.fish() != null) throw invalid("一次只能投稿鱼种或特殊事件中的一种。");
        var event = eventEditor.validate(command.event()); String hash = digest(List.of(author, event)); lock();
        var existing = submissions.findByRequestId(request);
        if (existing.isPresent()) {
            if (!hash.equals(existing.get().getPayloadHash())) throw conflict("提交内容与上次请求不同。");
            return receipt(existing.get());
        }
        if (eventDefinitions.existsById(event.code())) throw conflict("该事件编号已存在，现有事件请由管理员在管理页编辑。");
        if (submissions.findBySubmissionTypeAndStatus("EVENT", Status.PENDING).stream().anyMatch(s ->
                JSON.readValue(s.getEventDefinition(), FishingEventEditorModels.Event.class).code().equalsIgnoreCase(event.code()))) throw conflict("该事件编号已有待审核投稿。");
        var row = new EconomyFishSubmission(); row.setRequestId(request); row.setPayloadHash(hash); row.setAuthor(author);
        row.setSubmissionType("EVENT"); row.setEventDefinition(JSON.writeValueAsString(event)); row.setCreatedAt(time.now());
        copyDraft(row, eventMirror(event)); return receipt(submissions.saveAndFlush(row));
    }

    public Submission decide(long id, boolean approve, Decision command, String reviewer) {
        if (command == null) throw invalid("请填写审核意见。");
        String request = uuid(command.requestId());
        String note = command.note() == null ? "" : command.note().strip();
        if (note.length() > 500 || (!approve && note.isBlank())) throw invalid("驳回需填写原因，审核意见最多500字。");
        String hash = digest(Arrays.asList(id, approve, command.fish(), command.event(), note));
        lock();
        var previous = submissions.findByDecisionRequestId(request);
        if (previous.isPresent()) {
            if (previous.get().getId() != id || !hash.equals(previous.get().getDecisionHash())) throw conflict("审核请求编号已用于其他操作。");
            return view(previous.get());
        }
        var row = submissions.findById(id).orElseThrow(() -> notFound("投稿不存在。"));
        if (row.getStatus() != Status.PENDING) throw conflict("这条投稿已被审核，请刷新列表。");
        if (command.version() == null || !command.version().equals(row.getVersion())) throw conflict("投稿状态已变化，请刷新后再审核。");
        if (approve) {
            if (row.getSubmissionType().equals("EVENT")) {
                if (command.fish() != null) throw invalid("事件投稿不能作为普通鱼种通过。");
                var reviewed = eventEditor.validate(command.event());
                var original = JSON.readValue(row.getEventDefinition(), FishingEventEditorModels.Event.class);
                if (!original.code().equals(reviewed.code())) throw invalid("审核不能改变事件编号，请驳回后重新投稿。");
                eventEditor.publish(reviewed, false); row.setPublishedEventCode(reviewed.code());
                row.setReviewedDefinition(JSON.writeValueAsString(eventEditor.entry(reviewed.code()).event())); row.setReviewedEnabled(reviewed.enabled());
            } else {
                if (command.event() != null) throw invalid("鱼种投稿不能作为特殊事件通过。");
                Draft reviewed = validate(command.fish()); ensureNewName(reviewed.name());
                Fish published = toFish(reviewed); fish.saveAndFlush(published);
                row.setPublishedFishId(published.getId()); row.setReviewedDefinition(JSON.writeValueAsString(reviewed)); row.setReviewedEnabled(true);
            }
        }
        row.setStatus(approve ? Status.APPROVED : Status.REJECTED);
        row.setReviewer(shortText(reviewer, "审核员", 64, false)); row.setReviewNote(note);
        row.setReviewedAt(time.now()); row.setDecisionRequestId(request); row.setDecisionHash(hash);
        return view(submissions.saveAndFlush(row));
    }

    @Transactional(readOnly = true, transactionManager = "dmwTransactionManager")
    public List<FishEntry> managedFish() {
        return fish.findAllByOrderByLevelAscPriceDesc().stream().map(f ->
                new FishEntry(f.getId(), fromFish(f), f.isEnabled(), digest(List.of(fromFish(f), f.isEnabled())))).toList();
    }

    public Submission editFish(int id, FishEdit command, String reviewer) {
        if (command == null || command.enabled() == null) throw invalid("请填写鱼种及启用状态。");
        String request = uuid(command.requestId()), note = editNote(command.note());
        Draft reviewed = validate(command.fish(), Integer.MAX_VALUE, 255);
        String hash = digest(Arrays.asList(id, command.revision(), reviewed, command.enabled(), note)); lock();
        var repeat = submissions.findByRequestId(request);
        if (repeat.isPresent()) {
            if (!hash.equals(repeat.get().getPayloadHash())) throw conflict("请求编号已用于其他修改。"); return view(repeat.get());
        }
        Fish current = fish.findById(id).orElseThrow(() -> notFound("鱼种不存在。"));
        Draft original = fromFish(current);
        if (!digest(List.of(original, current.isEnabled())).equals(command.revision())) throw conflict("鱼种已经被修改，请刷新后再保存。");
        if (fish.existsByNameIgnoreCaseAndIdNot(reviewed.name(), id)) throw conflict("已有同名鱼种。");
        var row = audit(request, reviewer, note, hash); row.setSourceFishId(id); row.setPublishedFishId(id); copyDraft(row, original);
        row.setReviewedDefinition(JSON.writeValueAsString(reviewed)); row.setReviewedEnabled(command.enabled());
        current.setName(reviewed.name()); current.setDescription(reviewed.description()); current.setLevel(reviewed.level());
        current.setPrice(reviewed.price()); current.setDifficulty(reviewed.difficulty()); current.setDimensionsMin(reviewed.dimensionsMin());
        current.setDimensionsMax(reviewed.dimensionsMax()); current.setSpecial(reviewed.special()); current.setEnabled(command.enabled());
        fish.saveAndFlush(current); return view(submissions.saveAndFlush(row));
    }

    public Submission editEvent(String code, FishingEventEditorModels.Edit command, String reviewer) {
        if (command == null) throw invalid("请填写事件资料。");
        String request = uuid(command.requestId()), note = editNote(command.note());
        var reviewed = eventEditor.validate(command.event());
        if (!code.equals(reviewed.code())) throw invalid("已有事件编号不能更改。");
        String hash = digest(Arrays.asList(code, command.revision(), reviewed, note)); lock();
        var repeat = submissions.findByRequestId(request);
        if (repeat.isPresent()) {
            if (!hash.equals(repeat.get().getPayloadHash())) throw conflict("请求编号已用于其他修改。"); return view(repeat.get());
        }
        var current = eventEditor.entry(code);
        if (!current.revision().equals(command.revision())) throw conflict("事件已经被修改，请刷新后再保存。");
        var row = audit(request, reviewer, note, hash); row.setSubmissionType("EVENT"); row.setSourceEventCode(code);
        row.setPublishedEventCode(code); row.setEventDefinition(JSON.writeValueAsString(current.event())); copyDraft(row, eventMirror(current.event()));
        eventEditor.publish(reviewed, true);
        row.setReviewedDefinition(JSON.writeValueAsString(eventEditor.entry(code).event())); row.setReviewedEnabled(reviewed.enabled());
        return view(submissions.saveAndFlush(row));
    }

    private EconomyFishSubmission audit(String request, String reviewer, String note, String hash) {
        String author = shortText(reviewer, "审核员", 64, false);
        var row = new EconomyFishSubmission(); row.setRequestId(request); row.setPayloadHash(hash); row.setAuthor(author.substring(0, Math.min(40, author.length())));
        row.setReviewer(reviewer); row.setReviewNote(note); row.setCreatedAt(time.now()); row.setReviewedAt(time.now()); row.setStatus(Status.APPROVED);
        row.setDecisionRequestId(request); row.setDecisionHash(hash); return row;
    }
    private static String editNote(String value) { String text = value == null ? "" : value.strip(); if (text.length() > 500) throw invalid("修改说明最多500字。"); return text; }
    private static Draft eventMirror(FishingEventEditorModels.Event event) { return new Draft(event.name(), event.name(), 1, 1, 0, 1, 1, false); }
    private static void copyDraft(EconomyFishSubmission row, Draft draft) {
        row.setName(draft.name()); row.setDescription(draft.description()); row.setLevel(draft.level()); row.setPrice(draft.price());
        row.setDifficulty(draft.difficulty()); row.setDimensionsMin(draft.dimensionsMin()); row.setDimensionsMax(draft.dimensionsMax()); row.setSpecial(draft.special());
    }

    @Transactional(readOnly = true, transactionManager = "dmwTransactionManager")
    public Receipt progress(String requestId) {
        return submissions.findByRequestId(uuid(requestId)).map(this::receipt).orElseThrow(() -> notFound("未找到这条投稿，请检查查询码。"));
    }

    @Transactional(readOnly = true, transactionManager = "dmwTransactionManager")
    public FishReviewModels.Queue queue(String status, String query, int page) {
        Status filter;
        try { filter = status == null || status.equals("ALL") ? null : Status.valueOf(status); }
        catch (IllegalArgumentException bad) { throw invalid("审核状态无效。"); }
        var result = submissions.search(filter, search(query), PageRequest.of(page(page), 20,
                Sort.by(filter == Status.PENDING ? Sort.Direction.ASC : Sort.Direction.DESC, "createdAt", "id")));
        return new FishReviewModels.Queue(result.getContent().stream().map(this::view).toList(), page, Math.max(1, result.getTotalPages()),
                result.getTotalElements(), submissions.countByStatus(Status.PENDING), submissions.countByStatus(Status.APPROVED), submissions.countByStatus(Status.REJECTED));
    }

    @Transactional(readOnly = true, transactionManager = "dmwTransactionManager")
    public Catalog catalog(String query, int page) {
        String q = search(query).toLowerCase(Locale.ROOT);
        var all = fish.findByEnabledTrueOrderByLevelAscPriceDesc();
        var matching = all.stream().filter(f -> f.getName().toLowerCase(Locale.ROOT).contains(q)).toList();
        int start = (int) Math.min(matching.size(), (long) page(page) * 18);
        return new Catalog(matching.subList(start, Math.min(start + 18, matching.size())).stream()
                .map(f -> new CatalogFish(f.getId(), fromFish(f), EconomyFishingText.catalogDescription(f.getDescription(), f.getName()))).toList(), page, Math.max(1, (matching.size() + 17) / 18),
                matching.size(), all.stream().filter(f -> Boolean.TRUE.equals(f.getSpecial())).count(), all.size());
    }

    @Transactional(readOnly = true, transactionManager = "dmwTransactionManager")
    public Preview preview(Draft input, int rodLevel) {
        return previewDraft(validate(input), rodLevel);
    }
    @Transactional(readOnly = true, transactionManager = "dmwTransactionManager")
    public Preview previewExisting(Draft input, int rodLevel) { return previewDraft(validate(input, Integer.MAX_VALUE, 255), rodLevel); }
    private Preview previewDraft(Draft draft, int rodLevel) {
        if (rodLevel < 1 || rodLevel > 100) throw invalid("预览鱼竿等级应为1～100。");
        Fish specimen = toFish(draft);
        BigDecimal min = BigDecimal.valueOf(draft.dimensionsMin()), max = BigDecimal.valueOf(draft.dimensionsMax());
        BigDecimal size = min.add(max).divide(new BigDecimal("2"));
        BigDecimal reward = EconomyFishingRules.reward(draft.price(), size, draft.dimensionsMin(), draft.dimensionsMax(), rodLevel);
        var result = new EconomyFishingService.FishingResult(true, draft.name(), size, reward, BigDecimal.ZERO,
                BigDecimal.ZERO, rodLevel, false, draft.description(), EconomyFishingRules.rodMultiplier(rodLevel));
        String text = texts.format(result, "钓鱼人", EconomyFishingRandom.newSeed(),
                new EconomyFishingTextService.Narration(null, null, null, null, BigDecimal.ZERO, 0, false, null)).receiptText();
        var white = baitRules.findById(EconomyBaitQuality.WHITE).orElseGet(fun.luqing.dmws.entity.dmw.economy.EconomyFishingBaitRule::new);
        String chance = String.format(Locale.ROOT, "%.1f", EconomyFishingRules.successChance(specimen, rodLevel, white) * 100);
        return new Preview(text, reward.toPlainString(),
                EconomyFishingRules.reward(draft.price(), min, draft.dimensionsMin(), draft.dimensionsMax(), rodLevel).toPlainString(),
                EconomyFishingRules.reward(draft.price(), max, draft.dimensionsMin(), draft.dimensionsMax(), rodLevel).toPlainString(),
                chance, Math.max(1, draft.level() - 1), EconomyFishingRules.unlocked(specimen, rodLevel));
    }

    public static Draft validate(Draft input) {
        return validate(input, 1_000_000, 80);
    }
    private static Draft validate(Draft input, int maximum, int nameLength) {
        if (input == null) throw invalid("请填写鱼种资料。");
        String name = shortText(input.name(), "鱼种名称", nameLength, false);
        String description = shortText(input.description(), "鱼种描述", 255, true);
        range(input.level(), 1, maximum == Integer.MAX_VALUE ? maximum : 101, "鱼种等级"); range(input.price(), 1, maximum, "基础价格");
        range(input.difficulty(), 0, maximum == Integer.MAX_VALUE ? maximum : 1000, "钓获难度"); range(input.dimensionsMin(), 1, maximum, "最小尺寸");
        range(input.dimensionsMax(), 1, maximum, "最大尺寸");
        if (input.dimensionsMax() < input.dimensionsMin()) throw invalid("最大尺寸不能小于最小尺寸。");
        if (input.special() == null) throw invalid("请选择是否为彩蛋鱼种。");
        return new Draft(name, description, input.level(), input.price(), input.difficulty(), input.dimensionsMin(), input.dimensionsMax(), input.special());
    }

    private void lock() {
        locks.initialize(); locks.lockCatalog().orElseThrow(() -> new IllegalStateException("Missing fish review lock"));
    }
    private void ensureNewName(String name) {
        if (fish.existsByNameIgnoreCase(name)) throw conflict("鱼池中已有同名鱼种，请换一个名称。");
    }
    private static void range(Integer value, int minimum, int maximum, String label) {
        if (value == null || value < minimum || value > maximum) throw invalid(label + "应为" + minimum + "～" + maximum + "的整数。");
    }
    private static String shortText(String value, String label, int max, boolean multiline) {
        String text = value == null ? "" : value.strip();
        if (!multiline) text = Normalizer.normalize(text, Normalizer.Form.NFKC);
        if (text.isBlank() || text.length() > max || text.codePoints().anyMatch(c ->
                Character.getType(c) == Character.FORMAT || Character.isISOControl(c) && !(multiline && (c == '\n' || c == '\r' || c == '\t'))))
            throw invalid(label + "不能为空，最多" + max + "字，且不能含不可见控制字符。");
        return text;
    }
    private static String uuid(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) throw invalid("请求编号无效，请刷新页面重试。");
        return UUID.fromString(value).toString();
    }
    private static String search(String value) {
        String q = value == null ? "" : value.strip(); if (q.length() > 80) throw invalid("搜索内容最多80字。"); return q;
    }
    private static int page(int value) { if (value < 0 || value > 100000) throw invalid("页码无效。"); return value; }
    private static String digest(Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsString(value).getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static Fish toFish(Draft d) {
        var f = new Fish(); f.setName(d.name()); f.setDescription(d.description()); f.setLevel(d.level()); f.setPrice(d.price());
        f.setDifficulty(d.difficulty()); f.setDimensionsMin(d.dimensionsMin()); f.setDimensionsMax(d.dimensionsMax());
        f.setSpecial(d.special()); f.setEnabled(true); return f;
    }
    private static Draft fromFish(Fish f) { return new Draft(f.getName(), f.getDescription(), f.getLevel(), f.getPrice(), f.getDifficulty(), f.getDimensionsMin(), f.getDimensionsMax(), f.getSpecial()); }
    private Draft submitted(EconomyFishSubmission s) { return new Draft(s.getName(), s.getDescription(), s.getLevel(), s.getPrice(), s.getDifficulty(), s.getDimensionsMin(), s.getDimensionsMax(), s.isSpecial()); }
    private Submission view(EconomyFishSubmission s) {
        return new Submission(s.getId(), s.getVersion(), s.getRequestId(), s.getAuthor(), submitted(s), s.getStatus(),
                display(s.getCreatedAt()), display(s.getReviewedAt()), s.getReviewer(), s.getReviewNote(), s.getPublishedFishId(),
                s.getReviewedDefinition() == null || s.getSubmissionType().equals("EVENT") ? null : JSON.readValue(s.getReviewedDefinition(), Draft.class),
                s.getSubmissionType(), s.getEventDefinition() == null ? null : JSON.readValue(s.getEventDefinition(), FishingEventEditorModels.Event.class),
                s.getReviewedDefinition() == null || !s.getSubmissionType().equals("EVENT") ? null : JSON.readValue(s.getReviewedDefinition(), FishingEventEditorModels.Event.class),
                s.getPublishedEventCode(), s.getSourceFishId(), s.getSourceEventCode(), s.getReviewedEnabled());
    }
    private Receipt receipt(EconomyFishSubmission s) { return new Receipt(s.getRequestId(), s.getName(), s.getStatus(), display(s.getCreatedAt()), display(s.getReviewedAt()), s.getReviewNote(), s.getPublishedFishId(), s.getSubmissionType(), s.getPublishedEventCode()); }
    private static String display(LocalDateTime date) { return date == null ? null : DISPLAY_TIME.format(date); }
    private static FishReviewException invalid(String text) { return new FishReviewException("INVALID_INPUT", text, 400); }
    private static FishReviewException conflict(String text) { return new FishReviewException("CONFLICT", text, 409); }
    private static FishReviewException notFound(String text) { return new FishReviewException("NOT_FOUND", text, 404); }
}
