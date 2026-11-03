package fun.luqing.dmws.service.dmw.economy;

import fun.luqing.dmws.config.EconomyProperties;
import fun.luqing.dmws.entity.dmw.economy.EconomyAccount;
import fun.luqing.dmws.enums.EconomyAccountScope;
import fun.luqing.dmws.enums.EconomyLedgerReason;
import fun.luqing.dmws.repository.dmw.economy.EconomyAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.WeekFields;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "dmws.economy", name = "enabled", havingValue = "true", matchIfMissing = true)
public class EconomyBankInterestService {

    private final EconomyProperties properties;
    private final EconomyOperationService operationService;
    private final EconomyLedgerService ledgerService;
    private final EconomyAccountRepository accountRepository;

    @Scheduled(
            cron = "0 0 4 * * MON",
            zone = "${dmws.economy.time-zone:Asia/Shanghai}"
    )
    @Transactional(transactionManager = "dmwTransactionManager")
    public void accrueWeeklyInterest() {
        int week = LocalDate.now(properties.zoneId())
                .get(WeekFields.ISO.weekOfWeekBasedYear());
        int year = LocalDate.now(properties.zoneId()).get(WeekFields.ISO.weekBasedYear());
        String operationId = "bank-interest:" + year + "-W" + week;
        if (operationService.isProcessed(operationId)) {
            return;
        }

        operationService.start(operationId, "BANK_INTEREST", null, "主银行周利息");
        int credited = 0;
        for (EconomyAccount account : accountRepository.findAllByScopeOrderByBalanceDesc(
                EconomyAccountScope.MAIN_BANK
        )) {
            if (account.getOwnerId() == null || account.getBalance().signum() <= 0) {
                continue;
            }
            BigDecimal interest = account.getBalance()
                    .multiply(properties.getBankWeeklyRate())
                    .setScale(2, RoundingMode.DOWN);
            if (interest.signum() <= 0) {
                continue;
            }
            ledgerService.creditUser(
                    account.getOwnerId(),
                    EconomyAccountScope.MAIN_BANK,
                    interest,
                    operationId,
                    EconomyLedgerReason.BANK_INTEREST,
                    null,
                    "主银行周利息"
            );
            credited++;
        }
        log.info("主银行周利息结算完成: year={}, week={}, accounts={}", year, week, credited);
    }
}
