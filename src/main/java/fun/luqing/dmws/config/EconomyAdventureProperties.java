package fun.luqing.dmws.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "dmws.economy.adventure")
public class EconomyAdventureProperties {
    private double successChance = 0.35;
    private double counterChance = 0.30;
    private double policeChance = 0.25;
    private double poorAssaultChance = 0.45;
    private BigDecimal minStealRate = new BigDecimal("0.05");
    private BigDecimal maxStealRate = new BigDecimal("0.35");
    private BigDecimal maxStolen = new BigDecimal("5000");
    private int crimePerAttempt = 5;
    private int crimePerSuccess = 5;
    private int crimePerAssault = 15;
    private int crimePerArrest = 10;
    private int crimeDecayPerDay = 5;
    private int bailMaxCrime = 10;
    private BigDecimal bailBase = new BigDecimal("100");
    private BigDecimal bailPerCrime = new BigDecimal("20");
    private BigDecimal diagnosisPerInjury = new BigDecimal("10");
    private BigDecimal lightHourlyFee = new BigDecimal("30");
    private BigDecimal mediumHourlyFee = new BigDecimal("80");
    private BigDecimal severeHourlyFee = new BigDecimal("150");

    @PostConstruct
    void validate() {
        for (double chance : new double[]{successChance, counterChance, policeChance, poorAssaultChance}) {
            if (!Double.isFinite(chance) || chance < 0 || chance > 1) throw new IllegalArgumentException("抢劫概率须在 0–1 之间");
        }
        if (minStealRate == null || maxStealRate == null || minStealRate.signum() <= 0
                || minStealRate.compareTo(maxStealRate) > 0 || maxStealRate.compareTo(BigDecimal.ONE) > 0
                || maxStolen == null || maxStolen.compareTo(new BigDecimal("0.01")) < 0
                || maxStolen.compareTo(new BigDecimal("5000")) > 0) {
            throw new IllegalArgumentException("抢劫比例须在 (0,1] 内且上限不超过 5000");
        }
        for (int value : new int[]{crimePerAttempt, crimePerSuccess, crimePerAssault, crimePerArrest, crimeDecayPerDay, bailMaxCrime}) {
            if (value < 0 || value > 100) throw new IllegalArgumentException("犯罪值配置须在 0–100 之间");
        }
        for (BigDecimal value : new BigDecimal[]{bailBase, bailPerCrime, diagnosisPerInjury, lightHourlyFee, mediumHourlyFee, severeHourlyFee}) {
            if (value == null || value.compareTo(new BigDecimal("0.01")) < 0 || value.compareTo(new BigDecimal("1000000")) > 0) {
                throw new IllegalArgumentException("保释/医疗单价须在 0.01–1000000 之间");
            }
        }
    }
}
