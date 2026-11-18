package fun.luqing.dmws.plugin;

import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.dto.action.common.ActionData;
import com.mikuac.shiro.dto.action.common.MsgId;
import com.mikuac.shiro.dto.event.message.AnyMessageEvent;
import fun.luqing.dmws.config.DmwConfig;
import fun.luqing.dmws.service.old.SeTuService;
import fun.luqing.dmws.service.old.SeTuImageService;
import fun.luqing.dmws.service.old.SeTuImageService.ImageResult;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SeTuPluginTests {
    final Bot bot = mock(Bot.class);
    final SeTuService service = mock(SeTuService.class);
    final SeTuImageService downloads = mock(SeTuImageService.class);
    final DmwConfig config = new DmwConfig();
    final SeTuPlugin plugin = new SeTuPlugin(service, downloads, config);
    final ImageResult image = new ImageResult("星空[CQ:at,qq=all]", "画师", 123, "https://example.test/image.jpg", Path.of("cached.jpg"), null);

    AnyMessageEvent event(String text) {
        var event = new AnyMessageEvent();
        event.setUserId(3L); event.setGroupId(4L); event.setMessageId(5); event.setMessage(text); event.setMessageType("group");
        config.setBotId(9); config.setBotName("DMW"); config.setFileServerHost("127.0.0.1");
        when(bot.getSelfId()).thenReturn(9L);
        when(service.getLoliconImageByKeyword(anyList(), isNull(), anyInt(), anyInt(), anyList(), isNull(), anyBoolean()))
                .thenReturn(new JSONArray().put(new JSONObject().put("pid", 123)));
        when(downloads.download(any(JSONArray.class), anyList())).thenReturn(List.of(image));
        return event;
    }
    ActionData<MsgId> success() {
        var result = new ActionData<MsgId>(); result.setRetCode(0);
        var id = new MsgId(); id.setMessageId(42); result.setData(id); return result;
    }
    ActionData<MsgId> failure(int code) { var result = new ActionData<MsgId>(); result.setRetCode(code); return result; }
    boolean hasImage(List<Map<String,Object>> nodes) { return nodes.toString().contains("[CQ:image,") || nodes.toString().contains("type=image"); }
    @SuppressWarnings({"unchecked", "rawtypes"})
    List<List<Map<String,Object>>> groupForwards(int count) {
        ArgumentCaptor<List<Map<String,Object>>> captor = ArgumentCaptor.forClass((Class) List.class);
        verify(bot, times(count)).sendGroupForwardMsg(eq(4L), captor.capture()); return captor.getAllValues();
    }

    @Test void usesAnOverviewAndOneCompleteNodePerImage() {
        var event = event("涩图 tag=风景");
        when(bot.sendGroupForwardMsg(eq(4L), anyList())).thenReturn(success());
        plugin.getSeTu(bot, event);
        var nodes = groupForwards(1).getFirst();
        assertEquals(2, nodes.size());
        assertTrue(nodes.getFirst().toString().contains("已加载 1 张"));
        assertTrue(nodes.get(1).toString().contains("画师") && nodes.get(1).toString().contains("pixiv.net/artworks/123"));
        assertTrue(hasImage(nodes)); assertFalse(nodes.toString().contains("type=at"));
        verify(bot, times(1)).sendMsg(eq(event), anyString(), eq(false));
        verify(bot, never()).sendForwardMsg(any(AnyMessageEvent.class), anyList());
    }

    @Test void rejectedImagesFallBackToTextOnlyForwardWithBothLinks() {
        var event = event("涩图 tag=风景");
        when(bot.sendGroupForwardMsg(eq(4L), anyList())).thenReturn(failure(1200), success());
        plugin.getSeTu(bot, event);
        var forwards = groupForwards(2);
        assertTrue(hasImage(forwards.getFirst())); assertFalse(hasImage(forwards.getLast()));
        String info = forwards.getLast().toString();
        assertTrue(info.contains("作品信息") && info.contains("pixiv.net/artworks/123") && info.contains("example.test/image.jpg"));
        assertFalse(info.contains("[CQ:at") || info.contains("cached.jpg"));
        verify(bot, times(1)).sendMsg(eq(event), anyString(), eq(false));
    }

    @Test void totalDownloadFailureForwardsMetadataWithoutAttemptingImages() {
        var event = event("涩图 tag=风景");
        when(downloads.download(any(JSONArray.class), anyList())).thenReturn(List.of(
                new ImageResult("星空", "画师", 123, "https://example.test/image.jpg", null, "图片下载超时")));
        when(bot.sendGroupForwardMsg(eq(4L), anyList())).thenReturn(success());
        plugin.getSeTu(bot, event);
        var nodes = groupForwards(1).getFirst();
        assertFalse(hasImage(nodes)); assertTrue(nodes.toString().contains("图片下载超时"));
        assertTrue(nodes.toString().contains("星空") && nodes.toString().contains("example.test/image.jpg"));
        verify(bot, times(1)).sendMsg(eq(event), anyString(), eq(false));
    }

    @Test void parsesR18BeforeCheckingPermissionsAndDoesNotQueryGroupForPrivateMessages() {
        var event = event("涩图 tag=风景 r18=1"); event.setGroupId(null); event.setMessageType("private");
        plugin.getSeTu(bot, event);
        verifyNoInteractions(service, downloads);
        verify(bot, never()).getGroupMemberInfo(anyLong(), anyLong(), anyBoolean());
        verify(bot).sendMsg(eq(event), contains("仅限"), eq(false));
    }

    @Test void partialDownloadKeepsFailedWorkAndItsLinksInsideTheForward() {
        var event = event("涩图 风景 num=2");
        when(downloads.download(any(JSONArray.class), anyList())).thenReturn(List.of(image,
                new ImageResult("海边", "作者乙", 456, "https://example.test/2.jpg", null, "图片下载超时")));
        when(bot.sendGroupForwardMsg(eq(4L), anyList())).thenReturn(success());
        plugin.getSeTu(bot, event);
        var nodes = groupForwards(1).getFirst();
        assertEquals(3, nodes.size()); assertTrue(nodes.getFirst().toString().contains("未加载 1 张"));
        assertTrue(nodes.get(2).toString().contains("图片下载超时") && nodes.get(2).toString().contains("example.test/2.jpg"));
    }

    @Test void forwardExceptionsStillAttemptOneSeparateTextOnlyForward() {
        var event = event("涩图 风景");
        when(bot.sendGroupForwardMsg(eq(4L), anyList())).thenThrow(new IllegalStateException("transport unavailable")).thenReturn(success());
        plugin.getSeTu(bot, event);
        var forwards = groupForwards(2);
        assertFalse(hasImage(forwards.getLast())); assertTrue(forwards.getLast().toString().contains("尚未确认"));
        verify(bot, times(1)).sendMsg(eq(event), anyString(), eq(false));
    }

    @Test void emptySearchAndUpstreamFailureStayDirectAndDoNotForward() {
        var event = event("涩图 风景");
        when(service.getLoliconImageByKeyword(anyList(), isNull(), anyInt(), anyInt(), anyList(), isNull(), anyBoolean()))
                .thenReturn(new JSONArray(), new JSONArray().put(new JSONObject().put("error", "internal exception details")));
        plugin.getSeTu(bot, event); plugin.getSeTu(bot, event);
        verify(bot).sendMsg(eq(event), contains("没有找到"), eq(false));
        verify(bot).sendMsg(eq(event), contains("暂时不可用"), eq(false));
        verifyNoInteractions(downloads);
        verify(bot, never()).sendGroupForwardMsg(anyLong(), anyList());
    }

    @Test void privateMessagesUseThePrivateForwardApi() {
        var event = event("涩图 风景"); event.setGroupId(null); event.setMessageType("private");
        when(bot.sendPrivateForwardMsg(eq(3L), anyList())).thenReturn(success());
        plugin.getSeTu(bot,event);
        verify(bot).sendPrivateForwardMsg(eq(3L), argThat(this::hasImage));
        verify(bot, never()).sendGroupForwardMsg(anyLong(),anyList());
        verify(bot, never()).sendForwardMsg(any(AnyMessageEvent.class),anyList());
    }

    @Test void imageTimeoutNeverResendsImagesOrClaimsTheyWereRejected() {
        var event = event("涩图 风景");
        when(bot.sendGroupForwardMsg(eq(4L),anyList())).thenReturn(failure(-1),success());
        plugin.getSeTu(bot,event);
        var forwards=groupForwards(2);
        assertTrue(hasImage(forwards.getFirst())); assertFalse(hasImage(forwards.getLast()));
        assertTrue(forwards.getLast().toString().contains("回执尚未确认"));
    }

    @Test void bothExplicitRejectionsEndWithAShortNoticeWithoutDumpingLinks() {
        var event=event("涩图 风景");
        when(bot.sendGroupForwardMsg(eq(4L),anyList())).thenReturn(failure(1200));
        plugin.getSeTu(bot,event);groupForwards(2);
        verify(bot).sendMsg(eq(event),argThat((String text)->text.contains("QQ 未能发送作品信息") && !text.contains("example.test") && !text.contains("pixiv.net")),eq(false));
    }

    @Test void aTextForwardTimeoutDoesNotRetryTheUncertainForward() {
        var event=event("涩图 风景");
        when(bot.sendGroupForwardMsg(eq(4L),anyList())).thenReturn(failure(1200),failure(-1));
        plugin.getSeTu(bot,event);groupForwards(2);
        verify(bot).sendMsg(eq(event),contains("作品信息转发的回执尚未确认"),eq(false));
    }

    @Test void successWithoutAMessageIdIsUncertainRatherThanConfirmed() {
        var event=event("涩图 风景");var missingId=success();missingId.getData().setMessageId(null);
        when(bot.sendGroupForwardMsg(eq(4L),anyList())).thenReturn(missingId,success());
        plugin.getSeTu(bot,event);
        assertTrue(groupForwards(2).getLast().toString().contains("回执尚未确认"));
    }
}
