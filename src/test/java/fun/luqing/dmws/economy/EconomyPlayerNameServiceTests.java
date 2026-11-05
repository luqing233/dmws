package fun.luqing.dmws.economy;

import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.core.BotContainer;
import com.mikuac.shiro.dto.action.common.ActionData;
import com.mikuac.shiro.dto.action.common.ActionList;
import com.mikuac.shiro.dto.action.response.GroupMemberInfoResp;
import com.mikuac.shiro.dto.action.response.StrangerInfoResp;
import fun.luqing.dmws.config.DmwConfig;
import fun.luqing.dmws.entity.dmw.economy.EconomyUserProfile;
import fun.luqing.dmws.repository.dmw.economy.EconomyUserProfileRepository;
import fun.luqing.dmws.service.dmw.economy.EconomyPlayerNameService;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EconomyPlayerNameServiceTests {
    private final DmwConfig config = new DmwConfig();
    private final BotContainer bots = new BotContainer();
    private final EconomyUserProfileRepository profiles = mock(EconomyUserProfileRepository.class);
    private final Bot bot = mock(Bot.class);
    private final EconomyPlayerNameService names = new EconomyPlayerNameService(config, bots, profiles);

    @Test
    void currentGroupCardWinsOverNicknameAndStoredProfile() {
        var member = member(10001L, " 当前群名片 ", "昵称");
        var response = new ActionData<GroupMemberInfoResp>(); response.setData(member);
        when(bot.getGroupMemberInfo(30001L,10001L,false)).thenReturn(response);
        assertEquals("当前群名片", names.resolve(bot,30001L,10001L));
        verifyNoInteractions(profiles);
        verify(bot,never()).getStrangerInfo(anyLong(),anyBoolean());
    }

    @Test
    void nicknameAndStrangerNameFallbacksWorkWithoutNumericIds() {
        var response = new ActionData<GroupMemberInfoResp>(); response.setData(member(10001L," ","群内昵称"));
        when(bot.getGroupMemberInfo(30001L,10001L,false)).thenReturn(response);
        assertEquals("群内昵称", names.resolve(bot,30001L,10001L));
        when(bot.getGroupMemberInfo(30001L,10001L,false)).thenThrow(new IllegalStateException("API unavailable"));
        var stranger = new StrangerInfoResp(); stranger.setNickname("QQ昵称");
        var strangerResponse = new ActionData<StrangerInfoResp>(); strangerResponse.setData(stranger);
        when(bot.getStrangerInfo(10001L,false)).thenReturn(strangerResponse);
        assertEquals("QQ昵称", names.resolve(bot,30001L,10001L));
    }

    @Test
    void offlineReadsExistingNicknameWithoutCreatingOrChangingProfiles() {
        var profile = new EconomyUserProfile(); profile.setUserId(10001L); profile.setNickname("历史昵称");
        when(profiles.findById(10001L)).thenReturn(Optional.of(profile));
        assertEquals("历史昵称", names.resolve(null,10001L));
        verify(profiles).findById(10001L); verifyNoMoreInteractions(profiles);
    }

    @Test
    void missingNamesAndLegacyNumericAliasesNeverLeakQqNumbers() {
        var profile = new EconomyUserProfile(); profile.setNickname("10001");
        when(profiles.findById(10001L)).thenReturn(Optional.of(profile));
        assertEquals("这位玩家", names.resolve(null,10001L));
        assertEquals("", EconomyPlayerNameService.visible("10001","10001",10001L));
        when(profiles.findById(10001L)).thenThrow(new IllegalStateException("DB unavailable"));
        assertEquals("这位玩家", names.resolve(null,10001L));
    }

    @Test
    void rankingsResolveTheirGroupMembersWithOneListRequest() {
        var list = new ActionList<GroupMemberInfoResp>(); list.setData(List.of(member(10001L,"一号群名片","一号"),member(10002L,"二号群名片","二号")));
        when(bot.getGroupMemberList(30001L)).thenReturn(list);
        var found = names.resolveAll(bot,30001L,List.of(10001L,10002L));
        assertEquals("一号群名片",found.get(10001L)); assertEquals("二号群名片",found.get(10002L));
        verify(bot).getGroupMemberList(30001L); verifyNoMoreInteractions(bot); verifyNoInteractions(profiles);
    }

    @Test
    void configuredBotIdentityDoesNotBorrowAnUnrelatedBotConnection() {
        config.setBotId(20001L); bots.robots.put(20002L,bot);
        assertEquals("这位玩家",names.resolve(null,10001L));
        verifyNoInteractions(bot);
    }

    private GroupMemberInfoResp member(long id,String card,String nickname) {
        var member = new GroupMemberInfoResp(); member.setUserId(id); member.setCard(card); member.setNickname(nickname); return member;
    }
}
