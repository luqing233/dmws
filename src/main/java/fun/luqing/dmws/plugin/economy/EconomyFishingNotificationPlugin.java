package fun.luqing.dmws.plugin.economy;

import com.mikuac.shiro.common.utils.MsgUtils;
import com.mikuac.shiro.common.utils.ShiroUtils;
import com.mikuac.shiro.core.BotContainer;
import fun.luqing.dmws.config.EconomyProperties;
import fun.luqing.dmws.entity.dmw.economy.EconomyFishingCast.Status;
import fun.luqing.dmws.service.dmw.economy.EconomyFishingQueue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "dmws.economy", name = "fishing-scheduler-enabled", matchIfMissing = true)
public class EconomyFishingNotificationPlugin {
    private final BotContainer bots;
    private final EconomyFishingQueue queue;
    private final EconomyProperties properties;

    @Scheduled(fixedDelay = 1000)
    public void sendReceipts() {
        if (!properties.isEnabled()) return;
        for (var connection : bots.robots.entrySet()) {
            for (var cast : queue.ready(connection.getKey())) {
                String operationId = cast.getOperationId();
                var notice = queue.claim(operationId, connection.getKey());
                if (notice == null) continue;
                Status status = Status.UNKNOWN;
                try {
                    var response = connection.getValue().sendGroupMsg(notice.groupId(),
                            MsgUtils.builder().reply(notice.messageId()).text(ShiroUtils.escape(notice.receipt())).build(), false);
                    if (response != null && response.getRetCode() != null) {
                        if (response.getRetCode() != 0) status = Status.FAILED;
                        else if (response.getData() != null) status = Status.SENT;
                    }
                } catch (RuntimeException e) {
                    log.warn("收竿回执发送结果不明: operation={}", operationId, e);
                }
                queue.finish(operationId, status);
            }
        }
    }
}
