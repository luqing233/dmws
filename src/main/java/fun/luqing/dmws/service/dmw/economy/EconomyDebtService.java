package fun.luqing.dmws.service.dmw.economy;

import fun.luqing.dmws.common.economy.EconomyException;
import fun.luqing.dmws.common.economy.EconomyMoney;
import fun.luqing.dmws.entity.dmw.economy.EconomyMarketState;
import fun.luqing.dmws.entity.dmw.economy.EconomyPrivateBank;
import fun.luqing.dmws.enums.EconomyAccountScope;
import fun.luqing.dmws.enums.EconomyLedgerReason;
import fun.luqing.dmws.enums.EconomyLoanStatus;
import fun.luqing.dmws.repository.dmw.economy.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional(transactionManager = "dmwTransactionManager", isolation = Isolation.READ_COMMITTED)
public class EconomyDebtService {
    @jakarta.persistence.PersistenceContext(unitName = "dmw")
    private jakarta.persistence.EntityManager entityManager;
    private final EconomyDebtQueryService queries;
    private final EconomyLedgerService ledger;
    private final EconomyOperationService operations;
    private final EconomyUserProfileService profiles;
    private final EconomyPrivateBankLoanService loanService;
    private final EconomyPrivateBankLoanRepository loans;
    private final EconomyPrivateBankRepository banks;
    private final EconomyMarketStateRepository marketStates;

    public Repayment repay(long userId, String nickname, BigDecimal requested, String operationId) {
        var state = marketStates.lockMarket().orElse(null);
        operations.start(operationId, "DEBT_REPAY", userId, "偿还欠款");
        var creditors = lockCreditors(userId);
        lockPlayer(userId, nickname, creditors);
        BigDecimal owed = queries.total(userId);
        if (owed.signum() <= 0) throw new EconomyException("NO_DEBT", "当前没有欠款");
        BigDecimal paid = requested == null ? owed : EconomyMoney.positive(requested).min(owed);
        // All-or-nothing funding, before any creditor receives money.
        ledger.debitUserWithBankFallback(userId, paid, operationId, EconomyLedgerReason.BANK_DEPOSIT,
                userId, "偿还欠款");
        distribute(userId, paid, operationId, creditors, state);
        return new Repayment(paid, queries.total(userId));
    }

    /** Deposits pay the same total debt first; only the excess becomes bank savings. */
    public void deposit(long userId, String nickname, BigDecimal requested, String operationId) {
        BigDecimal amount = EconomyMoney.positive(requested);
        var state = marketStates.lockMarket().orElse(null);
        operations.start(operationId, "BANK_DEPOSIT", userId, "主银行存款并优先还款");
        var creditors = lockCreditors(userId);
        lockPlayer(userId, nickname, creditors);
        BigDecimal paid = amount.min(queries.total(userId));
        ledger.debitUser(userId, EconomyAccountScope.WALLET, amount, operationId,
                EconomyLedgerReason.BANK_DEPOSIT, userId, "存入主银行并优先还款");
        distribute(userId, paid, operationId, creditors, state);
        BigDecimal surplus = amount.subtract(paid);
        if (surplus.signum() > 0) ledger.creditUser(userId, EconomyAccountScope.MAIN_BANK, surplus,
                operationId, EconomyLedgerReason.BANK_DEPOSIT, userId, "还款后存入主银行");
    }

    // Existing private-bank writes acquire bank -> player -> accounts. Lock all creditors
    // in ascending order before the player, after the market lock, to preserve that order.
    private Map<Long, EconomyPrivateBank> lockCreditors(long userId) {
        Map<Long, EconomyPrivateBank> result = new LinkedHashMap<>();
        loans.findByBorrowerIdAndStatusInOrderByDueAtAsc(userId,
                        List.of(EconomyLoanStatus.ACTIVE, EconomyLoanStatus.OVERDUE)).stream()
                .map(loan -> loan.getBankId()).distinct().sorted().forEach(id -> result.put(id,
                        banks.findForUpdate(id).orElseThrow(() -> new EconomyException("BANK_NOT_FOUND", "贷款银行不存在"))));
        return result;
    }

    private void lockPlayer(long userId, String nickname, Map<Long, EconomyPrivateBank> creditors) {
        profiles.getOrCreateForUpdate(userId, nickname);
        // The creditor scan may have loaded entities before waiting on locks. READ_COMMITTED
        // alone does not refresh JPA's first-level cache after another repayment commits.
        loans.findByBorrowerIdAndStatusInOrderByDueAtAsc(userId,
                        List.of(EconomyLoanStatus.ACTIVE, EconomyLoanStatus.OVERDUE)).stream()
                .sorted(java.util.Comparator.comparing(loan -> loan.getId())).forEach(loan -> {
                    if (!creditors.containsKey(loan.getBankId())) {
                        throw new EconomyException("DEBT_CHANGED", "欠款发生变化，请重新还款");
                    }
                    entityManager.refresh(loan, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
                });
    }

    private void distribute(long userId, BigDecimal payment, String operationId,
                            Map<Long, EconomyPrivateBank> creditors, EconomyMarketState state) {
        BigDecimal remaining = payment;
        BigDecimal bankDebt = ledger.balance(userId, EconomyAccountScope.MAIN_BANK).balance().min(BigDecimal.ZERO).negate();
        BigDecimal paid = remaining.min(bankDebt);
        if (paid.signum() > 0) ledger.creditUser(userId, EconomyAccountScope.MAIN_BANK, paid, operationId,
                EconomyLedgerReason.BANK_DEPOSIT, userId, "偿还欠款");
        remaining = remaining.subtract(paid);
        paid = remaining.min(ledger.accountBalance(EconomyMarketService.debtKey(userId), EconomyAccountScope.PLUGIN));
        if (paid.signum() > 0) {
            ledger.debitAccount(EconomyMarketService.debtKey(userId), EconomyAccountScope.PLUGIN, userId,
                    paid, operationId, EconomyLedgerReason.MARKET_REPAY, userId, "偿还欠款");
            if (state != null) state.setNetIssued(state.getNetIssued().subtract(paid));
        }
        remaining = remaining.subtract(paid);
        for (var loan : loans.findByBorrowerIdAndStatusInOrderByDueAtAsc(userId,
                List.of(EconomyLoanStatus.ACTIVE, EconomyLoanStatus.OVERDUE))) {
            paid = remaining.min(loan.getRemainingDue());
            if (paid.signum() > 0) loanService.repayCollected(loan, creditors.get(loan.getBankId()), paid, operationId);
            remaining = remaining.subtract(paid);
            if (remaining.signum() == 0) break;
        }
        if (remaining.signum() != 0) throw new IllegalStateException("欠款分配金额不一致");
    }

    public record Repayment(BigDecimal paid, BigDecimal remaining) {
        public String message() {
            return "已还款 " + EconomyMoney.format(paid) + " 金币"
                    + (remaining.signum() > 0 ? "\n剩余欠款：" + EconomyMoney.format(remaining) + " 金币" : "，欠款已还清");
        }
    }
}
