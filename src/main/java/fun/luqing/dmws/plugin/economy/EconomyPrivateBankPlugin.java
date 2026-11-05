package fun.luqing.dmws.plugin.economy;

import com.mikuac.shiro.annotation.GroupMessageHandler;
import com.mikuac.shiro.annotation.MessageHandlerFilter;
import com.mikuac.shiro.annotation.common.Shiro;
import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.dto.event.message.GroupMessageEvent;
import fun.luqing.dmws.common.annotation.CommandInfo;
import fun.luqing.dmws.common.economy.EconomyException;
import fun.luqing.dmws.common.economy.EconomyMoney;
import fun.luqing.dmws.service.dmw.economy.EconomyPrivateBankService;
import fun.luqing.dmws.service.dmw.economy.EconomyPlayerNameService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

@Shiro
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "dmws.economy", name = "enabled", havingValue = "true", matchIfMissing = true)
public class EconomyPrivateBankPlugin {

    private final EconomyPrivateBankService privateBankService;
    private final EconomyPlayerNameService names;

    @GroupMessageHandler
    @MessageHandlerFilter(startWith = {"银行创建"})
    @CommandInfo(startWith = "银行创建", desc = "银行创建 <编码> <名称>，需要主银行存款达到创建门槛")
    public void create(Bot bot, GroupMessageEvent event) {
        EconomyCommandSupport.execute(bot, event, () -> {
            String[] parts = EconomyCommandSupport.arguments(event, "银行创建");
            if (parts.length < 2) {
                throw new EconomyException("ARGUMENT_REQUIRED", "格式: 银行创建 <编码> <名称>");
            }
            EconomyPrivateBankService.BankView bank = privateBankService.create(
                    event.getUserId(),
                    EconomyCommandSupport.nickname(event),
                    parts[0],
                    parts[1],
                    EconomyCommandSupport.operationId("private-bank-create", event)
            );
            return "银行创建成功: " + bank.name() + " [" + bank.code() + "]"
                    + "\n银行资金: " + EconomyMoney.format(bank.capital());
        });
    }

    @GroupMessageHandler
    @MessageHandlerFilter(cmd = "^银行列表$")
    @CommandInfo(cmd = "银行列表", desc = "查看全部私人银行")
    public void list(Bot bot, GroupMessageEvent event) {
        EconomyCommandSupport.execute(bot, event, () -> {
            List<EconomyPrivateBankService.BankView> banks = privateBankService.list();
            if (banks.isEmpty()) {
                return "暂无私人银行";
            }
            StringBuilder builder = new StringBuilder("私人银行列表");
            var owners = names.resolveAll(bot, event.getGroupId(), banks.stream().map(EconomyPrivateBankService.BankView::ownerId).toList());
            for (EconomyPrivateBankService.BankView bank : banks) {
                builder.append("\n- ").append(bank.name())
                        .append(" [").append(bank.code()).append("]")
                        .append(" 行长:").append(owners.get(bank.ownerId()))
                        .append(" 资金:").append(EconomyMoney.format(bank.capital()));
            }
            return builder.toString();
        });
    }

    @GroupMessageHandler
    @MessageHandlerFilter(startWith = {"银行信息", "私人银行信息"})
    @CommandInfo(startWith = "银行信息", desc = "银行信息 <编码或名称>")
    public void info(Bot bot, GroupMessageEvent event) {
        EconomyCommandSupport.execute(bot, event, () -> {
            String[] parts = EconomyCommandSupport.arguments(event, "银行信息", "私人银行信息");
            if (parts.length == 0) {
                EconomyPrivateBankService.BankView bank = privateBankService.myBank(event.getUserId());
                if (bank == null) {
                    throw new EconomyException("ARGUMENT_REQUIRED", "请提供银行编码或名称");
                }
                return formatBank(bank, bot, event.getGroupId());
            }
            EconomyPrivateBankService.BankView bank = privateBankService.info(parts[0]);
            return formatBank(bank, bot, event.getGroupId());
        });
    }

    @GroupMessageHandler
    @MessageHandlerFilter(cmd = "^我的银行$")
    @CommandInfo(cmd = "我的银行", desc = "查看自己创建的银行")
    public void myBank(Bot bot, GroupMessageEvent event) {
        EconomyCommandSupport.execute(bot, event, () -> {
            EconomyPrivateBankService.BankView bank = privateBankService.myBank(event.getUserId());
            return bank == null ? "你还没有创建银行" : formatBank(bank, bot, event.getGroupId());
        });
    }

    @GroupMessageHandler
    @MessageHandlerFilter(cmd = "^默认银行$")
    @CommandInfo(cmd = "默认银行", desc = "查看默认私人银行")
    public void defaultBank(Bot bot, GroupMessageEvent event) {
        EconomyCommandSupport.execute(bot, event, () -> {
            EconomyPrivateBankService.BankView bank =
                    privateBankService.defaultBank(event.getUserId());
            return bank == null
                    ? "尚未设置默认银行，存取款默认使用主银行"
                    : "当前默认银行: " + bank.name() + " [" + bank.code() + "]";
        });
    }

    @GroupMessageHandler
    @MessageHandlerFilter(startWith = {"默认银行设置"})
    @CommandInfo(startWith = "默认银行设置", desc = "默认银行设置 <银行编码或名称>")
    public void setDefaultBank(Bot bot, GroupMessageEvent event) {
        EconomyCommandSupport.execute(bot, event, () -> {
            String[] parts = EconomyCommandSupport.arguments(event, "默认银行设置");
            if (parts.length == 0) {
                throw new EconomyException("ARGUMENT_REQUIRED", "请提供银行编码或名称");
            }
            EconomyPrivateBankService.BankView bank = privateBankService.setDefaultBank(
                    event.getUserId(),
                    EconomyCommandSupport.nickname(event),
                    parts[0]
            );
            return "默认银行已设置为: " + bank.name() + " [" + bank.code() + "]";
        });
    }

