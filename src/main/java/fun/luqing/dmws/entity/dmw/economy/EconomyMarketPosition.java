package fun.luqing.dmws.entity.dmw.economy;

import fun.luqing.dmws.enums.EconomyFxPair;
import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "economy_market_position", indexes = {
        @Index(name = "idx_market_position_owner", columnList = "user_id,status"),
        @Index(name = "idx_market_position_status", columnList = "status")
}, uniqueConstraints = @UniqueConstraint(name = "uk_market_position_operation", columnNames = "operation_id"))
public class EconomyMarketPosition {
    public enum Side { LONG, SHORT }
    public enum Status { OPEN, CLOSED, LIQUIDATED }
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 96)
    private String operationId;
    @Column(nullable = false)
    private Long userId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 8)
    private EconomyFxPair pair;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 8)
    private Side side;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    private Status status = Status.OPEN;
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal margin;
    @Column(nullable = false)
    private int leverage;
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal notional;
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal openingFee;
    @Column(nullable = false, precision = 24, scale = 8)
    private BigDecimal entryPrice;
    @Column(nullable = false)
    private Long entryQuote;
    @Column(nullable = false)
    private LocalDateTime openedAt;
    private Long originGroupId;
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean riskWarned;
    private Long marginCallQuote;
    private LocalDateTime marginCallDeadline;
    @Column(precision = 24, scale = 8)
    private BigDecimal exitPrice;
    private Long exitQuote;
    private LocalDateTime closedAt;
    @Column(precision = 19, scale = 2)
    private BigDecimal pnl;
    @Column(precision = 19, scale = 2)
    private BigDecimal newDebt;
    @Column(precision = 19, scale = 2)
    private BigDecimal debtRepaid;
    @Column(precision = 19, scale = 2)
    private BigDecimal payout;
    @Column(precision = 19, scale = 2)
    private BigDecimal extraPaid;
    @Column(length = 1000)
    private String closeReceipt;
}
