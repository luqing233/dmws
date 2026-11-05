package fun.luqing.dmws.entity.dmw.economy;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/** One row per owned baton; inventory quantities remain the source of ownership. */
@Data
@Entity
@Table(name = "economy_electric_baton", indexes = @Index(name = "idx_baton_owner", columnList = "user_id"))
public class EconomyElectricBaton {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id", nullable = false)
    private Long userId;
    @Column(nullable = false)
    private int energy = 100;
    /** Energy above is the snapshot at this instant; one whole minute restores one point. */
    @Column(name = "charging_since")
    private LocalDateTime chargingSince;
}
