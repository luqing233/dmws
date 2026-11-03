package fun.luqing.dmws.service.dmw.economy;

import fun.luqing.dmws.common.economy.EconomyException;
import fun.luqing.dmws.common.economy.EconomyMoney;
import fun.luqing.dmws.config.EconomyProperties;
import fun.luqing.dmws.entity.dmw.economy.EconomyRobRecord;
import fun.luqing.dmws.entity.dmw.economy.EconomyUserProfile;
import fun.luqing.dmws.enums.EconomyAccountScope;
import fun.luqing.dmws.enums.EconomyLedgerReason;
import fun.luqing.dmws.repository.dmw.economy.EconomyRobRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
public class EconomyRobService {

    private static final BigDecimal POOR_THRESHOLD = new BigDecimal("50.00");

    private final EconomyProperties properties;
    private final EconomyOperationService operationService;
    private final EconomyUserProfileService profileService;
    private final EconomyLedgerService ledgerService;
    private final EconomyRobRecordRepository recordRepository;

    @Transactional(transactionManager = "dmwTransactionManager")
    public RobResult rob(
            long robberId,
            String robberNickname,
            long targetId,
            String operationId
    ) {
        if (robberId == targetId) {
            throw new EconomyException("ROB_SELF", "不能抢劫自己");
        }
        EconomyUserProfile profile = profileService.getOrCreateForUpdate(robberId, robberNickname);
        LocalDateTime now = LocalDateTime.now(properties.zoneId());
        if (profile.getLastRobAt() != null) {
            long elapsed = Duration.between(profile.getLastRobAt(), now).toSeconds();
            if (elapsed < properties.getRobCooldownSeconds()) {
                long remaining = properties.getRobCooldownSeconds() - Math.max(0, elapsed);
                throw new EconomyException(
                        "ROB_COOLDOWN",
                        "先喘口气吧，距离下次行动还有 " + formatCooldown(remaining) + "。"
                );
            }
        }

        operationService.start(operationId, "ROB", robberId, "抢劫用户 " + targetId);
        profile.setLastRobAt(now);
        BigDecimal targetBalance = ledgerService.balance(targetId, EconomyAccountScope.WALLET).balance();

        if (targetBalance.compareTo(POOR_THRESHOLD) < 0) {
            boolean angry = ThreadLocalRandom.current().nextDouble() < 0.45;
            EconomyRobRecord poorRecord = new EconomyRobRecord();
            poorRecord.setRobberId(robberId);
            poorRecord.setTargetId(targetId);
            poorRecord.setSuccess(false);
            poorRecord.setAmount(EconomyMoney.zero());
            recordRepository.save(poorRecord);
            return new RobResult(
                    false,
                    true,
                    false,
                    angry,
                    false,
                    targetId,
                    EconomyMoney.zero(),
                    EconomyMoney.zero(),
                    targetBalance,
                    ledgerService.balance(robberId, EconomyAccountScope.WALLET).balance()
            );
        }

        boolean robBoostUsed = profile.getRobBoostCharges() > 0;
        if (robBoostUsed) {
            profile.setRobBoostCharges(profile.getRobBoostCharges() - 1);
        }
        double successChance = Math.min(0.75, 0.35 + (robBoostUsed ? 0.20 : 0.0));
        boolean success = ThreadLocalRandom.current().nextDouble() < successChance;
        BigDecimal amount = EconomyMoney.zero();
        BigDecimal targetAfter = targetBalance;
        boolean caught = false;
        boolean resisted = false;

        if (success) {
            BigDecimal rate = BigDecimal.valueOf(
                    ThreadLocalRandom.current().nextDouble(0.05, 0.350001)
            );
            amount = EconomyMoney.normalize(
                    targetBalance.multiply(rate).setScale(2, RoundingMode.DOWN)
            );
            if (amount.signum() > 0) {
                ledgerService.transferUser(
                        targetId,
                        EconomyAccountScope.WALLET,
                        robberId,
                        EconomyAccountScope.WALLET,
                        amount,
                        operationId,
                        EconomyLedgerReason.ROB_REWARD,
                        robberId,
                        "被用户 " + robberId + " 抢劫"
                );
                targetAfter = EconomyMoney.normalize(targetBalance.subtract(amount));
            }
            resisted = ThreadLocalRandom.current().nextDouble() < 0.30;
        } else {
            caught = ThreadLocalRandom.current().nextDouble() < 0.25;
            if (caught) {
                amount = ledgerService.debitUpTo(
                        robberId,
                        EconomyAccountScope.WALLET,
                        EconomyMoney.positive(properties.getRobFine()),
                        operationId,
                        EconomyLedgerReason.ROB_FINE,
                        robberId,
                        "抢劫失败罚款"
                );
            }
        }

        EconomyRobRecord record = new EconomyRobRecord();
        record.setRobberId(robberId);
        record.setTargetId(targetId);
        record.setSuccess(success);
        record.setAmount(amount);
        recordRepository.save(record);

        return new RobResult(
                success,
                false,
                caught,
                resisted,
                robBoostUsed,
                targetId,
                success ? amount : EconomyMoney.zero(),
                success ? EconomyMoney.zero() : amount,
                targetAfter,
                ledgerService.balance(robberId, EconomyAccountScope.WALLET).balance()
        );
    }

    public record RobResult(
            boolean success,
            boolean poorTarget,
            boolean caught,
            boolean resisted,
            boolean robBoostUsed,
            long targetId,
            BigDecimal stolen,
            BigDecimal fine,
            BigDecimal targetBalanceAfter,
            BigDecimal walletBalance
    ) {
    }

    private String formatCooldown(long seconds) {
        long minutes = seconds / 60;
        long remainingSeconds = seconds % 60;
        if (minutes <= 0) {
            return remainingSeconds + " 秒";
        }
        return remainingSeconds == 0
                ? minutes + " 分钟"
                : minutes + " 分 " + remainingSeconds + " 秒";
    }
}
