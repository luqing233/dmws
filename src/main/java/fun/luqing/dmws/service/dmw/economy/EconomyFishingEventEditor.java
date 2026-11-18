package fun.luqing.dmws.service.dmw.economy;

import fun.luqing.dmws.common.economy.EconomyMoney;
import fun.luqing.dmws.entity.dmw.economy.*;
import fun.luqing.dmws.repository.dmw.economy.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.ObjectMapper;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static fun.luqing.dmws.service.dmw.economy.FishingEventEditorModels.*;

/** Event definitions and owner-scoped texts; callers serialize writes with the catalog lock. */
@Service @RequiredArgsConstructor
public class EconomyFishingEventEditor {
    private static final ObjectMapper JSON = new ObjectMapper();
    public static final List<String> SCENES = List.of("ENCOUNTER", "DESCRIPTION", "MONEY_GAIN", "MONEY_LOSS", "MONEY_ZERO",
            "COMBAT_WIN", "COMBAT_LOSS", "INJURY", "JAIL", "DEBT", "BOOST_RED_BULL", "BOOST_BATON",
            "BAIT_PURCHASE", "BAIT_EMPTY_DEFAULT", "BAIT_EMPTY_OTHER", "RECEIPT", "COMBAT_FLOW", "INJURY_WIN", "INJURY_LOSS",
            "COMBAT_WIN_FLOW", "COMBAT_LOSS_FLOW", "BOOST_RED_BULL_WIN", "BOOST_RED_BULL_LOSS", "BOOST_BATON_WIN", "BOOST_BATON_LOSS");
    private final EconomyFishingEventRepository events;
    private final EconomyFishingTextRepository texts;
    private final EconomyFishingBaitRuleRepository rules;
    private final EconomyFishingTextService narration;

    @Transactional(readOnly = true, transactionManager = "dmwTransactionManager")
    public List<Entry> list() { return events.findAll().stream().sorted(Comparator.comparing(EconomyFishingEvent::getCode)).map(e -> entry(e.getCode())).toList(); }

    @Transactional(readOnly = true, transactionManager = "dmwTransactionManager")
    public Entry entry(String code) {
        var e = events.findById(code).orElseThrow(() -> new FishReviewException("NOT_FOUND", "事件不存在。", 404));
        Event value = new Event(e.getCode(), e.getName(), e.getKind(), e.getPoolCode(), e.getTickets(),
                decimal(e.getAmountMin()), decimal(e.getAmountMax()), decimal(e.getAmountStep()), e.getJailMinutes(),
                e.getStrengthMin(), e.getStrengthMax(), e.getWinInjuryPercent(), e.getLightPercent(), e.getMediumPercent(),
                e.isEnabled(), texts.findByOwnerCodeOrderByCodeAsc(e.getCode()).stream()
                .map(t -> new Text(t.getCode(), t.getScene(), t.getTemplate(), t.getWeight(), t.isEnabled())).toList());
        return new Entry(value, revision(value));
    }

