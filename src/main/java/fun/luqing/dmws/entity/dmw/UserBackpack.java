package fun.luqing.dmws.entity.dmw;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Table(name = "user_backpack")
@Data
public class UserBackpack {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long ownerId;   // 玩家ID

    @Column(nullable = false)
    private int itemId;    // 道具实例ID

    @Column(nullable = false)
    private Integer count;  // 数量

    private Integer durability; // 耐久（非装备可为 null）

    @Column(nullable = false)
    private LocalDateTime createdAt; // 创建时间

    @PrePersist
    public void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
