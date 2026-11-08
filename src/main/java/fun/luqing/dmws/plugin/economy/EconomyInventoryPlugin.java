package fun.luqing.dmws.plugin.economy;

import com.mikuac.shiro.annotation.GroupMessageHandler;
import com.mikuac.shiro.annotation.MessageHandlerFilter;
import com.mikuac.shiro.annotation.common.Shiro;
import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.dto.event.message.GroupMessageEvent;
import fun.luqing.dmws.common.annotation.CommandInfo;
import fun.luqing.dmws.common.economy.EconomyException;
import fun.luqing.dmws.common.economy.EconomyMoney;
import fun.luqing.dmws.config.DmwConfig;
import fun.luqing.dmws.entity.dmw.economy.EconomyItemDefinition;
import fun.luqing.dmws.entity.dmw.economy.EconomyTitleDefinition;
import fun.luqing.dmws.entity.dmw.economy.EconomyUserItem;
import fun.luqing.dmws.entity.dmw.economy.EconomyUserTitle;
import fun.luqing.dmws.service.dmw.economy.EconomyItemService;
import fun.luqing.dmws.service.dmw.economy.EconomyTitleService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Shiro
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "dmws.economy", name = "enabled", havingValue = "true", matchIfMissing = true)
public class EconomyInventoryPlugin {

    private final EconomyItemService itemService;
    private final EconomyTitleService titleService;
    private final DmwConfig dmwConfig;

    @GroupMessageHandler
    @fun.luqing.dmws.common.annotation.EconomyReadOnly
    @MessageHandlerFilter(cmd = "(?i)^(我的背包|背包|backpack)$")
    @CommandInfo(cmd = "我的背包", desc = "查看背包中的道具")
    public void backpack(Bot bot, GroupMessageEvent event) {
        EconomyCommandSupport.execute(bot, event, () -> {
            List<EconomyUserItem> items = itemService.backpack(event.getUserId());
            List<String> lines = new java.util.ArrayList<>();
            for (EconomyUserItem item : items) {
                var definition=itemService.definition(item.getItemCode());
                String name = definition.getName();
                lines.add(name + " [" + item.getItemCode() + "] " + item.getQuantity()
                        + (definition.getEffectType() == fun.luqing.dmws.enums.EconomyItemEffectType.FISHING_LUCK ? "包" : "个"));
            }
            String equipment = "";
            if (items.stream().anyMatch(i -> i.getItemCode().equals("electric-baton"))) {
                equipment = itemService.equipment(event.getUserId()).text();
            }
            EconomyCommandSupport.sendForward(bot, event, dmwConfig,
                    EconomyDisplayFormatter.backpack(lines, equipment, itemService.baitStatus(event.getUserId())));
            return null;
        });
    }

    @GroupMessageHandler
    @MessageHandlerFilter(cmd = "^(电棍电量|电棍状态)$")
    @CommandInfo(cmd = "电棍电量", desc = "查看电棍电量与充电进度")
    public void batonStatus(Bot bot, GroupMessageEvent event) {
        EconomyCommandSupport.execute(bot, event, () -> itemService.equipment(event.getUserId()).text());
    }

    @GroupMessageHandler
    @MessageHandlerFilter(cmd = "^道具商店( \\d+)?$")
    @CommandInfo(cmd = "道具商店", desc = "查看可购买道具")
    public void itemShop(Bot bot, GroupMessageEvent event) {
        EconomyCommandSupport.execute(bot, event, () -> {
            List<EconomyItemDefinition> items = itemService.shop();
            if (items.isEmpty()) {
                return "道具商店暂未开放";
            }
            EconomyCommandSupport.sendForward(
                    bot,
                    event,
                    dmwConfig,
                    EconomyDisplayFormatter.itemShop(items)
            );
            return null;
        });
    }

    @GroupMessageHandler
    @MessageHandlerFilter(cmd = EconomyCommandPatterns.ITEM_BUY)
    @CommandInfo(startWith = "购买", desc = "购买 <道具> [数量]；购买称号 <名称>")
    public void buy(Bot bot, GroupMessageEvent event) {
        EconomyCommandSupport.execute(bot, event, () -> {
            String purchaseArgument = EconomyCommandSupport.argument(event, "购买道具", "购买", "buy")
                    .replaceFirst("\\s*[*×]\\s*(\\d+)$", " $1");
            String[] parts = purchaseArgument.isBlank() ? new String[0] : purchaseArgument.split("\\s+");
            if (parts.length == 0) {
                throw new EconomyException("ARGUMENT_REQUIRED", "请提供要购买的道具或称号");
            }

            if ("称号".equals(parts[0])) {
                if (parts.length < 2) {
                    throw new EconomyException("ARGUMENT_REQUIRED", "请提供称号编码或名称");
                }
                EconomyTitleDefinition title = titleService.resolve(parts[1]);
                titleService.buy(
                        event.getUserId(),
                        EconomyCommandSupport.nickname(event),
                        title.getCode(),
                        EconomyCommandSupport.operationId("title-buy", event)
                );
                return "购买称号成功: " + title.getName()
                        + "，花费 " + EconomyMoney.format(title.getPrice());
            }

            boolean qualityArgument=parts[0].equals("幸运鱼饵") && parts.length>1 && parts[1].matches("[白绿蓝紫金红]色?");
            String itemName=parts[0]+(qualityArgument?parts[1]:"");
            int quantityIndex=qualityArgument?2:1;
            EconomyItemDefinition item = itemService.resolve(itemName);
            int quantity = EconomyCommandSupport.quantity(parts.length > quantityIndex ? parts[quantityIndex] : null, 1);
            EconomyItemService.PurchaseResult purchase = itemService.buy(
                    event.getUserId(),
                    EconomyCommandSupport.nickname(event),
                    item.getCode(),
                    quantity,
                    EconomyCommandSupport.operationId("item-buy", event, item.getCode())
            );
            String message = "购买成功: " + item.getName() + " " + quantity + (item.getEffectType()==fun.luqing.dmws.enums.EconomyItemEffectType.FISHING_LUCK?"包":"个")
                    + "，花费 " + EconomyMoney.format(
                    item.getPrice().multiply(java.math.BigDecimal.valueOf(quantity))
            );
            if (purchase.bankTopUp().signum() > 0) {
                message += "，主银行自动补足 " + EconomyMoney.format(purchase.bankTopUp());
            }
            return message;
        });
    }

