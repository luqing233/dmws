package fun.luqing.dmws.entity.dmw.item;

import fun.luqing.dmws.enums.ItemTypeEnum;
import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "item_data")
@Data
public class ItemData {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String name; // 道具名称

    @Enumerated(EnumType.ORDINAL)
    @Column(nullable = false)
    private ItemTypeEnum type; // 装备/食物

    @Column(nullable = false)
    private Long price = 0L; // 道具价格

    @Column
    private String description; // 描述

    /**
     * extra JSON 格式
     * {
     *   "hp": 50,
     *   "attack": 10,
     *   "defense": -3,
     *   "dodge": 0.05,
     *   "critRate": 0.02,
     *   "critDamage": 0.5
     * }
     */
    @Column(columnDefinition = "json")
    private String extra;//效果

}
