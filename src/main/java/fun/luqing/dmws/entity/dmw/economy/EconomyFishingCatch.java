package fun.luqing.dmws.entity.dmw.economy;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(
        name = "economy_fishing_catch",
        indexes = @Index(name = "idx_economy_fishing_catch_user", columnList = "user_id,caught_at")
)
public class EconomyFishingCatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;
    private Long groupId;
    private String groupName;
    private String nickname;

    @Column(name = "fish_id", nullable = false)
    private Integer fishId;

    @Column(name = "fish_name", nullable = false, length = 80)
    private String fishName;

    @Column(name = "size_cm", nullable = false, precision = 10, scale = 2)
    private BigDecimal sizeCm;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal reward;

    @Column(name = "caught_at", nullable = false, updatable = false)
    private LocalDateTime caughtAt;

    @PrePersist
    void onCreate() {
        if (caughtAt == null) caughtAt = LocalDateTime.now();
    }
}
