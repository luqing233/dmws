package fun.luqing.dmws.economy;

import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.dto.event.message.GroupMessageEvent;
import fun.luqing.dmws.common.economy.EconomyMessageOperation;
import fun.luqing.dmws.config.EconomyMarketProperties;
import fun.luqing.dmws.entity.dmw.economy.EconomyMarketPosition.Side;
import fun.luqing.dmws.enums.EconomyFxPair;
import fun.luqing.dmws.plugin.economy.EconomyMarketPlugin;
import fun.luqing.dmws.service.dmw.economy.EconomyMarketService;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.mockito.Mockito.*;

class EconomyMarketPluginTests {
    final EconomyMarketService market = mock(EconomyMarketService.class);
    final Bot bot = mock(Bot.class);
    final fun.luqing.dmws.service.dmw.economy.EconomyMarketHistoryService prices = mock(fun.luqing.dmws.service.dmw.economy.EconomyMarketHistoryService.class);
    final fun.luqing.dmws.service.dmw.economy.EconomyMarketChartService charts = mock(fun.luqing.dmws.service.dmw.economy.EconomyMarketChartService.class);
    final EconomyMarketPlugin plugin = new EconomyMarketPlugin(market, new EconomyMarketProperties(), new fun.luqing.dmws.config.DmwConfig(), prices, charts);
    GroupMessageEvent event(String message) {
        var event = new GroupMessageEvent(); event.setUserId(123L); event.setGroupId(456L); event.setMessageId(-77); event.setMessage(message);
        return event;
    }
    @Test void nativeOpeningUsesSameActorSideAndMessageIdentityAsMcp() {
        String op = EconomyMessageOperation.id("fx-open", 456, -77, 123, "");
        when(market.openInGroup(eq(123L), anyString(), eq(EconomyFxPair.EUR_USD), eq(Side.LONG), eq(new BigDecimal("500.00")), eq(20), eq(op), eq(456L)))
                .thenReturn(new EconomyMarketService.ActionResult(9L, new BigDecimal("501.50"), BigDecimal.ZERO, "开仓成功 #9"));
        plugin.open(bot, event("做多 EUR/USD 500 20"));
        verify(market).openInGroup(eq(123L), anyString(), eq(EconomyFxPair.EUR_USD), eq(Side.LONG), eq(new BigDecimal("500.00")), eq(20), eq(op), eq(456L));
        verify(bot).sendGroupMsg(eq(456L), contains("开仓成功 #9"), eq(false));
    }
    @Test void invalidAmountOrLeverageReturnsHelpfulErrorWithoutCallingMarket() {
        plugin.open(bot, event("做多 EUR/USD 500 20.5"));
        plugin.open(bot, event("做多 EUR/USD 500.001 20"));
        verifyNoInteractions(market);
        verify(bot).sendGroupMsg(eq(456L), contains("杠杆必须"), eq(false));
        verify(bot).sendGroupMsg(eq(456L), contains("最多两位小数"), eq(false));
    }
    @Test void closingAndRepayingPreserveOwnedPositionAndCanonicalOperation() {
        var receipt = new EconomyMarketService.ActionResult(9L, BigDecimal.ZERO, BigDecimal.ZERO, "结算回执");
        String close = EconomyMessageOperation.id("fx-close",456,-77,123,"9");
        when(market.close(eq(123L),anyString(),eq(9L),eq(close))).thenReturn(receipt);
        plugin.close(bot,event("平仓 #9"));
        verify(market).close(eq(123L),anyString(),eq(9L),eq(close));
        String repay = EconomyMessageOperation.id("fx-repay",456,-77,123,"");
        when(market.repay(eq(123L),anyString(),isNull(),eq(repay))).thenReturn(receipt);
        plugin.repay(bot,event("交易还款 全部"));
        verify(market).repay(eq(123L),anyString(),isNull(),eq(repay));
    }

