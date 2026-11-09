package fun.luqing.dmws.entity.dmw.economy;

import jakarta.persistence.*;
import lombok.Data;

/** Durable transport outbox. SENDING after a crash is uncertain and must never be blindly resent. */
@Data
@Entity
@Table(name = "economy_market_notice", indexes = @Index(name = "idx_market_notice_status", columnList = "status,id"))
public class EconomyMarketNotice {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false) private Long userId;
    @Column(nullable = false) private Long groupId;
    @Column(nullable = false) private Long positionId;
    @Column(nullable = false, length = 16) private String status = "PENDING";
    @Column(nullable = false, length = 16) private String kind;
    private Long triggerQuote;
    @Column(nullable = false, length = 1200) private String message;
    private String messageId;
}
