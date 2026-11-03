package fun.luqing.dmws.entity.dmw.economy;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(
        name = "economy_user_title",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_economy_user_title",
                columnNames = {"user_id", "title_code"}
        ),
        indexes = @Index(name = "idx_economy_user_title_user", columnList = "user_id")
)
public class EconomyUserTitle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "title_code", nullable = false, length = 64)
    private String titleCode;

    @Column(name = "acquired_at", nullable = false)
    private LocalDateTime acquiredAt = LocalDateTime.now();
}
