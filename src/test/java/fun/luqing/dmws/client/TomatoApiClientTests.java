package fun.luqing.dmws.client;

import fun.luqing.dmws.config.ConfigManager;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TomatoApiClientTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private final McpClient downloader = mock(McpClient.class);
    private TomatoApiClient client() {
        var client = new TomatoApiClient(mock(ConfigManager.class));
        ReflectionTestUtils.setField(client,"downloader",downloader); return client;
    }
    @Test void cachedBatchNeedsNoDownloadDirectoryOrPerChapterFallback() {
        var response = mapper.createObjectNode().put("status","ready");
        response.putArray("chapters").addObject().put("item_id","901").put("chapter_order",999).put("index",77).put("content","完整正文").put("truncated",false);
        when(downloader.callToolResult(eq("tnd_get_chapters"),any())).thenReturn(response);
        var records = client().getChapterContents("123",List.of(new TomatoApiClient.ChapterTarget("901",7)));
        assertEquals(7,records.get("901").realChapterOrder());
        verify(downloader,times(1)).callToolResult(eq("tnd_get_chapters"),any());
        verifyNoMoreInteractions(downloader);
    }
    @Test void completedBatchIsRereadByOriginalIdsAndPartialContentIsKept() {
        var queued=mapper.createObjectNode().put("status","downloading").put("batch_id","batch-a");
        queued.putArray("chapters");
        var partial=mapper.createObjectNode().put("status","partial");
        partial.putArray("chapters").addObject().put("item_id","901").put("content","正文").put("truncated",false);
        when(downloader.callToolResult(eq("tnd_get_chapters"),any())).thenReturn(queued,partial);
        when(downloader.callToolResult(eq("tnd_get_batch_status"),any())).thenReturn(mapper.createObjectNode().put("status","done_with_errors"));
        var records=client().getChapterContents("123",List.of(new TomatoApiClient.ChapterTarget("901",4),new TomatoApiClient.ChapterTarget("100",5)));
        assertEquals(1,records.size());
        var args=org.mockito.ArgumentCaptor.forClass(tools.jackson.databind.node.ObjectNode.class);
        verify(downloader,times(2)).callToolResult(eq("tnd_get_chapters"),args.capture());
        assertTrue(args.getAllValues().get(0).path("download_missing").asBoolean());
        assertFalse(args.getAllValues().get(1).path("download_missing").asBoolean());
        assertEquals(args.getAllValues().get(0).path("item_ids"),args.getAllValues().get(1).path("item_ids"));
        verify(downloader).callToolResult(eq("tnd_get_batch_status"),any());
        verifyNoMoreInteractions(downloader);
    }
    @Test void changedItemAndTruncatedBodyAreNotSavedAsComplete() {
        var response=mapper.createObjectNode().put("status","partial");
        response.putArray("chapters").addObject().put("item_id","900").put("content","错误章节");
        ((tools.jackson.databind.node.ArrayNode)response.path("chapters")).addObject().put("item_id","901").put("content","截断").put("truncated",true);
        when(downloader.callToolResult(eq("tnd_get_chapters"),any())).thenReturn(response);
        assertTrue(client().getChapterContents("123",List.of(new TomatoApiClient.ChapterTarget("901",4))).isEmpty());
    }
    @Test void aDifferentBookIsRejectedBeforeItsContentCanBeUsed() {
        when(downloader.callToolResult(eq("tnd_get_chapters"),any())).thenReturn(mapper.createObjectNode().put("book_id","999").put("status","ready"));
        var result=client().requestChapters("123",List.of("901"),false,0,100);
        assertEquals("BOOK_ID_MISMATCH",result.path("code").asText());
        assertTrue(result.path("mcp_is_error").asBoolean());
    }
    @Test void numericIdsAndPositiveTotalBudgetAreMandatory() {
        var client=client();
        assertThrows(IllegalArgumentException.class,()->client.requestChapters("bad",List.of("901"),false,0,100));
        assertThrows(IllegalArgumentException.class,()->client.requestChapters("123",List.of("901"),false,0,0));
        verifyNoInteractions(downloader);
        assertFalse(TomatoApiClient.hasBody("&#32;&#160;\u200b"));
    }
}
