package fun.luqing.dmws.entity.dmw.economy;

import fun.luqing.dmws.enums.EconomyPrizeType;
import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Data
@Entity
@Table(
        name = "economy_raffle_prize",
        uniqueConstraints = @UniqueConstraint(name = "uk_economy_raffle_prize_code", columnNames = "code")
)
public class EconomyRafflePrize {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String code;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(nullable = false, length = 300)
    private String description = "";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private EconomyPrizeType type;

    @Column(nullable = false)
    private Integer weight = 100;

    @Column(name = "stock", nullable = false)
    private Integer stock = -1;

    @Column(name = "money_amount", precision = 19, scale = 2)
    private BigDecimal moneyAmount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    @Column(name = "title_code", length = 64)
    private String titleCode;

    @Column(name = "item_code", length = 64)
    private String itemCode;

    @Column(name = "item_quantity")
    private Integer itemQuantity = 1;

    @Column(nullable = false)
    private Boolean enabled = true;
}
