package fun.luqing.dmws.entity.dmw;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Entity
@Table(name = "tomato_subscription_group")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TomatoSubscriptionGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private long groupId;    // 群号
    private String bookId;   // 对应 TomatoBookList.bookId

    private long operator;   // 操作者（QQ）
    @Column(updatable = false)
    private String time;     // 创建时间，只在插入时赋值
    private boolean atAll;   // 是否 @全体
    private boolean enable = true; // 是否启用订阅

    @PrePersist
    public void prePersist() {
        if (this.time == null) {
            this.time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        }
    }

}
