package fun.luqing.dmws.service;

import fun.luqing.dmws.entity.dmw.TomatoBookList;
import fun.luqing.dmws.repository.dmw.TomatoBookListRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class TomatoBookListService {

    private final TomatoBookListRepository repository;

    public TomatoBookList save(TomatoBookList book) {
        log.info("保存书籍, bookId={}, bookName={}", book.getBookId(), book.getBookName());
        TomatoBookList saved = repository.save(book);
        log.info("保存成功, id={}", saved.getId());
        return saved;
    }

    public List<TomatoBookList> findAll() {
        //log.info("查询所有书籍");
        return repository.findAll();
    }

    public Optional<TomatoBookList> findByBookId(String bookId) {
        log.info("查询书籍, bookId={}", bookId);
        return repository.findByBookId(bookId);
    }

    public TomatoBookList addBook(TomatoBookList book) {
        log.info("新增书籍, bookId={}, bookName={}", book.getBookId(), book.getBookName());
        TomatoBookList saved = repository.save(book);
        log.info("新增成功, id={}", saved.getId());
        return saved;
    }

    public TomatoBookList updateBookInfo(String bookId, String bookName, String lastTitle, String lastChapterId, String lastTime) {
        log.info("更新书籍信息, bookId={}", bookId);
        return repository.findByBookId(bookId).map(book -> {
            if (bookName != null) book.setBookName(bookName);
            if (lastTitle != null) book.setLastTitle(lastTitle);
            if (lastChapterId != null) book.setLastChapterId(lastChapterId);
            if (lastTime != null) book.setLastTime(lastTime);
            TomatoBookList updated = repository.save(book);
            log.info("更新书籍信息成功, bookId={}", bookId);
            return updated;
        }).orElseThrow(() -> {
            log.error("更新失败，书籍不存在: {}", bookId);
            return new RuntimeException("书籍不存在：" + bookId);
        });
    }

    public void deleteByBookId(String bookId) {
        log.info("删除书籍, bookId={}", bookId);
        repository.findByBookId(bookId).ifPresentOrElse(
                book -> {
                    repository.delete(book);
                    log.info("删除成功, bookId={}", bookId);
                },
                () -> log.warn("删除失败，书籍不存在, bookId={}", bookId)
        );
    }

    // 更新总字数
    public TomatoBookList updateTotalWords(String bookId, long totalWords) {
        log.info("更新书籍总字数, bookId={}, totalWords={}", bookId, totalWords);
        return repository.findByBookId(bookId).map(book -> {
            book.setTotalWords(totalWords);
            TomatoBookList updated = repository.save(book);
            log.info("更新总字数成功, bookId={}", bookId);
            return updated;
        }).orElseThrow(() -> {
            log.error("更新总字数失败，书籍不存在: {}", bookId);
            return new RuntimeException("书籍不存在：" + bookId);
        });
    }


    public void saveOrUpdate(TomatoBookList book) {
        Optional<TomatoBookList> opt = repository.findByBookId(book.getBookId());

        if (opt.isPresent()) {
            TomatoBookList exist = opt.get();

            exist.setBookName(book.getBookName());
            exist.setLastTitle(book.getLastTitle());
            exist.setLastChapterId(book.getLastChapterId());
            exist.setLastTime(book.getLastTime());
            exist.setTotalWords(book.getTotalWords());

            repository.save(exist);
        } else {
            repository.save(book);
        }
    }

}
