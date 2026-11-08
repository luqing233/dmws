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
    final EconomyMarketPlugin plugin = new EconomyMarketPlugin(market, new EconomyMarketProperties());
    GroupMessageEvent event(String message) {
        var event = new GroupMessageEvent(); event.setUserId(123L); event.setGroupId(456L); event.setMessageId(-77); event.setMessage(message);
        return event;
    }
    @Test void nativeOpeningUsesSameActorSideAndMessageIdentityAsMcp() {
        String op = EconomyMessageOperation.id("fx-open", 456, -77, 123, "");
        when(market.open(eq(123L), anyString(), eq(EconomyFxPair.EUR_USD), eq(Side.LONG), eq(new BigDecimal("500.00")), eq(20), eq(op)))
                .thenReturn(new EconomyMarketService.ActionResult(9L, new BigDecimal("501.50"), BigDecimal.ZERO, "开仓成功 #9"));
        plugin.open(bot, event("做多 EUR/USD 500 20"));
        verify(market).open(eq(123L), anyString(), eq(EconomyFxPair.EUR_USD), eq(Side.LONG), eq(new BigDecimal("500.00")), eq(20), eq(op));
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
}
