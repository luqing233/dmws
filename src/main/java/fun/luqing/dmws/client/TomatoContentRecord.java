package fun.luqing.dmws.client;

public record TomatoContentRecord(
        String bookId,
        String bookName,
        int realChapterOrder,
        String title,
        String content,
        String updateTime,
        int wordCount) {
}
