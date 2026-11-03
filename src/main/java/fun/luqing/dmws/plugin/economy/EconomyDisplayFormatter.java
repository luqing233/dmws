package fun.luqing.dmws.plugin.economy;

import fun.luqing.dmws.entity.dmw.economy.EconomyItemDefinition;
import fun.luqing.dmws.enums.EconomyItemEffectType;
import fun.luqing.dmws.service.dmw.economy.EconomyBankingService;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class EconomyDisplayFormatter {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private EconomyDisplayFormatter() {
    }

    public static List<String> leaderboard(List<EconomyBankingService.LeaderboardEntry> entries) {
        List<String> nodes = new ArrayList<>();
        nodes.add("主银行存款排行榜\n更新时间："
                + LocalDateTime.now().format(TIME_FORMAT)
                + "\n共 " + entries.size() + " 位用户上榜");

        int rank = 1;
        for (EconomyBankingService.LeaderboardEntry entry : entries) {
            nodes.add("第 " + rank + " 名"
                    + "\n用户：" + entry.nickname()
                    + "\n开户群：" + entry.groupDisplay()
                    + "\n主银行存款：" + money(entry.balance()) + " 金币");
            rank++;
        }
        nodes.add("排行榜仅统计主银行账户余额。");
        return nodes;
    }

    public static List<String> itemShop(List<EconomyItemDefinition> items) {
        List<String> nodes = new ArrayList<>();
        nodes.add("道具商店\n在售商品：" + items.size() + " 件");
        for (EconomyItemDefinition item : items) {
            nodes.add("【" + item.getName() + "】"
                    + "\n编码：" + item.getCode()
                    + "\n价格：" + money(item.getPrice()) + " 金币"
                    + "\n效果：" + effect(item)
                    + "\n说明：" + item.getDescription()
                    + "\n购买：购买 " + item.getCode() + " [数量]");
        }
        nodes.add("发送「购买 道具编码 数量」即可购买。");
        return nodes;
    }

    public static String money(BigDecimal amount) {
        DecimalFormat format = new DecimalFormat(
                "#,##0.00",
                DecimalFormatSymbols.getInstance(Locale.CHINA)
        );
        return format.format(amount);
    }

    private static String effect(EconomyItemDefinition item) {
        if (item.getEffectType() == EconomyItemEffectType.SIGN_BONUS) {
            return "签到自动生效，奖励 ×" + money(item.getEffectValue());
        }
        if (item.getEffectType() == EconomyItemEffectType.TITLE_UNLOCK) {
            return "使用后解锁称号（" + item.getEffectText() + "）";
        }
        if (item.getEffectType() == EconomyItemEffectType.MONEY) {
            return "使用后获得 " + money(item.getEffectValue()) + " 金币";
        }
        if (item.getEffectType() == EconomyItemEffectType.SIGN_STREAK_RESTORE) {
            return "使用后恢复最近一次断签的连续天数";
        }
        if (item.getEffectType() == EconomyItemEffectType.ROB_BOOST) {
            return "使用后下一次抢劫成功率提高 20%";
        }
        if (item.getEffectType() == EconomyItemEffectType.FISHING_LUCK) {
            return "使用后下一次钓鱼成功率提高 20%";
        }
        return "纪念收藏";
    }
}
