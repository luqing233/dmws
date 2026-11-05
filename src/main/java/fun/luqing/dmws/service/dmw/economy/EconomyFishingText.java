package fun.luqing.dmws.service.dmw.economy;

import fun.luqing.dmws.common.economy.EconomyMoney;

public final class EconomyFishingText {
    private EconomyFishingText() { }

    public static String receipt(EconomyFishingService.FishingResult result) {
        String text;
        if (result.success()) {
            text = "钓到了「" + result.fishName() + "」！ " + EconomyMoney.format(result.size()) + " cm";
            text += "\n" + (result.fishDescription() == null || result.fishDescription().isBlank() ? "暂无鱼类介绍" : result.fishDescription());
            text += "\n售鱼收入：" + EconomyMoney.format(result.reward()) + " 金币";
            if (result.rodMultiplier().compareTo(java.math.BigDecimal.ONE) > 0) {
                text += "（鱼竿 ×" + result.rodMultiplier().toPlainString() + "）";
            }
        } else {
            text = "鱼跑掉了……";
        }
        text += "\n出钓费用：" + EconomyMoney.format(result.cost()) + " 金币";
        text += "\n净收益：" + (result.netProfit().signum() > 0 ? "+" : "") + EconomyMoney.format(result.netProfit()) + " 金币";
        if (result.luckUsed()) text += "\n消耗 1 次幸运鱼饵增益；净收益未计道具购入成本";
        return text;
    }
}