    public Event validate(Event input) {
        if (input == null) throw bad("请填写特殊事件资料。");
        if (input.code() == null || !input.code().matches("[a-zA-Z0-9][a-zA-Z0-9_-]{0,39}")) throw bad("事件编号需为1～40位字母、数字、短横线或下划线。");
        if (input.name() == null || input.name().isBlank() || input.name().length() > 80 || input.name().codePoints().anyMatch(Character::isISOControl)) throw bad("事件名称需为1～80字，不能含控制字符。");
        if (input.kind() == null || !List.of("MONEY", "COMBAT", "JAIL").contains(input.kind())) throw bad("事件类型应为金币、战斗或监狱。");
        if (input.poolCode() == null || rules.findAll().stream().flatMap(r -> r.getPools().stream()).noneMatch(p -> p.getPoolCode().equals(input.poolCode()))) throw bad("请选择已经配置到鱼饵规则中的分组。");
        if (input.tickets() == null || input.tickets() < 0 || input.enabled() == null) throw bad("事件权重需为非负整数，并设置启用状态。");
        BigDecimal min = amount(input.amountMin()), max = amount(input.amountMax()), step = amount(input.amountStep());
        if (min.compareTo(max) > 0 || step.signum() <= 0) throw bad("金额上限不能小于下限，步长必须大于0。");
        try {
            if (max.subtract(min).divide(step, 0, RoundingMode.UNNECESSARY).compareTo(new BigDecimal("10000000")) > 0) throw bad("金额区间最多允许1000万个间隔。");
        } catch (ArithmeticException invalid) { throw bad("金额区间宽度必须能被步长整除。"); }
        if (input.kind().equals("COMBAT")) {
            if (min.signum() < 0) throw bad("战斗奖金不能为负数。");
            range(input.strengthMin(), 1, 100, "敌方武力下限"); range(input.strengthMax(), 1, 100, "敌方武力上限");
            if (input.strengthMax() < input.strengthMin()) throw bad("敌方武力上限不能小于下限。");
            range(input.winInjuryPercent(), 0, 100, "获胜受伤概率"); range(input.lightPercent(), 0, 100, "失败轻伤概率");
            range(input.mediumPercent(), 0, 100, "失败中伤概率");
            if (input.lightPercent() + input.mediumPercent() > 100) throw bad("轻伤与中伤概率之和不能超过100%。");
        }
        if (input.kind().equals("JAIL")) range(input.jailMinutes(), 1, 30, "调查/服刑分钟数");
        if (input.texts() == null || input.texts().size() > 60) throw bad("事件最多配置60条专属文案；未配置的分支沿用默认文案。");
        Set<String> ids = new HashSet<>(); Map<String, Long> weights = new HashMap<>(); List<Text> checked = new ArrayList<>();
        for (Text t : input.texts()) {
            if (t == null || t.scene() == null || !SCENES.contains(t.scene()) || t.template() == null || t.template().isBlank() || t.template().length() > 4000
                    || t.weight() == null || t.weight() < 0 || t.enabled() == null) throw bad("文案场景、内容、权重或启用状态无效。");
            if (t.code() != null && (!ids.add(t.code()) || t.code().length() > 120)) throw bad("文案编号重复或过长。");
            if (t.scene().equals("RECEIPT") && (t.template().lines().count() != 4
                    || !List.of("{encounter}", "{description}", "{outcome}", "{net}").stream().allMatch(t.template()::contains))) throw bad("回执布局必须正好四行，并保留开场、描述、结果与净收益占位符。");
            if (t.scene().startsWith("COMBAT_") && t.scene().endsWith("FLOW") && !EconomyFishingText.validCombatFlow(t.template()))
                throw bad("战斗顺序需且仅需各包含一次战斗结果、伤势、红牛和电棍片段；请使用分支树调整顺序。");
            if (t.enabled()) weights.merge(t.scene(), (long) t.weight(), Long::sum);
            checked.add(new Text(t.code(), t.scene(), t.template().strip(), t.weight(), t.enabled()));
        }
        if (weights.values().stream().anyMatch(w -> w <= 0)) throw bad("每个启用场景的总权重需大于0；不自定义时请停用该分支的全部专属文案。");
        if (!weights.containsKey("DESCRIPTION") && texts.findByOwnerCodeAndSceneAndEnabledTrueOrderByCodeAsc("*", "DESCRIPTION").stream().noneMatch(t -> t.getWeight() > 0))
            throw bad("默认描述尚未配置，请添加一条启用且权重大于0的专属描述。");
        return new Event(input.code(), input.name().strip(), input.kind(), input.poolCode(), input.tickets(), decimal(min), decimal(max), decimal(step),
                input.jailMinutes() == null ? 10 : input.jailMinutes(), input.strengthMin() == null ? 60 : input.strengthMin(),
                input.strengthMax() == null ? 100 : input.strengthMax(), input.winInjuryPercent() == null ? 20 : input.winInjuryPercent(),
                input.lightPercent() == null ? 50 : input.lightPercent(), input.mediumPercent() == null ? 35 : input.mediumPercent(), input.enabled(), List.copyOf(checked));
    }

