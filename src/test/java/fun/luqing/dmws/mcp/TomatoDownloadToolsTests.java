package fun.luqing.dmws.mcp;

import fun.luqing.dmws.config.DmwConfig;
import fun.luqing.dmws.service.dmw.tomato.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TomatoDownloadToolsTests {
    private final ObjectMapper mapper=new ObjectMapper();
    private final TomatoReadingService reading=mock(TomatoReadingService.class);
    private TomatoMcpTools adapter() {
        var config=new DmwConfig();config.setMaster(new long[]{7});
        return new TomatoMcpTools(config,mock(TomatoBookListService.class),mock(TomatoBookContentService.class),
                mock(TomatoSubscriptionGroupService.class),mock(TomatoBookChartService.class),mock(BookUpdateJobs.class),mock(BookSubscriptionJobs.class),reading);
    }
    private tools.jackson.databind.JsonNode execute(String tool, tools.jackson.databind.node.ObjectNode args) {
        var blocks=mapper.createArrayNode();adapter().execute(tool,args,blocks);return mapper.readTree(blocks.get(0).path("text").asText());
    }
    @Test void cancellationReportsPendingAndSignalFailureRatherThanCanceled() {
        var args=mapper.createObjectNode().put("batch_id","old").put("operator",7).put("confirm",true);
        when(reading.batch("tnd_cancel_batch","old",false)).thenReturn(mapper.createObjectNode().put("status","canceling").put("cancel_error","failed signal"));
        var r=execute("dmw_cancel_download_batch",args);
        assertEquals("canceling",r.path("status").asText());assertTrue(r.path("display_text").asText().contains("尚未确认已停止"));
        assertFalse(r.path("content_verified").asBoolean());
    }
    @Test void retryCarriesNewBatchIdAndDoesNotResubmitAnActiveBatch() {
        var args=mapper.createObjectNode().put("batch_id","old").put("operator",7).put("confirm",true);
        when(reading.batch("tnd_retry_batch","old",false)).thenReturn(mapper.createObjectNode().put("status","running").put("batch_id","new").put("retry_of","old"));
        var r=execute("dmw_retry_download_batch",args);
        assertEquals("new",r.path("batch_id").asText());assertEquals("old",r.path("retry_of").asText());
        when(reading.batch("tnd_retry_batch","old",false)).thenReturn(mapper.createObjectNode().put("mcp_is_error",true).put("code","BATCH_ACTIVE"));
        assertFalse(execute("dmw_retry_download_batch",args).path("ok").asBoolean());
        verify(reading,times(2)).batch("tnd_retry_batch","old",false);
    }
    @Test void explicitBatchConfirmationAndMasterAreRequiredBeforeMutation() {
        assertThrows(McpToolException.class,()->execute("dmw_cancel_download_batch",mapper.createObjectNode().put("batch_id","old").put("operator",7)));
        assertThrows(McpToolException.class,()->execute("dmw_retry_download_batch",mapper.createObjectNode().put("operator",8).put("confirm",true).put("batch_id","old")));
        verifyNoInteractions(reading);
    }
    @Test void diagnosticNegativeOutcomeIsNotTransportFailure() {
        var diagnostic=mapper.createObjectNode().put("ok",false); diagnostic.putObject("worker").put("status","stale");
        when(reading.diagnostics()).thenReturn(diagnostic);
        var r=execute("dmw_get_downloader_diagnostics",mapper.createObjectNode());
        assertTrue(r.path("ok").asBoolean());assertFalse(r.path("diagnostics_ok").asBoolean());
    }
    @Test void bulkReadDoesNotDownloadUnlessRequestedAndCanPrepareForwardNodes() {
        var args=mapper.createObjectNode().put("book_id","123").put("include_forward_nodes",true);args.putArray("item_ids").add("901");
        var ready=mapper.createObjectNode().put("book_id","123").put("status","ready").put("complete",true);
        ready.putArray("chapters").addObject().put("title","章").put("content","正文");
        when(reading.read("123",List.of("901"),false,0,100000)).thenReturn(ready);
        var r=execute("dmw_get_chapters",args);assertFalse(r.path("forward_nodes").isEmpty());assertTrue(r.path("forward_complete").asBoolean());
    }
}
