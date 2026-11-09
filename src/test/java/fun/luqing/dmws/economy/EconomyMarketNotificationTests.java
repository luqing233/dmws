package fun.luqing.dmws.economy;

import com.mikuac.shiro.core.*;
import com.mikuac.shiro.dto.action.common.*;
import fun.luqing.dmws.config.*;
import fun.luqing.dmws.plugin.economy.EconomyMarketNotificationPlugin;
import fun.luqing.dmws.service.dmw.economy.EconomyMarketService;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class EconomyMarketNotificationTests {
    final EconomyMarketService market = mock(EconomyMarketService.class);
    final Bot bot = mock(Bot.class);
    final BotContainer bots = new BotContainer();
    final DmwConfig config = new DmwConfig();
    final EconomyMarketNotificationPlugin plugin = new EconomyMarketNotificationPlugin(market,bots,config,new EconomyMarketProperties(),new EconomyProperties());
    void setup() { config.setBotId(100); bots.robots.put(100L,bot); when(market.available()).thenReturn(true); }
    @Test void sendsAtMentionToOriginGroupOnlyAfterClaimAndPersistsOneBotReceipt() {
        setup();
        when(market.claimNotice()).thenReturn(new EconomyMarketService.Notice(1,123,456,"请补仓"),null);
        var response = new ActionData<MsgId>(); response.setRetCode(0); var id = new MsgId(); id.setMessageId(789); response.setData(id);
        when(bot.sendGroupMsg(eq(456L),contains("[CQ:at,qq=123]"),eq(false))).thenReturn(response);
        when(bot.sendGroupForwardMsg(eq(456L),anyList())).thenReturn(response);
        plugin.sendPending();
        var order=inOrder(market,bot); order.verify(market).available(); order.verify(market).claimNotice();
        order.verify(bot).sendGroupMsg(eq(456L),contains("[CQ:at,qq=123]"),eq(false));
        order.verify(bot).sendGroupForwardMsg(eq(456L),argThat(nodes->nodes.toString().contains("请补仓") && nodes.toString().contains("【交易｜风险与结算】")));
        order.verify(market).finishNotice(1,"SENT","789");
    }
    @Test void timeoutIsUnknownAndDoesNotRetryTheSend() {
        setup(); when(market.claimNotice()).thenReturn(new EconomyMarketService.Notice(1,123,456,"提醒"),null);
        when(bot.sendGroupMsg(anyLong(),anyString(),anyBoolean())).thenThrow(new RuntimeException("timeout"));
        plugin.sendPending(); verify(bot,times(1)).sendGroupMsg(anyLong(),anyString(),anyBoolean());
        verify(market).finishNotice(1,"UNKNOWN",null);
    }
    @Test void missingConnectionLeavesDurableOutboxUnclaimed() {
        plugin.sendPending(); verifyNoInteractions(market,bot);
    }
    @Test void rejectedTransportDoesNotTouchFinancialSettlement() {
        setup(); when(market.claimNotice()).thenReturn(new EconomyMarketService.Notice(1,123,456,"提醒"),null);
        var response = new ActionData<MsgId>(); response.setRetCode(100);
        when(bot.sendGroupMsg(anyLong(),anyString(),anyBoolean())).thenReturn(response);
        plugin.sendPending(); verify(market).finishNotice(1,"FAILED",null);
        verify(market,never()).tick();
    }
}
