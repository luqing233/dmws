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
    @Column(length=40) private String legacyKind;
    @Column(precision=19,scale=2) private BigDecimal amountMin;
    @Column(precision=19,scale=2) private BigDecimal amountMax;
    @Column(precision=19,scale=2,columnDefinition="decimal(19,2) default 0.01") private BigDecimal amountStep = new BigDecimal("0.01");
    @Column(length = 40) private String poolCode;
    private String encounterTemplate;
    @Column(nullable=false) private int tickets;
    @Column(nullable=false,precision=19,scale=2,columnDefinition="decimal(19,2) default 0") private BigDecimal penalty = BigDecimal.ZERO;
    @Column(nullable=false,columnDefinition="int default 10") private int jailMinutes = 10;
    @Column(nullable=false,columnDefinition="int default 60") private int strengthMin = 60;
    @Column(nullable=false,columnDefinition="int default 100") private int strengthMax = 100;
    @Column(nullable=false,precision=19,scale=2,columnDefinition="decimal(19,2) default 800") private BigDecimal rewardMin = new BigDecimal("800");
    @Column(nullable=false,precision=19,scale=2,columnDefinition="decimal(19,2) default 2000") private BigDecimal rewardMax = new BigDecimal("2000");
    @Column(nullable=false,columnDefinition="int default 20") private int winInjuryPercent = 20;
    @Column(nullable=false,columnDefinition="int default 50") private int lightPercent = 50;
    @Column(nullable=false,columnDefinition="int default 35") private int mediumPercent = 35;
    @Column(nullable=false) private boolean enabled = true;
}
