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

    /**
     * 保存或更新书籍信息。
     *
     * @param book 待保存的TomatoBookList对象
     * @return 返回已保存的TomatoBookList对象
     */
    public TomatoBookList save(TomatoBookList book) {
        log.info("保存书籍, bookId={}, bookName={}", book.getBookId(), book.getBookName());
        TomatoBookList saved = repository.save(book);
        log.info("保存成功, id={}", saved.getId());
        return saved;
    }

    /**
     * 查询所有书籍信息。
     *
     * @return 返回包含所有书籍的列表，如果没有任何书籍，则返回一个空列表。
     */
    public List<TomatoBookList> findAll() {
        //log.info("查询所有书籍");
        return repository.findAll();
    }

    /**
     * 根据书籍ID查询书籍信息。
     *
     * @param bookId 书籍的唯一标识符
     * @return 返回一个Optional对象，如果找到对应的书籍则包含TomatoBookList实例，否则为空
     */
    public Optional<TomatoBookList> findByBookId(String bookId) {
        log.info("查询书籍, bookId={}", bookId);
        return repository.findByBookId(bookId);
    }

    /**
     * 新增书籍到数据库。
     *
     * @param book 要新增的书籍信息
     * @return 返回保存到数据库后的书籍对象，包含更新后的ID等信息
     */
    public TomatoBookList addBook(TomatoBookList book) {
        log.info("新增书籍, bookId={}, bookName={}", book.getBookId(), book.getBookName());
        TomatoBookList saved = repository.save(book);
        log.info("新增成功, id={}", saved.getId());
        return saved;
    }

    /**
     * 更新书籍信息。
     *
     * @param bookId 书籍的唯一标识符
     * @param bookName 书籍名称，可以为空
     * @param lastTitle 最新章节标题，可以为空
     * @param lastChapterId 最新章节ID，可以为空
     * @param lastTime 更新时间，可以为空
     * @return 更新后的书籍信息
     * @throws RuntimeException 如果提供的bookId对应的书籍不存在，则抛出异常
     */
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

    /**
     * 根据书籍ID删除书籍。
     *
     * @param bookId 书籍的唯一标识符
     */
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

    /**
     * 更新指定书籍的总字数。
     *
     * @param bookId 书籍的唯一标识符
     * @param totalWords 要更新的总字数
     * @return 更新后的书籍信息
     * @throws RuntimeException 如果提供的bookId对应的书籍不存在，则抛出异常
     */
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

    /**
     * 根据bookId保存新的{@code TomatoBookList}或更新现有的。
     *
     * @param book 要保存或更新的{@code TomatoBookList}
     */
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
