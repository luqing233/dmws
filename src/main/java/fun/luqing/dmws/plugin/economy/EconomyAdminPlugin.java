package fun.luqing.dmws.plugin.economy;

import com.mikuac.shiro.annotation.GroupMessageHandler;
import com.mikuac.shiro.annotation.MessageHandlerFilter;
import com.mikuac.shiro.annotation.common.Shiro;
import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.dto.event.message.GroupMessageEvent;
import fun.luqing.dmws.common.annotation.CommandInfo;
import fun.luqing.dmws.common.economy.EconomyException;
import fun.luqing.dmws.common.economy.EconomyMoney;
import fun.luqing.dmws.config.DmwConfig;
import fun.luqing.dmws.service.dmw.economy.EconomyAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Shiro
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "dmws.economy", name = "enabled", havingValue = "true", matchIfMissing = true)
public class EconomyAdminPlugin {

    private final DmwConfig dmwConfig;
    private final EconomyAdminService adminService;

    @GroupMessageHandler
    @MessageHandlerFilter(startWith = {"发钱", "加钱"})
    @CommandInfo(startWith = "发钱", desc = "管理员：发钱 @用户 <金额>")
    public void credit(Bot bot, GroupMessageEvent event) {
        adjust(bot, event, true, "发钱", "加钱");
    }

    @GroupMessageHandler
    @MessageHandlerFilter(startWith = {"扣钱", "减钱"})
    @CommandInfo(startWith = "扣钱", desc = "管理员：扣钱 @用户 <金额>")
    public void debit(Bot bot, GroupMessageEvent event) {
        adjust(bot, event, false, "扣钱", "减钱");
    }

    @GroupMessageHandler
    @MessageHandlerFilter(startWith = {"greedisgood"})
    @CommandInfo(startWith = "greedisgood", desc = "管理员：greedisgood <金额>")
    public void greedisgood(Bot bot, GroupMessageEvent event) {
        EconomyCommandSupport.execute(bot, event, () -> {
            if (!dmwConfig.isMaster(event.getUserId())) {
                throw new EconomyException("PERMISSION_DENIED", "只有机器人主人可以执行该操作");
            }
            String[] parts = EconomyCommandSupport.arguments(event, "greedisgood");
            if (parts.length == 0) {
                throw new EconomyException("ARGUMENT_REQUIRED", "格式: greedisgood <金额>");
            }
            BigDecimal amount = EconomyMoney.parse(parts[0]);
            BigDecimal balance = adminService.adjust(
                    event.getUserId(),
                    event.getUserId(),
                    EconomyCommandSupport.nickname(event),
                    amount,
                    true,
                    EconomyCommandSupport.operationId("greedisgood", event)
            );
            return "已增加 " + EconomyMoney.format(amount)
                    + "\n当前余额: " + EconomyMoney.format(balance);
        });
    }

    private void adjust(
            Bot bot,
            GroupMessageEvent event,
            boolean credit,
            String... prefixes
    ) {
        EconomyCommandSupport.execute(bot, event, () -> {
            if (!dmwConfig.isMaster(event.getUserId())) {
                throw new EconomyException("PERMISSION_DENIED", "只有机器人主人可以执行该操作");
            }
            Long target = EconomyCommandSupport.mentionedUserId(event);
            if (target == null) {
                throw new EconomyException("TARGET_REQUIRED", "请 @ 要调整的用户");
            }
            String[] parts = EconomyCommandSupport.arguments(event, prefixes);
            BigDecimal amount = parts.length == 0
                    ? EconomyCommandSupport.amount(event, prefixes)
                    : EconomyMoney.parse(parts[0]);
            String operationId = EconomyCommandSupport.operationId(
                    credit ? "admin-credit" : "admin-debit",
                    event
            );
            BigDecimal balance = adminService.adjust(
                    event.getUserId(),
                    target,
                    null,
                    amount,
                    credit,
                    operationId
            );
            return (credit ? "已增加 " : "已扣减 ") + EconomyMoney.format(amount)
                    + "\n目标用户余额: " + EconomyMoney.format(balance);
        });
    }
}
