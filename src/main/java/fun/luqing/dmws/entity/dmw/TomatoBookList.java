package fun.luqing.dmws.entity.dmw;

import fun.luqing.dmws.enums.BookStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "tomato_book_list", uniqueConstraints = @UniqueConstraint(columnNames = "bookId"))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TomatoBookList {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String bookId;      // 全局唯一书籍ID

    private String bookName;    // 书名
    private String lastTitle;   // 最新章节标题
    private String lastChapterId; // 最新章节ID
    private String lastTime;    // 更新时间

    @Column(nullable = false)
    private Long totalWords = 0L; // 总字数，默认 0

/*    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BookStatus status = BookStatus.SERIALIZING; //书籍状态，默认为 连载*/

}
