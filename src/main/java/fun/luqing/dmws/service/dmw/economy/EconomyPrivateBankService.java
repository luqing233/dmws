package fun.luqing.dmws.service.dmw.economy;

import fun.luqing.dmws.common.economy.EconomyException;
import fun.luqing.dmws.common.economy.EconomyMoney;
import fun.luqing.dmws.config.EconomyProperties;
import fun.luqing.dmws.entity.dmw.economy.EconomyPrivateBank;
import fun.luqing.dmws.entity.dmw.economy.EconomyPrivateBankDeposit;
import fun.luqing.dmws.entity.dmw.economy.EconomyPrivateBankLoan;
import fun.luqing.dmws.enums.EconomyAccountScope;
import fun.luqing.dmws.enums.EconomyLedgerReason;
import fun.luqing.dmws.enums.EconomyLoanStatus;
import fun.luqing.dmws.repository.dmw.economy.EconomyPrivateBankDepositRepository;
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
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class EconomyPrivateBankService {

    private final EconomyProperties properties;
    private final EconomyOperationService operationService;
    private final EconomyUserProfileService profileService;
    private final EconomyLedgerService ledgerService;
    private final EconomyPrivateBankRepository bankRepository;
    private final EconomyPrivateBankDepositRepository depositRepository;
    private final EconomyPrivateBankLoanRepository loanRepository;

    @Transactional(transactionManager = "dmwTransactionManager")
    public BankView create(
            long ownerId,
            String nickname,
            String rawCode,
            String rawName,
            String operationId
    ) {
        String code = normalizeCode(rawCode);
        String name = normalizeName(rawName);
        if (bankRepository.findByCodeIgnoreCase(code).isPresent()) {
            throw new EconomyException("BANK_CODE_EXISTS", "银行编码已经存在");
        }
        if (bankRepository.findByOwnerId(ownerId).isPresent()) {
            throw new EconomyException("BANK_OWNER_EXISTS", "每位用户只能创建一家银行");
        }

        operationService.start(operationId, "PRIVATE_BANK_CREATE", ownerId, "创建私人银行 " + code);
        profileService.getOrCreateForUpdate(ownerId, nickname);

        EconomyPrivateBank bank = new EconomyPrivateBank();
        bank.setCode(code);
        bank.setName(name);
        bank.setOwnerId(ownerId);
        bank.setDescription("由 " + nickname + " 创建");
        bank.setCapitalAccountKey(capitalAccountKey(code));
        bank = bankRepository.saveAndFlush(bank);

        BigDecimal capital = EconomyMoney.positive(properties.getPrivateBankMinCapital());
        ledgerService.transferUserToAccount(
                ownerId,
                EconomyAccountScope.MAIN_BANK,
                bank.getCapitalAccountKey(),
                EconomyAccountScope.PLUGIN,
                ownerId,
                capital,
                operationId,
                EconomyLedgerReason.PRIVATE_BANK_CREATE,
                ownerId,
                "创建私人银行 " + code
        );
        return view(bank);
    }

    @Transactional(readOnly = true, transactionManager = "dmwTransactionManager")
    public List<BankView> list() {
        return bankRepository.findAllByOrderByIdAsc().stream().map(this::view).toList();
    }

    @Transactional(readOnly = true, transactionManager = "dmwTransactionManager")
    public BankView info(String codeOrName) {
        return view(resolveBank(codeOrName));
    }

    @Transactional(readOnly = true, transactionManager = "dmwTransactionManager")
    public BankView myBank(long ownerId) {
        return bankRepository.findByOwnerId(ownerId)
                .map(this::view)
                .orElse(null);
    }

    @Transactional(readOnly = true, transactionManager = "dmwTransactionManager")
    public BankView defaultBank(long userId) {
        String code = profileService.get(userId).getDefaultPrivateBankCode();
        return code == null || code.isBlank() ? null : info(code);
    }

    @Transactional(readOnly = true, transactionManager = "dmwTransactionManager")
    public BigDecimal depositBalance(long userId, String codeOrName) {
        EconomyPrivateBank bank = resolveBank(codeOrName);
        return depositRepository.findByBankIdAndUserId(bank.getId(), userId)
                .map(EconomyPrivateBankDeposit::getPrincipal)
                .map(EconomyMoney::normalize)
                .orElseGet(EconomyMoney::zero);
    }

    @Transactional(transactionManager = "dmwTransactionManager")
    public BankView setDefaultBank(
            long userId,
            String nickname,
            String codeOrName
    ) {
        EconomyPrivateBank bank = resolveBank(codeOrName);
        var profile = profileService.getOrCreateForUpdate(userId, nickname);
        profile.setDefaultPrivateBankCode(bank.getCode());
        return view(bank);
    }

    @Transactional(transactionManager = "dmwTransactionManager")
    public BankView deposit(
            long userId,
            String nickname,
            String codeOrName,
            BigDecimal amount,
            String operationId
    ) {
        EconomyPrivateBank bank = lockBank(codeOrName);
        operationService.start(operationId, "PRIVATE_BANK_DEPOSIT", userId, "私人银行存款");
        profileService.getOrCreateForUpdate(userId, nickname);
        ledgerService.transferUserToAccount(
                userId,
                EconomyAccountScope.WALLET,
                bank.getCapitalAccountKey(),
                EconomyAccountScope.PLUGIN,
                bank.getOwnerId(),
                amount,
                operationId,
                EconomyLedgerReason.PRIVATE_BANK_DEPOSIT,
                userId,
                "存入私人银行 " + bank.getCode()
        );

        EconomyPrivateBankDeposit deposit = depositRepository
                .findForUpdate(bank.getId(), userId)
                .orElseGet(() -> newDeposit(bank, userId));
        deposit.setPrincipal(EconomyMoney.normalize(deposit.getPrincipal().add(amount)));
        deposit.setUpdatedAt(LocalDateTime.now());
        depositRepository.save(deposit);
        return view(bank);
    }

    @Transactional(transactionManager = "dmwTransactionManager")
    public BankView withdraw(
            long userId,
            String nickname,
            String codeOrName,
            BigDecimal amount,
            String operationId
    ) {
        EconomyPrivateBank bank = lockBank(codeOrName);
        EconomyPrivateBankDeposit deposit = depositRepository.findForUpdate(bank.getId(), userId)
                .orElseThrow(() -> new EconomyException("BANK_DEPOSIT_MISSING", "你在该银行没有存款"));
        if (deposit.getPrincipal().compareTo(amount) < 0) {
            throw new EconomyException("BANK_DEPOSIT_INSUFFICIENT", "私人银行存款余额不足");
        }

        operationService.start(operationId, "PRIVATE_BANK_WITHDRAW", userId, "私人银行取款");
        profileService.getOrCreateForUpdate(userId, nickname);
        ledgerService.transferAccountToUser(
                bank.getCapitalAccountKey(),
                EconomyAccountScope.PLUGIN,
                bank.getOwnerId(),
                userId,
                EconomyAccountScope.WALLET,
                amount,
                operationId,
                EconomyLedgerReason.PRIVATE_BANK_WITHDRAW,
                userId,
                "从私人银行 " + bank.getCode() + " 取款"
        );
        deposit.setPrincipal(EconomyMoney.normalize(deposit.getPrincipal().subtract(amount)));
        deposit.setUpdatedAt(LocalDateTime.now());
        depositRepository.save(deposit);
        return view(bank);
    }

    @Transactional(transactionManager = "dmwTransactionManager")
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

    @Transactional(transactionManager = "dmwTransactionManager")
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
        for (EconomyPrivateBankLoan loan : dueLoans) {
            loan.setStatus(EconomyLoanStatus.OVERDUE);
        }
    }

    @Transactional(transactionManager = "dmwTransactionManager")
    public void settleOverdueLoan(long loanId) {
        EconomyPrivateBankLoan loan = loanRepository.findForUpdate(loanId).orElse(null);
        if (loan == null
                || loan.getStatus() == EconomyLoanStatus.REPAID
                || loan.getDueAt().isAfter(LocalDateTime.now())) {
            return;
        }
        EconomyPrivateBank bank = bankRepository.findById(loan.getBankId()).orElse(null);
        if (bank == null) {
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

    private EconomyPrivateBankDeposit newDeposit(EconomyPrivateBank bank, long userId) {
        EconomyPrivateBankDeposit deposit = new EconomyPrivateBankDeposit();
        deposit.setBankId(bank.getId());
        deposit.setUserId(userId);
        deposit.setPrincipal(EconomyMoney.zero());
        return depositRepository.saveAndFlush(deposit);
    }

    private BankView view(EconomyPrivateBank bank) {
        return new BankView(
                bank.getCode(),
                bank.getName(),
                bank.getOwnerId(),
                bank.getDescription(),
                ledgerService.accountBalance(bank.getCapitalAccountKey(), EconomyAccountScope.PLUGIN)
        );
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

    private String normalizeCode(String code) {
        String normalized = code == null ? "" : code.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9_-]{2,20}")) {
            throw new EconomyException("INVALID_BANK_CODE", "银行编码只能使用 2 到 20 位字母、数字、下划线或横线");
        }
        return normalized;
    }

    private String normalizeName(String name) {
        String normalized = name == null ? "" : name.trim();
        if (normalized.length() < 2 || normalized.length() > 30) {
            throw new EconomyException("INVALID_BANK_NAME", "银行名称长度必须为 2 到 30 个字符");
        }
        return normalized;
    }

    private String capitalAccountKey(String code) {
        return "bank:" + code + ":capital";
    }

    public record BankView(
            String code,
            String name,
            long ownerId,
            String description,
            BigDecimal capital
    ) {
    }

    public record LoanView(
            long id,
            String bankCode,
            BigDecimal principal,
            BigDecimal totalDue,
            BigDecimal remainingDue,
            String status,
            LocalDateTime dueAt,
            long remainingDays
    ) {
    }

    public record RepaymentView(
            String bankCode,
            BigDecimal repaid,
            BigDecimal unusedAmount
    ) {
    }
}
