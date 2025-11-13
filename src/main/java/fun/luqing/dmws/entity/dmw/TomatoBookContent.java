package fun.luqing.dmws.entity.dmw;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "tomato_book_content",
        uniqueConstraints = @UniqueConstraint(columnNames = {"bookId", "chapterId"})
)
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TomatoBookContent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String bookId; // 所属书籍ID

    @Column(nullable = false)
    private String chapterId; // 章节ID（唯一标识每一章）

    private Integer realChapterOrder;//章节的order

    private String title; // 章节标题

    @Lob
    @Column(columnDefinition = "TEXT")
    private String content; // 章节内容

    private String updateTime; // 更新时间

    private Integer wordCount; //章节字数
}
