package fun.luqing.dmws.service.dmw.economy;

import fun.luqing.dmws.common.economy.EconomyException;
import fun.luqing.dmws.common.economy.EconomyMoney;
import fun.luqing.dmws.entity.dmw.economy.EconomyFishingEvent;
import fun.luqing.dmws.repository.dmw.economy.EconomyFishingTextRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, transactionManager = "dmwTransactionManager")
public class EconomyFishingTextService {
    private final EconomyFishingTextRepository repository;

    public String message(String scene, Map<String, String> values, String seed) {
        return render("*", scene, values, seed);
    }

    public String render(String owner, String scene, Map<String, String> values, String seed) {
        var choices = repository.findByOwnerCodeAndSceneAndEnabledTrueOrderByCodeAsc(owner, scene);
        String fallback = EconomyFishingText.fallbackScene(scene);
        if (choices.isEmpty() && !fallback.equals(scene))
            choices = repository.findByOwnerCodeAndSceneAndEnabledTrueOrderByCodeAsc(owner, fallback);
        if (choices.isEmpty() && !owner.equals("*"))
            choices = repository.findByOwnerCodeAndSceneAndEnabledTrueOrderByCodeAsc("*", scene);
        if (choices.isEmpty() && !fallback.equals(scene))
            choices = repository.findByOwnerCodeAndSceneAndEnabledTrueOrderByCodeAsc("*", fallback);
        long total = 0;
        for (var choice : choices) {
            if (choice.getWeight() < 0 || choice.getTemplate() == null || choice.getTemplate().isBlank()
                    || choice.getTemplate().length() > 4000)
                throw invalid(scene);
            total += choice.getWeight();
        }
        if (total <= 0) throw invalid(scene);
        // Narration draws are independent of all gameplay draws and of other scenes.
        long ticket = draw(seed, owner, scene, total);
        for (var choice : choices) {
            ticket -= choice.getWeight();
            if (ticket < 0) {
                if (scene.equals("RECEIPT") && !java.util.List.of("{encounter}", "{description}", "{outcome}", "{net}")
                        .stream().allMatch(choice.getTemplate()::contains)) throw invalid(scene);
                if (scene.startsWith("COMBAT_") && scene.endsWith("FLOW") && !EconomyFishingText.validCombatFlow(choice.getTemplate())) throw invalid(scene);
                return EconomyFishingText.render(choice.getTemplate(), values);
            }
        }
        throw invalid(scene);
    }

