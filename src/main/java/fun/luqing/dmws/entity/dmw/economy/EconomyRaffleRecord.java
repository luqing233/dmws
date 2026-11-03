package fun.luqing.dmws.entity.dmw.economy;

import fun.luqing.dmws.enums.EconomyPrizeType;
import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(
        name = "economy_raffle_record",
        indexes = {
                @Index(name = "idx_economy_raffle_record_user", columnList = "user_id"),
                @Index(name = "idx_economy_raffle_record_operation", columnList = "operation_id")
        }
)
public class EconomyRaffleRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "operation_id", nullable = false, length = 80)
    private String operationId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "prize_code", nullable = false, length = 64)
    private String prizeCode;

    @Column(name = "prize_name", nullable = false, length = 80)
    private String prizeName;

    @Enumerated(EnumType.STRING)
    @Column(name = "prize_type", nullable = false, length = 16)
    private EconomyPrizeType prizeType;

    @Column(name = "reward_amount", precision = 19, scale = 2)
    private BigDecimal rewardAmount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
