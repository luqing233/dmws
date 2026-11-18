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
import fun.luqing.dmws.config.EconomyProperties;
import fun.luqing.dmws.service.dmw.economy.EconomyBankingService;
import fun.luqing.dmws.service.dmw.economy.EconomyPrivateBankService;
import fun.luqing.dmws.service.dmw.economy.EconomySignService;
import fun.luqing.dmws.service.dmw.economy.EconomyTitleService;
import fun.luqing.dmws.service.dmw.economy.EconomyUserProfileService;
import fun.luqing.dmws.service.dmw.economy.EconomyPlayerNameService;
import fun.luqing.dmws.service.dmw.economy.EconomyDebtQueryService;
import fun.luqing.dmws.service.dmw.economy.EconomyMarketService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Shiro
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "dmws.economy", name = "enabled", havingValue = "true", matchIfMissing = true)
public class EconomyAccountPlugin {

    private final EconomyBankingService bankingService;
    private final EconomySignService signService;
    private final EconomyTitleService titleService;
    private final EconomyPrivateBankService privateBankService;
    private final EconomyProperties economyProperties;
    private final EconomyUserProfileService profileService;
    private final DmwConfig dmwConfig;
    private final EconomyPlayerNameService names;
    private final EconomyMarketService repayments;
    private final EconomyDebtQueryService debtQueries;

    @GroupMessageHandler
    @MessageHandlerFilter(cmd = "^(欠款|我的欠款|总欠款)$")
    @CommandInfo(cmd = "我的欠款", desc = "查看总欠款，含全部贷款本金和利息")
    public void debt(Bot bot, GroupMessageEvent event) {
        EconomyCommandSupport.execute(bot, event, () -> debtQueries.describe(event.getUserId()));
    }

    @GroupMessageHandler
    @MessageHandlerFilter(cmd = "^还款(?:\\s+\\S+)?$")
    @CommandInfo(startWith = "还款", desc = "还款 <金额或全部>，统一偿还总欠款")
    public void repay(Bot bot, GroupMessageEvent event) {
        EconomyCommandSupport.execute(bot, event, () -> {
            String raw = EconomyCommandSupport.argument(event, "还款");
            if (raw.isBlank()) throw new EconomyException("ARGUMENT_REQUIRED", "格式：还款 <金额或全部>");
            BigDecimal amount = EconomyCommandSupport.requestsAll(raw) ? null : EconomyMoney.parse(raw);
            return repayments.repay(event.getUserId(), EconomyCommandSupport.nickname(event), amount,
                    EconomyCommandSupport.operationId("fx-repay", event)).displayText();
        });
    }

    @GroupMessageHandler
    @MessageHandlerFilter(cmd = "(?i)^(签到|打卡|sign)$")
    @CommandInfo(cmd = "签到", desc = "每日签到，奖励 50 到 500 金币")
    public void sign(Bot bot, GroupMessageEvent event) {
        EconomyCommandSupport.execute(bot, event, () -> {
            EconomySignService.SignResult result = signService.sign(
                    event.getUserId(),
                    EconomyCommandSupport.nickname(event),
                    EconomyCommandSupport.operationId("sign", event)
            );
            return "签到成功！连续签到 " + result.streak() + " 天，获得 "
                    + EconomyMoney.format(result.reward()) + " 金币" + result.bonusText()
                    + "\n钱包余额: " + EconomyMoney.format(result.walletBalance());
        });
    }

    @GroupMessageHandler
    @MessageHandlerFilter(cmd = "(?i)^(经济信息|money|我的资金|个人信息|info|我的信息|用户信息)$")
    @CommandInfo(cmd = "经济信息", desc = "查看个人信息和钱包、银行余额")
    public void info(Bot bot, GroupMessageEvent event) {
        EconomyCommandSupport.execute(bot, event, () -> {
            EconomyBankingService.Balances balances =
                    bankingService.balances(event.getUserId());
            return "经济信息\n用户: " + EconomyCommandSupport.nickname(event)
                    + "\n称号: " + titleService.displayName(event.getUserId())
                    + "\n" + balances.format();
        });
    }