    private long draw(String seed, String owner, String scene, long bound) {
        try {
            String key = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((seed + "\n" + owner + "\n" + scene).getBytes(StandardCharsets.UTF_8)));
            return EconomyFishingRandom.withSeed(key, () -> new EconomyFishingRandom().weight(bound));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private EconomyException invalid(String scene) {
        // Do not recurse if the error template itself is missing or malformed.
        String message = "钓鱼文案配置无效，请联系管理员。";
        if (!scene.equals("INVALID_TEXT")) {
            try { message = message("INVALID_TEXT", Map.of("scene", scene), EconomyFishingRandom.newSeed()); }
            catch (EconomyException ignored) { /* Keep a readable last-resort receipt for broken configurations. */ }
        }
        return new EconomyException("INVALID_FISHING_TEXT", message);
    }

    public EconomyFishingService.FishingResult format(EconomyFishingService.FishingResult result,
                                                       String player, String seed, Narration details) {
        String name = EconomyFishingText.line(player);
        if (name.isBlank() || name.matches("[0-9]+")) name = "这位玩家";
        Map<String, String> values = new HashMap<>();
        values.put("player", name);
        values.put("fish", EconomyFishingText.line(result.fishName()));
        values.put("event", EconomyFishingText.line(result.fishName()));
        values.put("reward", EconomyMoney.format(result.reward()));
        values.put("penalty", EconomyMoney.format(result.penalty()));
        values.put("amount", EconomyMoney.format(result.reward().signum() > 0 ? result.reward() : result.penalty()));
        values.put("change", signed(result.reward().subtract(result.penalty())));
        values.put("net", signed(result.netProfit()));
        values.put("bait_cost", EconomyMoney.format(result.cost()));
        values.put("size", result.size().signum() > 0 ? EconomyMoney.format(result.size()) : "—");
        values.put("minutes", details.event() == null || !"JAIL".equals(details.event().getKind())
                ? "0" : String.valueOf(details.event().getJailMinutes()));
        values.put("injury", details.injury() == 0 ? "" : EconomyGameText.injury(details.injury()));
        values.put("injury_score", String.valueOf(details.injury()));
        values.put("borrowed", EconomyMoney.format(details.borrowed()));
        values.put("witness", EconomyFishingText.line(details.witness()));
        values.put("estimate", details.estimate() == null ? "—" : EconomyMoney.format(details.estimate()));
        values.put("baton_energy", details.batonEnergy() == null ? "—" : String.valueOf(details.batonEnergy()));
        values.put("bait", details.bait() == null ? "" : details.bait().quality().displayName());
        values.put("bait_color", details.bait() == null ? "" : details.bait().quality().label);
        values.put("min_value", result.estimatedValueMin() == null ? "—" : EconomyMoney.format(result.estimatedValueMin()));
        values.put("max_value", result.estimatedValueMax() == null ? "—" : EconomyMoney.format(result.estimatedValueMax()));
        String owner = details.event() == null ? "*" : details.event().getCode();
        String encounterScene = details.event() != null ? "ENCOUNTER" : result.success() ? "CATCH_ENCOUNTER" : "ESCAPE_ENCOUNTER";
        String encounter = line(owner, encounterScene, values, seed);
        String sourceDescription = result.fishDescription();
        if ((sourceDescription == null || sourceDescription.isBlank()) && details.event() != null) sourceDescription = details.event().getName();
        values.put("description", EconomyFishingText.line(EconomyFishingText.render(sourceDescription, values)));
        String description = line(owner, "DESCRIPTION", values, seed);
        String outcome = line(owner, outcomeScene(result, details), values, seed);
        boolean combat = details.event() != null && "COMBAT".equals(details.event().getKind());
        if (combat) {
            String layout = render(owner, result.success() ? "COMBAT_WIN_FLOW" : "COMBAT_LOSS_FLOW", Map.of(), seed);
            values.put("combat_result", outcome);
            values.put("injury_notice", details.injury() > 0 ? line(owner, EconomyFishingText.combatPartScene(layout, "injury_notice", result.success()), values, seed) : "");
            values.put("red_bull", details.redBull() ? " " + line(owner, EconomyFishingText.combatPartScene(layout, "red_bull", result.success()), values, seed) : "");
            values.put("baton", details.batonEnergy() != null ? " " + line(owner, EconomyFishingText.combatPartScene(layout, "baton", result.success()), values, seed) : "");
            values.put("branch", "");
            outcome = EconomyFishingText.line(EconomyFishingText.render(layout, values));
        }
        if (details.borrowed().signum() > 0) outcome += line(owner, "DEBT", values, seed);
        if (!combat) {
            if (details.injury() > 0) outcome += line(owner, "INJURY", values, seed);
            if (details.redBull()) outcome += " " + line(owner, "BOOST_RED_BULL", values, seed);
            if (details.batonEnergy() != null) outcome += " " + line(owner, "BOOST_BATON", values, seed);
        }
        String baitNotice = "";
        if (result.cost().signum() > 0) baitNotice += line(owner, "BAIT_PURCHASE", values, seed);
        if (details.bait() != null && details.bait().exhausted())
            baitNotice += line(owner, details.bait().defaultQuality() ? "BAIT_EMPTY_DEFAULT" : "BAIT_EMPTY_OTHER", values, seed);
        if (!baitNotice.isBlank()) outcome += " " + baitNotice;
        values.put("encounter", encounter); values.put("description", description);
        values.put("outcome", outcome);
        String receipt = render(owner, "RECEIPT", values, seed);
        if (receipt.lines().count() != 4 || !receipt.contains(values.get("net"))) throw invalid("RECEIPT");
        return result.withReceipt(description, encounter, outcome, baitNotice, receipt);
    }

    private String line(String owner, String scene, Map<String, String> values, String seed) {
        return EconomyFishingText.line(render(owner, scene, values, seed));
    }

    private String outcomeScene(EconomyFishingService.FishingResult result, Narration details) {
        if (details.event() == null) return result.success() ? "CATCH_RESULT"
                : details.witness() == null || details.witness().isBlank() ? "ESCAPE_ALONE" : "ESCAPE_ESTIMATE";
        return switch (details.event().getKind()) {
            case "MONEY" -> result.reward().signum() > 0 ? "MONEY_GAIN"
                    : result.penalty().signum() > 0 ? "MONEY_LOSS" : "MONEY_ZERO";
            case "COMBAT" -> result.success() ? "COMBAT_WIN" : "COMBAT_LOSS";
            case "JAIL" -> "JAIL";
            default -> throw invalid("EVENT_KIND");
        };
    }

    private String signed(BigDecimal amount) {
        return (amount.signum() > 0 ? "+" : "") + EconomyMoney.format(amount);
    }

    public record Narration(EconomyFishingEvent event, EconomyFishingBaitService.Use bait, String witness,
                            BigDecimal estimate, BigDecimal borrowed, int injury, boolean redBull, Integer batonEnergy) { }
}
