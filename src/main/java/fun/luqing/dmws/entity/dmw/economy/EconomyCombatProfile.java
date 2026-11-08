package fun.luqing.dmws.entity.dmw.economy;
import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDate;
@Data @Entity @Table(name="economy_combat_profile")
public class EconomyCombatProfile {
    @Id private Long userId;
    @Column(nullable=false) private int strength = 50;
    private LocalDate growthOn;
    @Column(nullable=false) private int growthToday;
    @Column(nullable=false) private long fishingAttempts;
}
