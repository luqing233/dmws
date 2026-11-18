package fun.luqing.dmws.entity.dmw.economy;
import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;
import java.math.BigDecimal;
@Data @Entity @Table(name="economy_fishing_attempt",
        indexes = @Index(name = "idx_fishing_attempt_group_time", columnList = "group_id,occurred_at"))
public class EconomyFishingAttempt {
    @Id @Column(length=160) private String operationId;
    private Long userId;
    private Long groupId;
    private String eventCode;
    @Column(length=16) private String baitQuality;
    private LocalDateTime occurredAt;
    // Nullable snapshots: legacy records must never acquire inferred probabilities.
    private Integer rodLevel;
    private Integer fishId;
    private Integer fishLevel;
    private Integer fishDifficulty;
    private Double baseChance;
    private Double rodChanceBonus;
    private Double baitChanceBonus;
    private Double successChance;
    @Column(precision=19,scale=2) private BigDecimal reward;
    @Column(precision=19,scale=2) private BigDecimal penalty;
    private int injury;
    private boolean redBullUsed;
    private Integer batonEnergy;
    @Column(columnDefinition="text") private String receipt;
}
