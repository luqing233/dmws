package fun.luqing.dmws.config;

import com.mikuac.shiro.annotation.GroupMessageHandler;
import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.dto.action.response.GroupInfoResp;
import com.mikuac.shiro.dto.event.message.GroupMessageEvent;
import fun.luqing.dmws.service.dmw.economy.EconomyUserProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class EconomyPresenceAspect {

    private final EconomyUserProfileService profileService;

    @Around("@annotation(groupMessageHandler) && execution(* fun.luqing.dmws.plugin.economy..*(..))")
    public Object recordEconomyGroup(
            ProceedingJoinPoint joinPoint,
            GroupMessageHandler groupMessageHandler
    ) throws Throwable {
        GroupMessageEvent event = null;
        Bot bot = null;
        for (Object argument : joinPoint.getArgs()) {
            if (argument instanceof GroupMessageEvent messageEvent) {
                event = messageEvent;
            } else if (argument instanceof Bot shiroBot) {
                bot = shiroBot;
            }
        }
        if (event != null && bot != null && event.getSender() != null) {
            rememberGroup(bot, event);
        }
        return joinPoint.proceed();
    }

    private void rememberGroup(Bot bot, GroupMessageEvent event) {
        try {
            if (profileService.hasRegisteredGroup(event.getUserId())) {
                return;
            }
            String nickname = event.getSender().getCard();
            if (nickname == null || nickname.isBlank()) {
                nickname = event.getSender().getNickname();
            }
            String groupName = "";
            try {
                GroupInfoResp group = bot.getGroupInfo(event.getGroupId(), false).getData();
                if (group != null) {
                    groupName = group.getGroupName();
                }
            } catch (RuntimeException ignored) {
                // The group id is still stored when group metadata lookup is unavailable.
            }
            profileService.rememberGroup(
                    event.getUserId(),
                    nickname,
                    event.getGroupId(),
                    groupName
            );
        } catch (RuntimeException exception) {
            log.warn("记录经济开户群失败: userId={}, groupId={}",
                    event.getUserId(), event.getGroupId(), exception);
        }
    }
}
