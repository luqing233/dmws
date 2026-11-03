package fun.luqing.dmws.entity.dmw.economy;

import fun.luqing.dmws.enums.EconomyAccountScope;
import fun.luqing.dmws.enums.EconomyLedgerReason;
import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(
        name = "economy_ledger_entry",
        indexes = {
                @Index(name = "idx_economy_ledger_operation", columnList = "operation_id"),
                @Index(name = "idx_economy_ledger_account", columnList = "account_key,scope"),
                @Index(name = "idx_economy_ledger_created", columnList = "created_at")
        }
)
public class EconomyLedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "operation_id", nullable = false, length = 80)
    private String operationId;

    @Column(name = "account_key", nullable = false, length = 64)
    private String accountKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EconomyAccountScope scope;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal delta;

    @Column(name = "balance_after", nullable = false, precision = 19, scale = 2)
    private BigDecimal balanceAfter;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private EconomyLedgerReason reason;

    @Column(name = "actor_id")
    private Long actorId;

    @Column(length = 200)
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
