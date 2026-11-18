package fun.luqing.dmws.plugin.economy;

import com.mikuac.shiro.annotation.GroupMessageHandler;
import com.mikuac.shiro.annotation.MessageHandlerFilter;
import com.mikuac.shiro.annotation.common.Shiro;
import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.dto.event.message.GroupMessageEvent;
import fun.luqing.dmws.common.annotation.CommandInfo;
import fun.luqing.dmws.common.economy.EconomyException;
import fun.luqing.dmws.common.economy.EconomyMoney;
import fun.luqing.dmws.service.dmw.economy.EconomyConditionService;
import fun.luqing.dmws.service.dmw.economy.EconomyGameTime;
import fun.luqing.dmws.service.dmw.economy.EconomyGameText;
import fun.luqing.dmws.service.dmw.economy.EconomyPlayerNameService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Shiro
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "dmws.economy", name = "enabled", havingValue = "true", matchIfMissing = true)
public class EconomyConditionPlugin {
    private final EconomyConditionService conditions;
    private final EconomyGameTime gameTime;
    private final EconomyPlayerNameService names;

    @GroupMessageHandler
    @MessageHandlerFilter(startWith = {"当前位置", "我的位置", "查询位置", "玩家位置", "位置"})
    @CommandInfo(cmd = "当前位置", desc = "当前位置 [@玩家或QQ号]，查询医院、监狱或自由活动")
    public void location(Bot bot, GroupMessageEvent event) {
        EconomyCommandSupport.execute(bot, event, () -> {
            Long target = EconomyCommandSupport.mentionedUserId(event);
            String argument = EconomyCommandSupport.argument(event, "当前位置", "我的位置", "查询位置", "玩家位置", "位置");
            if (target == null && !argument.isBlank()) {
                try { target = Long.parseLong(argument); }
                catch (NumberFormatException e) { throw new EconomyException("TARGET_REQUIRED", "发送“当前位置”查自己，或“当前位置 @玩家”查群友"); }
                if (target <= 0) throw new EconomyException("INVALID_USER", "玩家 QQ 号必须为正整数");
            }
            long userId = target == null ? event.getUserId() : target;
            String name = EconomyCommandSupport.nickname(event);
            if (userId != event.getUserId()) {
                var response = bot.getGroupMemberList(event.getGroupId());
                if (response == null || response.getData() == null) throw new EconomyException("MEMBERS_UNAVAILABLE", "暂时无法获取群成员，请稍后再试");
                var member = response.getData().stream().filter(m -> m.getUserId() != null && m.getUserId() == userId)
                        .findFirst().orElseThrow(() -> new EconomyException("TARGET_NOT_IN_GROUP", "只能查询当前群的玩家"));
                name = names.member(bot, event.getGroupId(), member);
            }
            return conditions.describeLocation(conditions.location(userId, ""), name);
        });
    }

    @GroupMessageHandler
    @MessageHandlerFilter(cmd = "^(我的状态|伤势|住院信息|监狱信息|犯罪值)$")
    @CommandInfo(cmd = "我的状态", desc = "查询伤势、住院费、服刑时间与犯罪值")
    public void status(Bot bot, GroupMessageEvent event) {
        EconomyCommandSupport.execute(bot, event, () -> format(conditions.status(event.getUserId(), EconomyCommandSupport.nickname(event))));
    }

    @GroupMessageHandler
    @MessageHandlerFilter(cmd = "^出院$")
    @CommandInfo(cmd = "出院", desc = "提前出院并结算医疗费，不足自动记入主银行欠款")
    public void discharge(Bot bot, GroupMessageEvent event) {
        EconomyCommandSupport.execute(bot, event, () -> conditions.dischargeReceipt(event.getUserId(),
                EconomyCommandSupport.nickname(event), EconomyCommandSupport.operationId("discharge", event)).message());
    }

    @GroupMessageHandler
    @MessageHandlerFilter(startWith = {"保释"})
    @CommandInfo(startWith = "保释", desc = "保释 @用户；保释费用 @用户 可先查价")
    public void bail(Bot bot, GroupMessageEvent event) {
        EconomyCommandSupport.execute(bot, event, () -> {
            Long target = EconomyCommandSupport.mentionedUserId(event);
            if (target == null) {
                if (!EconomyCommandSupport.isExact(EconomyCommandSupport.plainText(event), "保释", "保释费用", "保释查询")) return null;
                throw new EconomyException("TARGET_REQUIRED", "请 @ 要保释的用户；先查询对方的监狱信息核对保释费");
            }
            var response = bot.getGroupMemberList(event.getGroupId());
            if (response == null || response.getData() == null || response.getData().stream().noneMatch(m -> target.equals(m.getUserId()))) {
                throw new EconomyException("TARGET_NOT_IN_GROUP", "无法确认被保释人在当前群聊中");
            }
            var targetMember = response.getData().stream().filter(m -> target.equals(m.getUserId())).findFirst().orElseThrow();
            String targetName = names.member(bot, event.getGroupId(), targetMember);
            String plain = EconomyCommandSupport.plainText(event);
            if (plain.startsWith("保释费用") || plain.startsWith("保释查询")) {
                var status = conditions.status(target, "");
                if (status.jailUntil() == null) return targetName + " 当前未服刑，无需保释";
                return targetName + " 的保释费：" + EconomyMoney.format(status.bailFee()) + " 金币"
                        + "\n释放时间：" + EconomyGameText.time(status.jailUntil(), gameTime.now())
                        + "\n确认支付请发送“保释 @对方”";
            }
            String raw = event.getMessage() == null ? plain : event.getMessage();
            if (!raw.replaceAll("\\[CQ:at,[^]]*]", "").trim().equals("保释")) {
                throw new EconomyException("BAIL_COMMAND", "查询请发送 保释费用 @用户；确认支付请发送 保释 @用户");
            }
            var result = conditions.bail(event.getUserId(), EconomyCommandSupport.nickname(event), target,
                    EconomyCommandSupport.operationId("bail", event));
            return "保释成功，" + targetName + " 已出狱\n已支付保释费：" + EconomyMoney.format(result.fee()) + " 金币";
        });
    }

    private String format(EconomyConditionService.Status status) {
        String text = "犯罪值: " + status.crime() + "/100";
        if (status.hospitalUntil() != null) text += "\n住院中，伤势 " + EconomyGameText.injury(status.injury()) + "（" + status.injury() + "/100）\n最晚强制出院: " + EconomyGameText.time(status.hospitalUntil(), gameTime.now())
                + "\n当前医疗费: " + EconomyMoney.format(status.currentHospitalFee()) + "；本人发送“出院”办理，满24小时才强制出院";
        else text += "\n当前未住院";
        if (status.jailUntil() != null) text += "\n服刑至: " + EconomyGameText.time(status.jailUntil(), gameTime.now()) + "\n保释费: " + EconomyMoney.format(status.bailFee());
        else text += "\n当前未服刑";
        if (status.lastDischargedAt() != null) text += "\n上次出院: " + EconomyGameText.time(status.lastDischargedAt(), gameTime.now())
                + "，医疗费 " + EconomyMoney.format(status.lastHospitalFee())
                + (status.lastMedicalLoan() != null && status.lastMedicalLoan().signum() > 0
                ? "，新增欠款 " + EconomyMoney.format(status.lastMedicalLoan()) : "");
        if (status.totalDebt().signum() > 0) text += "\n总欠款：" + EconomyMoney.format(status.totalDebt()) + " 金币";
        return text;
    }
}
