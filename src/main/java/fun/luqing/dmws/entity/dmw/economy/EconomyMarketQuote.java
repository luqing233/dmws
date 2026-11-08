package fun.luqing.dmws.entity.dmw.economy;

import fun.luqing.dmws.enums.EconomyFxPair;
import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Immutable, server-generated simultaneous quotes; never presented as real exchange rates. */
@Data
@Entity
@Table(name = "economy_market_quote", uniqueConstraints = @UniqueConstraint(
        name = "uk_market_quote_minute", columnNames = "quoted_at"))
public class EconomyMarketQuote {
    @Id
    private Long id;
    @Column(nullable = false)
    private LocalDateTime quotedAt;
    @Column(nullable = false, precision = 24, scale = 8)
    private BigDecimal eurUsd;
    @Column(nullable = false, precision = 24, scale = 8)
    private BigDecimal gbpUsd;
    @Column(nullable = false, precision = 24, scale = 8)
    private BigDecimal usdJpy;
    @Column(nullable = false, precision = 24, scale = 8)
    private BigDecimal usdChf;
    public BigDecimal price(EconomyFxPair pair) {
        return switch (pair) {
            case EUR_USD -> eurUsd; case GBP_USD -> gbpUsd; case USD_JPY -> usdJpy; case USD_CHF -> usdChf;
        };
    }
    public void price(EconomyFxPair pair, BigDecimal price) {
        switch (pair) {
            case EUR_USD -> eurUsd = price; case GBP_USD -> gbpUsd = price;
            case USD_JPY -> usdJpy = price; case USD_CHF -> usdChf = price;
        }
    }
}
