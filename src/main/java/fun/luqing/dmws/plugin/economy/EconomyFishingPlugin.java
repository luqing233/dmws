package fun.luqing.dmws.plugin.economy;

import com.mikuac.shiro.annotation.GroupMessageHandler;
import com.mikuac.shiro.annotation.MessageHandlerFilter;
import com.mikuac.shiro.annotation.common.Shiro;
import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.common.utils.ShiroUtils;
import com.mikuac.shiro.dto.event.message.GroupMessageEvent;
import fun.luqing.dmws.common.annotation.CommandInfo;
import fun.luqing.dmws.common.annotation.EconomyReadOnly;
import fun.luqing.dmws.common.economy.EconomyMoney;
import fun.luqing.dmws.entity.dmw.economy.EconomyFishingCatch;
import fun.luqing.dmws.service.dmw.economy.EconomyFishingService;
import fun.luqing.dmws.service.dmw.economy.EconomyFishingQueue;
import fun.luqing.dmws.service.dmw.economy.EconomyPlayerNameService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
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
    private final fun.luqing.dmws.config.DmwConfig config;
    private final fun.luqing.dmws.service.dmw.economy.EconomyCombatService combat;
    private final EconomyFishingQueue queue;
    @Value("${dmws.economy.fishing-scheduler-enabled:true}")
    private boolean schedulerEnabled = true;

    @GroupMessageHandler
    @MessageHandlerFilter(cmd = "^(钓鱼|抛竿|fish)(\\s+[白绿蓝紫金红]色?)?$")
    @CommandInfo(cmd = "钓鱼", desc = "抛竿后等待20～60秒自动收竿，结算后可立即再抛")
    public void fish(Bot bot, GroupMessageEvent event) {
        EconomyCommandSupport.execute(bot, event, () -> {
            if (!schedulerEnabled) return "钓鱼暂时关闭，请稍后再试。";
            String[] parts=EconomyCommandSupport.plainText(event).split("\\s+");
            String operation=EconomyCommandSupport.operationId("fishing",event);
            String group=EconomyCommandSupport.groupName(bot,event.getGroupId());
            var sender = event.getSender();
            String nickname = sender == null ? "" : EconomyPlayerNameService.visible(
                    sender.getCard(), sender.getNickname(), event.getUserId());
            var ticket = fishingService.cast(event.getUserId(), nickname, operation, bot.getSelfId(),
                    event.getGroupId(), group, event.getMessageId(), parts.length == 1 ? null : parts[1]);
            return ShiroUtils.escape(ticket.message());
        });
    }

    @GroupMessageHandler
    @MessageHandlerFilter(cmd = "^钓鱼状态$")
    @EconomyReadOnly
    @CommandInfo(cmd = "钓鱼状态", desc = "查看当前抛竿状态或最近一次收竿回执")
    public void status(Bot bot, GroupMessageEvent event) {
        EconomyCommandSupport.execute(bot, event, () -> ShiroUtils.escape(queue.status(event.getUserId())));
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
            return "鱼竿购买成功。\n当前等级：" + level + "级";
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
            return "鱼竿升级成功。\n当前等级：" + level + "级";
        });
    }

    @GroupMessageHandler
    @MessageHandlerFilter(cmd = "^(鱼竿等级|鱼塘等级|钓鱼等级)$")
    @EconomyReadOnly
    @CommandInfo(cmd = "鱼竿等级", desc = "查看鱼竿等级")
    public void level(Bot bot, GroupMessageEvent event) {
        EconomyCommandSupport.execute(
                bot,
                event,
                () -> fishingService.describeRod(event.getUserId())
        );
    }

    @GroupMessageHandler
    @MessageHandlerFilter(cmd = "^(钓鱼记录|钓鱼信息)$")
    @EconomyReadOnly
    @CommandInfo(cmd = "钓鱼信息", desc = "查看最近钓到的鱼")
    public void history(Bot bot, GroupMessageEvent event) {
        EconomyCommandSupport.execute(bot, event, () -> {
            List<EconomyFishingCatch> catches = fishingService.recentCatches(event.getUserId());
            if (catches.isEmpty()) {
                return "暂无钓获记录。";
            }
            StringBuilder builder = new StringBuilder("最近钓鱼记录");
            for (EconomyFishingCatch item : catches) {
                builder.append("\n\n鱼种：").append(item.getFishName())
                        .append("\n大小：").append(EconomyMoney.format(item.getSizeCm())).append("厘米")
                        .append("\n售鱼收入：").append(EconomyMoney.format(item.getReward())).append("金币");
                if (item.getCaughtAt() != null) builder.append("\n钓获时间：")
                        .append(item.getCaughtAt().format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")));
            }
            EconomyCommandSupport.sendTextQuery(bot, event, config, "钓鱼记录", builder.toString());
            return null;
        });
    }

    @GroupMessageHandler
    @MessageHandlerFilter(cmd = "^(鱼类图鉴|鱼种列表)(\\s+[0-9]+)?$")
    @EconomyReadOnly
    @CommandInfo(cmd = "鱼类图鉴", desc = "查看数据库鱼种、彩蛋和解锁情况；可加页码")
    public void catalog(Bot bot, GroupMessageEvent event) {
        EconomyCommandSupport.execute(bot, event, () -> {
            String[] parts = EconomyCommandSupport.plainText(event).split("\\s+");
            int page = 1;
            if (parts.length > 1) {
                try { page = Integer.parseInt(parts[1]); }
                catch (NumberFormatException exception) { return "图鉴页码过大"; }
            }
            EconomyCommandSupport.sendTextQuery(bot, event, config, "鱼类图鉴", fishingService.describeCatalog(event.getUserId(), page));
            return null;
        });
    }

    @GroupMessageHandler
    @MessageHandlerFilter(cmd = "^(武力值|战斗信息)$")
    @EconomyReadOnly
    @CommandInfo(cmd = "武力值", desc = "查看抢劫和鳄鱼搏斗共用的武力值")
    public void strength(Bot bot,GroupMessageEvent event) {
        EconomyCommandSupport.execute(bot,event,()->"武力值："+combat.strength(event.getUserId())+"/100；有效胜利每天最多成长2点。");
    }

    @GroupMessageHandler
    @MessageHandlerFilter(cmd = "^(钓鱼排行榜|钓鱼榜)$")
    @EconomyReadOnly
    @CommandInfo(cmd = "钓鱼排行榜", desc = "合并转发单条鱼价值最高的前十位玩家")
    public void leaderboard(Bot bot, GroupMessageEvent event) {
        EconomyCommandSupport.execute(bot, event, () -> {
            List<EconomyFishingService.LeaderboardEntry> entries =
                    fishingService.leaderboard(10);
            if (entries.isEmpty()) {
                return "暂无钓鱼排行数据";
            }
            var nodes = new java.util.ArrayList<String>();
            var playerNames = names.resolveAll(bot, event.getGroupId(), entries.stream().map(EconomyFishingService.LeaderboardEntry::userId).toList());
            int rank=1;
            for(var entry:entries) {
                String name=playerNames.get(entry.userId());
                if(name==null || name.isBlank() || name.equals("这位玩家")) name=entry.nickname();
                if(name==null || name.isBlank() || name.matches("[0-9]+")) name="这位玩家";
                nodes.add("第"+(rank++)+"名："+name+"\n鱼种："+entry.fishName()+"\n大小："+EconomyMoney.format(entry.size())
                        +"厘米\n价值："+EconomyMoney.format(entry.reward())+"金币\n钓获群聊："
                        +(entry.groupName()==null || entry.groupName().isBlank()?"未记录":entry.groupName()));
            }
            EconomyCommandSupport.sendForward(bot,event,config,nodes);
            return null;
        });
    }
}
