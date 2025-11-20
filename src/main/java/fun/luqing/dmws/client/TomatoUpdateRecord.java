package fun.luqing.dmws.client;

public record TomatoUpdateRecord(
        String bookName,
        String lastChapterTitle,
        String lastChapterId,
        String lastPublishTime,
        long totalWords) {

}