package fun.luqing.dmws.plugin.economy;

import com.mikuac.shiro.annotation.GroupMessageHandler;
import com.mikuac.shiro.annotation.MessageHandlerFilter;
import com.mikuac.shiro.annotation.common.Shiro;
import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.dto.event.message.GroupMessageEvent;
import fun.luqing.dmws.common.annotation.CommandInfo;
import fun.luqing.dmws.common.economy.EconomyException;
import fun.luqing.dmws.service.dmw.economy.EconomyRobService;
import fun.luqing.dmws.service.dmw.economy.EconomyPlayerNameService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Shiro
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "dmws.economy", name = "enabled", havingValue = "true", matchIfMissing = true)
public class EconomyRobPlugin {

    private final EconomyRobService robService;
    private final EconomyPlayerNameService names;

    @GroupMessageHandler
    @MessageHandlerFilter(startWith = {"抢劫", "打人"})
    @CommandInfo(startWith = "抢劫", desc = "抢劫/打人 @用户，可能被反打或被捕，单次最多5000")
    public void rob(Bot bot, GroupMessageEvent event) {
        EconomyCommandSupport.execute(bot, event, () -> {
            Long target = EconomyCommandSupport.mentionedUserId(event);
            if (target == null) {
                throw new EconomyException("TARGET_REQUIRED", "请 @ 要抢劫的用户");
            }
            String targetName = names.resolve(
                    bot,
                    event.getGroupId(),
                    target
            );
            EconomyRobService.RobResult result = robService.rob(
                    event.getUserId(),
                    EconomyCommandSupport.nickname(event),
                    target,
                    EconomyCommandSupport.operationId("rob", event)
            );
            return robService.describe(result, targetName);
        });
    }
}
