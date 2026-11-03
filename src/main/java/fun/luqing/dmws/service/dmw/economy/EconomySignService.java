package fun.luqing.dmws.service.dmw.economy;

import fun.luqing.dmws.common.economy.EconomyException;
import fun.luqing.dmws.common.economy.EconomyMoney;
import fun.luqing.dmws.config.EconomyProperties;
import fun.luqing.dmws.entity.dmw.economy.EconomyUserProfile;
import fun.luqing.dmws.enums.EconomyAccountScope;
import fun.luqing.dmws.enums.EconomyLedgerReason;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
public class EconomySignService {

    private static final String DOUBLE_CARD = "double-sign-card";
    private static final String TRIPLE_CARD = "triple-sign-card";
    private static final String MONTHLY_CARD = "sign-monthly-card";

    private final EconomyProperties properties;
    private final EconomyOperationService operationService;
    private final EconomyUserProfileService profileService;
    private final EconomyItemService itemService;
    private final EconomyLedgerService ledgerService;

    @Transactional(transactionManager = "dmwTransactionManager")
    public SignResult sign(long userId, String nickname, String operationId) {
        operationService.start(operationId, "SIGN", userId, "每日签到");

        EconomyUserProfile profile = profileService.getOrCreateForUpdate(userId, nickname);
        LocalDate today = LocalDate.now(properties.zoneId());
        if (today.equals(profile.getLastSignDate())) {
            throw new EconomyException("ALREADY_SIGNED", "今天已经签到过了");
        }

        boolean continued = profile.getLastSignDate() != null
                && profile.getLastSignDate().equals(today.minusDays(1));
        int streak;
        if (continued) {
            streak = profile.getSignStreak() + 1;
            if (streak == 2) {
                profile.setOldSignNumber(0);
            }
        } else {
            if (profile.getSignStreak() > 0) {
                profile.setOldSignNumber(profile.getSignStreak());
            }
            streak = 1;
        }

        BigDecimal base = randomReward();
        BigDecimal multiplier = BigDecimal.ONE;
        String bonusText = "";
        if (itemService.consumeOneIfPresent(userId, MONTHLY_CARD)) {
            multiplier = new BigDecimal("5");
            bonusText = "，消耗签到月卡";
        } else if (itemService.consumeOneIfPresent(userId, TRIPLE_CARD)) {
            multiplier = new BigDecimal("3");
            bonusText = "，消耗三倍签到卡";
        } else if (itemService.consumeOneIfPresent(userId, DOUBLE_CARD)) {
            multiplier = new BigDecimal("2");
            bonusText = "，消耗双倍签到卡";
        }

        BigDecimal reward = EconomyMoney.positive(
                base.multiply(multiplier).setScale(2, RoundingMode.HALF_UP)
        );
        EconomyLedgerService.AccountBalance balance = ledgerService.creditUser(
                userId,
                EconomyAccountScope.WALLET,
                reward,
                operationId,
                EconomyLedgerReason.SIGN_REWARD,
                userId,
                "签到奖励，连续 " + streak + " 天"
        );

        profile.setLastSignDate(today);
        profile.setSignStreak(streak);
        profile.setTotalSignCount(profile.getTotalSignCount() + 1);
        profile.setTotalEarned(profile.getTotalEarned().add(reward));

        return new SignResult(reward, streak, balance.balance(), bonusText);
    }

    private BigDecimal randomReward() {
        BigDecimal min = properties.getSignMin().setScale(0, RoundingMode.CEILING);
        BigDecimal max = properties.getSignMax().setScale(0, RoundingMode.FLOOR);
        if (max.compareTo(min) < 0) {
            return EconomyMoney.normalize(properties.getSignMin());
        }
        int lower = min.intValueExact();
        int upper = max.intValueExact();
        return new BigDecimal(ThreadLocalRandom.current().nextInt(lower, upper + 1))
                .setScale(2, RoundingMode.HALF_UP);
    }

    public record SignResult(
            BigDecimal reward,
            int streak,
            BigDecimal walletBalance,
            String bonusText
    ) {
    }
}
