package fun.luqing.dmws.plugin.tomatoplugin.command;

import com.mikuac.shiro.annotation.GroupMessageHandler;
import com.mikuac.shiro.annotation.MessageHandlerFilter;
import com.mikuac.shiro.annotation.common.Shiro;
import com.mikuac.shiro.core.Bot;
import fun.luqing.dmws.common.annotation.CommandInfo;
import fun.luqing.dmws.entity.dmw.TomatoBookList;
import fun.luqing.dmws.plugin.tomatoplugin.util.TomatoMessageUtil;
import fun.luqing.dmws.service.TomatoBookChartService;
import fun.luqing.dmws.service.TomatoBookContentService;
import fun.luqing.dmws.service.TomatoBookListService;
import fun.luqing.dmws.service.TomatoSubscriptionGroupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Shiro
@Slf4j
@Component
@RequiredArgsConstructor
public class ChartCommand {

    private final TomatoBookContentService contentService;
    private final TomatoBookListService bookListService;
    private final TomatoBookChartService chartService;
    private final TomatoSubscriptionGroupService subscriptionService;

    @GroupMessageHandler
    @MessageHandlerFilter(startWith = "获取更新图表")
    @Async("taskExecutor")
    @CommandInfo(startWith = "获取更新图表", desc = "根据订阅ID获取书籍最近30天更新图表")
    public void getBookUpdateChart(Bot bot, com.mikuac.shiro.dto.event.message.GroupMessageEvent event) {
        long groupId = event.getGroupId();
        String param = event.getMessage().replace("获取更新图表", "").trim();

        if (param.isEmpty()) {
            TomatoMessageUtil.sendGroupMessage(bot, groupId, "请输入订阅序号，例如：获取更新图表 1");
            return;
        }

        long subId;
        try {
            subId = Long.parseLong(param);
        } catch (NumberFormatException e) {
            TomatoMessageUtil.sendGroupMessage(bot, groupId, "格式错误，请输入数字序号。");
            return;
        }

        subscriptionService.findById(subId).ifPresent(sub -> {
            TomatoBookList book = bookListService.findByBookId(sub.getBookId()).orElse(null);
            if (book == null) {
                TomatoMessageUtil.sendGroupMessage(bot, groupId, "书籍不存在。");
                return;
            }
            try {
                contentService.updateAllChapters(book.getBookId(), false);
                String chartUrl = chartService.generateBook30DaysChart(book.getBookId(), book.getBookName());
                bot.sendGroupMsg(groupId, com.mikuac.shiro.common.utils.MsgUtils.builder()
                        .text("《" + book.getBookName() + "》最近30天更新情况为\n").img(chartUrl).build(), false);
            } catch (Exception e) {
                TomatoMessageUtil.sendGroupMessage(bot, groupId, "生成图表失败：" + e.getMessage());
            }
        });
    }
}
