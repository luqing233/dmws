package fun.luqing.dmws.economy;

import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.dto.event.message.GroupMessageEvent;
import fun.luqing.dmws.plugin.economy.EconomyFishingPlugin;
import fun.luqing.dmws.service.dmw.economy.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.mockito.Mockito.*;

class EconomyFishingPluginTests {
    @Test void valueLeaderboardUsesForwardNodesWithNamesSizeValueAndCatchGroup() {
        var service=mock(EconomyFishingService.class);var bot=mock(Bot.class);
        var names=mock(EconomyPlayerNameService.class);var config=new fun.luqing.dmws.config.DmwConfig();
        config.setBotId(100L);config.setBotName("钓鱼助手");
        var plugin=new EconomyFishingPlugin(service,names,config,mock(EconomyCombatService.class));
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
        var plugin = new EconomyFishingPlugin(service, mock(EconomyPlayerNameService.class),mock(fun.luqing.dmws.config.DmwConfig.class),mock(EconomyCombatService.class));
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

    @Test void actualFishingCommandUsesTheDescriptionAndNetReceipt() {
        var service=mock(EconomyFishingService.class);var bot=mock(Bot.class);
        var plugin=new EconomyFishingPlugin(service,mock(EconomyPlayerNameService.class),mock(fun.luqing.dmws.config.DmwConfig.class),mock(EconomyCombatService.class));
        var event=new GroupMessageEvent();event.setGroupId(300L);event.setUserId(100L);event.setMessageId(42);
        when(service.fish(eq(100L),anyString(),anyString(),eq(300L),anyString())).thenReturn(new EconomyFishingService.FishingResult(
                true,"锦鲤",new BigDecimal("55"),new BigDecimal("940"),new BigDecimal("20"),
                new BigDecimal("123456.78"),45,false,"传说中的幸运鱼",new BigDecimal("1.88")));
        plugin.fish(bot,event);
        verify(bot).sendGroupMsg(eq(300L),argThat((String text)->text.contains("传说中的幸运鱼")
                && text.contains("+920.00") && !text.contains("123456.78") && !text.contains("钱包")),eq(false));
    }
}
