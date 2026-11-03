package fun.luqing.dmws.economy;

import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.dto.action.common.ActionData;
import com.mikuac.shiro.dto.action.response.GroupMemberInfoResp;
import fun.luqing.dmws.plugin.economy.EconomyCommandSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EconomyCommandSupportTests {

    @Mock
    private Bot bot;

    @Test
    void prefersGroupCardThenNickname() {
        GroupMemberInfoResp member = new GroupMemberInfoResp();
        member.setUserId(90001L);
        member.setCard("抢劫目标名片");
        member.setNickname("目标昵称");
        ActionData<GroupMemberInfoResp> response = new ActionData<>();
        response.setData(member);

        when(bot.getGroupMemberInfo(10001L, 90001L, false)).thenReturn(response);

        assertEquals(
                "抢劫目标名片",
                EconomyCommandSupport.groupMemberDisplayName(bot, 10001L, 90001L)
        );
    }

    @Test
    void fallsBackToNicknameOrUserId() {
        GroupMemberInfoResp member = new GroupMemberInfoResp();
        member.setNickname("目标昵称");
        ActionData<GroupMemberInfoResp> response = new ActionData<>();
        response.setData(member);
        when(bot.getGroupMemberInfo(10001L, 90001L, false)).thenReturn(response);
        when(bot.getGroupMemberInfo(10001L, 90002L, false))
                .thenThrow(new IllegalStateException("lookup failed"));

        assertEquals(
                "目标昵称",
                EconomyCommandSupport.groupMemberDisplayName(bot, 10001L, 90001L)
        );
        assertEquals(
                "对方",
                EconomyCommandSupport.groupMemberDisplayName(bot, 10001L, 90002L)
        );
    }
}
