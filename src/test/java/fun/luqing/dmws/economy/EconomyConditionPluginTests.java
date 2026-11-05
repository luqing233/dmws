package fun.luqing.dmws.economy;

import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.dto.action.common.ActionList;
import com.mikuac.shiro.dto.action.response.GroupMemberInfoResp;
import com.mikuac.shiro.dto.event.message.GroupMessageEvent;
import com.mikuac.shiro.enums.MsgTypeEnum;
import com.mikuac.shiro.model.ArrayMsg;
import fun.luqing.dmws.plugin.economy.EconomyConditionPlugin;
import fun.luqing.dmws.service.dmw.economy.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EconomyConditionPluginTests {
    @Test
    void bailReceiptUsesNicknameAndDoesNotDumpStatusOrBalances() {
        var service = mock(EconomyConditionService.class); var names = mock(EconomyPlayerNameService.class); var bot = mock(Bot.class);
        var plugin = new EconomyConditionPlugin(service,mock(EconomyGameTime.class),names);
        var target = new GroupMemberInfoResp(); target.setUserId(3253912136L); target.setCard("群友昵称");
        var members = new ActionList<GroupMemberInfoResp>(); members.setData(List.of(target));
        when(bot.getGroupMemberList(30001L)).thenReturn(members);
        when(names.member(bot,30001L,target)).thenReturn("群友昵称");
        when(service.bail(eq(10001L),anyString(),eq(3253912136L),eq(fun.luqing.dmws.common.economy.EconomyMessageOperation.id("bail",30001L,1L,10001L,"")))).thenReturn(
                new EconomyConditionService.BailResult(10001L,3253912136L,new BigDecimal("400"),15,BigDecimal.ZERO,new BigDecimal("124037.30")));
        var event = new GroupMessageEvent(); event.setGroupId(30001L); event.setUserId(10001L); event.setMessageId(1);
        event.setMessage("保释 [CQ:at,qq=3253912136]");
        event.setArrayMsg(List.of(new ArrayMsg().setType(MsgTypeEnum.text).setData(Map.of("text","保释 ")),
                new ArrayMsg().setType(MsgTypeEnum.at).setData(Map.of("qq","3253912136"))));
        plugin.bail(bot,event);
        var message=ArgumentCaptor.forClass(String.class);
        verify(bot).sendGroupMsg(eq(30001L),message.capture(),eq(false));
        assertTrue(message.getValue().contains("群友昵称 已出狱"));
        assertTrue(message.getValue().contains("400.00"));
        for (String hidden:List.of("3253912136","124037.30","钱包","犯罪值")) assertFalse(message.getValue().contains(hidden),hidden);
    }
}