    @Transactional(transactionManager = "dmwTransactionManager", propagation = Propagation.MANDATORY)
    public void publish(Event draft, boolean update) {
        Event value = validate(draft);
        var existing = events.findById(value.code());
        if (update != existing.isPresent()) throw new FishReviewException("CONFLICT", update ? "事件已不存在，请刷新。" : "这个事件编号已经存在，请换一个编号。", 409);
        if (existing.isPresent() && !existing.get().getCode().equals(value.code())) throw bad("不能修改已有事件编号的大小写。");
        validateBudget(value);
        var e = existing.orElseGet(EconomyFishingEvent::new);
        e.setCode(value.code()); e.setName(value.name()); e.setKind(value.kind()); e.setPoolCode(value.poolCode()); e.setTickets(value.tickets());
        e.setAmountMin(new BigDecimal(value.amountMin())); e.setAmountMax(new BigDecimal(value.amountMax())); e.setAmountStep(new BigDecimal(value.amountStep()));
        e.setJailMinutes(value.jailMinutes()); e.setStrengthMin(value.strengthMin()); e.setStrengthMax(value.strengthMax());
        e.setWinInjuryPercent(value.winInjuryPercent()); e.setLightPercent(value.lightPercent()); e.setMediumPercent(value.mediumPercent()); e.setEnabled(value.enabled());
        if (!update) e.setDescription(""); // No legacy definition exists; keep inheritance across startup seeding.
        events.save(e);
        var owned = new HashMap<String, EconomyFishingTextTemplate>();
        texts.findByOwnerCodeOrderByCodeAsc(value.code()).forEach(t -> owned.put(t.getCode(), t));
        // Retain disabled rows so startup seeding cannot resurrect intentionally removed phrases.
        owned.values().forEach(t -> t.setEnabled(false));
        int index = 0;
        for (Text t : value.texts()) {
            String code = t.code();
            if (code == null) code = "web." + value.code() + "." + (++index) + "." + UUID.randomUUID().toString().substring(0, 8);
            else if (!owned.containsKey(code)) throw bad("文案编号不属于本事件，不能覆盖其他事件或全局文案。");
            var row = owned.getOrDefault(code, new EconomyFishingTextTemplate());
            row.setCode(code); row.setOwnerCode(value.code()); row.setScene(t.scene()); row.setTemplate(t.template()); row.setWeight(t.weight()); row.setEnabled(t.enabled());
            texts.save(row);
        }
        texts.flush(); events.flush();
    }

    private void validateBudget(Event value) {
        for (var rule : rules.findAll()) {
            long total = 0;
            for (var pool : rule.getPools()) {
                if (pool.getTicketBudget() != null) total += pool.getTicketBudget();
                else {
                    for (var e : events.findByEnabledTrueOrderByCodeAsc())
                        if (!e.getCode().equalsIgnoreCase(value.code()) && e.getPoolCode().equals(pool.getPoolCode())) total += Math.round(e.getTickets() * (pool.getTicketScaleBps() / 10000.0));
                    if (value.enabled() && pool.getPoolCode().equals(value.poolCode())) total += Math.round(value.tickets() * (pool.getTicketScaleBps() / 10000.0));
                }
            }
            if (total > 10000) throw bad(rule.getQuality().label + "饵的事件总概率超过100%，请降低权重或调整分组。");
        }
    }