    @Test void administratorCommandsUseCanonicalMessageOperation() {
        String op = EconomyMessageOperation.id("fx-switch", 456, -77, 123, "");
        when(market.setTradingEnabled(123, true, op)).thenReturn(new EconomyMarketService.ActionResult(null, BigDecimal.ZERO, BigDecimal.ZERO, "交易功能已开启"));
        plugin.switchTrading(bot, event("开启交易"));
        verify(market).setTradingEnabled(123, true, op);
        verify(bot).sendGroupMsg(eq(456L), contains("交易功能已开启"), eq(false));
        when(market.setTradingEnabled(123, false, op)).thenThrow(new fun.luqing.dmws.common.economy.EconomyException("PERMISSION_DENIED", "只有机器人主人可以开关交易功能"));
        plugin.switchTrading(bot, event("关闭交易"));
        verify(bot).sendGroupMsg(eq(456L), contains("只有机器人主人"), eq(false));
    }

    @Test void additionalMarginAndRefusalRouteToOwnedPositionAndStableOperations() {
        String op=EconomyMessageOperation.id("fx-margin",456,-77,123,"");
        var result=new EconomyMarketService.ActionResult(9L,new BigDecimal("500.00"),BigDecimal.ZERO,"已补仓");
        when(market.addMargin(eq(123L),anyString(),eq(9L),eq(new BigDecimal("500.00")),eq(op))).thenReturn(result);
        plugin.addMargin(bot,event("补仓 #9 500"));
        verify(market).addMargin(eq(123L),anyString(),eq(9L),eq(new BigDecimal("500.00")),eq(op));
        String close=EconomyMessageOperation.id("fx-close",456,-77,123,"9");
        when(market.close(eq(123L),anyString(),eq(9L),eq(close))).thenReturn(result);
        plugin.close(bot,event("不补仓 #9")); verify(market).close(eq(123L),anyString(),eq(9L),eq(close));
    }

    @Test void prefixedCommandsAcceptSpacesAndSuccessfulRepliesUseMergedForwardOnly() {
        var response=new com.mikuac.shiro.dto.action.common.ActionData<com.mikuac.shiro.dto.action.common.MsgId>();
        response.setRetCode(0); response.setData(new com.mikuac.shiro.dto.action.common.MsgId());
        when(bot.sendGroupForwardMsg(eq(456L),anyList())).thenReturn(response);
        String op=EconomyMessageOperation.id("fx-open",456,-77,123,"");
        when(market.openInGroup(eq(123L),anyString(),eq(EconomyFxPair.EUR_USD),eq(Side.LONG),eq(new BigDecimal("500.00")),eq(20),eq(op),eq(456L)))
                .thenReturn(new EconomyMarketService.ActionResult(9L,BigDecimal.ZERO,BigDecimal.ZERO,"开仓成功 #9\n保证金500，名义仓位10000"));
        plugin.open(bot,event("交易 做多 EUR/USD 500 20"));
        verify(bot).sendGroupForwardMsg(eq(456L),argThat(nodes->nodes.toString().contains("【交易｜做多】") && nodes.toString().contains("名义仓位10000")));
        verify(bot,never()).sendGroupMsg(anyLong(),anyString(),anyBoolean());
    }

    @Test void commandFiltersRecognizeCanonicalSpaceAndCompactForms() throws Exception {
        for (String[] pair : new String[][]{{"help","帮助"},{"quote","行情"},{"positions","持仓"},{"history","记录"},
                {"switchTrading","开启"},{"open","做多 EUR/USD 500 20"},{"addMargin","补仓 9 500"},{"close","平仓 9"},{"repay","还款 全部"},{"priceHistory","历史行情 EUR/USD 7天"}}) {
            var filter=EconomyMarketPlugin.class.getMethod(pair[0],Bot.class,GroupMessageEvent.class)
                    .getAnnotation(com.mikuac.shiro.annotation.MessageHandlerFilter.class);
            org.junit.jupiter.api.Assertions.assertTrue(("交易"+pair[1]).matches(filter.cmd()));
            org.junit.jupiter.api.Assertions.assertTrue(("交易 "+pair[1]).matches(filter.cmd()));
        }
        String body=("保证金😀与仓位\n").repeat(600);
        var pages=fun.luqing.dmws.plugin.economy.EconomyDisplayFormatter.market("持仓",body+"\n\n仓位 #2\n可补仓或平仓");
        org.junit.jupiter.api.Assertions.assertTrue(pages.stream().allMatch(p->p.length()<=1800 && p.startsWith("【交易｜持仓")));
        org.junit.jupiter.api.Assertions.assertEquals(600,String.join("\n",pages).split("保证金😀与仓位",-1).length-1);
    }

