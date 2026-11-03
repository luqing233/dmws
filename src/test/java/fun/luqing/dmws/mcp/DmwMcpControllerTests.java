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

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private DmwMcpTools tools;

    @Mock
    private DmwConfig dmwConfig;

    @Test
    void listsToolsWithDistinctSearchDescriptions() {
        when(dmwConfig.isMcpServerEnabled()).thenReturn(true);
        when(dmwConfig.getMcpServerToken()).thenReturn("Bearer test");

        DmwMcpController controller = new DmwMcpController(tools, dmwConfig);
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
    }
}
