package fun.luqing.dmws.entity.dmw.economy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Entity
@Table(name = "economy_farm_crop")
public class EconomyFarmCrop {

    @Id
    @Column(length = 32)
    private String code;

    @Column(nullable = false, length = 60)
    private String name;

    @Column(nullable = false, length = 200)
    private String description = "";

    @Column(name = "seed_price", nullable = false, precision = 19, scale = 2)
    private BigDecimal seedPrice;

    @Column(name = "growth_seconds", nullable = false)
    private Long growthSeconds;

    @Column(name = "yield_min", nullable = false)
    private Integer yieldMin = 1;

    @Column(name = "yield_max", nullable = false)
    private Integer yieldMax = 1;

    @Column(name = "sell_price", nullable = false, precision = 19, scale = 2)
    private BigDecimal sellPrice;

    @Column(nullable = false)
    private Boolean enabled = true;
}
