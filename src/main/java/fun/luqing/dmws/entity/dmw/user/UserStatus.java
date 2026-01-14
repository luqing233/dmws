package fun.luqing.dmws.entity.dmw.user;

import fun.luqing.dmws.enums.UserStatusEnum;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Table(name = "user_status")
@Data
public class UserStatus {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.ORDINAL)
    @Column(nullable = false)
    private UserStatusEnum status; // 新增：状态类型

    private Integer recoveryTime;   // 可用于医院/监狱/冷却等

    @Column(name = "start_time")
    private LocalDateTime startTime;

    @Column(name = "expire_time")
    private LocalDateTime expireTime; // 状态过期时间

    @Column(columnDefinition = "json")
    private String extraData;        // 扩展信息，例如钓鱼鱼竿/抢劫目标ID
}

