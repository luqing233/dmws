package fun.luqing.dmws.service.dmw.economy;

import fun.luqing.dmws.common.economy.EconomyMoney;
import fun.luqing.dmws.enums.EconomyAccountScope;
import fun.luqing.dmws.enums.EconomyLoanStatus;
import fun.luqing.dmws.repository.dmw.economy.EconomyPrivateBankLoanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/** One player-facing liability, retaining source records for settlement and creditor accounting. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, transactionManager = "dmwTransactionManager")
public class EconomyDebtQueryService {
    private final EconomyLedgerQueryService ledger;
    private final EconomyPrivateBankLoanRepository loans;

    public BigDecimal total(long userId) {
        BigDecimal bank = ledger.balance(userId, EconomyAccountScope.MAIN_BANK).balance();
        BigDecimal amount = bank.min(BigDecimal.ZERO).negate()
                .add(ledger.accountBalance(EconomyMarketService.debtKey(userId), EconomyAccountScope.PLUGIN));
        for (var loan : loans.findByBorrowerIdAndStatusInOrderByDueAtAsc(userId,
                List.of(EconomyLoanStatus.ACTIVE, EconomyLoanStatus.OVERDUE))) {
            amount = amount.add(loan.getRemainingDue());
        }
        return EconomyMoney.normalize(amount);
    }

    public String describe(long userId) {
        return "总欠款：" + EconomyMoney.format(total(userId)) + " 金币\n还款 <金额或全部>；存入主银行也会优先还款。";
    }
}
