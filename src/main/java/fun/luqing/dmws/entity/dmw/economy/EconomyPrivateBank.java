package fun.luqing.dmws.entity.dmw.economy;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(
        name = "economy_private_bank",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_economy_private_bank_code", columnNames = "code"),
                @UniqueConstraint(name = "uk_economy_private_bank_owner", columnNames = "owner_id")
        }
)
public class EconomyPrivateBank {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String code;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(name = "owner_id", nullable = false, unique = true)
    private Long ownerId;

    @Column(nullable = false, length = 300)
    private String description = "";

    @Column(name = "capital_account_key", nullable = false, unique = true, length = 64)
    private String capitalAccountKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
