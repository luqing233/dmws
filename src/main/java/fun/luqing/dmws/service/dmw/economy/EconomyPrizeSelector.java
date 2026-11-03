package fun.luqing.dmws.service.dmw.economy;

import fun.luqing.dmws.common.economy.EconomyException;
import fun.luqing.dmws.entity.dmw.economy.EconomyRafflePrize;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public final class EconomyPrizeSelector {

    private EconomyPrizeSelector() {
    }

    public static EconomyRafflePrize select(List<EconomyRafflePrize> prizes) {
        if (prizes == null || prizes.isEmpty()) {
            throw new EconomyException("PRIZE_POOL_EMPTY", "当前没有可抽取的奖品");
        }
        long totalWeight = prizes.stream()
                .filter(prize -> prize.getWeight() != null && prize.getWeight() > 0)
                .mapToLong(EconomyRafflePrize::getWeight)
                .sum();
        if (totalWeight <= 0) {
            throw new EconomyException("PRIZE_POOL_INVALID", "奖品权重配置无效");
        }

        long selected = ThreadLocalRandom.current().nextLong(totalWeight);
        long cursor = 0;
        for (EconomyRafflePrize prize : prizes) {
            if (prize.getWeight() == null || prize.getWeight() <= 0) {
                continue;
            }
            cursor += prize.getWeight();
            if (selected < cursor) {
                return prize;
            }
        }
        throw new EconomyException("PRIZE_POOL_INVALID", "奖品选择失败");
    }
}
