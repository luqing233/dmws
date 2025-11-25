package fun.luqing.dmws.client;

/**
 * 表示在番茄出版系统中的章节记录。
 * 该记录包含关于特定章节的重要信息，包括其标识、标题以及与可访问性和支付状态相关的各种属性。
 *
 * @param itemId            章节的唯一标识符。
 * @param title             章节的标题。
 * @param volumeName        章节所属卷的名称。
 * @param isChapterLock     指示章节是否被锁定并需要特殊访问权限。
 * @param isPaidPublication 指示该章节的出版是否标记为付费。
 * @param isPaidStory       指定包含该章节的故事是否为付费故事。
 * @param needPay           表示访问章节是否需要支付。该值可以根据上下文解释为标志或金额。
 * @param realChapterOrder  章节在其卷或系列中的实际顺序。
 * @param firstPassTime     章节首次发布的时间（UTC+8时区）。
 */
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
