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
import fun.luqing.dmws.service.dmw.economy.EconomyPlayerNameService;
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
    private final EconomyPlayerNameService names;

    @GroupMessageHandler
    @MessageHandlerFilter(cmd = "^(钓鱼|抛竿|fish)$")
    @CommandInfo(cmd = "钓鱼", desc = "按鱼种、实际尺寸和鱼竿等级结算收入，显示描述与净收益")
    public void fish(Bot bot, GroupMessageEvent event) {
        EconomyCommandSupport.execute(bot, event, () -> {
            EconomyFishingService.FishingResult result = fishingService.fish(
                    event.getUserId(),
                    EconomyCommandSupport.nickname(event),
                    EconomyCommandSupport.operationId("fishing", event)
            );
            return fun.luqing.dmws.service.dmw.economy.EconomyFishingText.receipt(result);
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
    @CommandInfo(cmd = "升级鱼竿", desc = "消耗当前等级×1000金币升级，最高100级，保留历史等级")
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
                () -> fishingService.describeRod(event.getUserId())
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
                        .append("  收入 ").append(EconomyMoney.format(item.getReward())).append(" 金币");
            }
            return builder.toString();
        });
    }

    @GroupMessageHandler
    @MessageHandlerFilter(cmd = "^(钓鱼排行榜|钓鱼榜)$")
    @CommandInfo(cmd = "钓鱼排行榜", desc = "查看最大尺寸钓鱼排行")
    public void leaderboard(Bot bot, GroupMessageEvent event) {
        EconomyCommandSupport.execute(bot, event, () -> {
            List<EconomyFishingService.LeaderboardEntry> entries =
                    fishingService.leaderboard(10);
            if (entries.isEmpty()) {
                return "暂无钓鱼排行数据";
            }
            StringBuilder builder = new StringBuilder("钓鱼排行榜");
            var playerNames = names.resolveAll(bot, event.getGroupId(), entries.stream().map(EconomyFishingService.LeaderboardEntry::userId).toList());
            int rank = 1;
            for (EconomyFishingService.LeaderboardEntry entry : entries) {
                builder.append("\n").append(rank++).append(". ")
                        .append(playerNames.get(entry.userId())).append("  ")
                        .append(entry.fishName()).append("  ")
                        .append(EconomyMoney.format(entry.size())).append(" cm");
            }
            return builder.toString();
        });
    }
}
