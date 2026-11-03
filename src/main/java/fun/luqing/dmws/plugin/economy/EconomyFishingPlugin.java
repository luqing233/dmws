package fun.luqing.dmws.plugin.economy;

import com.mikuac.shiro.annotation.GroupMessageHandler;
import com.mikuac.shiro.annotation.MessageHandlerFilter;
import com.mikuac.shiro.annotation.common.Shiro;
import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.dto.event.message.GroupMessageEvent;
import fun.luqing.dmws.common.annotation.CommandInfo;
import fun.luqing.dmws.common.economy.EconomyMoney;
import fun.luqing.dmws.entity.dmw.economy.EconomyFishingCatch;
import fun.luqing.dmws.service.dmw.economy.EconomyFishingService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Shiro
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "dmws.economy", name = "enabled", havingValue = "true", matchIfMissing = true)
public class EconomyFishingPlugin {

    private final EconomyFishingService fishingService;

    @GroupMessageHandler
    @MessageHandlerFilter(cmd = "^(钓鱼|抛竿|fish)$")
    @CommandInfo(cmd = "钓鱼", desc = "消耗金币钓鱼，奖励取决于鱼类和尺寸")
    public void fish(Bot bot, GroupMessageEvent event) {
        EconomyCommandSupport.execute(bot, event, () -> {
            EconomyFishingService.FishingResult result = fishingService.fish(
                    event.getUserId(),
                    EconomyCommandSupport.nickname(event),
                    EconomyCommandSupport.operationId("fishing", event)
            );
            if (!result.success()) {
                return "鱼跑掉了……\n消耗: " + EconomyMoney.format(result.cost())
                        + (result.luckUsed() ? "\n本次消耗了幸运鱼饵。" : "")
                        + "\n钱包余额: " + EconomyMoney.format(result.walletBalance());
            }
            return "钓到了 " + result.fishName() + "！"
                    + "\n尺寸: " + EconomyMoney.format(result.size()) + " cm"
                    + "\n获得: " + EconomyMoney.format(result.reward())
                    + "\n消耗: " + EconomyMoney.format(result.cost())
                    + (result.luckUsed() ? "\n本次消耗了幸运鱼饵。" : "")
                    + "\n钱包余额: " + EconomyMoney.format(result.walletBalance());
        });
    }

    @GroupMessageHandler
    @MessageHandlerFilter(cmd = EconomyCommandPatterns.FISHING_ROD_BUY)
    @CommandInfo(cmd = "购买鱼竿", desc = "购买第一根鱼竿")
    public void buyRod(Bot bot, GroupMessageEvent event) {
        EconomyCommandSupport.execute(bot, event, () -> {
            int level = fishingService.buyRod(
                    event.getUserId(),
                    EconomyCommandSupport.nickname(event),
                    EconomyCommandSupport.operationId("fishing-rod-buy", event)
            );
            return "购买鱼竿成功，当前等级: " + level;
        });
    }

    @GroupMessageHandler
    @MessageHandlerFilter(cmd = "^(升级鱼竿)$")
    @CommandInfo(cmd = "升级鱼竿", desc = "消耗金币提升鱼竿等级，最高 5 级")
    public void upgrade(Bot bot, GroupMessageEvent event) {
        EconomyCommandSupport.execute(bot, event, () -> {
            int level = fishingService.upgradeRod(
                    event.getUserId(),
                    EconomyCommandSupport.nickname(event),
                    EconomyCommandSupport.operationId("fishing-rod-upgrade", event)
            );
            return "鱼竿升级成功，当前等级: " + level;
        });
    }

    @GroupMessageHandler
    @MessageHandlerFilter(cmd = "^(鱼竿等级|鱼塘等级|钓鱼等级)$")
    @CommandInfo(cmd = "鱼竿等级", desc = "查看鱼竿等级")
    public void level(Bot bot, GroupMessageEvent event) {
        EconomyCommandSupport.execute(
                bot,
                event,
                () -> "当前鱼竿等级: " + fishingService.rodLevel(event.getUserId())
        );
    }

    @GroupMessageHandler
    @MessageHandlerFilter(cmd = "^(钓鱼记录|鱼类图鉴|钓鱼信息)$")
    @CommandInfo(cmd = "钓鱼信息", desc = "查看最近钓到的鱼")
    public void history(Bot bot, GroupMessageEvent event) {
        EconomyCommandSupport.execute(bot, event, () -> {
            List<EconomyFishingCatch> catches = fishingService.recentCatches(event.getUserId());
            if (catches.isEmpty()) {
                return "还什么都没有钓到";
            }
            StringBuilder builder = new StringBuilder("最近钓鱼记录");
            for (EconomyFishingCatch item : catches) {
                builder.append("\n- ").append(item.getFishName())
                        .append(" ").append(EconomyMoney.format(item.getSizeCm())).append(" cm")
                        .append("  ").append(EconomyMoney.format(item.getReward())).append(" 金币");
            }
            return builder.toString();
        });
    }

    @GroupMessageHandler
    @MessageHandlerFilter(cmd = "^钓鱼排行榜$")
    @CommandInfo(cmd = "钓鱼排行榜", desc = "查看最大尺寸钓鱼排行")
    public void leaderboard(Bot bot, GroupMessageEvent event) {
        EconomyCommandSupport.execute(bot, event, () -> {
            List<EconomyFishingService.LeaderboardEntry> entries =
                    fishingService.leaderboard(10);
            if (entries.isEmpty()) {
                return "暂无钓鱼排行数据";
            }
            StringBuilder builder = new StringBuilder("钓鱼排行榜");
            int rank = 1;
            for (EconomyFishingService.LeaderboardEntry entry : entries) {
                builder.append("\n").append(rank++).append(". ")
                        .append(entry.userId()).append("  ")
                        .append(entry.fishName()).append("  ")
                        .append(EconomyMoney.format(entry.size())).append(" cm");
            }
            return builder.toString();
        });
    }
}
