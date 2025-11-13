package fun.luqing.dmws.utils;

public record TomatoContentRecord(
        String bookId,
        String bookName,
        int realChapterOrder,
        String title,
        String content,
        String updateTime,
        int wordCount) {
}
