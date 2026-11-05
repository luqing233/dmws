package fun.luqing.dmws.service.dmw.economy;

import fun.luqing.dmws.repository.dmw.economy.EconomyPlayerConditionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "dmws.economy", name = {"enabled", "condition-scheduler-enabled"}, havingValue = "true", matchIfMissing = true)
public class EconomyConditionScheduler {
    private final EconomyPlayerConditionRepository repository;
    private final EconomyConditionService conditions;
    private final EconomyGameTime time;

    @Scheduled(fixedDelay = 15000, initialDelay = 60000)
    public void settleExpired() {
        for (long id : repository.findDue(time.now(), PageRequest.of(0, 200))) {
            try { conditions.settleUser(id); }
            catch (RuntimeException e) { log.error("住院/服刑到期结算失败，将重试: userId={}", id, e); }
        }
    }
}
