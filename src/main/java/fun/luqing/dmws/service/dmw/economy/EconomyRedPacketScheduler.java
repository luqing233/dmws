package fun.luqing.dmws.service.dmw.economy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "dmws.economy", name = "enabled", havingValue = "true", matchIfMissing = true)
public class EconomyRedPacketScheduler {

    private final EconomyRedPacketService redPacketService;

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void expireRedPackets() {
        for (Long packetId : redPacketService.duePacketIds()) {
            try {
                redPacketService.expire(packetId);
            } catch (RuntimeException exception) {
                log.error("红包过期退款失败: packetId={}", packetId, exception);
            }
        }
    }
}
