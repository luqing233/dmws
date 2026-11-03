package fun.luqing.dmws.entity.dmw.economy;

import fun.luqing.dmws.enums.EconomyLoanStatus;
import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(
        name = "economy_private_bank_loan",
        indexes = {
                @Index(name = "idx_economy_private_bank_loan_bank", columnList = "bank_id,status"),
                @Index(name = "idx_economy_private_bank_loan_borrower", columnList = "borrower_id,status"),
                @Index(name = "idx_economy_private_bank_loan_due", columnList = "status,due_at")
        }
)
public class EconomyPrivateBankLoan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "bank_id", nullable = false)
    private Long bankId;

    @Column(name = "borrower_id", nullable = false)
    private Long borrowerId;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal principal;

    @Column(name = "total_due", nullable = false, precision = 19, scale = 2)
    private BigDecimal totalDue;

    @Column(name = "remaining_due", nullable = false, precision = 19, scale = 2)
    private BigDecimal remainingDue;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private EconomyLoanStatus status = EconomyLoanStatus.ACTIVE;

    @Column(name = "due_at", nullable = false)
    private LocalDateTime dueAt;

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
