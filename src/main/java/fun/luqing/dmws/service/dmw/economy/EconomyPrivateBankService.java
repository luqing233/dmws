package fun.luqing.dmws.service.dmw.economy;

import fun.luqing.dmws.common.economy.EconomyException;
import fun.luqing.dmws.common.economy.EconomyMoney;
import fun.luqing.dmws.config.EconomyProperties;
import fun.luqing.dmws.entity.dmw.economy.EconomyPrivateBank;
import fun.luqing.dmws.entity.dmw.economy.EconomyPrivateBankDeposit;
import fun.luqing.dmws.enums.EconomyAccountScope;
import fun.luqing.dmws.enums.EconomyLedgerReason;
import fun.luqing.dmws.repository.dmw.economy.EconomyPrivateBankDepositRepository;
import fun.luqing.dmws.repository.dmw.economy.EconomyPrivateBankRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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
    private final EconomyPrivateBankLoanService loanService;

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

    @Transactional(transactionManager = "dmwTransactionManager", isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public LoanView borrow(
            long userId,
            String nickname,
            String codeOrName,
            BigDecimal amount,
            int days,
            String operationId
    ) {
        return loanService.borrow(userId, nickname, codeOrName, amount, days, operationId);
    }

    @Transactional(transactionManager = "dmwTransactionManager")
    public RepaymentView repay(
            long userId,
            String nickname,
            String codeOrName,
            BigDecimal requestedAmount,
            String operationId
    ) {
        return loanService.repay(userId, nickname, codeOrName, requestedAmount, operationId);
    }

    @Transactional(transactionManager = "dmwTransactionManager")
    public void markOverdueLoans() {
        loanService.markOverdueLoans();
    }

    @Transactional(transactionManager = "dmwTransactionManager")
    public void settleOverdueLoan(long loanId) {
        loanService.settleOverdueLoan(loanId);
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
