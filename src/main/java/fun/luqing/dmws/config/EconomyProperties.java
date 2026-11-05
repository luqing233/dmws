package fun.luqing.dmws.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.ZoneId;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "dmws.economy")
public class EconomyProperties {

    private boolean enabled = true;
    private boolean conditionSchedulerEnabled = true;
    private String timeZone = "Asia/Shanghai";
    private BigDecimal signMin = new BigDecimal("50.00");
    private BigDecimal signMax = new BigDecimal("500.00");
    private BigDecimal bankWeeklyRate = new BigDecimal("0.005");
    private BigDecimal rafflePrice = new BigDecimal("1000.00");
    private BigDecimal raffleTenPrice = new BigDecimal("10000.00");
    private long raffleCooldownSeconds = 60;
    private long raffleTenCooldownSeconds = 600;
    private long redPacketExpireHours = 24;
    private BigDecimal privateBankMinCapital = new BigDecimal("100000.00");
    private BigDecimal privateBankLoanRate = new BigDecimal("0.10");
    private int privateBankMaxLoanDays = 30;
    private BigDecimal fishingRodPrice = new BigDecimal("500.00");
    private BigDecimal fishingCost = new BigDecimal("20.00");
    private long fishingCooldownSeconds = 30;
    private long robCooldownSeconds = 300;
    private BigDecimal robFine = new BigDecimal("50.00");

    public ZoneId zoneId() {
        return ZoneId.of(timeZone);
    }
}
