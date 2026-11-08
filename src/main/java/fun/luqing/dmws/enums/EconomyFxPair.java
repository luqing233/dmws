package fun.luqing.dmws.enums;

import fun.luqing.dmws.common.economy.EconomyException;
import java.math.BigDecimal;
import java.util.Locale;

public enum EconomyFxPair {
    EUR_USD("欧元/美元", "1.08000000"), GBP_USD("英镑/美元", "1.27000000"),
    USD_JPY("美元/日元", "150.00000000"), USD_CHF("美元/瑞郎", "0.89000000");

    private final String label;
    private final BigDecimal initial;
    EconomyFxPair(String label, String initial) { this.label = label; this.initial = new BigDecimal(initial); }
    public String label() { return label; }
    public String code() { return name().replace('_', '/'); }
    public BigDecimal initial() { return initial; }
    public static EconomyFxPair parse(String text) {
        String value = text == null ? "" : text.trim().toUpperCase(Locale.ROOT).replace('_', '/');
        for (var pair : values()) {
            if (pair.code().equals(value) || pair.label.equals(value)) return pair;
        }
        throw new EconomyException("INVALID_PAIR", "货币对支持 EUR/USD、GBP/USD、USD/JPY、USD/CHF");
    }
}
