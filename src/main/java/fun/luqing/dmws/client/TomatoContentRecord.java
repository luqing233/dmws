package fun.luqing.dmws.client;

/**
 * 用于封装书籍章节的内容和元数据的记录。
 * 该记录设计用于保存诸如书籍的唯一标识符、书名、章节在书中的实际顺序、章节标题、章节文本内容、内容的最后更新时间以及章节的字数等信息。
 *
 * @param bookId           书籍的唯一标识符
 * @param bookName         书籍的名称或标题
 * @param realChapterOrder 章节在书中的实际位置或序列号
 * @param title            特定章节的标题
 * @param content          章节的完整文本内容
 * @param updateTime       表示内容最后一次更新时间的字符串
 * @param wordCount        章节内容的总字数
 */
public record TomatoContentRecord(
        String bookId,
        String bookName,
        int realChapterOrder,
        String title,
        String content,
        String updateTime,
        int wordCount) {
}
