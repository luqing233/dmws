package fun.luqing.dmws.plugin.tomatoplugin.command;

import com.mikuac.shiro.annotation.GroupMessageHandler;
import com.mikuac.shiro.annotation.MessageHandlerFilter;
import com.mikuac.shiro.annotation.common.Shiro;
import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.dto.event.message.GroupMessageEvent;
import fun.luqing.dmws.client.TomatoContentRecord;
import fun.luqing.dmws.entity.dmw.tomato.TomatoBookContent;
import fun.luqing.dmws.entity.dmw.tomato.TomatoBookList;
import fun.luqing.dmws.entity.dmw.tomato.TomatoSubscriptionGroup;
import fun.luqing.dmws.service.dmw.tomato.TomatoBookContentService;
import fun.luqing.dmws.service.dmw.tomato.TomatoBookListService;
import fun.luqing.dmws.service.dmw.tomato.TomatoSubscriptionGroupService;
import fun.luqing.dmws.client.TomatoApiClient;
import fun.luqing.dmws.common.annotation.CommandInfo;
import fun.luqing.dmws.config.ConfigManager;
import fun.luqing.dmws.plugin.tomatoplugin.util.TomatoMessageUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.mikuac.shiro.common.utils.ShiroUtils.generateForwardMsg;

@Shiro
@Slf4j
@Component
@RequiredArgsConstructor
public class ChapterContentCommand {

    private final TomatoBookContentService contentService;
    private final ConfigManager configManager;
    private final TomatoBookListService bookListService;
    private final TomatoSubscriptionGroupService subscriptionService;
    private final fun.luqing.dmws.service.dmw.tomato.TomatoReadingService readingService;

    @GroupMessageHandler
    @MessageHandlerFilter(startWith = "获取章节内容")
    @Async("taskExecutor")
    @CommandInfo(startWith = "获取章节内容", desc = "获取章节内容 <订阅ID> <章节序号> [下载]，默认仅本地读取")
    public void getChapterContent(Bot bot, GroupMessageEvent event) {
        long groupId = event.getGroupId();
        String[] params = event.getMessage().replace("获取章节内容", "").trim().split("\\s+");
        if (params.length < 2) {
            TomatoMessageUtil.sendGroupMessage(bot, groupId, "请输入订阅ID和章节序号，例如：获取章节内容 1 10");
            return;
        }

        long subId;
        int chapterIndex;
        try {
            subId = Long.parseLong(params[0]);
            chapterIndex = Integer.parseInt(params[1]);
        } catch (NumberFormatException e) {
            TomatoMessageUtil.sendGroupMessage(bot, groupId, "格式错误，请输入数字ID和章节号。");
            return;
        }

        Optional<TomatoSubscriptionGroup> subOpt = subscriptionService.findById(subId);
        if (subOpt.isEmpty() || subOpt.get().getGroupId() != groupId) {
            TomatoMessageUtil.sendGroupMessage(bot, groupId, "未找到对应订阅。");
            return;
        }

        TomatoSubscriptionGroup sub = subOpt.get();
        String bookId = sub.getBookId();
        TomatoBookList book = bookListService.findByBookId(bookId).orElse(null);
        if (book == null) {
            TomatoMessageUtil.sendGroupMessage(bot, groupId, "书籍不存在。");
            return;
        }

        List<TomatoBookContent> chapters = contentService.findAllByBookIdOrderByRealChapterOrderAsc(bookId);
        TomatoBookContent chapter = chapters.stream().filter(c -> c.getRealChapterOrder() != null
                && c.getRealChapterOrder() == chapterIndex).findFirst().orElse(null);
        if (chapter == null) {
            TomatoMessageUtil.sendGroupMessage(bot, groupId, "本地目录未找到该章节序号，请先更新目录。");
            return;
        }

        boolean download = params.length == 3 && ("下载".equals(params[2]) || "下载缺失".equals(params[2]));
        if (params.length > 3 || (params.length == 3 && !download)) {
            TomatoMessageUtil.sendGroupMessage(bot, groupId, "格式：获取章节内容 <订阅ID> <章节序号> [下载]");
            return;
        }
        try {
            var result = readingService.read(bookId, List.of(chapter.getChapterId()), download, 0, 45000);
            fun.luqing.dmws.service.dmw.tomato.TomatoForwardFormatter.attach(result, book.getBookName());
            var nodes = result.path("forward_nodes");
            if (!nodes.isEmpty()) {
                List<Map<String,Object>> messages = new ArrayList<>();
                for (var node : nodes) {
                    messages.add(Map.of("type","node","data",Map.of("uin",String.valueOf(bot.getSelfId()),
                            "name",configManager.getDmwConfig().getBotName(),"content",List.of(Map.of(
                            "type","text","data",Map.of("text",node.asText()))))));
                }
                var response = bot.sendGroupForwardMsg(groupId, messages);
                if (response == null || !Integer.valueOf(0).equals(response.getRetCode()) || response.getData() == null) {
                    TomatoMessageUtil.sendGroupMessage(bot,groupId,"正文转发未获成功确认，不会自动重发，请稍后查询。");
                    return;
                }
            }
            if (!result.path("complete").asBoolean() || result.path("forward_truncated").asBoolean()) {
                String message = result.path("display_text").asText();
                if (result.hasNonNull("batch_id")) message += "\n下载批次：" + result.path("batch_id").asText() + "，请按此批次查询；完成后重新获取本章。";
                if (!download && !result.path("missing_item_ids").isEmpty()) message += "\n如需下载，请发送：获取章节内容 " + subId + " " + chapterIndex + " 下载";
                if (result.path("forward_truncated").asBoolean()) message += "\n合并转发已截断，请分段读取。";
                TomatoMessageUtil.sendGroupMessage(bot,groupId,message);
            }
        } catch (RuntimeException e) {
            log.warn("正文获取失败，bookId={}, chapterId={}",bookId,chapter.getChapterId(),e);
            TomatoMessageUtil.sendGroupMessage(bot,groupId,"获取正文未获确认，请稍后查询；不能认定下载或转发已经成功。");
        }
    }
}
