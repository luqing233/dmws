package fun.luqing.dmws.economy;

import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.dto.event.message.GroupMessageEvent;
import fun.luqing.dmws.config.DmwConfig;
import fun.luqing.dmws.entity.dmw.economy.*;
import fun.luqing.dmws.plugin.economy.*;
import fun.luqing.dmws.service.dmw.economy.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static org.mockito.Mockito.*;

class EconomyCommandCompatibilityTests {
    @Test void multiplicationQuantityNotationBuysRequestedAmount() {
        var items = mock(EconomyItemService.class); var titles = mock(EconomyTitleService.class); var bot = mock(Bot.class);
        var event = new GroupMessageEvent(); event.setUserId(10001L); event.setGroupId(30001L); event.setMessageId(1);
        event.setPlainText("购买幸运鱼饵*20");
        var item = new EconomyItemDefinition(); item.setCode("fishing-luck-bait"); item.setName("幸运鱼饵"); item.setPrice(new BigDecimal("300"));
        when(items.resolve("幸运鱼饵")).thenReturn(item);
        when(items.buy(eq(10001L),anyString(),eq("fishing-luck-bait"),eq(20),anyString())).thenReturn(
                new EconomyItemService.PurchaseResult("fishing-luck-bait","幸运鱼饵",20,new BigDecimal("6000"),BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO));
        new EconomyInventoryPlugin(items,titles,new DmwConfig()).buy(bot,event);
        verify(items).buy(eq(10001L),anyString(),eq("fishing-luck-bait"),eq(20),eq(EconomyCommandSupport.operationId("item-buy",event,"fishing-luck-bait")));
    }

    @Test void baitQualityAndPackageQuantityAreParsedTogether() {
        var items=mock(EconomyItemService.class);var bot=mock(Bot.class);
        var event=new GroupMessageEvent();event.setUserId(10001L);event.setGroupId(30001L);event.setMessageId(9);event.setPlainText("购买 幸运鱼饵 红 3");
        var item=new EconomyItemDefinition();item.setCode("fishing-bait-red");item.setName("幸运鱼饵（红）");item.setPrice(new BigDecimal("400"));
        when(items.resolve("幸运鱼饵红")).thenReturn(item);
        when(items.buy(eq(10001L),anyString(),eq(item.getCode()),eq(3),anyString())).thenReturn(new EconomyItemService.PurchaseResult(item.getCode(),item.getName(),3,new BigDecimal("1200"),BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO));
        new EconomyInventoryPlugin(items,mock(EconomyTitleService.class),new DmwConfig()).buy(bot,event);
        verify(items).buy(eq(10001L),anyString(),eq(item.getCode()),eq(3),anyString());
    }

    @Test void ownedDelistedTitleCanBeActivatedByItsDisplayedCode() {
        var items = mock(EconomyItemService.class); var titles = mock(EconomyTitleService.class); var bot = mock(Bot.class);
        var event = new GroupMessageEvent(); event.setUserId(10001L); event.setGroupId(30001L); event.setMessageId(2);
        event.setPlainText("切换称号 monopoly-0");
        var owned = new EconomyUserTitle(); owned.setUserId(10001L); owned.setTitleCode("monopoly-0");
        when(titles.owned(10001L)).thenReturn(List.of(owned));
        new EconomyInventoryPlugin(items,titles,new DmwConfig()).switchTitle(bot,event);
        verify(titles).activate(eq(10001L),anyString(),eq("monopoly-0"));
        verify(titles,never()).resolve(anyString());
    }
}
