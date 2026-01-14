package fun.luqing.dmws.entity.dmw.user;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Table(name = "user_backpack")
@Data
public class UserBackpack {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long itemId;

    @Column(nullable = false)
    private Integer count = 1;

    @Column
    private Integer durability;

    @Column(columnDefinition = "json")
    private String extra;

    @Column(nullable = false)
    private LocalDateTime createTime = LocalDateTime.now();
}