    @Transactional(readOnly = true, transactionManager = "dmwTransactionManager")
    public Preview preview(Event draft) {
        Event e = validate(draft); Map<String, String> result = new LinkedHashMap<>();
        if (e.kind().equals("MONEY")) {
            if (new BigDecimal(e.amountMax()).signum() > 0) result.put("获得金币", render(e, "MONEY_GAIN", e.amountMax(), "0.00", false));
            if (new BigDecimal(e.amountMin()).signum() < 0) result.put("损失金币", render(e, "MONEY_LOSS", "0.00", new BigDecimal(e.amountMin()).negate().toPlainString(), false));
            if (new BigDecimal(e.amountMin()).signum() <= 0 && new BigDecimal(e.amountMax()).signum() >= 0
                    && new BigDecimal(e.amountMin()).remainder(new BigDecimal(e.amountStep())).signum() == 0) result.put("无金币变化", render(e, "MONEY_ZERO", "0.00", "0.00", false));
        } else if (e.kind().equals("COMBAT")) {
            result.put("战斗获胜", render(e, "COMBAT_WIN", e.amountMax(), "0.00", false));
            result.put("战斗获胜并入院", render(e, "COMBAT_WIN", e.amountMax(), "0.00", true));
            result.put("战斗失败并入院", render(e, "COMBAT_LOSS", "0.00", "0.00", true));
            for (int boosts = 1; boosts <= 3; boosts++) {
                boolean redBull = (boosts & 1) != 0, baton = (boosts & 2) != 0;
                String label = redBull && baton ? "红牛＋电棍" : redBull ? "红牛" : "电棍";
                result.put("战斗获胜（" + label + "）", render(e, "COMBAT_WIN", e.amountMax(), "0.00", false, redBull, baton));
                result.put("战斗获胜并入院（" + label + "）", render(e, "COMBAT_WIN", e.amountMax(), "0.00", true, redBull, baton));
                result.put("战斗失败并入院（" + label + "）", render(e, "COMBAT_LOSS", "0.00", "0.00", true, redBull, baton));
            }
        } else result.put("进入调查/监狱", render(e, "JAIL", "0.00", "0.00", false));
        var sampleValues = previewValues(e, new BigDecimal(e.amountMax()).max(BigDecimal.ZERO).toPlainString(),
                new BigDecimal(e.amountMin()).min(BigDecimal.ZERO).negate().toPlainString(), e.kind().equals("COMBAT"), true);
        sampleValues.put("borrowed", "12.00"); sampleValues.put("bait_cost", "20.00");
        sampleValues.put("bait", "白色鱼饵"); sampleValues.put("bait_color", "白");
        Set<String> applicable = new LinkedHashSet<>(List.of("ENCOUNTER", "DESCRIPTION", "BAIT_PURCHASE", "BAIT_EMPTY_DEFAULT", "BAIT_EMPTY_OTHER"));
        applicable.addAll(switch (e.kind()) {
            case "COMBAT" -> List.of("COMBAT_WIN", "COMBAT_LOSS", "INJURY", "INJURY_WIN", "INJURY_LOSS", "BOOST_RED_BULL", "BOOST_BATON", "COMBAT_FLOW",
                    "COMBAT_WIN_FLOW", "COMBAT_LOSS_FLOW", "BOOST_RED_BULL_WIN", "BOOST_RED_BULL_LOSS", "BOOST_BATON_WIN", "BOOST_BATON_LOSS");
            case "MONEY" -> List.of("MONEY_GAIN", "MONEY_LOSS", "MONEY_ZERO", "DEBT");
            default -> List.of("JAIL");
        });
        Map<String, String> samples = new LinkedHashMap<>();
        for (String scene : applicable) if (!scene.endsWith("FLOW")) {
            var values = new HashMap<>(sampleValues);
            if (scene.equals("INJURY_WIN")) { values.put("injury", "轻伤"); values.put("injury_score", "1"); }
            samples.put(scene, line(e, scene, values));
        }
        if (e.kind().equals("COMBAT")) samples.put("COMBAT_FLOW", result.get("战斗获胜（红牛＋电棍）").lines().skip(2).findFirst().orElse(""));
        if (e.kind().equals("COMBAT")) {
            samples.put("COMBAT_WIN_FLOW", result.get("战斗获胜（红牛＋电棍）").lines().skip(2).findFirst().orElse(""));
            samples.put("COMBAT_LOSS_FLOW", result.get("战斗失败并入院（红牛＋电棍）").lines().skip(2).findFirst().orElse(""));
        }
        samples.put("RECEIPT", result.values().iterator().next());
        Map<String, List<Text>> fallbacks = new LinkedHashMap<>();
        applicable.add("RECEIPT");
        for (String scene : applicable) {
            String baseScene = EconomyFishingText.fallbackScene(scene);
            if (!scene.equals(baseScene)) {
                var inherited = e.texts().stream().filter(t -> t.scene().equals(baseScene) && t.enabled() && t.weight() > 0)
                        .map(t -> new Text(null, scene, t.template(), t.weight(), true)).toList();
                if (!inherited.isEmpty()) { fallbacks.put(scene, inherited); continue; }
            }
            var defaults = texts.findByOwnerCodeAndSceneAndEnabledTrueOrderByCodeAsc("*", scene);
            if (defaults.isEmpty() && !scene.equals(baseScene)) defaults = texts.findByOwnerCodeAndSceneAndEnabledTrueOrderByCodeAsc("*", baseScene);
            fallbacks.put(scene, defaults.stream().filter(t -> t.getWeight() > 0).map(t -> new Text(null, scene, t.getTemplate(), t.getWeight(), true)).toList());
        }
        return new Preview(result, samples, fallbacks);
    }
    private String render(Event event, String scene, String reward, String penalty, boolean injured) {
        return render(event, scene, reward, penalty, injured, false, false);
    }
    private String render(Event event, String scene, String reward, String penalty, boolean injured, boolean redBull, boolean baton) {
        var values = previewValues(event, reward, penalty, injured, baton);
        if (injured && scene.equals("COMBAT_WIN")) { values.put("injury", "轻伤"); values.put("injury_score", "1"); }
        values.put("encounter", line(event, "ENCOUNTER", values)); values.put("description", line(event, "DESCRIPTION", values));
        String outcome = line(event, scene, values) + (injured ? line(event, "INJURY", values) : "");
        if (event.kind().equals("COMBAT")) {
            boolean success = scene.equals("COMBAT_WIN");
            String layout = template(event, success ? "COMBAT_WIN_FLOW" : "COMBAT_LOSS_FLOW", Map.of());
            values.put("combat_result", line(event, scene, values));
            values.put("injury_notice", injured ? line(event, EconomyFishingText.combatPartScene(layout, "injury_notice", success), values) : "");
            values.put("red_bull", redBull ? " " + line(event, EconomyFishingText.combatPartScene(layout, "red_bull", success), values) : "");
            values.put("baton", baton ? " " + line(event, EconomyFishingText.combatPartScene(layout, "baton", success), values) : "");
            values.put("branch", "");
            outcome = EconomyFishingText.line(EconomyFishingText.render(layout, values));
        }
        values.put("outcome", outcome);
        return template(event, "RECEIPT", values);
    }
    private Map<String, String> previewValues(Event event, String reward, String penalty, boolean injured, boolean baton) {
        var values = new HashMap<String, String>(); BigDecimal change = new BigDecimal(reward).subtract(new BigDecimal(penalty));
        values.put("player", "钓鱼人"); values.put("fish", event.name()); values.put("event", event.name());
        values.put("reward", reward); values.put("penalty", penalty); values.put("amount", new BigDecimal(reward).signum() > 0 ? reward : penalty);
        values.put("change", (change.signum() > 0 ? "+" : "") + change.toPlainString()); values.put("net", values.get("change"));
        values.put("minutes", event.kind().equals("JAIL") ? String.valueOf(event.jailMinutes()) : "0"); values.put("injury", injured ? "中伤" : "");
        values.put("injury_score", injured ? "40" : "0"); values.put("description", event.name());
        for (String key : List.of("borrowed", "bait_cost")) values.put(key, "0.00");
        for (String key : List.of("size", "witness", "estimate", "baton_energy", "bait", "bait_color", "min_value", "max_value")) values.put(key, "—");
        if (baton) values.put("baton_energy", "80"); // Example: one full battery after the actual 20-point cost.
        return values;
    }
    private String line(Event event, String scene, Map<String, String> values) { return EconomyFishingText.line(template(event, scene, values)); }
    private String template(Event event, String scene, Map<String, String> values) {
        var dedicated = event.texts().stream().filter(t -> t.enabled() && t.weight() > 0 && t.scene().equals(scene)).findFirst();
        if (dedicated.isPresent()) return EconomyFishingText.render(dedicated.get().template(), values);
        String base = EconomyFishingText.fallbackScene(scene);
        if (!base.equals(scene)) {
            var inherited = event.texts().stream().filter(t -> t.enabled() && t.weight() > 0 && t.scene().equals(base)).findFirst();
            if (inherited.isPresent()) return EconomyFishingText.render(inherited.get().template(), values);
        }
        return narration.message(scene, values, EconomyFishingRandom.newSeed());
    }
    public static String revision(Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsString(value).getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static BigDecimal amount(String value) {
        try {
            if (value == null || !value.matches("-?[0-9]{1,17}(\\.[0-9]{1,2})?")) throw new IllegalArgumentException();
            BigDecimal number = new BigDecimal(value).setScale(2, RoundingMode.UNNECESSARY);
            if (number.abs().compareTo(EconomyMoney.MAX) > 0) throw new IllegalArgumentException(); return number;
        } catch (RuntimeException invalid) { throw bad("金额需使用最多两位小数的十进制数，不支持科学计数法。"); }
    }
    private static String decimal(BigDecimal value) { return value == null ? "0.00" : value.setScale(2, RoundingMode.UNNECESSARY).toPlainString(); }
    private static void range(Integer value, int min, int max, String label) { if (value == null || value < min || value > max) throw bad(label + "应为" + min + "～" + max + "的整数。"); }
    private static FishReviewException bad(String text) { return new FishReviewException("INVALID_EVENT", text, 400); }
}