    @GroupMessageHandler
    @MessageHandlerFilter(startWith = {"存银行", "私人银行存款"})
    @CommandInfo(startWith = "存银行", desc = "存银行 <编码或名称> <金额>")
    public void deposit(Bot bot, GroupMessageEvent event) {
        EconomyCommandSupport.execute(bot, event, () -> {
            String[] parts = EconomyCommandSupport.arguments(event, "存银行", "私人银行存款");
            if (parts.length < 2) {
                throw new EconomyException("ARGUMENT_REQUIRED", "格式: 存银行 <编码或名称> <金额>");
            }
            BigDecimal amount = EconomyMoney.parse(parts[1]);
            EconomyPrivateBankService.BankView bank = privateBankService.deposit(
                    event.getUserId(),
                    EconomyCommandSupport.nickname(event),
                    parts[0],
                    amount,
                    EconomyCommandSupport.operationId("private-bank-deposit", event)
            );
            return "存款成功: " + EconomyMoney.format(amount)
                    + " -> " + bank.name()
                    + "\n银行资金: " + EconomyMoney.format(bank.capital());
        });
    }

    @GroupMessageHandler
    @MessageHandlerFilter(startWith = {"取银行", "私人银行取款"})
    @CommandInfo(startWith = "取银行", desc = "取银行 <编码或名称> <金额>")
    public void withdraw(Bot bot, GroupMessageEvent event) {
        EconomyCommandSupport.execute(bot, event, () -> {
            String[] parts = EconomyCommandSupport.arguments(event, "取银行", "私人银行取款");
            if (parts.length < 2) {
                throw new EconomyException("ARGUMENT_REQUIRED", "格式: 取银行 <编码或名称> <金额>");
            }
            BigDecimal amount = EconomyMoney.parse(parts[1]);
            EconomyPrivateBankService.BankView bank = privateBankService.withdraw(
                    event.getUserId(),
                    EconomyCommandSupport.nickname(event),
                    parts[0],
                    amount,
                    EconomyCommandSupport.operationId("private-bank-withdraw", event)
            );
            return "取款成功: " + EconomyMoney.format(amount)
                    + " 从 " + bank.name()
                    + "\n银行资金: " + EconomyMoney.format(bank.capital());
        });
    }

    @GroupMessageHandler
    @MessageHandlerFilter(startWith = {"银行贷款", "贷款", "借款"})
    @CommandInfo(startWith = "银行贷款", desc = "银行贷款 <编码或名称> <金额> <天数>")
    public void borrow(Bot bot, GroupMessageEvent event) {
        EconomyCommandSupport.execute(bot, event, () -> {
            String[] parts = EconomyCommandSupport.arguments(event, "银行贷款", "贷款", "借款");
            if (parts.length < 3) {
                throw new EconomyException("ARGUMENT_REQUIRED", "格式: 银行贷款 <编码或名称> <金额> <天数>");
            }
            BigDecimal amount = EconomyMoney.parse(parts[1]);
            int days = EconomyCommandSupport.quantity(parts[2], 0);
            EconomyPrivateBankService.LoanView loan = privateBankService.borrow(
                    event.getUserId(),
                    EconomyCommandSupport.nickname(event),
                    parts[0],
                    amount,
                    days,
                    EconomyCommandSupport.operationId("private-bank-loan", event)
            );
            return "借款成功，贷款编号: " + loan.id()
                    + "\n本金: " + EconomyMoney.format(loan.principal())
                    + "\n应还: " + EconomyMoney.format(loan.totalDue())
                    + "\n到期时间: " + loan.dueAt().toLocalDate();
        });
    }

    @GroupMessageHandler
    @MessageHandlerFilter(startWith = {"银行还款", "还款"})
    @CommandInfo(startWith = "银行还款", desc = "银行还款 <编码或名称> <金额>")
    public void repay(Bot bot, GroupMessageEvent event) {
        EconomyCommandSupport.execute(bot, event, () -> {
            String[] parts = EconomyCommandSupport.arguments(event, "银行还款", "还款");
            if (parts.length < 2) {
                throw new EconomyException("ARGUMENT_REQUIRED", "格式: 银行还款 <编码或名称> <金额>");
            }
            BigDecimal amount = EconomyMoney.parse(parts[1]);
            EconomyPrivateBankService.RepaymentView result = privateBankService.repay(
                    event.getUserId(),
                    EconomyCommandSupport.nickname(event),
                    parts[0],
                    amount,
                    EconomyCommandSupport.operationId("private-bank-repay", event)
            );
            return "还款成功: " + EconomyMoney.format(result.repaid())
                    + "\n银行: " + result.bankCode()
                    + (result.unusedAmount().signum() > 0
                    ? "\n未使用金额: " + EconomyMoney.format(result.unusedAmount())
                    : "");
        });
    }

    private String formatBank(EconomyPrivateBankService.BankView bank, Bot bot, long groupId) {
        return "银行信息"
                + "\n名称: " + bank.name()
                + "\n编码: " + bank.code()
                + "\n行长: " + names.resolve(bot, groupId, bank.ownerId())
                + "\n资金: " + EconomyMoney.format(bank.capital())
                + "\n描述: " + bank.description();
    }
}