    @GroupMessageHandler
    @MessageHandlerFilter(startWith = {"使用", "use"})
    @CommandInfo(startWith = "使用", desc = "使用 <道具> [数量]")
    public void use(Bot bot, GroupMessageEvent event) {
        EconomyCommandSupport.execute(bot, event, () -> {
            String[] parts = EconomyCommandSupport.arguments(event, "使用", "use");
            if (parts.length == 0) {
                throw new EconomyException("ARGUMENT_REQUIRED", "请提供要使用的道具");
            }
            boolean qualityArgument=parts[0].equals("幸运鱼饵") && parts.length>1 && parts[1].matches("[白绿蓝紫金红]色?");
            String itemName=parts[0]+(qualityArgument?parts[1]:"");
            int quantityIndex=qualityArgument?2:1;
            EconomyItemDefinition item = itemService.resolve(itemName);
            int quantity = EconomyCommandSupport.quantity(parts.length > quantityIndex ? parts[quantityIndex] : null, 1);
            return itemService.use(
                    event.getUserId(),
                    EconomyCommandSupport.nickname(event),
                    item.getCode(),
                    quantity,
                    EconomyCommandSupport.operationId("item-use", event, item.getCode())
            );
        });
    }

    @GroupMessageHandler
    @MessageHandlerFilter(cmd = "^称号商店$")
    @CommandInfo(cmd = "称号商店", desc = "查看可购买称号")
    public void titleShop(Bot bot, GroupMessageEvent event) {
        EconomyCommandSupport.execute(bot, event, () -> {
            List<EconomyTitleDefinition> titles = titleService.shop();
            if (titles.isEmpty()) {
                return "称号商店暂未开放";
            }
            StringBuilder builder = new StringBuilder("称号商店");
            for (EconomyTitleDefinition title : titles) {
                builder.append("\n- ").append(title.getName())
                        .append(" [").append(title.getCode()).append("]")
                        .append("  ").append(EconomyMoney.format(title.getPrice()))
                        .append("\n  ").append(title.getDescription());
            }
            builder.append("\n购买方式: 购买称号 <编码或名称>");
            return builder.toString();
        });
    }

    @GroupMessageHandler
    @MessageHandlerFilter(cmd = "^(我的称号|称号列表|拥有称号)$")
    @CommandInfo(cmd = "我的称号", desc = "查看已经拥有的称号")
    public void myTitles(Bot bot, GroupMessageEvent event) {
        EconomyCommandSupport.execute(bot, event, () -> {
            List<EconomyUserTitle> titles = titleService.owned(event.getUserId());
            if (titles.isEmpty()) {
                return "你还没有任何称号";
            }
            StringBuilder builder = new StringBuilder("我的称号");
            int index = 1;
            for (EconomyUserTitle title : titles) {
                builder.append("\n").append(index++).append(". ")
                        .append(titleService.titleName(title.getTitleCode()))
                        .append(" [").append(title.getTitleCode()).append("]");
            }
            builder.append("\n切换方式: 切换称号 <序号或编码>");
            return builder.toString();
        });
    }

    @GroupMessageHandler
    @MessageHandlerFilter(startWith = {"切换称号"})
    @CommandInfo(startWith = "切换称号", desc = "切换称号 <序号或编码>，0 表示卸下")
    public void switchTitle(Bot bot, GroupMessageEvent event) {
        EconomyCommandSupport.execute(bot, event, () -> {
            String token = EconomyCommandSupport.argument(event, "切换称号");
            if (token.isBlank()) {
                throw new EconomyException("ARGUMENT_REQUIRED", "请输入称号序号或编码");
            }
            String titleCode = resolveTitleCode(event.getUserId(), token);
            return titleService.activate(
                    event.getUserId(),
                    EconomyCommandSupport.nickname(event),
                    titleCode
            );
        });
    }

    private String resolveTitleCode(long userId, String token) {
        if ("0".equals(token)) {
            return null;
        }
        try {
            int index = Integer.parseInt(token);
            List<EconomyUserTitle> titles = titleService.owned(userId);
            if (index < 1 || index > titles.size()) {
                throw new EconomyException("TITLE_NOT_FOUND", "称号序号不存在");
            }
            return titles.get(index - 1).getTitleCode();
        } catch (NumberFormatException ignored) {
            if (titleService.owned(userId).stream().anyMatch(t -> token.equals(t.getTitleCode()))) return token;
            return titleService.resolve(token).getCode();
        }
    }
}
