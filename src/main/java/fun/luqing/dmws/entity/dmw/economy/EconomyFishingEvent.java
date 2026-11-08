package fun.luqing.dmws.entity.dmw.economy;
import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
@Data @Entity @Table(name="economy_fishing_event")
public class EconomyFishingEvent {
    @Id @Column(length=40) private String code;
    @Column(nullable=false) private String name;
    @Column(nullable=false) private String description;
    @Column(nullable=false) private String kind;
    @Column(nullable=false) private int tickets;
    @Column(nullable=false,precision=19,scale=2) private BigDecimal penalty = BigDecimal.ZERO;
    @Column(nullable=false) private int jailMinutes = 10;
    @Column(nullable=false) private int strengthMin = 60;
    @Column(nullable=false) private int strengthMax = 100;
    @Column(nullable=false,precision=19,scale=2) private BigDecimal rewardMin = new BigDecimal("800");
    @Column(nullable=false,precision=19,scale=2) private BigDecimal rewardMax = new BigDecimal("2000");
    @Column(nullable=false) private int winInjuryPercent = 20;
    @Column(nullable=false) private int lightPercent = 50;
    @Column(nullable=false) private int mediumPercent = 35;
    @Column(nullable=false) private boolean enabled = true;
}
