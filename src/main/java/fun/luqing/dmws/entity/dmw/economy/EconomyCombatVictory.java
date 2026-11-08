package fun.luqing.dmws.entity.dmw.economy;
import jakarta.persistence.*;
import lombok.Data;
@Data @Entity @Table(name="economy_combat_victory")
public class EconomyCombatVictory {
    @Id @Column(length=120) private String id;
}
