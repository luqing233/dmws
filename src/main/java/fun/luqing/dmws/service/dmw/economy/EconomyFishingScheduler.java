package fun.luqing.dmws.service.dmw.economy;

import fun.luqing.dmws.common.economy.EconomyException;
import fun.luqing.dmws.config.EconomyProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "dmws.economy", name = "fishing-scheduler-enabled", matchIfMissing = true)
public class EconomyFishingScheduler {
    private final EconomyProperties properties;
    private final EconomyGameTime time;
    private final EconomyFishingService fishing;
    private final EconomyFishingQueue queue;

    @Scheduled(fixedDelay = 1000)
    public void reelDueCasts() {
        if (!properties.isEnabled()) return;
        var now = time.now();
        for (var cast : queue.due(now)) {
            try {
                fishing.reel(cast.getUserId(), cast.getOperationId(), now);
            } catch (EconomyException e) {
                queue.recordFailure(cast.getOperationId(), now, e.getMessage());
            } catch (RuntimeException e) {
                log.error("收竿结算异常: operation={}", cast.getOperationId(), e);
                queue.recordFailure(cast.getOperationId(), now, null);
            }
        }
    }
}
