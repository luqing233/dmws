package fun.luqing.dmws.plugin.economy;

public final class EconomyCommandPatterns {

    public static final String ITEM_BUY =
            "(?i)^(buy(?:\\s+.*)?|购买(?!\\s*鱼竿).*)$";

    public static final String FISHING_ROD_BUY = "^购买\\s*鱼竿$";

    private EconomyCommandPatterns() {
    }
}
