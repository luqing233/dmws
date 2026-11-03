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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class EconomyLedgerService {

    private final EconomyAccountRepository accountRepository;
    private final EconomyLedgerEntryRepository ledgerEntryRepository;
    private final EconomyAccountProvisioner accountProvisioner;

    @Transactional(
            propagation = Propagation.MANDATORY,
            transactionManager = "dmwTransactionManager"
    )
    public AccountBalance creditUser(
            long userId,
            EconomyAccountScope scope,
            BigDecimal amount,
            String operationId,
            EconomyLedgerReason reason,
            Long actorId,
            String description
    ) {
        BigDecimal normalized = EconomyMoney.positive(amount);
        EconomyAccount account = lockOrCreate(userAccountKey(userId), scope, userId);
        return change(account, normalized, operationId, reason, actorId, description);
    }

    @Transactional(
            propagation = Propagation.MANDATORY,
            transactionManager = "dmwTransactionManager"
    )
    public AccountBalance debitUser(
            long userId,
            EconomyAccountScope scope,
            BigDecimal amount,
            String operationId,
            EconomyLedgerReason reason,
            Long actorId,
            String description
    ) {
        BigDecimal normalized = EconomyMoney.positive(amount);
        EconomyAccount account = lockOrCreate(userAccountKey(userId), scope, userId);
        if (account.getBalance().compareTo(normalized) < 0) {
            throw new EconomyException("INSUFFICIENT_BALANCE", "余额不足");
        }
        return change(account, normalized.negate(), operationId, reason, actorId, description);
    }

    @Transactional(
            propagation = Propagation.MANDATORY,
            transactionManager = "dmwTransactionManager"
    )
    public BigDecimal debitUpTo(
            long userId,
            EconomyAccountScope scope,
            BigDecimal requested,
            String operationId,
            EconomyLedgerReason reason,
            Long actorId,
            String description
    ) {
        BigDecimal normalized = EconomyMoney.positive(requested);
        EconomyAccount account = lockOrCreate(userAccountKey(userId), scope, userId);
        BigDecimal actual = account.getBalance().min(normalized);
        if (actual.signum() > 0) {
            change(account, actual.negate(), operationId, reason, actorId, description);
        }
        return EconomyMoney.normalize(actual);
    }

    @Transactional(
            propagation = Propagation.MANDATORY,
            transactionManager = "dmwTransactionManager"
    )
    public TransferResult transferUser(
            long fromUserId,
            EconomyAccountScope fromScope,
            long toUserId,
            EconomyAccountScope toScope,
            BigDecimal amount,
            String operationId,
            EconomyLedgerReason reason,
            Long actorId,
            String description
    ) {
        BigDecimal normalized = EconomyMoney.positive(amount);
        if (fromUserId == toUserId && fromScope == toScope) {
            throw new EconomyException("SAME_ACCOUNT", "不能向同一个账户转账");
        }

        AccountRef fromRef = new AccountRef(userAccountKey(fromUserId), fromScope, fromUserId);
        AccountRef toRef = new AccountRef(userAccountKey(toUserId), toScope, toUserId);
        Map<AccountRef, EconomyAccount> lockedAccounts = new HashMap<>();
        Stream.of(fromRef, toRef)
                .sorted()
                .forEach(ref -> lockedAccounts.put(
                        ref,
                        lockOrCreate(ref.key(), ref.scope(), ref.ownerId())
                ));

        EconomyAccount lockedFrom = lockedAccounts.get(fromRef);
        EconomyAccount lockedTo = lockedAccounts.get(toRef);

        if (lockedFrom.getBalance().compareTo(normalized) < 0) {
            throw new EconomyException("INSUFFICIENT_BALANCE", "余额不足");
        }
        BigDecimal targetBalance = lockedTo.getBalance().add(normalized);
        if (targetBalance.compareTo(EconomyMoney.MAX) > 0) {
            throw new EconomyException("AMOUNT_TOO_LARGE", "目标账户余额超过系统上限");
        }

        AccountBalance fromBalance = change(
                lockedFrom,
                normalized.negate(),
                operationId,
                reason,
                actorId,
                description
        );
        AccountBalance toBalance = change(
                lockedTo,
                normalized,
                operationId,
                reason,
                actorId,
                description
        );
        return new TransferResult(fromBalance, toBalance);
    }

    @Transactional(
            propagation = Propagation.MANDATORY,
            transactionManager = "dmwTransactionManager"
    )
    public AccountBalance creditAccount(
            String accountKey,
            EconomyAccountScope scope,
            Long ownerId,
            BigDecimal amount,
            String operationId,
            EconomyLedgerReason reason,
            Long actorId,
            String description
    ) {
        EconomyAccount account = lockOrCreate(accountKey, scope, ownerId);
        return change(account, EconomyMoney.positive(amount), operationId, reason, actorId, description);
    }

    @Transactional(
            propagation = Propagation.MANDATORY,
            transactionManager = "dmwTransactionManager"
    )
    public AccountBalance debitAccount(
            String accountKey,
            EconomyAccountScope scope,
            Long ownerId,
            BigDecimal amount,
            String operationId,
            EconomyLedgerReason reason,
            Long actorId,
            String description
    ) {
        BigDecimal normalized = EconomyMoney.positive(amount);
        EconomyAccount account = lockOrCreate(accountKey, scope, ownerId);
        if (account.getBalance().compareTo(normalized) < 0) {
            throw new EconomyException("INSUFFICIENT_BALANCE", "账户余额不足");
        }
        return change(account, normalized.negate(), operationId, reason, actorId, description);
    }

    @Transactional(
            propagation = Propagation.MANDATORY,
            transactionManager = "dmwTransactionManager"
    )
    public RawTransferResult transferAccount(
            String fromAccountKey,
            EconomyAccountScope fromScope,
            Long fromOwnerId,
            String toAccountKey,
            EconomyAccountScope toScope,
            Long toOwnerId,
            BigDecimal amount,
            String operationId,
            EconomyLedgerReason reason,
            Long actorId,
            String description
    ) {
        BigDecimal normalized = EconomyMoney.positive(amount);
        if (fromAccountKey.equals(toAccountKey) && fromScope == toScope) {
            throw new EconomyException("SAME_ACCOUNT", "不能向同一个账户转账");
        }

        AccountRef fromRef = new AccountRef(fromAccountKey, fromScope, fromOwnerId);
        AccountRef toRef = new AccountRef(toAccountKey, toScope, toOwnerId);
        Map<AccountRef, EconomyAccount> lockedAccounts = new HashMap<>();
        Stream.of(fromRef, toRef)
                .sorted()
                .forEach(ref -> lockedAccounts.put(
                        ref,
                        lockOrCreate(ref.key(), ref.scope(), ref.ownerId())
                ));
        EconomyAccount from = lockedAccounts.get(fromRef);
        EconomyAccount to = lockedAccounts.get(toRef);
        if (from.getBalance().compareTo(normalized) < 0) {
            throw new EconomyException("INSUFFICIENT_BALANCE", "余额不足");
        }
        if (to.getBalance().add(normalized).compareTo(EconomyMoney.MAX) > 0) {
            throw new EconomyException("AMOUNT_TOO_LARGE", "目标账户余额超过系统上限");
        }

        AccountBalance fromBalance = change(
                from,
                normalized.negate(),
                operationId,
                reason,
                actorId,
                description
        );
        AccountBalance toBalance = change(
                to,
                normalized,
                operationId,
                reason,
                actorId,
                description
        );
        return new RawTransferResult(fromBalance, toBalance);
    }

    @Transactional(
            propagation = Propagation.MANDATORY,
            transactionManager = "dmwTransactionManager"
    )
    public RawTransferResult transferUserToAccount(
            long userId,
            EconomyAccountScope fromScope,
            String toAccountKey,
            EconomyAccountScope toScope,
            Long toOwnerId,
            BigDecimal amount,
            String operationId,
            EconomyLedgerReason reason,
            Long actorId,
            String description
    ) {
        return transferAccount(
                userAccountKey(userId),
                fromScope,
                userId,
                toAccountKey,
                toScope,
                toOwnerId,
                amount,
                operationId,
                reason,
                actorId,
                description
        );
    }

    @Transactional(
            propagation = Propagation.MANDATORY,
            transactionManager = "dmwTransactionManager"
    )
    public RawTransferResult transferAccountToUser(
            String fromAccountKey,
            EconomyAccountScope fromScope,
            Long fromOwnerId,
            long userId,
            EconomyAccountScope toScope,
            BigDecimal amount,
            String operationId,
            EconomyLedgerReason reason,
            Long actorId,
            String description
    ) {
        return transferAccount(
                fromAccountKey,
                fromScope,
                fromOwnerId,
                userAccountKey(userId),
                toScope,
                userId,
                amount,
                operationId,
                reason,
                actorId,
                description
        );
    }

    @Transactional(readOnly = true, transactionManager = "dmwTransactionManager")
    public AccountBalance balance(long userId, EconomyAccountScope scope) {
        BigDecimal balance = accountRepository.findByAccountKeyAndScope(userAccountKey(userId), scope)
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
                ledgerEntryRepository.findByAccountKeyAndScopeOrderByIdDesc(userAccountKey(userId), scope);
        return entries.stream().limit(Math.max(0, limit)).toList();
    }

    public String userAccountKey(long userId) {
        return "u" + userId;
    }

    private AccountBalance change(
            EconomyAccount account,
            BigDecimal delta,
            String operationId,
            EconomyLedgerReason reason,
            Long actorId,
            String description
    ) {
        BigDecimal balanceAfter = account.getBalance().add(delta);
        if (balanceAfter.signum() < 0) {
            throw new EconomyException("INSUFFICIENT_BALANCE", "余额不足");
        }
        if (balanceAfter.compareTo(EconomyMoney.MAX) > 0) {
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

    private EconomyAccount lockOrCreate(String accountKey, EconomyAccountScope scope, Long ownerId) {
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

    public record AccountBalance(long userId, EconomyAccountScope scope, BigDecimal balance) {
    }

    public record TransferResult(AccountBalance from, AccountBalance to) {
    }

    public record RawTransferResult(AccountBalance from, AccountBalance to) {
    }

    private record AccountRef(
            String key,
            EconomyAccountScope scope,
            Long ownerId
    ) implements Comparable<AccountRef> {
        @Override
        public int compareTo(AccountRef other) {
            int keyOrder = key.compareTo(other.key);
            return keyOrder != 0 ? keyOrder : scope.compareTo(other.scope);
        }
    }
}
