package fun.luqing.dmws.entity.dmw.economy;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;

@Data
@Entity
@Table(name = "economy_market_state")
public class EconomyMarketState {
    @Id
    private Integer id = 1;
    @Column(name = "tick_sequence", nullable = false)
    private long sequence;
    /** Null uses configuration until the first administrator override. */
    private Boolean tradingEnabled;
    /** Server-only seed, generated once before the first tick and never included in views/logs. */
    @lombok.ToString.Exclude
    @lombok.EqualsAndHashCode.Exclude
    @com.fasterxml.jackson.annotation.JsonIgnore
    @Column(nullable = false, length = 64)
    private String entropyKey;
    /** Actual coin issuance net of collected losses/fees, excludes unpaid debt. */
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal netIssued = new BigDecimal("0.00");
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal fees = new BigDecimal("0.00");
}
