package fun.luqing.dmws.mcp;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DmwMcpToolsTests {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void returnedPurchaseAndTransferFailuresAlwaysSetProtocolError() {
        var economy = mock(EconomyMcpTools.class);
        var tools = new DmwMcpTools(mock(TomatoMcpTools.class), economy, mock(ForwardMcpTools.class), mock(EconomyConditionMcpTools.class), mock(EconomyMarketMcpTools.class));
        var args = mapper.createObjectNode();
        when(economy.buy(args)).thenReturn(mapper.createObjectNode().put("ok", false).put("code", "INSUFFICIENT_BALANCE").put("message", "余额不足"));
        when(economy.transfer(args)).thenReturn(mapper.createObjectNode().put("ok", false).put("code", "SAME_ACCOUNT").put("message", "不能给自己转账"));
        for (String name : java.util.List.of("dmw_economy_buy", "dmw_economy_transfer")) {
            var result = tools.execute(name, args);
            assertTrue(result.path("isError").asBoolean());
            assertFalse(result.path("structuredContent").path("ok").asBoolean());
        }
    }

    @Test
    void routesEconomyAndTomatoToSeparateAdapters() {
        var tomato = mock(TomatoMcpTools.class);
        var economy = mock(EconomyMcpTools.class);
        var forward = mock(ForwardMcpTools.class);
        var tools = new DmwMcpTools(tomato, economy, forward, mock(EconomyConditionMcpTools.class), mock(EconomyMarketMcpTools.class));
        var args = mapper.createObjectNode();
        when(economy.shop(any())).thenReturn(mapper.createObjectNode().put("ok", true));
        assertTrue(tools.execute("dmw_economy_shop", args).path("content").get(0)
                .path("text").asText().contains("true"));
        var fishingArgs=mapper.createObjectNode().put("category","fishing");
        tools.execute("dmw_economy_shop",fishingArgs);
        verify(economy).shop(same(fishingArgs));
        tools.execute("dmw_get_book", args);
        verify(tomato).execute(eq("dmw_get_book"), same(args), any());
        verifyNoInteractions(forward);
    }

    @Test
    void routesItemUseAndMarksBusinessFailuresAsMcpErrors() throws Exception {
        var economy = mock(EconomyMcpTools.class);
        var tomato = mock(TomatoMcpTools.class);
        var tools = new DmwMcpTools(tomato, economy, mock(ForwardMcpTools.class), mock(EconomyConditionMcpTools.class), mock(EconomyMarketMcpTools.class));
        var args = mapper.createObjectNode().put("item", "红牛");
        when(economy.useItem(args)).thenReturn(mapper.createObjectNode().put("ok", true).put("message", "使用成功"));
        assertFalse(tools.execute("dmw_economy_use_item", args).path("isError").asBoolean());
        when(economy.useItem(args)).thenReturn(mapper.createObjectNode().put("ok", false).put("code", "ITEM_NOT_ENOUGH"));
        var failure = tools.execute("dmw_economy_use_item", args);
        assertTrue(failure.path("isError").asBoolean());
        assertEquals("ITEM_NOT_ENOUGH", mapper.readTree(failure.path("content").get(0).path("text").asText()).path("code").asText());
        verifyNoInteractions(tomato);
    }

    @Test
    void itemUseSchemaExposesOptionalOwnerAndBoundedQuantity() {
        tools.jackson.databind.JsonNode use = null;
        for (var definition : new McpToolCatalog().definitions()) {
            if ("dmw_economy_use_item".equals(definition.path("name").asText())) use = definition;
        }
        assertNotNull(use);
        var schema = use.path("inputSchema");
        var required = new java.util.HashSet<String>();
        schema.path("required").forEach(field -> required.add(field.asText()));
        assertEquals(java.util.Set.of("group_id", "item", "request_id"), required);
        var quantity = schema.path("properties").path("quantity");
        assertEquals(1, quantity.path("minimum").asInt());
        assertEquals(999, quantity.path("maximum").asInt());
        assertEquals(1, quantity.path("default").asInt());
        assertTrue(use.path("description").asText().contains("never the configured local operator/master"));
        assertTrue(use.path("description").asText().contains("1 point/minute"));
        assertTrue(use.path("description").asText().contains("golden-basin"));
        assertTrue(use.path("description").asText().contains("consumed_quantity"));
        assertTrue(use.path("description").asText().contains("at least20 energy stops charging"));
    }

    @Test
    void preservesExpectedGameplayErrorCodes() throws Exception {
        var catalog = new McpToolCatalog().definitions();
        var robberyDescription = java.util.stream.StreamSupport.stream(catalog.spliterator(), false)
                .filter(t -> t.path("name").asText().equals("dmw_economy_rob")).findFirst().orElseThrow().path("description").asText();
        assertTrue(robberyDescription.contains("+40 percentage points"));
        assertTrue(robberyDescription.contains("full60 minutes"));
        var economy = mock(EconomyMcpTools.class);
        var tools = new DmwMcpTools(mock(TomatoMcpTools.class), economy, mock(ForwardMcpTools.class), mock(EconomyConditionMcpTools.class), mock(EconomyMarketMcpTools.class));
        var args = mapper.createObjectNode();
        when(economy.rob(args)).thenThrow(new fun.luqing.dmws.common.economy.EconomyException("IN_JAIL", "正在服刑"));
        var result = tools.execute("dmw_economy_rob", args);
        assertTrue(result.path("isError").asBoolean());
        assertEquals("IN_JAIL", mapper.readTree(result.path("content").get(0).path("text").asText()).path("code").asText());
    }

    @Test
    void discardsPartialContentWhenAdapterFails() throws Exception {
        var tomato = mock(TomatoMcpTools.class);
        var tools = new DmwMcpTools(tomato, mock(EconomyMcpTools.class), mock(ForwardMcpTools.class), mock(EconomyConditionMcpTools.class), mock(EconomyMarketMcpTools.class));
        doThrow(new McpToolException("书籍不存在")).when(tomato).execute(anyString(), any(), any());
        var result = tools.execute("dmw_get_book", mapper.createObjectNode());
        assertTrue(result.path("isError").asBoolean());
        assertEquals("书籍不存在", mapper.readTree(result.path("content").get(0).path("text").asText()).path("message").asText());
    }
}
