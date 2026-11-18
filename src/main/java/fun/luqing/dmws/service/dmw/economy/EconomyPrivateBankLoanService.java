package fun.luqing.dmws.service.dmw.economy;

import fun.luqing.dmws.common.economy.EconomyException;
import fun.luqing.dmws.common.economy.EconomyMoney;
import fun.luqing.dmws.config.EconomyProperties;
import fun.luqing.dmws.entity.dmw.economy.EconomyPrivateBank;
import fun.luqing.dmws.entity.dmw.economy.EconomyPrivateBankLoan;
import fun.luqing.dmws.enums.EconomyAccountScope;
import fun.luqing.dmws.enums.EconomyLedgerReason;
import fun.luqing.dmws.enums.EconomyLoanStatus;
import fun.luqing.dmws.repository.dmw.economy.EconomyPrivateBankLoanRepository;
import fun.luqing.dmws.repository.dmw.economy.EconomyPrivateBankRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import fun.luqing.dmws.service.dmw.economy.EconomyPrivateBankService.LoanView;
import fun.luqing.dmws.service.dmw.economy.EconomyPrivateBankService.RepaymentView;

@Service
@RequiredArgsConstructor
public class EconomyPrivateBankLoanService {
    @jakarta.persistence.PersistenceContext(unitName = "dmw")
    private jakarta.persistence.EntityManager entityManager;
    private final EconomyProperties properties;
    private final EconomyOperationService operationService;
    private final EconomyUserProfileService profileService;
    private final EconomyLedgerService ledgerService;
    private final EconomyPrivateBankRepository bankRepository;
    private final EconomyPrivateBankLoanRepository loanRepository;
    private final fun.luqing.dmws.repository.dmw.economy.EconomyMarketPositionRepository marketPositions;
    private final EconomyDebtQueryService debts;

