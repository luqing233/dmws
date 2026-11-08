package fun.luqing.dmws.entity.dmw.economy;
import jakarta.persistence.*;
import lombok.Data;
import fun.luqing.dmws.service.dmw.economy.EconomyBaitQuality;
@Data @Entity @Table(name="economy_fishing_bait_state")
public class EconomyFishingBaitState {
    @Id private Long userId;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=16) private EconomyBaitQuality selected=EconomyBaitQuality.WHITE;
    private long whiteUses, greenUses, blueUses, purpleUses, goldUses, redUses;
    public long remaining(EconomyBaitQuality q) {
        return switch(q) {case WHITE->whiteUses;case GREEN->greenUses;case BLUE->blueUses;case PURPLE->purpleUses;case GOLD->goldUses;case RED->redUses;};
    }
    public void remaining(EconomyBaitQuality q,long value) {
        if(value<0) throw new IllegalArgumentException("Negative bait uses");
        switch(q) {case WHITE->whiteUses=value;case GREEN->greenUses=value;case BLUE->blueUses=value;case PURPLE->purpleUses=value;case GOLD->goldUses=value;case RED->redUses=value;}
    }
}