    @Test void historicalQueryForwardsOverviewImageAndRecentRowsWithoutSplittingImageOrExecutingTrades() {
        var now=java.time.LocalDateTime.of(2026,11,9,12,0);
        var row=new fun.luqing.dmws.entity.dmw.economy.EconomyMarketQuote();row.setQuotedAt(now);row.price(EconomyFxPair.EUR_USD,new BigDecimal("1.08000000"));
        var period=fun.luqing.dmws.service.dmw.economy.EconomyMarketHistoryService.Period.DAY;
        var view=fun.luqing.dmws.service.dmw.economy.EconomyMarketHistoryService.summarize(EconomyFxPair.EUR_USD,period,now.minusDays(1),now,java.util.List.of(row));
        when(prices.query(EconomyFxPair.EUR_USD,period)).thenReturn(view);
        when(charts.render(view)).thenReturn(new byte[]{1,2,3});
        var response=new com.mikuac.shiro.dto.action.common.ActionData<com.mikuac.shiro.dto.action.common.MsgId>();response.setRetCode(0);response.setData(new com.mikuac.shiro.dto.action.common.MsgId());
        when(bot.sendGroupForwardMsg(eq(456L),anyList())).thenReturn(response);
        plugin.priceHistory(bot,event("交易 历史行情 EUR/USD"));
        verify(bot).sendGroupForwardMsg(eq(456L),argThat(nodes->nodes.size()==3 && nodes.toString().contains("最近24小时") && nodes.toString().contains("[CQ:image") && nodes.toString().contains("最近10轮")));
        verify(bot,never()).sendGroupMsg(anyLong(),anyString(),anyBoolean()); verifyNoInteractions(market);
        plugin.priceHistory(bot,event("交易历史行情 EUR/USD 365天"));
        verify(prices,times(1)).query(any(),any());
    }

    @Test void helpListsHistoryCommandAndImageFailurePreservesSuccessfulQuery() {
        when(market.tradingStatus()).thenReturn("关闭"); plugin.help(bot,event("交易帮助"));
        verify(bot).sendGroupForwardMsg(eq(456L),argThat(nodes->nodes.toString().contains("交易历史行情") && nodes.toString().contains("默认24小时")));
        var now=java.time.LocalDateTime.of(2026,11,9,12,0);
        var row=new fun.luqing.dmws.entity.dmw.economy.EconomyMarketQuote();row.setQuotedAt(now);row.price(EconomyFxPair.EUR_USD,BigDecimal.ONE);
        var view=fun.luqing.dmws.service.dmw.economy.EconomyMarketHistoryService.summarize(EconomyFxPair.EUR_USD,fun.luqing.dmws.service.dmw.economy.EconomyMarketHistoryService.Period.WEEK,now.minusDays(7),now,java.util.List.of(row));
        when(prices.query(any(),any())).thenReturn(view); when(charts.render(view)).thenThrow(new IllegalStateException("test image encoder failure"));
        plugin.priceHistory(bot,event("交易历史行情 EUR/USD 7天"));
        verify(bot).sendGroupForwardMsg(eq(456L),argThat(nodes->nodes.toString().contains("行情查询成功") && nodes.toString().contains("最近10轮") && !nodes.toString().contains("[CQ:image")));
    }
}
