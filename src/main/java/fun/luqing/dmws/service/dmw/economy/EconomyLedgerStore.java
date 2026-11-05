package fun.luqing.dmws.service.dmw.economy;

import fun.luqing.dmws.common.economy.EconomyException;
import fun.luqing.dmws.common.economy.EconomyMoney;
import fun.luqing.dmws.entity.dmw.economy.EconomyAccount;
import fun.luqing.dmws.entity.dmw.economy.EconomyLedgerEntry;
import fun.luqing.dmws.enums.EconomyAccountScope;
import fun.luqing.dmws.enums.EconomyLedgerReason;
import fun.luqing.dmws.repository.dmw.economy.EconomyAccountRepository;
import fun.luqing.dmws.repository.dmw.economy.EconomyLedgerEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import fun.luqing.dmws.service.dmw.economy.EconomyLedgerService.AccountBalance;

@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY, transactionManager = "dmwTransactionManager")
public class EconomyLedgerStore {
    private final EconomyAccountRepository accountRepository;
    private final EconomyLedgerEntryRepository ledgerEntryRepository;
    private final EconomyAccountProvisioner accountProvisioner;

    public AccountBalance change(
            EconomyAccount account,
            BigDecimal delta,
            String operationId,
            EconomyLedgerReason reason,
            Long actorId,
            String description
    ) {
        return applyChange(account, delta, operationId, reason, actorId, description, false);
    }

    /** Only forced medical settlement can create/increase a main-bank overdraft. */
    public AccountBalance medicalDebt(EconomyAccount account, BigDecimal amount, String operationId, String description) {
        if (account.getScope() != EconomyAccountScope.MAIN_BANK) throw new IllegalArgumentException("医疗欠款只能记入主银行");
        return applyChange(account, EconomyMoney.positive(amount).negate(), operationId,
                EconomyLedgerReason.HOSPITAL_FEE, account.getOwnerId(), description, true);
    }

    private AccountBalance applyChange(EconomyAccount account, BigDecimal delta, String operationId,
                                       EconomyLedgerReason reason, Long actorId, String description, boolean medicalDebt) {
        BigDecimal balanceAfter = account.getBalance().add(delta);
        boolean debtRepayment = account.getScope() == EconomyAccountScope.MAIN_BANK && delta.signum() > 0;
        if (balanceAfter.signum() < 0 && !medicalDebt && !debtRepayment) {
            throw new EconomyException("INSUFFICIENT_BALANCE", "余额不足");
        }
        if (balanceAfter.abs().compareTo(EconomyMoney.MAX) > 0) {
            throw new EconomyException("AMOUNT_TOO_LARGE", "账户余额超过系统上限");
        }
        account.setBalance(EconomyMoney.normalize(balanceAfter));
        accountRepository.save(account);

        EconomyLedgerEntry entry = new EconomyLedgerEntry();
        entry.setOperationId(operationId);
        entry.setAccountKey(account.getAccountKey());
        entry.setScope(account.getScope());
        entry.setDelta(EconomyMoney.normalize(delta));
        entry.setBalanceAfter(account.getBalance());
        entry.setReason(reason);
        entry.setActorId(actorId);
        entry.setDescription(description);
        ledgerEntryRepository.save(entry);

        return new AccountBalance(
                account.getOwnerId() == null ? 0L : account.getOwnerId(),
                account.getScope(),
                account.getBalance()
        );
    }

    public EconomyAccount lockOrCreate(String accountKey, EconomyAccountScope scope, Long ownerId) {
        // MySQL InnoDB takes a gap lock for a missing row on SELECT ... FOR UPDATE.
        // Creating the account in another transaction afterwards would wait on that
        // gap lock until the outer request times out. Provision first, then lock.
        try {
            accountProvisioner.ensureAccount(accountKey, scope, ownerId);
        } catch (DataIntegrityViolationException ignored) {
            // Another request won the unique-key race. The account is now available.
        }
        return accountRepository.findForUpdate(accountKey, scope)
                .orElseThrow(() -> new EconomyException("ACCOUNT_MISSING", "账户创建失败"));
    }

}
