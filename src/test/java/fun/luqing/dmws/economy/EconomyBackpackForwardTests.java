package fun.luqing.dmws.economy;

import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.dto.action.common.ActionData;
import com.mikuac.shiro.dto.action.common.MsgId;
import com.mikuac.shiro.dto.event.message.GroupMessageEvent;
import fun.luqing.dmws.config.DmwConfig;
import fun.luqing.dmws.entity.dmw.economy.*;
import fun.luqing.dmws.enums.EconomyItemEffectType;
import fun.luqing.dmws.plugin.economy.*;
import fun.luqing.dmws.service.dmw.economy.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EconomyBackpackForwardTests {
    final EconomyItemService items = mock(EconomyItemService.class);
    final Bot bot = mock(Bot.class);
    final DmwConfig config = new DmwConfig();
    final EconomyInventoryPlugin plugin = new EconomyInventoryPlugin(items, mock(EconomyTitleService.class), config);
    GroupMessageEvent event() {
        var event = new GroupMessageEvent(); event.setGroupId(456L); event.setUserId(123L); event.setMessageId(9);
        config.setBotId(100L); config.setBotName("背包助手"); return event;
    }
    EconomyUserItem item(String code, String name, int quantity, EconomyItemEffectType type) {
        var row = new EconomyUserItem(); row.setItemCode(code); row.setQuantity(quantity);
        var definition = new EconomyItemDefinition(); definition.setCode(code); definition.setName(name); definition.setEffectType(type);
        when(items.definition(code)).thenReturn(definition); return row;
    }
    @Test void mergesInventoryEquipmentAndRemainingBaitWithoutDuplicatePlainReply() {
        var bait = item("fishing-luck-bait", "幸运鱼饵（蓝）", 3, EconomyItemEffectType.FISHING_LUCK);
        var baton = item("electric-baton", "电棍", 1, EconomyItemEffectType.NONE);
        when(items.backpack(123)).thenReturn(List.of(bait, baton));
        when(items.equipment(123)).thenReturn(new EconomyEquipmentService.EquipmentView(List.of(new EconomyEquipmentService.BatonView(60, true, 40)), true));
        when(items.baitStatus(123)).thenReturn("默认白色鱼饵，剩余2次");
        var response = new ActionData<MsgId>(); response.setRetCode(0); response.setData(new MsgId());
        when(bot.sendGroupForwardMsg(eq(456L), anyList())).thenReturn(response);
        plugin.backpack(bot, event());
        verify(bot).sendGroupForwardMsg(eq(456L), argThat(nodes -> nodes.size() == 4 && nodes.toString().contains("3包")
                && nodes.toString().contains("electric-baton") && nodes.toString().contains("60/100") && nodes.toString().contains("剩余2次")));
        verify(bot, never()).sendGroupMsg(anyLong(), anyString(), anyBoolean());
        verify(items).backpack(123); verify(items).equipment(123); verify(items).baitStatus(123);
        verify(items).definition("fishing-luck-bait"); verify(items).definition("electric-baton");
        verifyNoMoreInteractions(items);
    }
    @Test void emptyBackpackStillShowsRemainingBaitAndFallsBackIfForwardFails() {
        when(items.backpack(123)).thenReturn(List.of()); when(items.baitStatus(123)).thenReturn("鱼饵剩余4次");
        // An absent/failed OneBot receipt causes the established plain-message fallback.
        plugin.backpack(bot, event());
        verify(bot).sendGroupForwardMsg(eq(456L), argThat(nodes -> nodes.size() == 2));
        verify(bot).sendGroupMsg(eq(456L), argThat((String text) -> text.contains("暂无完整道具") && text.contains("鱼饵剩余4次")), eq(false));
        verify(items, never()).equipment(anyLong());
    }
    @Test void itemPagesAndLongEquipmentKeepAllContent() {
        List<String> lines = java.util.stream.IntStream.range(0, 19).mapToObj(i -> "道具-" + i + " [code-" + i + "] 1个").toList();
        String equipment = ("电棍😀 80/100\n").repeat(500);
        var nodes = EconomyDisplayFormatter.backpack(lines, equipment, "鱼饵状态");
        assertTrue(nodes.get(1).startsWith("道具清单 1")); assertTrue(nodes.get(3).startsWith("道具清单 3"));
        for (String line : lines) assertTrue(String.join("\n", nodes).contains(line));
        assertTrue(nodes.stream().allMatch(node -> node.length() <= 1800));
        assertEquals(500, String.join("\n", nodes).split("80/100", -1).length - 1);
    }
}
