package fun.luqing.dmws.plugin.economy;

import fun.luqing.dmws.entity.dmw.economy.EconomyItemDefinition;
import fun.luqing.dmws.enums.EconomyItemEffectType;
import fun.luqing.dmws.service.dmw.economy.EconomyBankingService;
import fun.luqing.dmws.service.dmw.economy.EconomyPlayerNameService;

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

    /** One overview and one node per position/receipt; bounded pages preserve all content. */
    public static List<String> market(String category, String text) {
        return textPages("交易｜" + category, text);
    }

    public static List<String> textPages(String title, String text) {
        List<String> bodies = new ArrayList<>();
        for (String section : text.strip().split("\\n\\s*\\n")) {
            while (section.length() > 1600) {
                int split = section.lastIndexOf('\n', 1600);
                if (split <= 0) split = 1600;
                if (Character.isHighSurrogate(section.charAt(split - 1))) split--;
                bodies.add(section.substring(0, split)); section = section.substring(split).stripLeading();
            }
            if (!section.isBlank()) bodies.add(section);
        }
        List<String> nodes = new ArrayList<>();
        for (int i = 0; i < bodies.size(); i++) nodes.add("【" + title + (bodies.size() > 1 ? " · " + (i + 1) + "/" + bodies.size() : "") + "】\n" + bodies.get(i));
        return List.copyOf(nodes);
    }

    public static List<String> backpack(List<String> items, String equipment, String bait) {
        List<String> nodes = new ArrayList<>();
        nodes.add("我的背包\n" + (items.isEmpty() ? "暂无完整道具" : "道具种类：" + items.size())
                + "\n使用：使用 <道具编码或名称> [数量]");
        for (int offset = 0; offset < items.size(); offset += 8) {
            nodes.add("道具清单 " + (offset / 8 + 1) + "\n" + String.join("\n", items.subList(offset, Math.min(offset + 8, items.size()))));
        }
        addBackpackSection(nodes, equipment);
        addBackpackSection(nodes, bait);
        return List.copyOf(nodes);
    }

    private static void addBackpackSection(List<String> nodes, String text) {
        if (text == null || text.isBlank()) return;
        // Multiple owned batons can produce a long status: keep each forward node comfortably bounded.
        while (text.length() > 1800) {
            int split = text.lastIndexOf('\n', 1800);
            if (split <= 0) split = 1800;
            if (Character.isHighSurrogate(text.charAt(split - 1))) split--;
            nodes.add(text.substring(0, split));
            text = text.substring(split).stripLeading();
        }
        if (!text.isBlank()) nodes.add(text);
    }

    public static List<String> leaderboard(List<EconomyBankingService.LeaderboardEntry> entries) {
        List<String> nodes = new ArrayList<>();
        nodes.add("主银行存款排行榜\n更新时间："
                + LocalDateTime.now().format(TIME_FORMAT)
                + "\n共 " + entries.size() + " 位用户上榜");

        int rank = 1;
        for (EconomyBankingService.LeaderboardEntry entry : entries) {
            String nickname = EconomyPlayerNameService.usable(entry.nickname(), entry.userId());
            nodes.add("第 " + rank + " 名"
                    + "\n用户：" + (nickname.isBlank() ? "这位玩家" : nickname)
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
            return "一次战斗 +20个百分点；同种仅一次，可与电棍叠加";
        }
        if (item.getEffectType() == EconomyItemEffectType.FISHING_LUCK) {
            return "每包5次，按品质提供效果；每次只用一种鱼饵";
        }
        if (item.getEffectType() == EconomyItemEffectType.ELECTRIC_BATON) return "可重复使用，100电量，每次抢劫耗电20，最终成功判定+20个百分点";
        if (item.getEffectType() == EconomyItemEffectType.BATON_CHARGER) return "可重复使用，每分钟充1电，每人同时充一根";
        if (item.getEffectType() == EconomyItemEffectType.CRIME_REDUCTION) return "金盆洗手，一次清零全部犯罪值，每日最多2次";
        return "纪念收藏";
    }
}
