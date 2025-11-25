package fun.luqing.dmws.client;

/**
 * 用于封装番茄更新的详细信息的记录，通常用于跟踪与书籍或类似内容相关的更改或更新。该记录包括书籍名称、最后章节的标题和ID、最后发布的时间以及总字数。
 *
 * @param bookName         书籍或内容的名称。
 * @param lastChapterTitle 最后添加或更新的章节的标题。
 * @param lastChapterId    最后章节的唯一标识符。
 * @param lastPublishTime  最后章节发布的日期。
 * @param totalWords       截至最新更新的书籍或内容的总字数。
 */
public record TomatoUpdateRecord(
        String bookName,
        String lastChapterTitle,
        String lastChapterId,
        String lastPublishTime,
        long totalWords) {

}