package fun.luqing.dmws.service.dmw.economy;

import fun.luqing.dmws.enums.EconomyLoanStatus;
import fun.luqing.dmws.repository.dmw.economy.EconomyPrivateBankLoanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "dmws.economy", name = "enabled", havingValue = "true", matchIfMissing = true)
public class EconomyPrivateBankLoanScheduler {

    private final EconomyPrivateBankLoanRepository loanRepository;
    private final EconomyPrivateBankService privateBankService;

    @Scheduled(
            cron = "0 0 4 * * *",
            zone = "${dmws.economy.time-zone:Asia/Shanghai}"
    )
    public void settleOverdueLoans() {
        List<Long> loanIds = loanRepository
                .findByStatusInAndDueAtBefore(
                        List.of(EconomyLoanStatus.ACTIVE, EconomyLoanStatus.OVERDUE),
                        LocalDateTime.now()
                )
                .stream()
                .map(loan -> loan.getId())
                .toList();
        for (Long loanId : loanIds) {
            try {
                privateBankService.settleOverdueLoan(loanId);
            } catch (RuntimeException exception) {
                log.error("私人银行逾期贷款处理失败: loanId={}", loanId, exception);
            }
        }
    }
}
