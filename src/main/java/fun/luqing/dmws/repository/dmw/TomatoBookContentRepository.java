package fun.luqing.dmws.repository.dmw;

import fun.luqing.dmws.entity.dmw.tomato.TomatoBookContent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface TomatoBookContentRepository extends JpaRepository<TomatoBookContent, Long> {

    /**
     * 统计该书最近 30 天的更新情况。
     * 时间窗口固定结束到今天。
     */
    @Query(value = """
    WITH RECURSIVE date_series AS (
        SELECT CURDATE() - INTERVAL 29 DAY AS dt
        UNION ALL
        SELECT dt + INTERVAL 1 DAY FROM date_series
        WHERE dt + INTERVAL 1 DAY <= CURDATE()
    )
    SELECT
        ds.dt AS update_date,
        COALESCE(SUM(CASE
            WHEN tbc.content IS NOT NULL
             AND tbc.content <> ''
             AND tbc.word_count > 0
            THEN 1 ELSE 0 END), 0) AS chapter_count,
        COALESCE(SUM(CASE
            WHEN tbc.content IS NOT NULL
             AND tbc.content <> ''
             AND tbc.word_count > 0
            THEN tbc.word_count ELSE 0 END), 0) AS total_word_count
    FROM date_series ds
    LEFT JOIN tomato_book_content tbc
        ON DATE(tbc.update_time) = ds.dt
        AND tbc.book_id = ?1
    GROUP BY ds.dt
    ORDER BY ds.dt ASC
""", nativeQuery = true)
    List<Object[]> findStatsLast30Days(String bookId);

    /**
     * 统计该书最近 365 天的更新情况，用于 GitHub 风格热力图。
     * 时间窗口固定结束到今天。
     */
    @Query(value = """
    WITH RECURSIVE date_series AS (
        SELECT CURDATE() - INTERVAL 364 DAY AS dt
        UNION ALL
        SELECT dt + INTERVAL 1 DAY FROM date_series
        WHERE dt + INTERVAL 1 DAY <= CURDATE()
    )
    SELECT
        ds.dt AS update_date,
        COALESCE(COUNT(tbc.book_id), 0) AS chapter_count,
        COALESCE(SUM(tbc.word_count), 0) AS total_word_count
    FROM date_series ds
    LEFT JOIN tomato_book_content tbc
        ON DATE(tbc.update_time) = ds.dt
        AND tbc.book_id = ?1
    GROUP BY ds.dt
    ORDER BY ds.dt ASC
""", nativeQuery = true)
    List<Object[]> findStatsLast365Days(String bookId);

    List<TomatoBookContent> findByBookIdOrderByRealChapterOrderAsc(String bookId);

    Optional<TomatoBookContent> findByBookIdAndChapterId(String bookId, String chapterId);

    void deleteByBookId(String bookId);

    void deleteByBookIdAndChapterId(String bookId, String chapterId);

    Optional<TomatoBookContent> findByChapterId(String chapterId);

    void deleteByChapterId(String chapterId);

    List<TomatoBookContent> findAllByBookIdOrderByRealChapterOrderAsc(String bookId);

    List<TomatoBookContent> findAllByBookIdOrderByRealChapterOrderDesc(String bookId);
}
