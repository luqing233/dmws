package fun.luqing.dmws.entity.dmw.economy;

import fun.luqing.dmws.enums.EconomyAccountScope;
import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

@Data
@Entity
@Table(
        name = "economy_account",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_economy_account_scope",
                columnNames = {"account_key", "scope"}
        ),
        indexes = {
                @Index(name = "idx_economy_account_owner", columnList = "owner_id"),
                @Index(name = "idx_economy_account_scope", columnList = "scope")
        }
)
public class EconomyAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_key", nullable = false, length = 64)
    private String accountKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EconomyAccountScope scope;

    @Column(name = "owner_id")
    private Long ownerId;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    @Version
    @Column
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
