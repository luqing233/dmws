package fun.luqing.dmws.config;

import fun.luqing.dmws.entity.dmw.economy.*;
import fun.luqing.dmws.repository.dmw.economy.*;
import fun.luqing.dmws.service.dmw.economy.EconomyBaitQuality;
import fun.luqing.dmws.service.dmw.economy.EconomyFishingEventMigration;
import lombok.RequiredArgsConstructor;
import org.json.JSONArray;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix="dmws.economy", name="enabled", havingValue="true", matchIfMissing=true)
public class EconomyFishingEventInitializer implements ApplicationRunner {
    private final EconomyFishingEventRepository repository;
    private final EconomyFishingBaitRuleRepository baitRules;
    private final EconomyFishingTextRepository texts;

    @Override
    @Transactional(transactionManager="dmwTransactionManager")
    public void run(ApplicationArguments args) throws Exception {
        var entries = resource("fishing-events.json");
        for (int i = 0; i < entries.length(); i++) {
            var row = entries.getJSONObject(i);
            if (repository.existsById(row.getString("code"))) continue;
            var event = new EconomyFishingEvent();
            event.setCode(row.getString("code")); event.setName(row.getString("name"));
            event.setDescription(row.getString("description")); event.setKind(row.getString("kind"));
            event.setPoolCode(row.getString("pool_code"));
            event.setEncounterTemplate(row.optString("encounter_template", null));
            event.setTickets(row.getInt("tickets"));
            event.setAmountMin(new BigDecimal(row.get("amount_min").toString()));
            event.setAmountMax(new BigDecimal(row.get("amount_max").toString()));
            event.setAmountStep(new BigDecimal(row.get("amount_step").toString()));
            event.setJailMinutes(row.optInt("jail_minutes", event.getJailMinutes()));
            event.setStrengthMin(row.optInt("strength_min", event.getStrengthMin()));
            event.setStrengthMax(row.optInt("strength_max", event.getStrengthMax()));
            event.setWinInjuryPercent(row.optInt("win_injury_percent", event.getWinInjuryPercent()));
            event.setLightPercent(row.optInt("light_percent", event.getLightPercent()));
            event.setMediumPercent(row.optInt("medium_percent", event.getMediumPercent()));
            repository.save(event);
        }
        for (var event : repository.findAll()) {
            seedEventText(event, "ENCOUNTER", event.getEncounterTemplate());
            seedEventText(event, "DESCRIPTION", event.getDescription());
            EconomyFishingEventMigration.normalize(event);
            if (event.getPoolCode() == null) event.setPoolCode(poolForLegacyKind(event.getLegacyKind() == null
                    ? event.getKind() : event.getLegacyKind()));
        }
        var defaults = resource("fishing-texts.json");
        for (int i = 0; i < defaults.length(); i++) {
            var row = defaults.getJSONObject(i);
            if (texts.existsById(row.getString("code"))) continue;
            var text = new EconomyFishingTextTemplate();
            text.setCode(row.getString("code")); text.setOwnerCode(row.getString("owner_code"));
            text.setScene(row.getString("scene")); text.setTemplate(row.getString("template"));
            text.setWeight(row.getInt("weight")); texts.save(text);
        }
        var rules = resource("fishing-bait-rules.json");
        for (int i = 0; i < rules.length(); i++) {
            var row = rules.getJSONObject(i);
            var quality = EconomyBaitQuality.valueOf(row.getString("quality"));
            if (baitRules.existsById(quality)) continue;
            var rule = new EconomyFishingBaitRule();
            rule.setQuality(quality);
            rule.setSuccessBonusBps(row.getInt("success_bonus_bps"));
            rule.setHighFishWeightBps(row.getInt("high_fish_weight_bps"));
            rule.setSpecialMultiplierBps(row.getInt("special_multiplier_bps"));
            rule.setSpecialCapBps(row.getInt("special_cap_bps"));
            var pools = row.getJSONArray("pools");
            for (int j = 0; j < pools.length(); j++) {
                var pool = pools.getJSONObject(j);
                var entry = new EconomyFishingBaitRule.PoolRule();
                entry.setPoolCode(pool.getString("code"));
                entry.setTicketBudget(pool.has("budget") ? pool.getInt("budget") : null);
                entry.setTicketScaleBps(pool.optInt("scale_bps", 10000));
                rule.getPools().add(entry);
            }
            baitRules.save(rule);
        }
    }

    private void seedEventText(EconomyFishingEvent event, String scene, String template) {
        if (template == null || template.isBlank() || texts.existsByOwnerCodeAndScene(event.getCode(), scene)) return;
        var text = new EconomyFishingTextTemplate();
        text.setCode("migrated." + event.getCode() + "." + scene.toLowerCase(java.util.Locale.ROOT));
        text.setOwnerCode(event.getCode()); text.setScene(scene); text.setTemplate(template);
        texts.save(text);
    }

    private JSONArray resource(String name) throws Exception {
        try (var in = new ClassPathResource("economy/" + name).getInputStream()) {
            return new JSONArray(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private String poolForLegacyKind(String kind) {
        return switch (kind) {
            case "FINE", "JAIL" -> "NEGATIVE";
            case "CROCODILE", "COMBAT" -> "COMBAT";
            case "TEXT" -> "FUN";
            default -> kind;
        };
    }
}
