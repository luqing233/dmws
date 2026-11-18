package fun.luqing.dmws.entity.dmw.fish;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "fish", indexes = @Index(name = "idx_fish_active_level", columnList = "enabled,level,price"))
public class Fish {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    /** Immutable built-in seed identity; display-name edits must not recreate the original species on restart. */
    @Column(length = 255, unique = true)
    private String seedName;

    @Column(nullable = false)
    private String name;

    private String description;

    @Column(nullable = false)
    private Integer level;

    @Column(nullable = false)
    private Integer price;

    @Column(nullable = false)
    private Integer difficulty;

    @Column(name = "dimensions_min", nullable = false)  //最小尺寸
    private Integer dimensionsMin;

    @Column(name = "dimensions_max", nullable = false)  //最大尺寸
    private Integer dimensionsMax;

    @Column(nullable = false)
    private Boolean special = false;

    @Column(nullable = false, columnDefinition = "boolean default true")
    private boolean enabled = true;

}
