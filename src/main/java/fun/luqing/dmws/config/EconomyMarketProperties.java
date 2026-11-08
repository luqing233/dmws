package fun.luqing.dmws.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "dmws.economy.market")
public class EconomyMarketProperties {
    private boolean enabled = false;
    private boolean acceptingOpens = true;
    private boolean schedulerEnabled = true;
    private BigDecimal minimumMargin = new BigDecimal("100.00");
    private BigDecimal positionMarginLimit = new BigDecimal("5000.00");
    private BigDecimal totalMarginLimit = new BigDecimal("20000.00");
    private BigDecimal netIssuanceBudget = new BigDecimal("1000000.00");
    private int maxPositions = 5;
    private int dailyOpenLimit = 20;
}
