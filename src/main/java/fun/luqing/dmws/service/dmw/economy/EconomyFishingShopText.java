package fun.luqing.dmws.service.dmw.economy;

import fun.luqing.dmws.entity.dmw.economy.EconomyItemDefinition;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public final class EconomyFishingShopText {
    private EconomyFishingShopText() { }
    public static List<String> nodes(List<EconomyItemDefinition> items,BigDecimal rodPrice) {
        var nodes=new ArrayList<String>();
        nodes.add("钓鱼商店\n幸运鱼饵每包可用5次。默认白色，默认品质不足时自动购买1包。\n\n购买 幸运鱼饵 蓝 3\n设置鱼饵 蓝\n钓鱼 红（仅本次）\n当前鱼饵\n购买鱼竿\n升级鱼竿");
        for(var item:items) nodes.add(item.getName()+"\n价格："+item.getPrice().stripTrailingZeros().toPlainString()+"金币/包，每包5次。\n"+item.getDescription());
        nodes.add("鱼竿\n购买价格："+rodPrice.stripTrailingZeros().toPlainString()+"金币。\n钓鱼人的第一件装备，购买后可长期使用。\n升级可提高钓鱼成功率、高级鱼出现机会和售鱼收益。\n最高升级至100级。\n升级费用：当前等级×1000金币。");
        return nodes;
    }
}
