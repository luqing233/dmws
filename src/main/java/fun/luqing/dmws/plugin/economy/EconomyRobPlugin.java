package fun.luqing.dmws.plugin.economy;

import com.mikuac.shiro.annotation.GroupMessageHandler;
import com.mikuac.shiro.annotation.MessageHandlerFilter;
import com.mikuac.shiro.annotation.common.Shiro;
import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.dto.event.message.GroupMessageEvent;
import fun.luqing.dmws.common.annotation.CommandInfo;
import fun.luqing.dmws.common.economy.EconomyException;
import fun.luqing.dmws.common.economy.EconomyMoney;
import fun.luqing.dmws.service.dmw.economy.EconomyRobService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Shiro
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "dmws.economy", name = "enabled", havingValue = "true", matchIfMissing = true)
public class EconomyRobPlugin {

    private final EconomyRobService robService;

    @GroupMessageHandler
    @MessageHandlerFilter(startWith = {"抢劫", "打人"})
    @CommandInfo(startWith = "抢劫", desc = "抢劫/打人 @用户，有冷却和失败罚款")
    public void rob(Bot bot, GroupMessageEvent event) {
        EconomyCommandSupport.execute(bot, event, () -> {
            Long target = EconomyCommandSupport.mentionedUserId(event);
            if (target == null) {
                throw new EconomyException("TARGET_REQUIRED", "请 @ 要抢劫的用户");
            }
            String targetName = EconomyCommandSupport.groupMemberDisplayName(
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
            if (result.poorTarget()) {
                String ending = result.resisted()
                        ? "你气不过，把对方按在地上一顿输出，然后骂骂咧咧地走了。"
                        : "他翻遍全身也凑不出一顿早饭钱，你只能自认倒霉。";
                return "你盯上了 " + targetName + "，结果发现对方是个穷光蛋。\n"
                        + ending
                        + "\n对方钱包: " + EconomyMoney.format(result.targetBalanceAfter())
                        + "\n你的钱包: " + EconomyMoney.format(result.walletBalance());
            }
            if (result.success()) {
                String action = result.resisted()
                        ? "对方拼命反抗，但你还是抢到钱后溜了！"
                        : "行动异常顺利，你趁着对方没反应过来拔腿就跑！";
                return "抢劫成功！\n"
                        + action
                        + (result.robBoostUsed() ? "\n本次消耗了抢劫增益道具。" : "")
                        + "\n目标: " + targetName
                        + "\n抢到: " + EconomyMoney.format(result.stolen())
                        + "\n对方剩余: " + EconomyMoney.format(result.targetBalanceAfter())
                        + "\n你的钱包: " + EconomyMoney.format(result.walletBalance());
            }
            if (result.caught()) {
                return "倒霉！警笛突然响起，你被警察当场抓获。\n"
                        + (result.robBoostUsed() ? "本次消耗了抢劫增益道具。\n" : "")
                        + "罚款: " + EconomyMoney.format(result.fine())
                        + "\n目标: " + targetName
                        + "\n你的钱包: " + EconomyMoney.format(result.walletBalance())
                        + "\n下次行动前先看看黄历吧。";
            }
            return "差一点！你刚把手伸向 " + targetName + " 的钱包，"
                    + "远处就传来了警笛声。\n你头也不回地跑了，这次一分钱没抢到。"
                    + (result.robBoostUsed() ? "\n本次消耗了抢劫增益道具。" : "")
                    + "\n你的钱包: " + EconomyMoney.format(result.walletBalance());
        });
    }
}
