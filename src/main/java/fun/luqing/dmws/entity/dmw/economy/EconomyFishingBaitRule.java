package fun.luqing.dmws.entity.dmw.economy;

import fun.luqing.dmws.service.dmw.economy.EconomyBaitQuality;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.ArrayList;
import java.util.List;

@Data
@Entity
@Table(name = "economy_fishing_bait_rule")
public class EconomyFishingBaitRule {
    @Id @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(length = 16)
    private EconomyBaitQuality quality;
    @Column(nullable = false) private int successBonusBps;
    @Column(nullable = false) private int highFishWeightBps;
    @Column(nullable = false) private int specialMultiplierBps;
    @Column(nullable = false) private int specialCapBps;
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "economy_fishing_pool_rule", joinColumns = @JoinColumn(name = "bait_quality"))
    @OrderColumn(name = "pool_order")
    private List<PoolRule> pools = new ArrayList<>();

    public double successBonus() { return successBonusBps / 10000.0; }
    public double highFishWeight() { return highFishWeightBps / 10000.0; }
    public double specialMultiplier() { return specialMultiplierBps / 10000.0; }
    public double specialCap() { return specialCapBps / 10000.0; }

    @Data
    @Embeddable
    public static class PoolRule {
        @Column(nullable = false, length = 40) private String poolCode;
        private Integer ticketBudget;
        @Column(nullable = false) private int ticketScaleBps;
    }
}
