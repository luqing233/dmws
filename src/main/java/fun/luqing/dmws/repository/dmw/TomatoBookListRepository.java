package fun.luqing.dmws.repository.dmw;

import fun.luqing.dmws.entity.dmw.TomatoBookList;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TomatoBookListRepository extends JpaRepository<TomatoBookList, Long> {
    Optional<TomatoBookList> findByBookId(String bookId);
}
