package fun.luqing.dmws.entity.dmw.economy;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;

@Data
@Entity
@Table(name = "economy_market_receipt")
public class EconomyMarketReceipt {
    @Id @Column(length = 96)
    private String operationId;
    @Column(nullable = false)
    private Long userId;
    @Column(nullable = false, length = 16)
    private String action;
    @Column(nullable = false, length = 64)
    private String requestFingerprint;
    private Long positionId;
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal debt;
    @Column(nullable = false, length = 1000)
    private String displayText;
}
