package fun.luqing.dmws.economy;

import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.dto.event.message.GroupMessageEvent;
import fun.luqing.dmws.plugin.economy.EconomyFishingPlugin;
import fun.luqing.dmws.service.dmw.economy.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.mockito.Mockito.*;

class EconomyFishingPluginTests {
    @Test void successfulHistoryForwardKeepsRecordFieldsWithoutAlsoSendingPlainText() {
        var service = mock(EconomyFishingService.class);
        var bot = mock(Bot.class, RETURNS_DEEP_STUBS);
        var config = new fun.luqing.dmws.config.DmwConfig();
        config.setBotId(200L); config.setBotName("钓鱼助手");
        var plugin = new EconomyFishingPlugin(service, mock(EconomyPlayerNameService.class), config,
                mock(EconomyCombatService.class), mock(EconomyFishingQueue.class));
        var event = new GroupMessageEvent();
        event.setGroupId(300L); event.setUserId(100L); event.setMessageId(43);
        var rows = new java.util.ArrayList<fun.luqing.dmws.entity.dmw.economy.EconomyFishingCatch>();
        for (int i = 0; i < 5; i++) {
            var row = new fun.luqing.dmws.entity.dmw.economy.EconomyFishingCatch();
            row.setFishName("银鳞鲤"); row.setSizeCm(new BigDecimal("35.50")); row.setReward(new BigDecimal("120.00"));
            row.setCaughtAt(java.time.LocalDateTime.of(2026, 11, 13, 20, i));
            rows.add(row);
        }
        when(service.recentCatches(100L)).thenReturn(rows);
        when(bot.sendGroupForwardMsg(eq(300L), anyList()).getRetCode()).thenReturn(0);
        plugin.history(bot, event);
        verify(bot).sendGroupForwardMsg(eq(300L), argThat(nodes -> nodes.size() >= 5
                && nodes.toString().contains("大小：35.50厘米")
                && nodes.toString().contains("售鱼收入：120.00金币")
                && nodes.toString().contains("钓获时间：11-13 20:04")));
        verify(bot, never()).sendGroupMsg(anyLong(), anyString(), anyBoolean());
    }
    @Test void longCatalogUsesForwardPagesAndFallsBackToReadablePlainPages() {
        var service = mock(EconomyFishingService.class);
        var bot = mock(Bot.class);
        var config = new fun.luqing.dmws.config.DmwConfig();
        config.setBotId(200L); config.setBotName("钓鱼助手");
        var plugin = new EconomyFishingPlugin(service, mock(EconomyPlayerNameService.class), config,
                mock(EconomyCombatService.class), mock(EconomyFishingQueue.class));
        var event = new GroupMessageEvent();
        event.setGroupId(300L); event.setUserId(100L); event.setMessageId(43); event.setMessage("鱼类图鉴");
        String entry = "\n\n银鳞鲤\n等级：3级\n尺寸：20～45厘米\n基础价：120金币\n介绍：" + "保留完整介绍。".repeat(60);
        when(service.describeCatalog(100L, 1)).thenReturn("鱼类图鉴" + entry.repeat(5) + "\n翻页：鱼类图鉴 2");
        plugin.catalog(bot, event);
        verify(bot).sendGroupForwardMsg(eq(300L), argThat(nodes ->
                nodes.size() > 1 && nodes.toString().contains("保留完整介绍") && nodes.toString().contains("鱼类图鉴 2")));
        var messages = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(bot, atLeast(2)).sendGroupMsg(eq(300L), messages.capture(), eq(false));
        org.junit.jupiter.api.Assertions.assertTrue(messages.getAllValues().stream().allMatch(text -> text.length() < 1800));
        org.junit.jupiter.api.Assertions.assertTrue(String.join("", messages.getAllValues()).contains("翻页：鱼类图鉴 2"));
    }

