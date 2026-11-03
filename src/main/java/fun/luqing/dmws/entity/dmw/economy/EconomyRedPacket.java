package fun.luqing.dmws.entity.dmw.economy;

import fun.luqing.dmws.enums.EconomyRedPacketKind;
import fun.luqing.dmws.enums.EconomyRedPacketStatus;
import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

@Data
@Entity
@Table(
        name = "economy_red_packet",
        indexes = {
                @Index(name = "idx_economy_red_packet_group", columnList = "group_id,status"),
                @Index(name = "idx_economy_red_packet_sender", columnList = "sender_id")
        }
)
public class EconomyRedPacket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sender_id", nullable = false)
    private Long senderId;

    @Column(name = "group_id", nullable = false)
    private Long groupId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private EconomyRedPacketKind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private EconomyRedPacketStatus status = EconomyRedPacketStatus.ACTIVE;

    @Column(name = "total_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal totalAmount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    @Column(name = "remaining_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal remainingAmount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    @Column(name = "total_count", nullable = false)
    private Integer totalCount;

    @Column(name = "remaining_count", nullable = false)
    private Integer remainingCount;

    @Column(length = 32)
    private String password;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @jakarta.persistence.PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
