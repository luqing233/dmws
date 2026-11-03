package fun.luqing.dmws.entity.dmw.economy;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(
        name = "economy_farm_plot",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_economy_farm_plot",
                columnNames = {"user_id", "plot_index"}
        ),
        indexes = @Index(name = "idx_economy_farm_plot_user", columnList = "user_id")
)
public class EconomyFarmPlot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "plot_index", nullable = false)
    private Integer plotIndex;

    @Column(name = "crop_code", length = 32)
    private String cropCode;

    @Column(name = "planted_at")
    private LocalDateTime plantedAt;

    @Column(name = "ready_at")
    private LocalDateTime readyAt;

    @Column(nullable = false)
    private Boolean watered = false;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();
}
