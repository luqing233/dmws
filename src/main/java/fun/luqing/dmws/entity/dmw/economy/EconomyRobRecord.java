package fun.luqing.dmws.entity.dmw.economy;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(
        name = "economy_rob_record",
        indexes = {
                @Index(name = "idx_economy_rob_record_robber", columnList = "robber_id,created_at"),
                @Index(name = "idx_economy_rob_record_target", columnList = "target_id,created_at")
        }
)
public class EconomyRobRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "robber_id", nullable = false)
    private Long robberId;

    @Column(name = "target_id", nullable = false)
    private Long targetId;

    @Column(nullable = false)
    private Boolean success;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(length = 32)
    private String outcome;
    private Integer injury;
    @Column(name = "injured_user_id")
    private Long injuredUserId;
    @Column(name = "crime_after")
    private Integer crimeAfter;
    @Column(name = "operation_id", length = 80)
    private String operationId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
