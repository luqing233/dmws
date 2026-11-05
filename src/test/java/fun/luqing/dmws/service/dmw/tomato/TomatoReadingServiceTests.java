package fun.luqing.dmws.service.dmw.tomato;

import fun.luqing.dmws.client.TomatoApiClient;
import fun.luqing.dmws.entity.dmw.tomato.TomatoBookContent;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TomatoReadingServiceTests {
    private final ObjectMapper mapper=new ObjectMapper();
    private final TomatoApiClient client=mock(TomatoApiClient.class);
    private final TomatoBookContentService content=mock(TomatoBookContentService.class);
    private final TomatoReadingService service=new TomatoReadingService(client,content);
    private TomatoBookContent cached(String id,int order,String body) {
        var c=new TomatoBookContent();c.setBookId("123");c.setChapterId(id);c.setRealChapterOrder(order);c.setTitle("标题"+order);
        c.setContent(body);c.setWordCount(body==null?0:body.length());return c;
    }
    @Test void completeLocalCacheDoesNotContactDownloaderOrOnlineDirectory() {
        when(content.findAllByBookIdOrderByRealChapterOrderAsc("123")).thenReturn(List.of(cached("900",7,"正文")));
        var r=service.read("123",List.of("900"),false,0,100);
        assertTrue(r.path("complete").asBoolean());assertEquals("正文",r.path("chapters").get(0).path("content").asText());
        verifyNoInteractions(client);
    }
    @Test void partialDownloadFailureKeepsAvailableTextAndOriginalRequestedOrder() {
        when(content.findAllByBookIdOrderByRealChapterOrderAsc("123")).thenReturn(List.of(cached("900",7,"已有"),cached("100",8,null),cached("200",9,null)));
        var upstream=mapper.createObjectNode().put("status","partial");
        upstream.putArray("chapters").addObject().put("item_id","100").put("chapter_order",999).put("content","新正文").put("truncated",false);
        upstream.putObject("download_error").put("code","BATCH_BUSY").put("message","另一个批次正在运行");
        when(client.requestChapters("123",List.of("100","200"),true,0,1000000)).thenReturn(upstream);
        var r=service.read("123",List.of("900","100","200"),true,0,100);
        assertEquals("partial",r.path("status").asText());assertFalse(r.path("complete").asBoolean());
        assertEquals("900",r.path("chapters").get(0).path("item_id").asText());assertEquals(8,r.path("chapters").get(1).path("order").asInt());
        assertEquals("BATCH_BUSY",r.path("download_error").path("code").asText());
        assertEquals("200",r.path("missing_item_ids").get(0).asText());
    }
    @Test void downloadingDoesNotWaitPollOrPretendToBeComplete() {
        var upstream=mapper.createObjectNode().put("status","downloading").put("batch_id","b1");upstream.putArray("chapters");
        when(client.requestChapters("123",List.of("901"),true,0,1000000)).thenReturn(upstream);
        var r=service.read("123",List.of("901"),true,0,100);
        assertEquals("downloading",r.path("status").asText());assertFalse(r.path("complete").asBoolean());
        assertEquals("b1",r.path("batch_id").asText());verify(client).requestChapters("123",List.of("901"),true,0,1000000);verifyNoMoreInteractions(client);
    }
    @Test void truncatedAndEmptyBudgetRowsRemainAvailableButNeverEnterFullBodyCache() {
        when(content.findAllByBookIdOrderByRealChapterOrderAsc("123")).thenReturn(List.of(cached("900",1,null),cached("100",2,null)));
        var upstream=mapper.createObjectNode().put("status","ready").put("truncated",true);
        var rows=upstream.putArray("chapters");rows.addObject().put("item_id","900").put("content","片").put("total_chars",300).put("truncated",true);
        rows.addObject().put("item_id","100").put("content","").put("total_chars",500).put("truncated",true);
        when(client.requestChapters("123",List.of("900","100"),false,0,1000000)).thenReturn(upstream);
        var r=service.read("123",List.of("900","100"),false,0,1);
        assertEquals(2,r.path("available_count").asInt());assertTrue(r.path("missing_item_ids").isEmpty());
        assertFalse(r.path("complete").asBoolean());assertTrue(r.path("truncated").asBoolean());
        verify(content,never()).saveOrUpdate(any());
    }
    @Test void refreshedSnapshotIsReadAgainWithoutStartingAnotherDownload() {
        var stale=mapper.createObjectNode().put("status","partial").put("refresh_required",true);stale.putArray("chapters");
        var fresh=mapper.createObjectNode().put("status","ready");fresh.putArray("chapters").addObject().put("item_id","900").put("content","新正文").put("index",999);
        when(client.requestChapters("123",List.of("900"),true,0,1000000)).thenReturn(stale);
        when(client.requestChapters("123",List.of("900"),false,0,1000000)).thenReturn(fresh);
        var r=service.read("123",List.of("900"),true,0,100);
        assertTrue(r.path("complete").asBoolean());assertFalse(r.path("chapters").get(0).has("order"));
        verify(client).requestChapters("123",List.of("900"),false,0,1000000);
    }
    @Test void totalBudgetCannotSplitSurrogatesOrBeZero() {
        when(content.findAllByBookIdOrderByRealChapterOrderAsc("123")).thenReturn(List.of(cached("900",1,"😀正文")));
        var r=service.read("123",List.of("900"),false,0,1);
        assertEquals(0,r.path("returned_chars").asInt());assertTrue(r.path("truncated").asBoolean());
        assertThrows(IllegalArgumentException.class,()->service.read("123",List.of("900"),false,0,0));
    }
    @Test void forwardNodesContainOnlyAvailableTextWithinSendLimits() {
        var r=mapper.createObjectNode().put("book_id","123").put("complete",false);
        r.putArray("chapters").addObject().put("title","正文").put("content","[CQ:at,qq=all]"+"文".repeat(90000));
        TomatoForwardFormatter.attach(r,"测试书");
        int total=0;for(var row:r.path("forward_nodes"))total+=row.asText().length();
        assertTrue(total<=49000);assertTrue(r.path("forward_nodes").size()<=100);
        assertTrue(r.path("forward_nodes").get(1).asText().contains("[CQ:at,qq=all]"));
        assertTrue(r.path("forward_truncated").asBoolean());assertFalse(r.path("forward_complete").asBoolean());
    }
}
