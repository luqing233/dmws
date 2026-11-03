package fun.luqing.dmws.plugin.economy;

import com.mikuac.shiro.annotation.GroupMessageHandler;
import com.mikuac.shiro.annotation.MessageHandlerFilter;
import com.mikuac.shiro.annotation.common.Shiro;
import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.dto.event.message.GroupMessageEvent;
import fun.luqing.dmws.common.annotation.CommandInfo;
import fun.luqing.dmws.common.economy.EconomyMoney;
import fun.luqing.dmws.service.dmw.economy.EconomyRaffleService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Shiro
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "dmws.economy", name = "enabled", havingValue = "true", matchIfMissing = true)
public class EconomyRafflePlugin {

    private final EconomyRaffleService raffleService;

    @GroupMessageHandler
    @MessageHandlerFilter(cmd = "(?i)^(抽奖|单抽|raffle)$")
    @CommandInfo(cmd = "抽奖", desc = "消耗金币进行一次抽奖")
    public void draw(Bot bot, GroupMessageEvent event) {
        drawInternal(bot, event, 1);
    }

    @GroupMessageHandler
    @MessageHandlerFilter(cmd = "(?i)^(十连|ten|raffle10)$")
    @CommandInfo(cmd = "十连", desc = "消耗金币进行十次抽奖")
    public void drawTen(Bot bot, GroupMessageEvent event) {
        drawInternal(bot, event, 10);
    }

    private void drawInternal(Bot bot, GroupMessageEvent event, int times) {
        EconomyCommandSupport.execute(bot, event, () -> {
            EconomyRaffleService.DrawResult result = raffleService.draw(
                    event.getUserId(),
                    EconomyCommandSupport.nickname(event),
                    times,
                    EconomyCommandSupport.operationId("raffle-" + times, event)
            );
            StringBuilder builder = new StringBuilder(times == 1 ? "抽奖结果" : "十连抽结果");
            for (EconomyRaffleService.PrizeView prize : result.prizes()) {
                builder.append("\n- ").append(prize.name())
                        .append(": ").append(prize.description());
                if (prize.reward().signum() > 0) {
                    builder.append("（").append(EconomyMoney.format(prize.reward())).append("）");
                }
            }
            builder.append("\n花费: ").append(EconomyMoney.format(result.price()))
                    .append("\n钱包余额: ").append(EconomyMoney.format(result.walletBalance()));
            return builder.toString();
        });
    }
}