    @Transactional(transactionManager = "dmwTransactionManager", isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public LoanView borrow(
            long userId,
            String nickname,
            String codeOrName,
            BigDecimal amount,
            int days,
            String operationId
    ) {
        if (days < 1 || days > properties.getPrivateBankMaxLoanDays()) {
            throw new EconomyException(
                    "INVALID_LOAN_DAYS",
                    "借款期限必须在 1 到 " + properties.getPrivateBankMaxLoanDays() + " 天之间"
            );
        }
        EconomyPrivateBank bank = lockBank(codeOrName);
        if (bank.getOwnerId().equals(userId)) {
            throw new EconomyException("BANK_OWNER_BORROW", "不能向自己创建的银行借款");
        }
        operationService.start(operationId, "PRIVATE_BANK_LOAN", userId, "私人银行借款");
        profileService.getOrCreateForUpdate(userId, nickname);
        // Both FX writes and borrowing hold this profile lock. Read AFTER acquiring it, under READ_COMMITTED.
        if (marketPositions.existsByUserIdAndStatus(userId, fun.luqing.dmws.entity.dmw.economy.EconomyMarketPosition.Status.OPEN)
                || debts.total(userId).signum() > 0) {
            throw new EconomyException("MARKET_CREDIT_RESTRICTED", "仍有外汇持仓或欠款，暂不能新增贷款；请先平仓并还清欠款");
        }
        ledgerService.transferAccountToUser(
                bank.getCapitalAccountKey(),
                EconomyAccountScope.PLUGIN,
                bank.getOwnerId(),
                userId,
                EconomyAccountScope.WALLET,
                amount,
                operationId,
                EconomyLedgerReason.PRIVATE_BANK_LOAN,
                userId,
                "从私人银行 " + bank.getCode() + " 借款"
        );

        BigDecimal totalDue = EconomyMoney.positive(
                amount.multiply(BigDecimal.ONE.add(properties.getPrivateBankLoanRate()))
                        .setScale(2, RoundingMode.HALF_UP)
        );
        EconomyPrivateBankLoan loan = new EconomyPrivateBankLoan();
        loan.setBankId(bank.getId());
        loan.setBorrowerId(userId);
        loan.setPrincipal(EconomyMoney.normalize(amount));
        loan.setTotalDue(totalDue);
        loan.setRemainingDue(totalDue);
        loan.setStatus(EconomyLoanStatus.ACTIVE);
        loan.setDueAt(LocalDateTime.now().plusDays(days));
        loan = loanRepository.saveAndFlush(loan);
        return view(loan, bank);
    }

    @Transactional(transactionManager = "dmwTransactionManager", isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public RepaymentView repay(
            long userId,
            String nickname,
            String codeOrName,
            BigDecimal requestedAmount,
            String operationId
    ) {
        EconomyPrivateBank bank = lockBank(codeOrName);
        profileService.getOrCreateForUpdate(userId, nickname);
        List<EconomyPrivateBankLoan> loans = loanRepository
                .findByBorrowerIdAndStatusInOrderByDueAtAsc(
                        userId,
                        List.of(EconomyLoanStatus.ACTIVE, EconomyLoanStatus.OVERDUE)
                )
                .stream()
                .filter(loan -> loan.getBankId().equals(bank.getId()))
                .toList();
        if (loans.isEmpty()) {
            throw new EconomyException("LOAN_NOT_FOUND", "你在该银行没有待还贷款");
        }

        BigDecimal remaining = EconomyMoney.positive(requestedAmount);
        BigDecimal repaid = EconomyMoney.zero();
        for (EconomyPrivateBankLoan loan : loans) {
            if (remaining.signum() <= 0) {
                break;
            }
            BigDecimal payment = remaining.min(loan.getRemainingDue());
            if (payment.signum() <= 0) {
                continue;
            }
            String loanOperationId = operationId + ":" + loan.getId();
            operationService.start(
                    loanOperationId,
                    "PRIVATE_BANK_REPAY",
                    userId,
                    "偿还私人银行贷款 " + loan.getId()
            );
            ledgerService.transferUserToAccount(
                    userId,
                    EconomyAccountScope.WALLET,
                    bank.getCapitalAccountKey(),
                    EconomyAccountScope.PLUGIN,
                    bank.getOwnerId(),
                    payment,
                    loanOperationId,
                    EconomyLedgerReason.PRIVATE_BANK_REPAY,
                    userId,
                    "偿还私人银行贷款 " + loan.getId()
            );
            settleLoan(loan, payment);
            repaid = repaid.add(payment);
            remaining = remaining.subtract(payment);
        }
        return new RepaymentView(bank.getCode(), repaid, remaining);
    }

    @Scheduled(
            cron = "0 30 3 * * *",
            zone = "${dmws.economy.time-zone:Asia/Shanghai}"
    )
    @Transactional(transactionManager = "dmwTransactionManager")
    public void markOverdueLoans() {
        List<EconomyPrivateBankLoan> dueLoans = loanRepository.findByStatusInAndDueAtBefore(
                List.of(EconomyLoanStatus.ACTIVE),
                LocalDateTime.now()
        );
        dueLoans.stream().sorted(java.util.Comparator.comparing(loan -> loan.getId())).forEach(loan -> {
            entityManager.refresh(loan, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
            if (loan.getStatus() == EconomyLoanStatus.ACTIVE && loan.getDueAt().isBefore(LocalDateTime.now())) {
                loan.setStatus(EconomyLoanStatus.OVERDUE);
            }
        });
    }

    @Transactional(transactionManager = "dmwTransactionManager", isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void settleOverdueLoan(long loanId) {
        EconomyPrivateBankLoan snapshot = loanRepository.findById(loanId).orElse(null);
        if (snapshot == null) return;
        EconomyPrivateBank bank = bankRepository.findForUpdate(snapshot.getBankId()).orElse(null);
        if (bank == null) return;
        profileService.getOrCreateForUpdate(snapshot.getBorrowerId(), "");
        EconomyPrivateBankLoan loan = loanRepository.findForUpdate(loanId).orElse(null);
        if (loan != null) entityManager.refresh(loan, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        if (loan == null
                || loan.getStatus() == EconomyLoanStatus.REPAID
                || loan.getDueAt().isAfter(LocalDateTime.now())) {
            return;
        }
        String day = LocalDate.now().toString();
        BigDecimal remaining = loan.getRemainingDue();
        BigDecimal wallet = ledgerService.balance(
                loan.getBorrowerId(),
                EconomyAccountScope.WALLET
        ).balance();
        BigDecimal walletPayment = wallet.min(remaining);
        if (walletPayment.signum() > 0) {
            String operationId = "privatebank-overdue:" + loanId + ":" + day + ":wallet";
            operationService.start(operationId, "PRIVATE_BANK_OVERDUE_WALLET", null, "贷款逾期扣款");
            ledgerService.transferUserToAccount(
                    loan.getBorrowerId(),
                    EconomyAccountScope.WALLET,
                    bank.getCapitalAccountKey(),
                    EconomyAccountScope.PLUGIN,
                    bank.getOwnerId(),
                    walletPayment,
                    operationId,
                    EconomyLedgerReason.PRIVATE_BANK_REPAY,
                    null,
                    "逾期贷款自动扣款"
            );
            remaining = remaining.subtract(walletPayment);
        }

        if (remaining.signum() > 0) {
            BigDecimal bankBalance = ledgerService.balance(
                    loan.getBorrowerId(),
                    EconomyAccountScope.MAIN_BANK
            ).balance();
            BigDecimal bankPayment = bankBalance.min(remaining);
            if (bankPayment.signum() > 0) {
                String operationId = "privatebank-overdue:" + loanId + ":" + day + ":bank";
                operationService.start(operationId, "PRIVATE_BANK_OVERDUE_BANK", null, "贷款逾期扣主银行");
                ledgerService.transferUserToAccount(
                        loan.getBorrowerId(),
                        EconomyAccountScope.MAIN_BANK,
                        bank.getCapitalAccountKey(),
                        EconomyAccountScope.PLUGIN,
                        bank.getOwnerId(),
                        bankPayment,
                        operationId,
                        EconomyLedgerReason.PRIVATE_BANK_REPAY,
                        null,
                        "逾期贷款自动扣主银行"
                );
                remaining = remaining.subtract(bankPayment);
            }
        }
        settleLoan(loan, loan.getRemainingDue().subtract(remaining));
    }

    private void settleLoan(EconomyPrivateBankLoan loan, BigDecimal payment) {
        loan.setRemainingDue(EconomyMoney.normalize(loan.getRemainingDue().subtract(payment)));
        loan.setStatus(loan.getRemainingDue().signum() == 0
                ? EconomyLoanStatus.REPAID
                : loan.getDueAt().isBefore(LocalDateTime.now())
                ? EconomyLoanStatus.OVERDUE
                : EconomyLoanStatus.ACTIVE);
        loanRepository.save(loan);
    }

    /** Caller holds bank/player locks and has already collected this payment. */
    @Transactional(transactionManager = "dmwTransactionManager", propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void repayCollected(EconomyPrivateBankLoan loan, EconomyPrivateBank bank, BigDecimal payment, String operationId) {
        if (bank == null || payment.signum() <= 0 || payment.compareTo(loan.getRemainingDue()) > 0) {
            throw new EconomyException("DEBT_CHANGED", "欠款发生变化，请重新还款");
        }
        ledgerService.creditAccount(bank.getCapitalAccountKey(), EconomyAccountScope.PLUGIN, bank.getOwnerId(),
                payment, operationId, EconomyLedgerReason.PRIVATE_BANK_REPAY, loan.getBorrowerId(), "还款归还债权银行");
        settleLoan(loan, payment);
    }

    private LoanView view(EconomyPrivateBankLoan loan, EconomyPrivateBank bank) {
        long remainingDays = Math.max(0, ChronoUnit.DAYS.between(LocalDateTime.now(), loan.getDueAt()));
        return new LoanView(
                loan.getId(),
                bank.getCode(),
                loan.getPrincipal(),
                loan.getTotalDue(),
                loan.getRemainingDue(),
                loan.getStatus().name(),
                loan.getDueAt(),
                remainingDays
        );
    }

    private EconomyPrivateBank lockBank(String codeOrName) {
        EconomyPrivateBank bank = resolveBank(codeOrName);
        return bankRepository.findForUpdate(bank.getId())
                .orElseThrow(() -> new EconomyException("BANK_NOT_FOUND", "银行不存在"));
    }

    private EconomyPrivateBank resolveBank(String codeOrName) {
        String value = codeOrName == null ? "" : codeOrName.trim();
        return bankRepository.findByCodeIgnoreCase(value)
                .or(() -> bankRepository.findFirstByNameIgnoreCase(value))
                .orElseThrow(() -> new EconomyException("BANK_NOT_FOUND", "银行不存在: " + value));
    }

}
