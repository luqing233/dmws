package fun.luqing.dmws.mcp;

import fun.luqing.dmws.config.DmwConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DmwMcpControllerTests {

    @Test
    void expiredSessionRequestsReinitializeAndClosedSessionsAreRemoved() {
        var config = new DmwConfig(); config.setMcpServerEnabled(true); config.setMcpServerToken("Bearer test");
        var controller = new DmwMcpController(tools, config, new McpToolCatalog(), new EconomyMcpContext(config));
        var ping = MAPPER.createObjectNode().put("method", "ping").put("id", 1);
        assertEquals(404, controller.handle(ping,"Bearer test","old-session",new MockHttpServletResponse()).getStatusCode().value());
        var response = new MockHttpServletResponse();
        controller.handle(MAPPER.createObjectNode().put("method","initialize").put("id",2),"Bearer test",null,response);
        String session = response.getHeader("mcp-session-id");
        assertEquals(204, controller.closeSession("Bearer test",session).getStatusCode().value());
        assertEquals(404, controller.handle(ping,"Bearer test",session,new MockHttpServletResponse()).getStatusCode().value());
        assertEquals(405, controller.noEventStream().getStatusCode().value());
    }

    @Test
    void sharedBearerAloneCannotAuthorizeEconomyWrites() {
        DmwConfig config = new DmwConfig(); config.setMcpServerEnabled(true); config.setMcpServerToken("Bearer test");
        var controller = new DmwMcpController(tools, config, new McpToolCatalog(), new EconomyMcpContext(config));
        var init = MAPPER.createObjectNode().put("method", "initialize").put("id", 1);
        var response = new MockHttpServletResponse(); controller.handle(init, "Bearer test", null, response);
        var call = MAPPER.createObjectNode().put("method", "tools/call").put("id", 2);
        call.putObject("params").put("name", "dmw_economy_buy").putObject("arguments").put("user_id", 3253912136L);
        var result = controller.handle(call, "Bearer test", response.getHeader("mcp-session-id"), new MockHttpServletResponse());
        assertTrue(String.valueOf(result.getBody()).contains("UNTRUSTED_CONTEXT"));
        org.mockito.Mockito.verifyNoInteractions(tools);
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private DmwMcpTools tools;

    @Mock
    private DmwConfig dmwConfig;

    @Test
    void listsToolsWithDistinctSearchDescriptions() {
        when(dmwConfig.isMcpServerEnabled()).thenReturn(true);
        when(dmwConfig.getMcpServerToken()).thenReturn("Bearer test");

        DmwMcpController controller = new DmwMcpController(tools, dmwConfig, new McpToolCatalog(), new EconomyMcpContext(new DmwConfig()));
        MockHttpServletResponse initResponse = new MockHttpServletResponse();

        ObjectNode initialize = MAPPER.createObjectNode();
        initialize.put("jsonrpc", "2.0");
        initialize.put("id", 1);
        initialize.put("method", "initialize");

        ResponseEntity<?> initialized =
                controller.handle(initialize, "Bearer test", null, initResponse);
        assertEquals(200, initialized.getStatusCode().value());
        String sessionId = initResponse.getHeader("mcp-session-id");
        assertNotNull(sessionId);

        ObjectNode list = MAPPER.createObjectNode();
        list.put("jsonrpc", "2.0");
        list.put("id", 2);
        list.put("method", "tools/list");

        ResponseEntity<?> response =
                controller.handle(list, "Bearer test", sessionId, new MockHttpServletResponse());
        assertEquals(200, response.getStatusCode().value());
        String body = String.valueOf(response.getBody());
        assertTrue(body.contains("dmw_search_books"));
        assertTrue(body.contains("tnd_search_books"));
        assertTrue(body.contains("does NOT call Fanqie"));
        assertTrue(body.contains("dmw_economy_transfer"));
        assertTrue(body.contains("dmw_economy_rob"));
        assertTrue(body.contains("dmw_economy_buy"));
        assertTrue(body.contains("dmw_economy_use_item"));
        assertTrue(body.contains("dmw_economy_status"));
        assertTrue(body.contains("dmw_economy_get_location"));
        assertTrue(body.contains("dmw_economy_discharge"));
        assertTrue(body.contains("dmw_economy_bail"));
        assertTrue(body.contains("manual_usable"));
        assertTrue(body.contains("as_bot"));
        assertTrue(body.contains("dmw_economy_shop"));
        assertTrue(body.contains("dmw_economy_balance"));
        assertTrue(body.contains("dmw_economy_backpack"));
        assertTrue(body.contains("dmw_get_update_job"));
        assertTrue(body.contains("red-bull or 红牛"));
    }
}
