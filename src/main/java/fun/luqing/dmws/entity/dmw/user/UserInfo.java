package fun.luqing.dmws.entity.dmw.user;

import jakarta.persistence.*;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

@Entity
@Table(name = "user_info")
@Data
@Getter
@Setter
public class UserInfo {

    @Id
    private Long id;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(nullable = false)
    private Integer hp = 100;

    @Column(nullable = false)
    private Integer maxHp = 100;

    @Column(nullable = false)
    private Integer attack = 10;

    @Column(nullable = false)
    private Integer defense = 5;

    // 闪避率：整数百分比（例如 15 = 15%）
    @Column(nullable = false)
    private Integer dodge = 0;

    // 暴击率：小数，四舍五入保留两位（例如 0.15）
    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal critRate = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    // 暴击伤害：小数，四舍五入保留两位（例如 1.50 = 150%）
    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal critDamage = new BigDecimal("1.00").setScale(2,RoundingMode.HALF_UP);

    @Column(nullable = false, updatable = false)
    private LocalDateTime createTime;

    @Column(nullable = false)
    private LocalDateTime updateTime;

    @PrePersist
    protected void onCreate() {
        this.createTime = LocalDateTime.now();
        this.updateTime = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updateTime = LocalDateTime.now();
    }


}
