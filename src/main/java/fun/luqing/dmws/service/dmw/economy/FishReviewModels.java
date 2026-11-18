package fun.luqing.dmws.service.dmw.economy;

import fun.luqing.dmws.entity.dmw.economy.EconomyFishSubmission.Status;
import java.util.List;

public final class FishReviewModels {
    private FishReviewModels() { }
    public record Draft(String name, String description, Integer level, Integer price, Integer difficulty,
                        Integer dimensionsMin, Integer dimensionsMax, Boolean special) { }
    public record Submit(String requestId, String author, Draft fish, FishingEventEditorModels.Event event) {
        public Submit(String requestId, String author, Draft fish) { this(requestId, author, fish, null); }
    }
    public record Decision(String requestId, Long version, Draft fish, String note, FishingEventEditorModels.Event event) {
        public Decision(String requestId, Long version, Draft fish, String note) { this(requestId, version, fish, note, null); }
    }
    public record Submission(long id, long version, String requestId, String author, Draft fish, Status status,
                             String createdAt, String reviewedAt, String reviewer, String note,
                             Integer publishedFishId, Draft reviewedFish, String type, FishingEventEditorModels.Event event,
                             FishingEventEditorModels.Event reviewedEvent, String publishedEventCode, Integer sourceFishId,
                             String sourceEventCode, Boolean reviewedEnabled) { }
    public record Receipt(String requestId, String name, Status status, String createdAt, String reviewedAt,
                          String note, Integer publishedFishId, String type, String publishedEventCode) { }
    public record FishEntry(int id, Draft fish, boolean enabled, String revision) { }
    public record FishEdit(String requestId, String revision, Draft fish, Boolean enabled, String note) { }
    public record CatalogFish(int id, Draft fish, String displayDescription) { }
    public record Catalog(List<CatalogFish> items, int page, int pages, long total, long specialCount, long allCount) { }
    public record Queue(List<Submission> items, int page, int pages, long total, long pending, long approved, long rejected) { }
    public record Preview(String receipt, String reward, String minimumReward, String maximumReward,
                          String successPercent, int requiredRodLevel, boolean unlocked) { }
}
