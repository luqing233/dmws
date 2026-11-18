package fun.luqing.dmws.entity.dmw.economy;

import fun.luqing.dmws.service.dmw.economy.EconomyBaitQuality;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "economy_fishing_cast", indexes = {
        @Index(name = "idx_fishing_cast_player", columnList = "user_id,cast_at"),
        @Index(name = "idx_fishing_cast_active", columnList = "user_id,status"),
        @Index(name = "idx_fishing_cast_due", columnList = "status,next_attempt_at"),
        @Index(name = "idx_fishing_cast_notice", columnList = "status,bot_id,due_at")
})
public class EconomyFishingCast {
    @Id @Column(length = 160)
    private String operationId;
    @Column(nullable = false) private long userId;
    private String nickname;
    @Column(nullable = false) private long botId;
    private Long groupId;
    private String groupName;
    private Integer messageId;
    @Column(nullable = false) private int rodLevel;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(nullable = false, length = 16)
    private EconomyBaitQuality baitQuality;
    @Column(nullable = false) private boolean defaultBait;
    @Column(nullable = false) private LocalDateTime castAt;
    @Column(nullable = false) private LocalDateTime dueAt;
    @Column(nullable = false) private LocalDateTime nextAttemptAt;
    @Column(nullable = false) private int failures;
    @lombok.ToString.Exclude
    @Column(length = 64) private String randomSeed;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(nullable = false, length = 16)
    private Status status = Status.WAITING;
    @Column(columnDefinition = "text") private String receipt;
    @Column(columnDefinition = "text") private String castReceipt;

    public enum Status { WAITING, READY, SENDING, SENT, FAILED, UNKNOWN }
}
