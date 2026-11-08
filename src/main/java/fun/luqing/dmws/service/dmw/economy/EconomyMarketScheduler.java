package fun.luqing.dmws.service.dmw.economy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class EconomyMarketScheduler {
    private final EconomyMarketService market;
    private final fun.luqing.dmws.config.EconomyMarketProperties rules;
    private final fun.luqing.dmws.config.EconomyProperties economy;
    @Scheduled(fixedDelay = 10000, initialDelay = 1000)
    public void update() {
        if (!rules.isSchedulerEnabled() || !economy.isEnabled()) return;
        try { if (market.available()) market.tick(); }
        catch (RuntimeException e) { log.error("模拟外汇行情/爆仓事务失败，暂停当前时段成交并稍后重试", e); }
    }
}
