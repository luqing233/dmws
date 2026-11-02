package fun.luqing.dmws.entity.dmw.tomato;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "tomato_book_content",
        uniqueConstraints = @UniqueConstraint(columnNames = {"book_id", "chapter_id"})
)
@Data
@NoArgsConstructor
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

    /**
     * 正文抓取连续失败次数。
     * 达到上限后不再重试，避免永远抓不到的章节每轮都消耗一次下载等待。
     */
    @Column(nullable = false)
    private Integer fetchAttempts = 0;

}
