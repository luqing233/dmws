package fun.luqing.dmws.economy;

import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.dto.event.message.GroupMessageEvent;
import fun.luqing.dmws.plugin.economy.EconomyFishingBaitPlugin;
import fun.luqing.dmws.plugin.economy.EconomyFishingPlugin;
import fun.luqing.dmws.repository.dmw.economy.EconomyUserProfileRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
class EconomyFishingReadOnlyTests {
    @Autowired EconomyFishingPlugin fishing;
    @Autowired EconomyFishingBaitPlugin bait;
    @Autowired EconomyUserProfileRepository profiles;

    @Test
    void realQueryEntryPointsDoNotRegisterNewPlayers() {
        var bot = mock(Bot.class);
        var event = new GroupMessageEvent();
        event.setUserId(9931003L);
        event.setGroupId(123L);
        event.setMessageId(44);
        var sender = new GroupMessageEvent.GroupSender();
        sender.setCard("群名片");
        sender.setNickname("QQ昵称");
        event.setSender(sender);
        long count = profiles.count();
        fishing.status(bot, event);
        fishing.level(bot, event);
        fishing.history(bot, event);
        fishing.strength(bot, event);
        event.setMessage("鱼类图鉴");
        fishing.catalog(bot, event);
        bait.status(bot, event);
        assertEquals(count, profiles.count());
        assertFalse(profiles.existsById(event.getUserId()));
        verify(bot, never()).getGroupInfo(anyLong(), anyBoolean());
    }
}
