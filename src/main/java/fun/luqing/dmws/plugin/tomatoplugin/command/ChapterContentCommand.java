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
import fun.luqing.dmws.service.tomatoservice.TomatoBookContentService;
import fun.luqing.dmws.service.tomatoservice.TomatoBookListService;
import fun.luqing.dmws.service.tomatoservice.TomatoSubscriptionGroupService;
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
    private final TomatoApiClient tomatoApiClient;

    @GroupMessageHandler
    @MessageHandlerFilter(startWith = "获取章节内容")
    @Async("taskExecutor")
    @CommandInfo(startWith = "获取章节内容", desc = "根据订阅ID和章节序号获取章节内容")
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
        if (chapterIndex < 1 || chapterIndex > chapters.size()) {
            TomatoMessageUtil.sendGroupMessage(bot, groupId, "章节序号超出范围。");
            return;
        }

        TomatoBookContent chapter = chapters.get(chapterIndex - 1);

        if (chapter.getContent() == null) {
            TomatoContentRecord record = tomatoApiClient.getChapterContent(
                    chapter.getBookId(), chapter.getChapterId(), chapter.getRealChapterOrder());
            if (record != null) {
                chapter.setContent(record.content());
                chapter.setWordCount(record.wordCount());
                contentService.saveOrUpdate(chapter);
            } else {
                TomatoMessageUtil.sendGroupMessage(bot, groupId, "无法获取章节内容，请稍后再试。");
                return;
            }
        }

        Document doc = Jsoup.parse(chapter.getContent());
        Elements h1s = doc.select("h1");
        String header = chapter.getTitle();
        if (!h1s.isEmpty()) header = h1s.first().text();
        Elements paragraphs = doc.select("article p");
        if (paragraphs.isEmpty()) paragraphs = doc.select("body > p");

        List<String> messageList = new ArrayList<>();
        messageList.add("《" + book.getBookName() + "》");
        messageList.add(header);
        messageList.add("共 " + chapter.getWordCount() + " 字");
        for (Element p : paragraphs) {
            String text = p.text();
            if (!text.isEmpty()) messageList.add(text);
        }

        List<Map<String, Object>> forwardMsg = generateForwardMsg(
                configManager.getDmwConfig().getBot_id(),
                configManager.getDmwConfig().getBot_name(),
                messageList
        );
        bot.sendGroupForwardMsg(groupId, forwardMsg);
    }
}