    @GroupMessageHandler
    @MessageHandlerFilter(startWith = {"存款", "deposit"})
    @CommandInfo(startWith = "存款", desc = "存款 <金额>，或使用 存款! 一键存入钱包全部余额")
    public void deposit(Bot bot, GroupMessageEvent event) {
        EconomyCommandSupport.execute(bot, event, () -> {
            String[] parts = EconomyCommandSupport.arguments(event, "存款", "deposit");
            EconomyPrivateBankService.BankView defaultBank =
                    privateBankService.defaultBank(event.getUserId());
            String bankKey = parts.length > 1 ? parts[1] : defaultBank == null ? null : defaultBank.code();
            bankKey = normalizeBankRoute(bankKey);
            BigDecimal amount = amountOrAll(
                    parts,
                    bankingService.balances(event.getUserId()).wallet()
            );
            if (bankKey != null && !bankKey.isBlank()) {
                EconomyPrivateBankService.BankView bank = privateBankService.deposit(
                        event.getUserId(),
                        EconomyCommandSupport.nickname(event),
                        bankKey,
                        amount,
                        EconomyCommandSupport.operationId("private-bank-deposit", event)
                );
                return "存款成功: " + EconomyMoney.format(amount) + " -> " + bank.name()
                        + "\n银行资金: " + EconomyMoney.format(bank.capital());
            }
            EconomyBankingService.Balances balances = bankingService.deposit(
                    event.getUserId(),
                    EconomyCommandSupport.nickname(event),
                    amount,
                    EconomyCommandSupport.operationId("bank-deposit", event)
            );
            return "存款成功: " + EconomyMoney.format(amount) + "\n" + balances.format();
        });
    }

    @GroupMessageHandler
    @MessageHandlerFilter(startWith = {"取款", "withdraw"})
    @CommandInfo(startWith = "取款", desc = "取款 <金额>，或使用 取款! 一键取回主银行全部余额")
    public void withdraw(Bot bot, GroupMessageEvent event) {
        EconomyCommandSupport.execute(bot, event, () -> {
            String[] parts = EconomyCommandSupport.arguments(event, "取款", "withdraw");
            EconomyPrivateBankService.BankView defaultBank =
                    privateBankService.defaultBank(event.getUserId());
            String bankKey = parts.length > 1 ? parts[1] : defaultBank == null ? null : defaultBank.code();
            bankKey = normalizeBankRoute(bankKey);
            BigDecimal available = bankKey == null
                    ? bankingService.balances(event.getUserId()).bank()
                    : privateBankService.depositBalance(event.getUserId(), bankKey);
            BigDecimal amount = amountOrAll(parts, available);
            if (bankKey != null && !bankKey.isBlank()) {
                EconomyPrivateBankService.BankView bank = privateBankService.withdraw(
                        event.getUserId(),
                        EconomyCommandSupport.nickname(event),
                        bankKey,
                        amount,
                        EconomyCommandSupport.operationId("private-bank-withdraw", event)
                );
                return "取款成功: " + EconomyMoney.format(amount) + " 从 " + bank.name()
                        + "\n银行资金: " + EconomyMoney.format(bank.capital());
            }
            EconomyBankingService.Balances balances = bankingService.withdraw(
                    event.getUserId(),
                    EconomyCommandSupport.nickname(event),
                    amount,
                    EconomyCommandSupport.operationId("bank-withdraw", event)
            );
            return "取款成功: " + EconomyMoney.format(amount) + "\n" + balances.format();
        });
    }

