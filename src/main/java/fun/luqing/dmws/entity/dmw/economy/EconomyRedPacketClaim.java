package fun.luqing.dmws.entity.dmw.economy;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(
        name = "economy_red_packet_claim",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_economy_red_packet_claim",
                columnNames = {"packet_id", "user_id"}
        ),
        indexes = @Index(name = "idx_economy_red_packet_claim_packet", columnList = "packet_id")
)
public class EconomyRedPacketClaim {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "packet_id", nullable = false)
    private Long packetId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "claimed_at", nullable = false)
    private LocalDateTime claimedAt = LocalDateTime.now();
}
