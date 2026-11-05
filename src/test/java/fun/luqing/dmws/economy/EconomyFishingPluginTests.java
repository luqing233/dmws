package fun.luqing.dmws.economy;

import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.dto.event.message.GroupMessageEvent;
import fun.luqing.dmws.plugin.economy.EconomyFishingPlugin;
import fun.luqing.dmws.service.dmw.economy.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.mockito.Mockito.*;

class EconomyFishingPluginTests {
    @Test void actualFishingCommandUsesTheDescriptionAndNetReceipt() {
        var service=mock(EconomyFishingService.class);var bot=mock(Bot.class);
        var plugin=new EconomyFishingPlugin(service,mock(EconomyPlayerNameService.class));
        var event=new GroupMessageEvent();event.setGroupId(300L);event.setUserId(100L);event.setMessageId(42);
        when(service.fish(eq(100L),anyString(),anyString())).thenReturn(new EconomyFishingService.FishingResult(
                true,"锦鲤",new BigDecimal("55"),new BigDecimal("940"),new BigDecimal("20"),
                new BigDecimal("123456.78"),45,false,"传说中的幸运鱼",new BigDecimal("1.88")));
        plugin.fish(bot,event);
        verify(bot).sendGroupMsg(eq(300L),argThat((String text)->text.contains("传说中的幸运鱼")
                && text.contains("+920.00") && !text.contains("123456.78") && !text.contains("钱包")),eq(false));
    }
}
