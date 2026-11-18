package fun.luqing.dmws.plugin.economy;
import com.mikuac.shiro.annotation.*;
import com.mikuac.shiro.annotation.common.Shiro;
import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.dto.event.message.GroupMessageEvent;
import fun.luqing.dmws.common.annotation.CommandInfo;
import fun.luqing.dmws.common.annotation.EconomyReadOnly;
import fun.luqing.dmws.service.dmw.economy.*;
import fun.luqing.dmws.config.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Shiro @Component @RequiredArgsConstructor
@ConditionalOnProperty(prefix="dmws.economy",name="enabled",havingValue="true",matchIfMissing=true)
public class EconomyFishingBaitPlugin {
    private final EconomyFishingBaitService bait;
    private final EconomyItemService items;
    private final EconomyProperties properties;
    private final DmwConfig config;
    @GroupMessageHandler @MessageHandlerFilter(cmd="^设置鱼饵\\s+[白绿蓝紫金红]色?$")
    @CommandInfo(startWith="设置鱼饵",desc="设置默认幸运鱼饵品质")
    public void select(Bot bot,GroupMessageEvent event) {
        EconomyCommandSupport.execute(bot,event,()->bait.select(event.getUserId(),EconomyCommandSupport.nickname(event),
                EconomyCommandSupport.plainText(event).split("\\s+")[1],EconomyCommandSupport.operationId("fishing-bait-select",event)));
    }
    @GroupMessageHandler @MessageHandlerFilter(cmd="^(当前鱼饵|我的鱼饵)$")
    @EconomyReadOnly
    @CommandInfo(cmd="当前鱼饵",desc="查看默认品质、鱼饵包数和剩余次数")
    public void status(Bot bot,GroupMessageEvent event) {
        EconomyCommandSupport.execute(bot,event,()->{
            EconomyCommandSupport.sendTextQuery(bot,event,config,"当前鱼饵",bait.describe(event.getUserId()));
            return null;
        });
    }
    @GroupMessageHandler @MessageHandlerFilter(cmd="^钓鱼商店$")
    @EconomyReadOnly
    @CommandInfo(cmd="钓鱼商店",desc="查看六档鱼饵及鱼竿购买、升级")
    public void shop(Bot bot,GroupMessageEvent event) {
        EconomyCommandSupport.execute(bot,event,()-> {
            var nodes=EconomyFishingShopText.nodes(items.fishingShop(),properties.getFishingRodPrice());
            EconomyCommandSupport.sendForward(bot,event,config,nodes);return null;
        });
    }
}
