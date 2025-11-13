package fun.luqing.dmws.repository.dmw;

import fun.luqing.dmws.entity.dmw.TomatoBookContent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TomatoBookChapterRepository extends JpaRepository<TomatoBookContent, Long> {
    Optional<TomatoBookContent> findByBookIdAndChapterId(String bookId, String chapterId);
    List<TomatoBookContent> findByBookIdOrderByIdAsc(String bookId);

    boolean existsByBookIdAndChapterId(String bookId, String chapterId);

    List<TomatoBookContent> findAllByBookIdOrderByRealChapterOrderAsc(String bookId);

}

