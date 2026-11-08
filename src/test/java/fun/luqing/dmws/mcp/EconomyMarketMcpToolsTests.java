package fun.luqing.dmws.mcp;

import fun.luqing.dmws.common.economy.EconomyException;
import fun.luqing.dmws.common.economy.EconomyMessageOperation;
import fun.luqing.dmws.config.DmwConfig;
import fun.luqing.dmws.entity.dmw.economy.EconomyMarketPosition.Side;
import fun.luqing.dmws.enums.EconomyFxPair;
import fun.luqing.dmws.service.dmw.economy.EconomyMarketService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EconomyMarketMcpToolsTests {
    final ObjectMapper mapper = new ObjectMapper();
    final EconomyMarketService market = mock(EconomyMarketService.class);
    final EconomyMcpTools identity = mock(EconomyMcpTools.class);
    final EconomyMarketMcpTools adapter = new EconomyMarketMcpTools(market, identity);
    DmwMcpTools router() { return new DmwMcpTools(mock(TomatoMcpTools.class), identity, mock(ForwardMcpTools.class), mock(EconomyConditionMcpTools.class), adapter); }

    @Test void catalogAndRoutingExposeAllSixToolsAndPropagateFailures() {
        var catalog = new McpToolCatalog().definitions();
        for (String suffix : List.of("market", "positions", "history", "open", "close", "repay")) {
            String name = "dmw_economy_fx_" + suffix;
            assertTrue(java.util.stream.StreamSupport.stream(catalog.spliterator(), false).anyMatch(d -> name.equals(d.path("name").asText())));
        }
        when(market.market()).thenThrow(new EconomyException("MARKET_DISABLED", "未开放"));
        var result = router().execute("dmw_economy_fx_market", mapper.createObjectNode());
        assertTrue(result.path("isError").asBoolean());
        assertEquals("MARKET_DISABLED", result.path("structuredContent").path("code").asText());
    }

    @Test void everyFxWriteRequiresSignedContextAndDefaultsAreCoveredByContextTests() {
        var guard = new EconomyMcpContext(new DmwConfig());
        for (String name : List.of("dmw_economy_fx_open", "dmw_economy_fx_close", "dmw_economy_fx_repay")) {
            assertTrue(EconomyMcpContext.WRITES.contains(name));
            assertThrows(McpToolException.class, () -> guard.authorize(name, mapper.createObjectNode().put("user_id", 123)));
            assertThrows(McpToolException.class, () -> adapter.execute(name, mapper.createObjectNode()));
        }
        verifyNoInteractions(market);
    }

    @Test void pricesAndAmountsAreExactDecimalStringsAndQuotesAreMarkedSimulated() {
        when(market.market()).thenReturn(new EconomyMarketService.MarketView(1L, LocalDateTime.of(2026,11,9,10,0), true,
                List.of(new EconomyMarketService.QuoteView("EUR/USD", "欧元/美元", new BigDecimal("1.08000000"), BigDecimal.ZERO))));
        var result = router().execute("dmw_economy_fx_market", mapper.createObjectNode()).path("structuredContent");
        assertTrue(result.path("ok").asBoolean()); assertEquals("SIMULATED", result.path("data").path("source").asText());
        assertEquals(10, result.path("data").path("interval_minutes").asInt());
        assertEquals("1.08000000", result.path("data").path("pairs").get(0).path("price").asText());
        assertTrue(result.path("data").path("pairs").get(0).path("price").isTextual());
    }

    @Test void openingUsesSameCanonicalMessageIdAsNativeCommand() {
        var args = mapper.createObjectNode().put("user_id", 123).put("group_id", 456).put("_source_message_id", -77)
                .put("request_id", "r1").put("pair", "EUR/USD").put("side", "long").put("margin", "500").put("leverage", 20);
        when(identity.actingUser(args)).thenReturn(123L); when(identity.requestId(args)).thenReturn("r1");
        when(identity.messageOperation(anyString(), any(), anyLong(), anyString(), anyString(), anyString())).thenCallRealMethod();
        when(identity.requiredLong(args, "group_id")).thenReturn(456L); when(identity.requiredLong(args, "_source_message_id")).thenReturn(-77L);
        String expected = EconomyMessageOperation.id("fx-open", 456, -77, 123, "");
        when(market.open(123, "", EconomyFxPair.EUR_USD, Side.LONG, new BigDecimal("500.00"), 20, expected))
                .thenReturn(new EconomyMarketService.ActionResult(9L, new BigDecimal("501.50"), new BigDecimal("0.00"), "开仓成功 #9"));
        var response = adapter.execute("dmw_economy_fx_open", args);
        assertEquals("开仓成功 #9", response.path("display_text").asText());
        assertEquals("501.50", response.path("data").path("amount").asText());
        args.put("pair", "USD/JPY").put("side", "short");
        when(market.open(123, "", EconomyFxPair.USD_JPY, Side.SHORT, new BigDecimal("500.00"), 20, expected))
                .thenThrow(new EconomyException("OPERATION_CONFLICT", "同一消息不能修改开仓参数后重复执行"));
        assertEquals("OPERATION_CONFLICT", router().execute("dmw_economy_fx_open", args).path("structuredContent").path("code").asText());
    }

    @Test void malformedLeverageNeverReachesBusinessService() {
        var args = mapper.createObjectNode().put("_source_message_id", -77).put("pair", "EUR/USD").put("side", "long")
                .put("margin", "500").put("leverage", 20.5);
        assertThrows(McpToolException.class, () -> adapter.execute("dmw_economy_fx_open", args)); verifyNoInteractions(market);
    }

    @Test void positionsHistoryCloseAndRepaymentUseTheirOwnRoutes() {
        var args = mapper.createObjectNode().put("user_id", 123).put("group_id", 456).put("_source_message_id", -77)
                .put("request_id", "r1").put("position_id", 9).put("amount", "all");
        when(identity.actingUser(args)).thenReturn(123L); when(identity.requestId(args)).thenReturn("r1");
        when(identity.requiredLong(args, "position_id")).thenReturn(9L);
        when(identity.messageOperation(anyString(), any(), anyLong(), anyString(), anyString(), anyString())).thenReturn("op");
        when(market.portfolio(123)).thenReturn(new EconomyMarketService.Portfolio(null,false,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,List.of()));
        when(market.history(123)).thenReturn(List.of());
        var receipt = new EconomyMarketService.ActionResult(9L, BigDecimal.ZERO, BigDecimal.ZERO, "已处理");
        when(market.close(123,"",9,"op")).thenReturn(receipt); when(market.repay(123,"",null,"op")).thenReturn(receipt);
        for (String suffix : List.of("positions", "history", "close", "repay")) {
            assertTrue(router().execute("dmw_economy_fx_" + suffix, args).path("structuredContent").path("ok").asBoolean());
        }
        verify(market).portfolio(123); verify(market).history(123); verify(market).close(123,"",9,"op"); verify(market).repay(123,"",null,"op");
    }
}
