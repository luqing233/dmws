package fun.luqing.dmws.plugin;

import com.mikuac.shiro.annotation.GroupMessageHandler;
import com.mikuac.shiro.annotation.MessageHandlerFilter;
import com.mikuac.shiro.annotation.common.Shiro;
import com.mikuac.shiro.common.utils.MsgUtils;
import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.dto.action.response.GroupMemberInfoResp;
import com.mikuac.shiro.dto.event.message.GroupMessageEvent;
import fun.luqing.dmws.common.annotation.CommandInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Shiro
@Component
@Slf4j
@RequiredArgsConstructor
public class FunPlugin {

    @GroupMessageHandler
    @MessageHandlerFilter(startWith = "可汗大点兵")
    @Async("taskExecutor")
    @CommandInfo(startWith = "可汗大点兵", desc = "随机艾特指定人数")
    public void gatherArms(Bot bot, GroupMessageEvent event) {
        log.info("触发事件：可汗大点兵");

        // 禁止凌晨 0 点到 8 点
        int hour = java.time.LocalTime.now().getHour();
        if (hour < 8) {
            bot.sendGroupMsg(event.getGroupId(),
                    MsgUtils.builder().reply(event.getMessageId())
                            .text("凌晨0点到8点禁止点兵，等到早上再来吧")
                            .build(),
                    false);
            return;
        }

        // 解析抽取人数
        String message = event.getMessage().trim(); // "可汗大点兵 3"
        int targetCount = 3;
        String[] parts = message.split("\\s+");
        if (parts.length > 1) {
            try {
                targetCount = Integer.parseInt(parts[1]);
            } catch (NumberFormatException ignored) {}
        }

        //获取群成员列表
        List<GroupMemberInfoResp> groupMemberList = bot.getGroupMemberList(event.getGroupId()).getData();
        if (groupMemberList == null || groupMemberList.isEmpty()) {
            bot.sendGroupMsg(event.getGroupId(),
                    MsgUtils.builder().reply(event.getMessageId())
                            .text("群成员为空，无法点兵")
                            .build(),
                    false);
            return;
        }

        //过滤出最近一天发言的成员
        long now = System.currentTimeMillis() / 1000L; // 当前时间戳（秒）
        long oneDaySeconds = 24 * 60 * 60;
        List<GroupMemberInfoResp> recentMembers = groupMemberList.stream()
                .filter(m -> m.getLastSentTime() > 0 && (now - m.getLastSentTime()) <= oneDaySeconds)
                .collect(Collectors.toList());

        if (recentMembers.size() < targetCount) {
            bot.sendGroupMsg(event.getGroupId(),
                    MsgUtils.builder().reply(event.getMessageId())
                            .text("没有这么多合格的兵")
                            .build(),
                    false);
            return;
        }

        //随机抽取 targetCount 个成员
        Collections.shuffle(recentMembers);
        List<GroupMemberInfoResp> selectedMembers = recentMembers.stream()
                .limit(targetCount)
                .toList();

        //构建艾特消息
        MsgUtils atMessage = MsgUtils.builder().reply(event.getMessageId());
        selectedMembers.forEach(member -> atMessage.at(member.getUserId()).text("\n"));
        atMessage.text("\n可汗" + event.getSender().getNickname() + "点兵了");

        bot.sendGroupMsg(event.getGroupId(), atMessage.build(), false);
    }


}
