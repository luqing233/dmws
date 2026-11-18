package fun.luqing.dmws.service.dmw.economy;

import java.util.List;
import java.util.Map;

public final class FishingEventEditorModels {
    private FishingEventEditorModels() { }
    public record Text(String code, String scene, String template, Integer weight, Boolean enabled) { }
    public record Event(String code, String name, String kind, String poolCode, Integer tickets,
                        String amountMin, String amountMax, String amountStep, Integer jailMinutes,
                        Integer strengthMin, Integer strengthMax, Integer winInjuryPercent,
                        Integer lightPercent, Integer mediumPercent, Boolean enabled, List<Text> texts) { }
    public record Entry(Event event, String revision) { }
    public record Edit(String requestId, String revision, Event event, String note) { }
    public record Preview(Map<String, String> receipts, Map<String, String> samples, Map<String, List<Text>> fallbacks) { }
}
