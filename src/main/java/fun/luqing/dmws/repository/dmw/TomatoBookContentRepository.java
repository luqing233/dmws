package fun.luqing.dmws.repository.dmw;

import fun.luqing.dmws.entity.dmw.TomatoBookContent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface TomatoBookContentRepository extends JpaRepository<TomatoBookContent, Long> {

    @Query(value = """
        SELECT
            DATE(update_time) AS update_date,
            COUNT(*) AS chapter_count,
            SUM(word_count) AS total_word_count
        FROM tomato_book_content
        WHERE book_id = ?1
        AND DATE(update_time) >= DATE_SUB(CURDATE(), INTERVAL 30 DAY)
        GROUP BY DATE(update_time)
        ORDER BY update_date ASC
    """, nativeQuery = true)
    List<Object[]> findStatsLast30Days(String bookId);
}