    @Test void committedActionIsNotReportedAsFailedWhenItsReplyCannotBeSent() {
        var bot = mock(Bot.class);
        var event = new GroupMessageEvent();
        event.setGroupId(300L); event.setUserId(100L); event.setMessageId(43);
        var action = mock(java.util.function.Supplier.class);
        when(action.get()).thenReturn("还款成功");
        doThrow(new IllegalStateException("transport unavailable")).when(bot).sendGroupMsg(anyLong(), anyString(), anyBoolean());
        fun.luqing.dmws.plugin.economy.EconomyCommandSupport.execute(bot, event, action);
        verify(action).get();
        verify(bot, times(1)).sendGroupMsg(eq(300L), contains("还款成功"), eq(false));
    }
    @Test void valueLeaderboardUsesForwardNodesWithNamesSizeValueAndCatchGroup() {
        var service=mock(EconomyFishingService.class);var bot=mock(Bot.class);
        var names=mock(EconomyPlayerNameService.class);var config=new fun.luqing.dmws.config.DmwConfig();
        config.setBotId(100L);config.setBotName("钓鱼助手");
        var plugin=new EconomyFishingPlugin(service,names,config,mock(EconomyCombatService.class),mock(EconomyFishingQueue.class));
        var event=new GroupMessageEvent();event.setGroupId(300L);event.setUserId(100L);event.setMessageId(44);
        when(service.leaderboard(10)).thenReturn(java.util.List.of(new EconomyFishingService.LeaderboardEntry(
                101L,"锦鲤",new BigDecimal("78.50"),new BigDecimal("1800.00"),301L,"旧鱼塘","旧昵称")));
        when(names.resolveAll(eq(bot),eq(300L),anyCollection())).thenReturn(java.util.Map.of(101L,"当前群名片"));
        plugin.leaderboard(bot,event);
        verify(bot).sendGroupForwardMsg(eq(300L),argThat(nodes->nodes.size()==1 && nodes.toString().contains("当前群名片")
                && nodes.toString().contains("78.50") && nodes.toString().contains("1800.00") && nodes.toString().contains("旧鱼塘")));
    }
    @Test void encyclopediaRoutesToTheDatabaseCatalogWithPageNumbers() {
        var service = mock(EconomyFishingService.class);
        var bot = mock(Bot.class);
        var plugin = new EconomyFishingPlugin(service, mock(EconomyPlayerNameService.class),mock(fun.luqing.dmws.config.DmwConfig.class),mock(EconomyCombatService.class),mock(EconomyFishingQueue.class));
        var event = new GroupMessageEvent();
        event.setGroupId(300L); event.setUserId(100L); event.setMessageId(43); event.setMessage("鱼类图鉴 2");
        when(service.describeCatalog(100L, 2)).thenReturn("鱼类图鉴：数据库鱼种");
        plugin.catalog(bot, event);
        verify(service).describeCatalog(100L, 2);
        verify(bot).sendGroupMsg(eq(300L), contains("鱼类图鉴：数据库鱼种"), eq(false));
        event.setMessage("鱼类图鉴 999999999999999999999");
        plugin.catalog(bot, event);
        verify(bot).sendGroupMsg(eq(300L), contains("图鉴页码过大"), eq(false));
    }

    @Test void castCommandRepliesWithWaitingStateWithoutSettling() {
        var service=mock(EconomyFishingService.class);var bot=mock(Bot.class);
        var plugin=new EconomyFishingPlugin(service,mock(EconomyPlayerNameService.class),mock(fun.luqing.dmws.config.DmwConfig.class),mock(EconomyCombatService.class),mock(EconomyFishingQueue.class));
        var event=new GroupMessageEvent();event.setGroupId(300L);event.setUserId(100L);event.setMessageId(42);
        when(bot.getSelfId()).thenReturn(200L);
        event.setMessage("钓鱼 蓝");
        var now = java.time.LocalDateTime.of(2026, 11, 14, 12, 0);
        when(service.cast(eq(100L),anyString(),anyString(),eq(200L),eq(300L),anyString(),eq(42),eq("蓝")))
                .thenReturn(new EconomyFishingService.CastTicket(now, now.plusSeconds(30), "数据库抛竿文案"));
        plugin.fish(bot,event);
        verify(service).cast(eq(100L),anyString(),anyString(),eq(200L),eq(300L),anyString(),eq(42),eq("蓝"));
        verify(bot).sendGroupMsg(eq(300L),contains("数据库抛竿文案"),eq(false));
    }
}
