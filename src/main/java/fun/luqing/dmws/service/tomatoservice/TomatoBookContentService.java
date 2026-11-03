package fun.luqing.dmws.service.tomatoservice;

import fun.luqing.dmws.client.TomatoApiClient;
import fun.luqing.dmws.client.TomatoChapterRecord;
import fun.luqing.dmws.client.TomatoContentRecord;
import fun.luqing.dmws.entity.dmw.tomato.TomatoBookContent;
import fun.luqing.dmws.repository.dmw.TomatoBookContentRepository;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    @Transactional
    public TomatoBookContent save(TomatoBookContent content) {
        TomatoBookContent saved = repository.save(content);
        log.debug("保存章节成功, bookId={}, chapterId={}", saved.getBookId(), saved.getChapterId());
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
    @Transactional
    public void deleteByChapterId(String chapterId) {
        repository.deleteByChapterId(chapterId);
        log.debug("删除章节成功, chapterId={}", chapterId);
    }

    /**
     * 根据给定的书籍ID删除该书籍的所有章节。
     *
     * @param bookId 要删除其所有章节的书籍的唯一标识符
     */
    @Transactional
    public void deleteByBookId(String bookId) {
        repository.deleteByBookId(bookId);
        log.debug("删除书籍所有章节成功, bookId={}", bookId);
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

        List<TomatoBookContent> targets = chapters.stream()
                .filter(chapter -> forceUpdate || needsContentUpdate(chapter))
                .toList();

        int batchSize = 20;
        int successCount = 0;
        int failCount = 0;

        for (int start = 0; start < targets.size(); start += batchSize) {
            int end = Math.min(start + batchSize, targets.size());
            List<TomatoBookContent> batch = targets.subList(start, end);
            List<TomatoApiClient.ChapterTarget> chapterTargets = batch.stream()
                    .map(chapter -> new TomatoApiClient.ChapterTarget(
                            chapter.getChapterId(),
                            chapter.getRealChapterOrder() == null ? 0 : chapter.getRealChapterOrder()))
                    .toList();

            Map<String, TomatoContentRecord> fetched =
                    tomatoApiClient.getChapterContents(bookId, chapterTargets);

            for (TomatoBookContent chapter : batch) {
                TomatoContentRecord record = fetched.get(chapter.getChapterId());
                if (record == null || record.content() == null || record.content().isBlank()) {
                    failCount++;
                    continue;
                }

                chapter.setContent(record.content());
                chapter.setWordCount(record.content().length());
                if (record.updateTime() != null && !record.updateTime().isBlank()) {
                    chapter.setUpdateTime(record.updateTime());
                }
                saveOrUpdate(chapter);
                successCount++;
            }
        }

        int existCount = chapters.size() - targets.size();
        if (successCount > 0) {
            log.info("[批量更新] bookId={} 成功更新 {} 章，失败 {} 章", bookId, successCount, failCount);
        } else {
            log.debug("[批量更新] bookId={} 无需更新，失败 {} 章", bookId, failCount);
        }
        return new UpdateResult(chapters.size(), successCount, failCount, existCount);
    }

    /**
     * 生成图表前确保正文和字数完整。
     * 已有正文只补字数；正文缺失时通过批量下载补齐，并保留章节更新时间。
     */
    public void ensureWordCountsForChart(String bookId) {
        List<TomatoBookContent> chapters = findAllByBookIdOrderByRealChapterOrderAsc(bookId);
        List<TomatoBookContent> missingWordCounts = chapters.stream()
                .filter(chapter -> chapter.getWordCount() == null || chapter.getWordCount() <= 0)
                .toList();
        if (missingWordCounts.isEmpty()) {
            return;
        }

        int repaired = 0;
        List<TomatoBookContent> needFetch = new ArrayList<>();
        for (TomatoBookContent chapter : missingWordCounts) {
            if (chapter.getContent() != null && !chapter.getContent().isBlank()) {
                chapter.setWordCount(chapter.getContent().length());
                saveOrUpdate(chapter);
                repaired++;
            } else {
                needFetch.add(chapter);
            }
        }

        int fetched = 0;
        int batchSize = 20;
        for (int start = 0; start < needFetch.size(); start += batchSize) {
            int end = Math.min(start + batchSize, needFetch.size());
            List<TomatoBookContent> batch = needFetch.subList(start, end);
            List<TomatoApiClient.ChapterTarget> targets = batch.stream()
                    .map(chapter -> new TomatoApiClient.ChapterTarget(
                            chapter.getChapterId(),
                            chapter.getRealChapterOrder() == null ? 0 : chapter.getRealChapterOrder()))
                    .toList();

            Map<String, TomatoContentRecord> records =
                    tomatoApiClient.getChapterContents(bookId, targets);
            for (TomatoBookContent chapter : batch) {
                TomatoContentRecord record = records.get(chapter.getChapterId());
                if (record == null || record.content() == null || record.content().isBlank()) {
                    continue;
                }
                chapter.setContent(record.content());
                chapter.setWordCount(record.content().length());
                if (record.updateTime() != null && !record.updateTime().isBlank()) {
                    chapter.setUpdateTime(record.updateTime());
                }
                saveOrUpdate(chapter);
                fetched++;
            }
        }

        if (repaired + fetched > 0) {
            log.info("[图表数据] bookId={} 补全字数 {} 章", bookId, repaired + fetched);
        } else {
            log.debug("[图表数据] bookId={} 未补全任何正文字数", bookId);
        }
    }

    /**
     * 判断章节是否需要拉取正文。
     */
    private boolean needsContentUpdate(TomatoBookContent chapter) {
        if (chapter.getContent() == null || chapter.getContent().isBlank()) {
            return true;
        }
        Integer wordCount = chapter.getWordCount();
        if (wordCount == null || wordCount == 0) {
            return true;
        }
        return chapter.getContent().length() < wordCount;
    }


    /**
     * 更新指定书籍的章节目录，补全缺失的章节。
     *
     * @param bookId 书籍的唯一标识符
     * @return 返回一个包含更新结果的对象，其中包括总章节数、成功添加的章节数以及失败添加的章节数
     */
    @Transactional
    public UpdateResult updateChapterList(String bookId) {
        List<TomatoBookContent> localChapters = getChapterList(bookId);

        // 获取远程章节目录
        List<TomatoChapterRecord> remoteChapters = tomatoApiClient.getChapters(bookId);
        if (remoteChapters == null || remoteChapters.isEmpty()) {
            log.debug("[补全目录] bookId={} 无法获取远程章节目录", bookId);
            return new UpdateResult(localChapters.size(), 0, 0,0);
        }

        Set<String> existingChapterIds =
                localChapters.stream()
                        .map(TomatoBookContent::getChapterId)
                        .collect(Collectors.toSet());

        int addedCount = 0;
        int failCount = 0;

        for (TomatoChapterRecord rec : remoteChapters) {
            if (!existingChapterIds.contains(rec.itemId())) {
                try {
                    TomatoBookContent chapter = new TomatoBookContent();
                    chapter.setBookId(bookId);
                    chapter.setChapterId(rec.itemId());
                    chapter.setRealChapterOrder(rec.realChapterOrder());
                    chapter.setTitle(rec.title());
                    chapter.setContent(null);
                    chapter.setUpdateTime(rec.firstPassTime());
                    chapter.setWordCount(0);
                    repository.save(chapter);
                    localChapters.add(chapter);
                    addedCount++;
                } catch (Exception e) {
                    failCount++;
                    log.error("[补全目录] bookId={} 补全章节 {} 失败", bookId, rec.itemId(), e);
                }
            }
        }

        localChapters.sort(Comparator.comparingInt(TomatoBookContent::getRealChapterOrder));

        if (addedCount > 0) {
            log.info("[补全目录] bookId={} 新增 {} 章，失败 {} 章", bookId, addedCount, failCount);
        } else {
            log.debug("[补全目录] bookId={} 无新增章节，失败 {} 章", bookId, failCount);
        }
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
    @Transactional
    public OperationStatus updateChapter(TomatoBookContent chapter, boolean forceUpdate) {
        if (chapter == null || chapter.getChapterId() == null) {
            log.debug("[更新章节内容] chapter 为空或 chapterId 为空");
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

            TomatoContentRecord record = tomatoApiClient.getChapterContent(
                    chapter.getBookId(), chapter.getChapterId(), chapter.getRealChapterOrder());
            if (record != null && record.content() != null) {
                chapter.setContent(record.content());
                chapter.setWordCount(record.wordCount());
                repository.save(chapter);
                log.debug("[更新章节内容] chapterId={} 成功更新", chapter.getChapterId());
                return OperationStatus.SUCCESS;
            } else {
                log.debug("[更新章节内容] chapterId={} 更新失败，API未返回内容", chapter.getChapterId());
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

    /**
     * 查询指定书籍最近一年的更新统计，用于 GitHub 风格热力图。
     */
    public List<Object[]> findStatsLast365Days(String bookId) {
        return repository.findStatsLast365Days(bookId);
    }

    @Transactional
    public TomatoBookContent saveOrUpdate(TomatoBookContent chapter) {
        Optional<TomatoBookContent> optional =
                repository.findByChapterId(chapter.getChapterId());

        if (optional.isPresent()) {

            TomatoBookContent exist = optional.get();

            // 目录同步会以空正文调用本方法，不能让它覆盖已抓取的正文
            if (chapter.getContent() != null && !chapter.getContent().isBlank()) {
                exist.setContent(chapter.getContent());
                // 字数口径统一为正文长度，与 Excel 导入和历史重算保持一致
                exist.setWordCount(chapter.getContent().length());
                // 正文抓取成功，失败计数归零
                exist.setFetchAttempts(0);
            }
            exist.setRealChapterOrder(chapter.getRealChapterOrder());
            exist.setTitle(chapter.getTitle());
            exist.setUpdateTime(chapter.getUpdateTime());

            return repository.save(exist);
        }

        return repository.save(chapter);
    }


    /**
     * 记录章节正文抓取失败一次。
     * 只更新失败计数，不触碰正文与字数，避免与目录同步、正文回写互相干扰。
     *
     * @param chapterId 章节ID
     * @return 累加后的失败次数；章节不存在时返回 -1
     */
    @Transactional
    public int incrementFetchAttempts(String chapterId) {
        Optional<TomatoBookContent> optional = repository.findByChapterId(chapterId);
        if (optional.isEmpty()) return -1;

        TomatoBookContent exist = optional.get();
        int attempts = exist.getFetchAttempts() == null ? 0 : exist.getFetchAttempts();
        exist.setFetchAttempts(attempts + 1);
        repository.save(exist);
        return attempts + 1;
    }

    /**
     * 标记章节不再尝试抓取正文。
     * 用于书不存在等永久失败，直接把失败计数拉到上限。
     *
     * @param chapterId 章节ID
     * @param maxAttempts 上限值
     */
    @Transactional
    public void markFetchGivenUp(String chapterId, int maxAttempts) {
        Optional<TomatoBookContent> optional = repository.findByChapterId(chapterId);
        if (optional.isEmpty()) return;

        TomatoBookContent exist = optional.get();
        exist.setFetchAttempts(maxAttempts);
        repository.save(exist);
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
