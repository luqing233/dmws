package fun.luqing.dmws.utils;

public record TomatoChapterRecord(
        String itemId,          // 章节 ID
        String title,           // 章节标题
        String volumeName,      // 卷名
        boolean isChapterLock,  // 是否锁章
        boolean isPaidPublication, // 是否为付费出版
        boolean isPaidStory,    // 是否为付费故事
        int needPay,            // 是否需要支付
        int realChapterOrder,   // 实际章节顺序
        String firstPassTime    // 发布时间（UTC+8）
) {
}
