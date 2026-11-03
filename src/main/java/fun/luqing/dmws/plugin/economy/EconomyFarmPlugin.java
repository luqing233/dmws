package fun.luqing.dmws.plugin.economy;

import com.mikuac.shiro.annotation.GroupMessageHandler;
import com.mikuac.shiro.annotation.MessageHandlerFilter;
import com.mikuac.shiro.annotation.common.Shiro;
import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.dto.event.message.GroupMessageEvent;
import fun.luqing.dmws.common.annotation.CommandInfo;
import fun.luqing.dmws.common.economy.EconomyException;
import fun.luqing.dmws.common.economy.EconomyMoney;
import fun.luqing.dmws.entity.dmw.economy.EconomyFarmCrop;
import fun.luqing.dmws.service.dmw.economy.EconomyFarmService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Shiro
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "dmws.economy", name = "enabled", havingValue = "true", matchIfMissing = true)
public class EconomyFarmPlugin {

    private final EconomyFarmService farmService;

    @GroupMessageHandler
    @MessageHandlerFilter(cmd = "^(农场|我的农场|农场信息|农场详情|农场等级)$")
    @CommandInfo(cmd = "农场", desc = "查看四块农田状态")
    public void overview(Bot bot, GroupMessageEvent event) {
        EconomyCommandSupport.execute(bot, event, () -> {
            List<EconomyFarmService.PlotView> plots = farmService.overview(
                    event.getUserId(),
                    EconomyCommandSupport.nickname(event)
            );
            StringBuilder builder = new StringBuilder("我的农场");
            for (EconomyFarmService.PlotView plot : plots) {
                builder.append("\n#").append(plot.plotIndex()).append(" ")
                        .append(plot.cropName());
                if (plot.remainingSeconds() > 0) {
                    builder.append(" 剩余 ").append(plot.remainingSeconds()).append(" 秒");
                }
                if (plot.watered()) {
                    builder.append("（已浇水）");
                }
            }
            return builder.toString();
        });
    }

    @GroupMessageHandler
    @MessageHandlerFilter(cmd = "^农场商店$")
    @CommandInfo(cmd = "农场商店", desc = "查看可种植作物")
    public void shop(Bot bot, GroupMessageEvent event) {
        EconomyCommandSupport.execute(bot, event, () -> {
            List<EconomyFarmCrop> crops = farmService.crops();
            if (crops.isEmpty()) {
                return "农场商店暂未开放";
            }
            StringBuilder builder = new StringBuilder("农场商店");
            for (EconomyFarmCrop crop : crops) {
                builder.append("\n- ").append(crop.getName())
                        .append(" [").append(crop.getCode()).append("]")
                        .append(" 种子:").append(EconomyMoney.format(crop.getSeedPrice()))
                        .append(" 成熟:").append(crop.getGrowthSeconds()).append("秒")
                        .append(" 售价:").append(EconomyMoney.format(crop.getSellPrice()));
            }
            builder.append("\n播种方式: 播种 <作物> <地块>");
            return builder.toString();
        });
    }

    @GroupMessageHandler
    @MessageHandlerFilter(startWith = {"播种", "种植"})
    @CommandInfo(startWith = "播种", desc = "播种/种植 <作物> <地块>")
    public void plant(Bot bot, GroupMessageEvent event) {
        EconomyCommandSupport.execute(bot, event, () -> {
            String[] parts = EconomyCommandSupport.arguments(event, "播种", "种植");
            if (parts.length < 2) {
                throw new EconomyException("ARGUMENT_REQUIRED", "格式: 播种 <作物> <地块>");
            }
            int plot = EconomyCommandSupport.quantity(parts[1], 0);
            EconomyFarmService.PlotView view = farmService.plant(
                    event.getUserId(),
                    EconomyCommandSupport.nickname(event),
                    parts[0],
                    plot,
                    EconomyCommandSupport.operationId("farm-plant", event)
            );
            return "播种成功: " + view.cropName() + " -> 地块 #" + plot
                    + "\n成熟需要 " + view.remainingSeconds() + " 秒";
        });
    }

    @GroupMessageHandler
    @MessageHandlerFilter(startWith = {"浇水", "帮浇水"})
    @CommandInfo(startWith = "浇水", desc = "浇水/帮浇水 <地块>，缩短 20% 剩余成熟时间")
    public void water(Bot bot, GroupMessageEvent event) {
        EconomyCommandSupport.execute(bot, event, () -> {
            String[] parts = EconomyCommandSupport.arguments(event, "帮浇水", "浇水");
            if (parts.length == 0) {
                throw new EconomyException("ARGUMENT_REQUIRED", "请提供地块编号");
            }
            int plot = EconomyCommandSupport.quantity(parts[0], 0);
            EconomyFarmService.PlotView view = farmService.water(
                    event.getUserId(),
                    EconomyCommandSupport.nickname(event),
                    plot
            );
            return "浇水成功: 地块 #" + plot + " 剩余 " + view.remainingSeconds() + " 秒";
        });
    }

    @GroupMessageHandler
    @MessageHandlerFilter(startWith = {"收获"})
    @CommandInfo(startWith = "收获", desc = "收获 <地块>")
    public void harvest(Bot bot, GroupMessageEvent event) {
        EconomyCommandSupport.execute(bot, event, () -> {
            String[] parts = EconomyCommandSupport.arguments(event, "收获");
            if (parts.length == 0) {
                throw new EconomyException("ARGUMENT_REQUIRED", "请提供地块编号");
            }
            int plot = EconomyCommandSupport.quantity(parts[0], 0);
            EconomyFarmService.HarvestView result = farmService.harvest(
                    event.getUserId(),
                    EconomyCommandSupport.nickname(event),
                    plot,
                    EconomyCommandSupport.operationId("farm-harvest", event)
            );
            return "收获成功: " + result.cropName() + " x" + result.yield()
                    + "\n获得: " + EconomyMoney.format(result.reward());
        });
    }
}
