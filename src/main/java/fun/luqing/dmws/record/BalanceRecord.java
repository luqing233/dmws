package fun.luqing.dmws.record;

import java.math.BigDecimal;

public record BalanceRecord(
        Long userId,
        boolean success,
        BigDecimal walletBalance,
        BigDecimal bankBalance
) {
}
