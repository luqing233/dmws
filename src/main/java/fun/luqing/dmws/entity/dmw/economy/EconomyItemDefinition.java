package fun.luqing.dmws.entity.dmw.economy;

import fun.luqing.dmws.enums.EconomyItemEffectType;
import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Data
@Entity
@Table(name = "economy_item_definition")
public class EconomyItemDefinition {

    @Id
    @Column(length = 64)
    private String code;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(nullable = false, length = 300)
    private String description = "";

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal price = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    @Enumerated(EnumType.STRING)
    @Column(name = "effect_type", nullable = false, length = 32)
    private EconomyItemEffectType effectType = EconomyItemEffectType.NONE;

    @Column(name = "effect_value", precision = 19, scale = 2)
    private BigDecimal effectValue = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    @Column(name = "effect_text", length = 64)
    private String effectText;

    @Column(nullable = false)
    private Boolean enabled = true;
}