    @GroupMessageHandler
    @MessageHandlerFilter(startWith = {"转账"})
    @CommandInfo(startWith = "转账", desc = "转账 @用户 <金额>")
    public void transfer(Bot bot, GroupMessageEvent event) {
        EconomyCommandSupport.execute(bot, event, () -> {
            Long target = EconomyCommandSupport.mentionedUserId(event);
            if (target == null) {
                throw new EconomyException("TARGET_REQUIRED", "请 @ 要转账的用户");
            }
            String targetName = names.resolve(
                    bot,
                    event.getGroupId(),
                    target
            );
            profileService.rememberGroup(
                    target,
                    targetName,
                    event.getGroupId(),
                    EconomyCommandSupport.groupName(bot, event.getGroupId())
            );
            String argument = EconomyCommandSupport.argument(event, "转账");
            String[] parts = argument.split("\\s+");
            if (parts.length == 0 || parts[0].isBlank()) {
                throw new EconomyException("INVALID_AMOUNT", "请提供转账金额");
            }
            BigDecimal amount = EconomyMoney.parse(parts[0]);
            EconomyBankingService.Balances balances = bankingService.transfer(
                    event.getUserId(),
                    EconomyCommandSupport.nickname(event),
                    target,
                    amount,
                    EconomyCommandSupport.operationId("transfer", event)
            );
            return "转账成功: " + EconomyMoney.format(amount) + " -> "
                    + targetName + "\n钱包余额: " + EconomyMoney.format(balances.wallet());
        });
    }

    @GroupMessageHandler
    @MessageHandlerFilter(cmd = "^(富豪榜|经济排行)$")
    @CommandInfo(cmd = "富豪榜", desc = "查看主银行存款排行榜")
    public void leaderboard(Bot bot, GroupMessageEvent event) {
        EconomyCommandSupport.execute(bot, event, () -> {
            var entries = bankingService.bankLeaderboard(10);
            if (entries.isEmpty()) {
                return "暂无银行存款排行数据";
            }
            var playerNames = names.resolveAll(bot, event.getGroupId(), entries.stream().map(EconomyBankingService.LeaderboardEntry::userId).toList());
            entries = entries.stream().map(entry -> new EconomyBankingService.LeaderboardEntry(entry.userId(),
                    playerNames.get(entry.userId()), entry.registerGroupId(), entry.registerGroupName(), entry.balance())).toList();
            EconomyCommandSupport.sendForward(
                    bot,
                    event,
                    dmwConfig,
                    EconomyDisplayFormatter.leaderboard(entries)
            );
            return null;
        });
    }

    @GroupMessageHandler
    @MessageHandlerFilter(cmd = "^(本周利率|银行利率)$")
    @CommandInfo(cmd = "银行利率", desc = "查看主银行本周利率")
    public void bankRate(Bot bot, GroupMessageEvent event) {
        EconomyCommandSupport.execute(bot, event, () -> {
            BigDecimal rate = economyProperties.getBankWeeklyRate()
                    .multiply(new BigDecimal("100"))
                    .setScale(2, RoundingMode.HALF_UP);
            return "本周主银行利率: " + EconomyMoney.format(rate) + "%";
        });
    }

    private BigDecimal amountOrAll(String[] parts, BigDecimal allBalance) {
        if (parts.length == 0 || EconomyCommandSupport.requestsAll(parts[0])) {
            if (allBalance.signum() <= 0) {
                throw new EconomyException("INSUFFICIENT_BALANCE", "当前账户没有可操作余额");
            }
            return EconomyMoney.normalize(allBalance);
        }
        String normalized = parts[0].replace('！', '!').trim();
        while (normalized.startsWith("!")) {
            normalized = normalized.substring(1).trim();
        }
        return EconomyMoney.parse(normalized);
    }

    private String normalizeBankRoute(String bankKey) {
        if (bankKey == null) {
            return null;
        }
        String value = bankKey.trim();
        return value.isBlank() || "main".equalsIgnoreCase(value) || "主银行".equals(value)
                ? null
                : value;
    }
}
