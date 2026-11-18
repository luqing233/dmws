package fun.luqing.dmws.entity.dmw.economy;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "economy_fishing_text", indexes = @Index(name = "idx_fishing_text_scene",
        columnList = "owner_code,scene,enabled"))
public class EconomyFishingTextTemplate {
    @Id @Column(length = 120) private String code;
    @Column(nullable = false, length = 40) private String ownerCode = "*";
    @Column(nullable = false, length = 40) private String scene;
    @Column(nullable = false, columnDefinition = "text") private String template;
    @Column(nullable = false) private int weight = 1;
    @Column(nullable = false) private boolean enabled = true;
}
