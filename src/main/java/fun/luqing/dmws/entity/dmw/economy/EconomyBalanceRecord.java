package fun.luqing.dmws.entity.dmw.economy;

import fun.luqing.dmws.enums.AccountContext;
import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "economy_balance_record",
        uniqueConstraints = @UniqueConstraint(columnNames = {"context", "user_id"})
)
@Data
public class EconomyBalanceRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long recordId; // 单字段主键

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountContext context;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, precision = 16, scale = 2)
    private BigDecimal balance = BigDecimal.ZERO.setScale(2, RoundingMode.DOWN);


    @Column(nullable = false)
    private LocalDateTime lastUpdate;
}

