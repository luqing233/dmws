package fun.luqing.dmws.plugin.economy;

import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.core.BotContainer;
import fun.luqing.dmws.config.*;
import fun.luqing.dmws.service.dmw.economy.EconomyMarketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Send after the financial transaction commits; no transport call can roll back settlement. */
@Slf4j @Component @RequiredArgsConstructor
public class EconomyMarketNotificationPlugin {
    private final EconomyMarketService market;
    private final BotContainer bots;
    private final DmwConfig config;
    private final EconomyMarketProperties rules;
    private final EconomyProperties economy;

    @Scheduled(fixedDelay = 10000, initialDelay = 10000)
    public void sendPending() {
        if (!economy.isEnabled() || !rules.isSchedulerEnabled()) return;
        Bot bot = config.getBotId() > 0 ? bots.robots.get(config.getBotId())
                : bots.robots.size() == 1 ? bots.robots.values().iterator().next() : null;
        if (bot == null) return;
        try {
            if (!market.available()) return;
            for (int count = 0; count < 20; count++) {
                var notice = market.claimNotice();
                if (notice == null) return;
                String status = "UNKNOWN", messageId = null;
                try {
                    // An @ inside a forward node does not reliably notify QQ users: send the mention outside.
                    var result = bot.sendGroupMsg(notice.groupId(), "[CQ:at,qq=" + notice.userId()
                            + "] 交易风险提醒，请查看合并转发；可发送「交易持仓」查询补仓期限。", false);
                    if (result != null && result.getRetCode() != null && result.getRetCode() != 0) status = "FAILED";
                    else if (result != null && Integer.valueOf(0).equals(result.getRetCode()) && result.getData() != null) {
                        status = "SENT"; messageId = String.valueOf(result.getData().getMessageId());
                    }
                    EconomyCommandSupport.sendForward(bot, notice.groupId(), config, EconomyDisplayFormatter.market("风险与结算", notice.message()));
                } catch (RuntimeException e) {
                    log.warn("外汇提醒发送结果不明，notice={}，不自动重发", notice.id());
                }
                market.finishNotice(notice.id(), status, messageId);
            }
        } catch (RuntimeException e) { log.error("外汇通知处理失败", e); }
    }
}
