package fun.luqing.dmws.service.dmw.economy;

import fun.luqing.dmws.common.economy.EconomyException;
import fun.luqing.dmws.common.economy.EconomyMoney;
import fun.luqing.dmws.entity.dmw.economy.EconomyUserProfile;
import fun.luqing.dmws.enums.EconomyAccountScope;
import fun.luqing.dmws.enums.EconomyLedgerReason;
import fun.luqing.dmws.repository.dmw.economy.EconomyAccountRepository;
import fun.luqing.dmws.repository.dmw.economy.EconomyUserProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
public class EconomyBankingService {

    private final EconomyOperationService operationService;
    private final EconomyUserProfileService profileService;
    private final EconomyLedgerService ledgerService;
    private final EconomyAccountRepository accountRepository;
    private final EconomyUserProfileRepository profileRepository;

    @Transactional(readOnly = true, transactionManager = "dmwTransactionManager")
    public Balances balances(long userId) {
        return new Balances(
                ledgerService.balance(userId, EconomyAccountScope.WALLET).balance(),
                ledgerService.balance(userId, EconomyAccountScope.MAIN_BANK).balance()
        );
    }

    @Transactional(transactionManager = "dmwTransactionManager")
    public Balances deposit(
            long userId,
            String nickname,
            BigDecimal amount,
            String operationId
    ) {
        operationService.start(operationId, "BANK_DEPOSIT", userId, "主银行存款");
        profileService.getOrCreateForUpdate(userId, nickname);
        EconomyLedgerService.TransferResult result = ledgerService.transferUser(
                userId,
                EconomyAccountScope.WALLET,
                userId,
                EconomyAccountScope.MAIN_BANK,
                amount,
                operationId,
                EconomyLedgerReason.BANK_DEPOSIT,
                userId,
                "主银行存款"
        );
        return new Balances(result.from().balance(), result.to().balance());
    }

    @Transactional(transactionManager = "dmwTransactionManager")
    public Balances withdraw(
            long userId,
            String nickname,
            BigDecimal amount,
            String operationId
    ) {
        operationService.start(operationId, "BANK_WITHDRAW", userId, "主银行取款");
        profileService.getOrCreateForUpdate(userId, nickname);
        EconomyLedgerService.TransferResult result = ledgerService.transferUser(
                userId,
                EconomyAccountScope.MAIN_BANK,
                userId,
                EconomyAccountScope.WALLET,
                amount,
                operationId,
                EconomyLedgerReason.BANK_WITHDRAW,
                userId,
                "主银行取款"
        );
        return new Balances(result.to().balance(), result.from().balance());
    }

    @Transactional(transactionManager = "dmwTransactionManager")
    public Balances transfer(
            long fromUserId,
            String fromNickname,
            long toUserId,
            BigDecimal amount,
            String operationId
    ) {
        if (fromUserId == toUserId) {
            throw new EconomyException("SAME_ACCOUNT", "不能给自己转账");
        }
        operationService.start(operationId, "USER_TRANSFER", fromUserId, "用户转账");
        profileService.getOrCreateForUpdate(fromUserId, fromNickname);
        EconomyLedgerService.TransferResult result = ledgerService.transferUser(
                fromUserId,
                EconomyAccountScope.WALLET,
                toUserId,
                EconomyAccountScope.WALLET,
                amount,
                operationId,
                EconomyLedgerReason.USER_TRANSFER,
                fromUserId,
                "转账给用户 " + toUserId
        );
        return new Balances(result.from().balance(), result.to().balance());
    }

    @Transactional(readOnly = true, transactionManager = "dmwTransactionManager")
    public List<LeaderboardEntry> bankLeaderboard(int limit) {
        return accountRepository.findAllByScopeOrderByBalanceDesc(EconomyAccountScope.MAIN_BANK)
                .stream()
                .filter(account -> account.getBalance().signum() > 0)
                .limit(Math.max(0, limit))
                .map(account -> {
                    EconomyUserProfile profile = account.getOwnerId() == null
                            ? null
                            : profileRepository.findById(account.getOwnerId()).orElse(null);
                    return new LeaderboardEntry(
                            account.getOwnerId() == null ? 0L : account.getOwnerId(),
                            profile == null || profile.getNickname().isBlank()
                                    ? "未知用户"
                                    : profile.getNickname(),
                            profile == null ? null : profile.getRegisterGroupId(),
                            profile == null ? "" : profile.getRegisterGroupName(),
                            account.getBalance()
                    );
                })
                .toList();
    }

    public record LeaderboardEntry(
            long userId,
            String nickname,
            Long registerGroupId,
            String registerGroupName,
            BigDecimal balance
    ) {
        public String groupDisplay() {
            if (registerGroupId == null) {
                return "未知群聊";
            }
            String name = registerGroupName == null || registerGroupName.isBlank()
                    ? "未知群聊"
                    : registerGroupName;
            return name;
        }
    }

    public record Balances(BigDecimal wallet, BigDecimal bank) {
        public String format() {
            return "钱包: " + EconomyMoney.format(wallet) + "，银行: " + EconomyMoney.format(bank)
                    + (bank.signum() < 0 ? "（医疗欠款，存入主银行可还款，不计息）" : "");
        }
    }
}
