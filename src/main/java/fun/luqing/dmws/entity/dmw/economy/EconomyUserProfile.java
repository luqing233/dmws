package fun.luqing.dmws.entity.dmw.economy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "economy_user_profile")
public class EconomyUserProfile {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false, length = 80)
    private String nickname = "";

    @Column(name = "last_sign_date")
    private LocalDate lastSignDate;

    @Column(name = "sign_streak", nullable = false)
    private Integer signStreak = 0;

    @Column(name = "old_sign_number", nullable = false, columnDefinition = "int default 0")
    private Integer oldSignNumber = 0;

    @Column(name = "total_sign_count", nullable = false)
    private Integer totalSignCount = 0;

    @Column(name = "total_earned", nullable = false, precision = 19, scale = 2)
    private BigDecimal totalEarned = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    @Column(name = "active_title_code", length = 64)
    private String activeTitleCode;

    @Column(name = "last_raffle_at")
    private LocalDateTime lastRaffleAt;

    @Column(name = "last_ten_raffle_at")
    private LocalDateTime lastTenRaffleAt;

    @Column(name = "fishing_rod_level", nullable = false)
    private Integer fishingRodLevel = 1;

    @Column(
            name = "fishing_rod_purchased",
            nullable = false,
            columnDefinition = "boolean default false"
    )
    private Boolean fishingRodPurchased = false;

    @Column(name = "last_fished_at")
    private LocalDateTime lastFishedAt;

    @Column(name = "last_rob_at")
    private LocalDateTime lastRobAt;

    @Column(name = "rob_boost_charges", nullable = false, columnDefinition = "int default 0")
    private Integer robBoostCharges = 0;

    @Column(name = "fishing_luck_charges", nullable = false, columnDefinition = "int default 0")
    private Integer fishingLuckCharges = 0;

    @Column(name = "default_private_bank_code", length = 32)
    private String defaultPrivateBankCode;

    @Column(name = "register_group_id")
    private Long registerGroupId;

    @Column(name = "register_group_name", length = 120)
    private String registerGroupName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @jakarta.persistence.PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @jakarta.persistence.PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
