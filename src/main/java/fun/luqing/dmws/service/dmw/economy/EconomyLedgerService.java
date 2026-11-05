package fun.luqing.dmws.service.dmw.economy;

import fun.luqing.dmws.common.economy.EconomyException;
import fun.luqing.dmws.common.economy.EconomyMoney;
import fun.luqing.dmws.entity.dmw.economy.EconomyAccount;
import fun.luqing.dmws.entity.dmw.economy.EconomyLedgerEntry;
import fun.luqing.dmws.enums.EconomyAccountScope;
import fun.luqing.dmws.enums.EconomyLedgerReason;
import lombok.RequiredArgsConstructor;
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

    private final EconomyLedgerStore store;
    private final EconomyLedgerQueryService queries;

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
        EconomyAccount account = store.lockOrCreate(userAccountKey(userId), scope, userId);
        return store.change(account, normalized, operationId, reason, actorId, description);
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
        EconomyAccount account = store.lockOrCreate(userAccountKey(userId), scope, userId);
        if (account.getBalance().compareTo(normalized) < 0) {
            throw new EconomyException("INSUFFICIENT_BALANCE", "余额不足");
        }
        return store.change(account, normalized.negate(), operationId, reason, actorId, description);
    }

    @Transactional(
            propagation = Propagation.MANDATORY,
            transactionManager = "dmwTransactionManager"
    )
    public BankFallbackDebit debitUserWithBankFallback(
            long userId,
            BigDecimal amount,
            String operationId,
            EconomyLedgerReason reason,
            Long actorId,
            String description
    ) {
        BigDecimal normalized = EconomyMoney.positive(amount);
        AccountRef walletRef = new AccountRef(
                userAccountKey(userId),
                EconomyAccountScope.WALLET,
                userId
        );
        AccountRef bankRef = new AccountRef(
                userAccountKey(userId),
                EconomyAccountScope.MAIN_BANK,
                userId
        );
        Map<AccountRef, EconomyAccount> lockedAccounts = new HashMap<>();
        Stream.of(walletRef, bankRef)
                .sorted()
                .forEach(ref -> lockedAccounts.put(
                        ref,
                        store.lockOrCreate(ref.key(), ref.scope(), ref.ownerId())
                ));

        EconomyAccount wallet = lockedAccounts.get(walletRef);
        EconomyAccount bank = lockedAccounts.get(bankRef);
        BigDecimal shortfall = normalized.subtract(wallet.getBalance()).max(BigDecimal.ZERO);
        if (shortfall.signum() > 0) {
            if (bank.getBalance().compareTo(shortfall) < 0) {
                throw new EconomyException(
                        "INSUFFICIENT_BALANCE",
                        "钱包和主银行余额不足：需要 " + EconomyMoney.format(normalized)
                                + "，钱包 " + EconomyMoney.format(wallet.getBalance())
                                + "，主银行 " + EconomyMoney.format(bank.getBalance())
                );
            }
            store.change(
                    bank,
                    shortfall.negate(),
                    operationId,
                    EconomyLedgerReason.BANK_WITHDRAW,
                    actorId,
                    "余额不足时自动从主银行补足"
            );
            store.change(
                    wallet,
                    shortfall,
                    operationId,
                    EconomyLedgerReason.BANK_WITHDRAW,
                    actorId,
                    "余额不足时自动补足钱包"
            );
        }

        AccountBalance charge = store.change(
                wallet,
                normalized.negate(),
                operationId,
                reason,
                actorId,
                description
        );
        return new BankFallbackDebit(
                charge,
                EconomyMoney.normalize(shortfall),
                EconomyMoney.normalize(bank.getBalance())
        );
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
        EconomyAccount account = store.lockOrCreate(userAccountKey(userId), scope, userId);
        BigDecimal actual = account.getBalance().max(BigDecimal.ZERO).min(normalized);
        if (actual.signum() > 0) {
            store.change(account, actual.negate(), operationId, reason, actorId, description);
        }
        return EconomyMoney.normalize(actual);
    }

    @Transactional(propagation = Propagation.MANDATORY, transactionManager = "dmwTransactionManager")
    public MedicalPayment chargeHospital(long userId, BigDecimal amount, String operationId, String description) {
        BigDecimal fee = EconomyMoney.positive(amount);
        // Same WALLET -> MAIN_BANK enum order as AccountRef sorting in other debit paths.
        EconomyAccount wallet = store.lockOrCreate(userAccountKey(userId), EconomyAccountScope.WALLET, userId);
        EconomyAccount bank = store.lockOrCreate(userAccountKey(userId), EconomyAccountScope.MAIN_BANK, userId);
        BigDecimal previousDebt = bank.getBalance().min(BigDecimal.ZERO).negate();
        BigDecimal walletPaid = wallet.getBalance().max(BigDecimal.ZERO).min(fee);
        if (walletPaid.signum() > 0) store.change(wallet, walletPaid.negate(), operationId,
                EconomyLedgerReason.HOSPITAL_FEE, userId, description);
        BigDecimal bankPaid = fee.subtract(walletPaid);
        if (bankPaid.signum() > 0) store.medicalDebt(bank, bankPaid, operationId, description);
        BigDecimal borrowed = bank.getBalance().min(BigDecimal.ZERO).negate().subtract(previousDebt);
        return new MedicalPayment(fee, EconomyMoney.normalize(borrowed), wallet.getBalance(), bank.getBalance());
    }

    public record MedicalPayment(BigDecimal fee, BigDecimal borrowed, BigDecimal wallet, BigDecimal bank) { }

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
        RawTransferResult result = transferAccount(userAccountKey(fromUserId), fromScope, fromUserId,
                userAccountKey(toUserId), toScope, toUserId, amount, operationId, reason, actorId, description);
        return new TransferResult(result.from(), result.to());
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
        EconomyAccount account = store.lockOrCreate(accountKey, scope, ownerId);
        return store.change(account, EconomyMoney.positive(amount), operationId, reason, actorId, description);
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
        EconomyAccount account = store.lockOrCreate(accountKey, scope, ownerId);
        if (account.getBalance().compareTo(normalized) < 0) {
            throw new EconomyException("INSUFFICIENT_BALANCE", "账户余额不足");
        }
        return store.change(account, normalized.negate(), operationId, reason, actorId, description);
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
                        store.lockOrCreate(ref.key(), ref.scope(), ref.ownerId())
                ));
        EconomyAccount from = lockedAccounts.get(fromRef);
        EconomyAccount to = lockedAccounts.get(toRef);
        if (from.getBalance().compareTo(normalized) < 0) {
            throw new EconomyException("INSUFFICIENT_BALANCE", "余额不足");
        }
        if (to.getBalance().add(normalized).compareTo(EconomyMoney.MAX) > 0) {
            throw new EconomyException("AMOUNT_TOO_LARGE", "目标账户余额超过系统上限");
        }

        AccountBalance fromBalance = store.change(
                from,
                normalized.negate(),
                operationId,
                reason,
                actorId,
                description
        );
        AccountBalance toBalance = store.change(
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
        return queries.balance(userId, scope);
    }

    @Transactional(readOnly = true, transactionManager = "dmwTransactionManager")
    public BigDecimal total(EconomyAccountScope scope) {
        return queries.total(scope);
    }

    @Transactional(readOnly = true, transactionManager = "dmwTransactionManager")
    public BigDecimal accountBalance(String accountKey, EconomyAccountScope scope) {
        return queries.accountBalance(accountKey, scope);
    }

    @Transactional(readOnly = true, transactionManager = "dmwTransactionManager")
    public List<EconomyLedgerEntry> recentEntries(long userId, EconomyAccountScope scope, int limit) {
        return queries.recentEntries(userId, scope, limit);
    }

    public String userAccountKey(long userId) {
        return "u" + userId;
    }

    public record AccountBalance(long userId, EconomyAccountScope scope, BigDecimal balance) {
    }

    public record TransferResult(AccountBalance from, AccountBalance to) {
    }

    public record RawTransferResult(AccountBalance from, AccountBalance to) {
    }

    public record BankFallbackDebit(
            AccountBalance charge,
            BigDecimal bankTopUp,
            BigDecimal bankBalance
    ) {
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
