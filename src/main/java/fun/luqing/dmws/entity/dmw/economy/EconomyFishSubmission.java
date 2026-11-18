package fun.luqing.dmws.entity.dmw.economy;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "economy_fish_submission", indexes = {
        @Index(name = "idx_fish_submission_status", columnList = "status,created_at,id")})
public class EconomyFishSubmission {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Version private Long version;
    @Column(nullable = false, unique = true, length = 36) private String requestId;
    @Column(nullable = false, length = 64) private String payloadHash;
    @Column(nullable = false, length = 40) private String author;
    @Column(nullable = false, length = 16, columnDefinition = "varchar(16) default 'FISH'") private String submissionType = "FISH";
    @Column(columnDefinition = "text") private String eventDefinition;
    private Integer sourceFishId;
    @Column(length = 40) private String sourceEventCode;
    private Boolean reviewedEnabled;
    @Column(length = 40) private String publishedEventCode;
    @Column(nullable = false, length = 255) private String name;
    @Column(nullable = false, length = 255) private String description;
    @Column(nullable = false) private int level;
    @Column(nullable = false) private int price;
    @Column(nullable = false) private int difficulty;
    @Column(nullable = false) private int dimensionsMin;
    @Column(nullable = false) private int dimensionsMax;
    @Column(nullable = false) private boolean special;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 16) private Status status = Status.PENDING;
    @Column(nullable = false) private LocalDateTime createdAt;
    private LocalDateTime reviewedAt;
    @Column(length = 64) private String reviewer;
    @Column(length = 500) private String reviewNote;
    private Integer publishedFishId;
    @Column(columnDefinition = "text") private String reviewedDefinition;
    @Column(unique = true, length = 36) private String decisionRequestId;
    @Column(length = 64) private String decisionHash;

    public enum Status { PENDING, APPROVED, REJECTED }
}
