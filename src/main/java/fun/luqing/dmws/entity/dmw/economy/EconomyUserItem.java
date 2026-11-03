package fun.luqing.dmws.entity.dmw.economy;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(
        name = "economy_user_item",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_economy_user_item",
                columnNames = {"user_id", "item_code"}
        ),
        indexes = @Index(name = "idx_economy_user_item_user", columnList = "user_id")
)
public class EconomyUserItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "item_code", nullable = false, length = 64)
    private String itemCode;

    @Column(nullable = false)
    private Integer quantity = 0;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();
}
