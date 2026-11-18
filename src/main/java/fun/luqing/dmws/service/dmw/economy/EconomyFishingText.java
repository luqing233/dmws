package fun.luqing.dmws.service.dmw.economy;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Literal, single-pass substitution. Dynamic values never become new template expressions. */
public final class EconomyFishingText {
    public static final java.util.List<String> COMBAT_PARTS = java.util.List.of("combat_result", "injury_notice", "red_bull", "baton");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z][a-z_0-9]*)}");
    private EconomyFishingText() { }

    public static String receipt(EconomyFishingService.FishingResult result) {
        return Objects.requireNonNull(result.receiptText(), "Fishing receipt has not been rendered");
    }

    static String line(String text) {
        if (text == null) return "";
        return text.replaceAll("[\\r\\n]+", " ")
                .replaceAll("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\uFE0F]", "").trim();
    }

    public static String render(String template, Map<String, String> values) {
        if (template == null) return "";
        return PLACEHOLDER.matcher(template).replaceAll(match ->
                Matcher.quoteReplacement(values.getOrDefault(match.group(1), match.group())));
    }

    public static boolean validCombatFlow(String template) {
        if (template == null) return false;
        String remainder = template;
        for (String part : COMBAT_PARTS) {
            String token = "{" + part + "}";
            if (template.indexOf(token) < 0 || template.indexOf(token) != template.lastIndexOf(token)) return false;
            remainder = remainder.replace(token, "");
        }
        if (template.indexOf("{branch}") != template.lastIndexOf("{branch}")) return false;
        return remainder.replace("{branch}", "").isBlank();
    }

    public static String fallbackScene(String scene) {
        return switch (scene) {
            case "INJURY_WIN", "INJURY_LOSS" -> "INJURY";
            case "BOOST_RED_BULL_WIN", "BOOST_RED_BULL_LOSS" -> "BOOST_RED_BULL";
            case "BOOST_BATON_WIN", "BOOST_BATON_LOSS" -> "BOOST_BATON";
            case "COMBAT_WIN_FLOW", "COMBAT_LOSS_FLOW" -> "COMBAT_FLOW";
            default -> scene;
        };
    }

    public static String combatPartScene(String layout, String part, boolean success) {
        String scene = switch (part) { case "red_bull" -> "BOOST_RED_BULL"; case "baton" -> "BOOST_BATON"; default -> "INJURY"; };
        return layout.indexOf("{" + part + "}") > layout.indexOf("{combat_result}")
                ? scene + (success ? "_WIN" : "_LOSS") : scene;
    }

    static String catalogDescription(String template, String fish) {
        var values = new java.util.HashMap<String, String>();
        for (String key : ("reward penalty amount change bait_cost net size minutes injury injury_score borrowed "
                + "baton_energy bait bait_color witness estimate min_value max_value description encounter outcome reason").split(" "))
            values.put(key, "—");
        values.put("player", "钓鱼人"); values.put("fish", line(fish)); values.put("event", line(fish));
        return line(render(template, values));
    }
}
