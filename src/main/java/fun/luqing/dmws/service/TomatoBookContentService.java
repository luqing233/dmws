package fun.luqing.dmws.service;

import fun.luqing.dmws.client.TomatoApiClient;
import fun.luqing.dmws.client.TomatoChapterRecord;
import fun.luqing.dmws.client.TomatoContentRecord;
import fun.luqing.dmws.entity.dmw.TomatoBookContent;
import fun.luqing.dmws.repository.dmw.TomatoBookContentRepository;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 提供了对书籍章节内容管理的一系列服务，包括新增、更新、查询和删除章节。
 * 通过与TomatoApiClient的交互，能够从远程获取最新的章节信息，并将其保存到本地数据库中。
 * 支持批量操作，如更新某本书的所有章节内容或补全目录中的缺失章节。
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class TomatoBookContentService {


    private final TomatoApiClient tomatoApiClient;

    private final TomatoBookContentRepository repository;


    /**
     * 将给定的章节内容保存到数据库中。
     *
     * @param content 要保存的TomatoBookContent对象
     * @return 保存后的TomatoBookContent对象
     */
    public TomatoBookContent save(TomatoBookContent content) {
        TomatoBookContent saved = repository.save(content);
        log.info("保存章节成功, bookId={}, chapterId={}", saved.getBookId(), saved.getChapterId());
        return saved;
    }

    /**
     * 根据章节ID查找对应的书籍内容。
     *
     * @param chapterId 章节的唯一标识符
     * @return 返回一个Optional对象，如果找到匹配的TomatoBookContent则包含该对象，否则返回空
     */
    public Optional<TomatoBookContent> findByChapterId(String chapterId) {
        return repository.findByChapterId(chapterId);
    }



    /**
     * 根据章节ID删除对应的章节内容。
     *
     * @param chapterId 要删除的章节的唯一标识符
     */
    public void deleteByChapterId(String chapterId) {
        repository.deleteByChapterId(chapterId);
        log.info("删除章节成功, chapterId={}", chapterId);
    }

    /**
     * 根据给定的书籍ID删除该书籍的所有章节。
     *
     * @param bookId 要删除其所有章节的书籍的唯一标识符
     */
    public void deleteByBookId(String bookId) {
        repository.deleteByBookId(bookId);
        log.info("删除书籍所有章节成功, bookId={}", bookId);
    }

    /**
     * 更新指定书籍的所有章节内容,在执行之前会先更新目录。
     *
     * @param bookId 书籍ID
     * @param forceUpdate 是否强制更新，即使章节已有内容也会尝试更新
     * @return 返回一个包含总章节数、成功更新的章节数和失败更新的章节数的结果对象
     */
    public UpdateResult updateAllChapters(String bookId, boolean forceUpdate) {
        updateChapterList(bookId);

        List<TomatoBookContent> chapters = findAllByBookIdOrderByRealChapterOrderAsc(bookId);

        int successCount = 0;
        int failCount = 0;
        int existCount = 0;
        for (TomatoBookContent chapter : chapters) {
            OperationStatus updated = updateChapter(chapter, forceUpdate);
            switch (updated) {
                case SUCCESS -> successCount++;
                case FAIL -> failCount++;
                case EXIST -> {
                    existCount++;
                    continue;
                }
            }
            try { Thread.sleep(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        return new UpdateResult(chapters.size(), successCount, failCount,existCount);
    }


    /**
     * 更新指定书籍的章节目录，补全缺失的章节。
     *
     * @param bookId 书籍的唯一标识符
     * @return 返回一个包含更新结果的对象，其中包括总章节数、成功添加的章节数以及失败添加的章节数
     */
    public UpdateResult updateChapterList(String bookId) {
        List<TomatoBookContent> localChapters = getChapterList(bookId);

        // 获取远程章节目录
        List<TomatoChapterRecord> remoteChapters = tomatoApiClient.getChapters(bookId);
        if (remoteChapters == null || remoteChapters.isEmpty()) {
            log.warn("[补全目录] bookId={} 无法获取远程章节目录", bookId);
            return new UpdateResult(localChapters.size(), 0, 0,0);
        }

        Set<Integer> existingOrders = localChapters.stream()
                .map(TomatoBookContent::getRealChapterOrder)
                .collect(Collectors.toSet());

        int addedCount = 0;
        int failCount = 0;

        for (TomatoChapterRecord rec : remoteChapters) {
            if (!existingOrders.contains(rec.realChapterOrder())) {
                try {
                    TomatoBookContent chapter = new TomatoBookContent(
                            null,
                            bookId,
                            rec.itemId(),
                            rec.realChapterOrder(),
                            rec.title(),
                            null,
                            rec.firstPassTime(),
                            0
                    );
                    repository.save(chapter);
                    localChapters.add(chapter);
                    addedCount++;
                    log.info("[补全目录] bookId={} 检测到缺失章节：{}，已补全存库", bookId, rec.itemId());
                } catch (Exception e) {
                    failCount++;
                    log.error("[补全目录] bookId={} 补全章节 {} 失败", bookId, rec.itemId(), e);
                }
            }
        }

        localChapters.sort(Comparator.comparingInt(TomatoBookContent::getRealChapterOrder));

        return new UpdateResult(localChapters.size(), addedCount, failCount,0);
    }

    /**
     * 获取指定书籍的所有章节列表，并按章节顺序降序排列。
     *
     * @param bookId 书籍的唯一标识符
     * @return 返回一个包含书籍所有章节的列表，按真实章节顺序降序排列
     */
    public List<TomatoBookContent> getChapterList(String bookId) {
        return repository.findAllByBookIdOrderByRealChapterOrderDesc(bookId);
    }


    /**
     * 更新给定章节的内容。
     * 如果章节内容为空或需要更新，则从API获取最新内容并保存到数据库。
     * @param chapter 要更新的章节内容对象
     * @param forceUpdate 是否强制更新章节，即使已有完整内容
     * @return 操作状态，可能值包括：SUCCESS（成功）、FAIL（失败）、EXIST（已存在无需更新）
     */
    public OperationStatus updateChapter(TomatoBookContent chapter, boolean forceUpdate) {
        if (chapter == null || chapter.getChapterId() == null) {
            log.warn("[更新章节内容] chapter 为空或 chapterId 为空");
            return OperationStatus.FAIL;
        }

        try {
            boolean needUpdate = forceUpdate
                    || chapter.getWordCount() == 0
                    || chapter.getContent() == null
                    || chapter.getContent().length() < chapter.getWordCount();

            if (!needUpdate) {
                //log.info("[更新章节内容] chapterId={} 已有完整内容，无需更新", chapter.getChapterId());
                return OperationStatus.EXIST;
            }

            TomatoContentRecord record = tomatoApiClient.getChapterContent(chapter.getChapterId());
            if (record != null && record.content() != null) {
                chapter.setContent(record.content());
                chapter.setWordCount(record.wordCount());
                repository.save(chapter);
                log.info("[更新章节内容] chapterId={} 成功更新", chapter.getChapterId());
                return OperationStatus.SUCCESS;
            } else {
                log.info("[更新章节内容] chapterId={} 更新失败，API未返回内容", chapter.getChapterId());
                return OperationStatus.FAIL;
            }
        } catch (Exception e) {
            log.error("[更新章节内容] chapterId={} 异常", chapter.getChapterId(), e);
            return OperationStatus.FAIL;
        }
    }



    /**
     * 查询指定书籍ID的所有章节内容，并按实际章节顺序升序排列。
     *
     * @param bookId 书籍的唯一标识符
     * @return 返回一个按照实际章节顺序排序的章节内容列表
     */
    public List<TomatoBookContent> findAllByBookIdOrderByRealChapterOrderAsc(String bookId) {
        return repository.findAllByBookIdOrderByRealChapterOrderAsc(bookId);
    }

    /**
     * 查询指定书籍在最近30天内的统计信息。
     *
     * @param bookId 书籍的唯一标识符
     * @return 返回一个列表，其中每个元素都是一个对象数组，包含特定日期的统计信息
     */
    public List<Object[]> findStatsLast30Days(String bookId) {
        return repository.findStatsLast30Days(bookId);
    }

    public TomatoBookContent saveOrUpdate(TomatoBookContent chapter) {
        Optional<TomatoBookContent> optional =
                repository.findByChapterId(chapter.getChapterId());

        if (optional.isPresent()) {

            TomatoBookContent exist = optional.get();

            exist.setContent(chapter.getContent());
            exist.setRealChapterOrder(chapter.getRealChapterOrder());
            exist.setTitle(chapter.getTitle());
            exist.setUpdateTime(chapter.getUpdateTime());
            exist.setWordCount(chapter.getWordCount());

            return repository.save(exist);
        }

        return repository.save(chapter);
    }


    public record UpdateResult(int total, int success, int fail, int exist) {}


    @Getter
    public enum OperationStatus {
        SUCCESS("成功"),
        FAIL("失败"),
        EXIST("已存在");

        private final String desc;

        OperationStatus(String desc) {
            this.desc = desc;
        }

    }

}
