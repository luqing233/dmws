package fun.luqing.dmws.entity.dmw.economy;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

@Data
@Entity
@Table(
        name = "economy_private_bank_deposit",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_economy_private_bank_deposit",
                columnNames = {"bank_id", "user_id"}
        ),
        indexes = @Index(name = "idx_economy_private_bank_deposit_user", columnList = "user_id")
)
public class EconomyPrivateBankDeposit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "bank_id", nullable = false)
    private Long bankId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal principal = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();
}
