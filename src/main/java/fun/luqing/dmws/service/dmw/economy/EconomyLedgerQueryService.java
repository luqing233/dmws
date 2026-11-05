package fun.luqing.dmws.service.dmw.economy;

import fun.luqing.dmws.common.economy.EconomyMoney;
import fun.luqing.dmws.entity.dmw.economy.EconomyAccount;
import fun.luqing.dmws.entity.dmw.economy.EconomyLedgerEntry;
import fun.luqing.dmws.enums.EconomyAccountScope;
import fun.luqing.dmws.repository.dmw.economy.EconomyAccountRepository;
import fun.luqing.dmws.repository.dmw.economy.EconomyLedgerEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import fun.luqing.dmws.service.dmw.economy.EconomyLedgerService.AccountBalance;

@Service
@RequiredArgsConstructor
public class EconomyLedgerQueryService {
    private final EconomyAccountRepository accountRepository;
    private final EconomyLedgerEntryRepository ledgerEntryRepository;

    @Transactional(readOnly = true, transactionManager = "dmwTransactionManager")
    public AccountBalance balance(long userId, EconomyAccountScope scope) {
        BigDecimal balance = accountRepository.findByAccountKeyAndScope("u" + userId, scope)
                .map(EconomyAccount::getBalance)
                .orElseGet(EconomyMoney::zero);
        return new AccountBalance(userId, scope, EconomyMoney.normalize(balance));
    }

    @Transactional(readOnly = true, transactionManager = "dmwTransactionManager")
    public BigDecimal total(EconomyAccountScope scope) {
        return accountRepository.findAllByScopeOrderByBalanceDesc(scope).stream()
                .map(EconomyAccount::getBalance)
                .reduce(EconomyMoney.zero(), BigDecimal::add);
    }

    @Transactional(readOnly = true, transactionManager = "dmwTransactionManager")
    public BigDecimal accountBalance(String accountKey, EconomyAccountScope scope) {
        return accountRepository.findByAccountKeyAndScope(accountKey, scope)
                .map(EconomyAccount::getBalance)
                .map(EconomyMoney::normalize)
                .orElseGet(EconomyMoney::zero);
    }

    @Transactional(readOnly = true, transactionManager = "dmwTransactionManager")
    public List<EconomyLedgerEntry> recentEntries(long userId, EconomyAccountScope scope, int limit) {
        List<EconomyLedgerEntry> entries =
                ledgerEntryRepository.findByAccountKeyAndScopeOrderByIdDesc("u" + userId, scope);
        return entries.stream().limit(Math.max(0, limit)).toList();
    }

}
