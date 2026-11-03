package fun.luqing.dmws.common.economy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

public final class EconomyMoney {

    public static final int SCALE = 2;
    public static final BigDecimal MAX = new BigDecimal("99999999999999999.99");

    private EconomyMoney() {
    }

    public static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal normalize(BigDecimal value) {
        if (value == null) {
            throw new EconomyException("INVALID_AMOUNT", "金额不能为空");
        }
        return value.setScale(SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal positive(BigDecimal value) {
        BigDecimal normalized = normalize(value);
        if (normalized.signum() <= 0) {
            throw new EconomyException("INVALID_AMOUNT", "金额必须大于 0");
        }
        if (normalized.compareTo(MAX) > 0) {
            throw new EconomyException("AMOUNT_TOO_LARGE", "金额超过系统上限");
        }
        return normalized;
    }

    public static BigDecimal parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new EconomyException(
                    "INVALID_AMOUNT",
                    "请提供金额，例如 100、100元、1.5万；也可使用 ! 表示全部"
            );
        }

        String value = normalizeInput(raw);

        BigDecimal multiplier = BigDecimal.ONE;
        if (value.endsWith("亿")) {
            multiplier = new BigDecimal("100000000");
            value = value.substring(0, value.length() - 1);
        } else if (value.endsWith("万")) {
            multiplier = new BigDecimal("10000");
            value = value.substring(0, value.length() - 1);
        } else if (value.endsWith("w")) {
            multiplier = new BigDecimal("10000");
            value = value.substring(0, value.length() - 1);
        } else if (value.endsWith("k")) {
            multiplier = new BigDecimal("1000");
            value = value.substring(0, value.length() - 1);
        }

        try {
            return positive(new BigDecimal(value).multiply(multiplier));
        } catch (NumberFormatException exception) {
            throw new EconomyException(
                    "INVALID_AMOUNT",
                    "金额格式不正确，可输入 100、100元、1.5万，或用 ! 表示全部"
            );
        }
    }

    public static String format(BigDecimal amount) {
        return normalize(amount).toPlainString();
    }

    private static String normalizeInput(String raw) {
        StringBuilder normalized = new StringBuilder(raw.length());
        for (char character : raw.trim().toCharArray()) {
            if (character >= '０' && character <= '９') {
                normalized.append((char) ('0' + character - '０'));
            } else if (character == '．') {
                normalized.append('.');
            } else if (character == '，' || character == ',') {
                continue;
            } else if (character == '￥' || character == '¥') {
                continue;
            } else if (!Character.isWhitespace(character)) {
                normalized.append(character);
            }
        }

        String value = normalized.toString().toLowerCase(Locale.ROOT);
        for (String suffix : new String[]{"金币", "块钱", "元钱", "元", "块", "币", "圆"}) {
            if (value.endsWith(suffix)) {
                value = value.substring(0, value.length() - suffix.length());
                break;
            }
        }
        return value;
    }
}
