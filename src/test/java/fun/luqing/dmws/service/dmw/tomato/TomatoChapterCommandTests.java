package fun.luqing.dmws.service.dmw.tomato;

import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.dto.action.common.ActionData;
import com.mikuac.shiro.dto.action.common.MsgId;
import com.mikuac.shiro.dto.event.message.GroupMessageEvent;
import fun.luqing.dmws.config.ConfigManager;
import fun.luqing.dmws.config.DmwConfig;
import fun.luqing.dmws.entity.dmw.tomato.*;
import fun.luqing.dmws.plugin.tomatoplugin.command.ChapterContentCommand;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import static org.mockito.Mockito.*;

class TomatoChapterCommandTests {
    private final ObjectMapper mapper=new ObjectMapper();
    private final Bot bot=mock(Bot.class);
    private final TomatoReadingService reading=mock(TomatoReadingService.class);
    private final TomatoBookContentService content=mock(TomatoBookContentService.class);
    private ChapterContentCommand command() {
        var config=mock(ConfigManager.class);var settings=new DmwConfig();settings.setBotName("DMW");
        when(config.getDmwConfig()).thenReturn(settings);
        var books=mock(TomatoBookListService.class);var subscriptions=mock(TomatoSubscriptionGroupService.class);
        var sub=new TomatoSubscriptionGroup();sub.setGroupId(300L);sub.setBookId("123");
        when(subscriptions.findById(1L)).thenReturn(Optional.of(sub));
        var book=new TomatoBookList();book.setBookId("123");book.setBookName("测试书");when(books.findByBookId("123")).thenReturn(Optional.of(book));
        var chapter=new TomatoBookContent();chapter.setBookId("123");chapter.setChapterId("901");chapter.setRealChapterOrder(7);
        when(content.findAllByBookIdOrderByRealChapterOrderAsc("123")).thenReturn(List.of(chapter));
        return new ChapterContentCommand(content,config,books,subscriptions,reading);
    }
    private GroupMessageEvent event(String suffix) {
        var event=new GroupMessageEvent();event.setGroupId(300L);event.setMessage("获取章节内容 1 7"+suffix);return event;
    }
    @Test void queuedContentProducesOnlyPendingReceiptAndNoEmptyForward() {
        var queued=mapper.createObjectNode().put("status","downloading").put("complete",false).put("batch_id","batch-a").put("display_text","缺失正文已排队，尚未下载完成");
        queued.putArray("chapters");queued.putArray("missing_item_ids").add("901");
        when(reading.read("123",List.of("901"),true,0,45000)).thenReturn(queued);
        command().getChapterContent(bot,event(" 下载"));
        verify(bot,never()).sendGroupForwardMsg(anyLong(),anyList());
        verify(bot).sendGroupMsg(eq(300L),contains("尚未下载完成"),eq(false));
    }
    @Test void readyLocalChapterIsForwardedAsLiteralTextWithoutDownloading() {
        var ready=mapper.createObjectNode().put("book_id","123").put("complete",true);
        ready.putArray("chapters").addObject().put("title","第七章").put("content","[CQ:at,qq=all] 正文");
        when(reading.read("123",List.of("901"),false,0,45000)).thenReturn(ready);
        var response=new ActionData<MsgId>();response.setRetCode(0);var id=new MsgId();id.setMessageId(123);response.setData(id);
        when(bot.sendGroupForwardMsg(eq(300L),anyList())).thenReturn(response);
        command().getChapterContent(bot,event(""));
        verify(reading).read("123",List.of("901"),false,0,45000);
        verify(bot).sendGroupForwardMsg(eq(300L),argThat(nodes->nodes.toString().contains("type=text") && nodes.toString().contains("[CQ:at,qq=all]")));
        verify(bot,never()).sendGroupMsg(anyLong(),anyString(),anyBoolean());
    }
    @Test void ambiguousSendOutcomeIsNotRetriedOrReportedAsSuccessful() {
        var ready=mapper.createObjectNode().put("book_id","123").put("complete",true);
        ready.putArray("chapters").addObject().put("content","正文");
        when(reading.read("123",List.of("901"),false,0,45000)).thenReturn(ready);
        command().getChapterContent(bot,event(""));
        verify(bot,times(1)).sendGroupForwardMsg(eq(300L),anyList());
        verify(bot).sendGroupMsg(eq(300L),contains("未获成功确认"),eq(false));
    }
}
