package fun.luqing.dmws.service.dmw.economy;

import fun.luqing.dmws.common.economy.EconomyException;
import fun.luqing.dmws.common.economy.EconomyMoney;
import fun.luqing.dmws.config.EconomyAdventureProperties;
import fun.luqing.dmws.config.EconomyProperties;
import fun.luqing.dmws.entity.dmw.economy.EconomyRobRecord;
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

@Service
@RequiredArgsConstructor
public class EconomyRobService {
    private final EconomyProperties properties;
    private final EconomyAdventureProperties rules;
    private final EconomyOperationService operationService;
    private final EconomyUserProfileService profileService;
    private final EconomyLedgerService ledgerService;
    private final EconomyRobRecordRepository recordRepository;
    private final EconomyConditionService conditions;
    private final EconomyGameTime time;
    private final EconomyRobRandom random;
    private final EconomyEquipmentService equipment;
    private final EconomyCombatService combat;

    @Transactional(transactionManager = "dmwTransactionManager")
    public RobResult rob(long robberId, String robberNickname, long targetId, String operationId) {
        if (robberId <= 0 || targetId <= 0) throw new EconomyException("INVALID_USER", "玩家 ID 必须为正整数");
        if (robberId == targetId) throw new EconomyException("ROB_SELF", "不能抢劫自己");
        operationService.start(operationId, "ROB", robberId, "抢劫用户 " + targetId);
        var pair = conditions.lockPair(robberId, robberNickname, targetId, "");
        var robber = pair.get(0);
        var target = pair.get(1);
        conditions.requireAvailable(robber);
        conditions.requireAvailable(target);
        var profile = profileService.getOrCreateForUpdate(robberId, robberNickname);
        LocalDateTime now = time.now();
        if (profile.getLastRobAt() != null) {
            long remaining = properties.getRobCooldownSeconds() - Math.max(0, Duration.between(profile.getLastRobAt(), now).toSeconds());
            if (remaining > 0) throw new EconomyException("ROB_COOLDOWN", "先喘口气吧，距离下次行动还有 " + remaining + " 秒。");
        }
        profile.setLastRobAt(now);
        conditions.addCrime(robber, rules.getCrimePerAttempt());
        BigDecimal targetBefore = ledgerService.balance(targetId, EconomyAccountScope.WALLET).balance();
        boolean poor = targetBefore.signum() <= 0;
        Integer batonEnergy = poor ? null : equipment.useForRobbery(robberId);
        boolean batonUsed = batonEnergy != null;
        boolean legacyBoost = !poor && profile.getRobBoostCharges() > 0;
        boolean boosted = batonUsed || legacyBoost;
        if (legacyBoost) profile.setRobBoostCharges(profile.getRobBoostCharges() - 1);
        boolean success = false, caught = false, resisted = false, assaulted = false;
        long injuredUser = 0;
        int injury = 0;
        LocalDateTime hospitalUntil = null;
        BigDecimal stolen = EconomyMoney.zero(), fine = EconomyMoney.zero();
        double policeChance = EconomyCombatService.policeChance(rules.getPoliceChance(), robber.getCrime());

        if (poor) {
            assaulted = random.chance(rules.getPoorAssaultChance());
            if (assaulted) {
                injury = random.injury();
                conditions.admit(target, injury);
                conditions.addCrime(robber, rules.getCrimePerAssault());
                injuredUser = targetId;
                hospitalUntil = target.getHospitalUntil();
            }
            caught = random.chance(EconomyCombatService.policeChance(rules.getPoliceChance(), robber.getCrime()));
        } else if (random.chance(Math.max(0.05, Math.min(0.60, rules.getCounterChance() + (combat.strength(targetId)-combat.strength(robberId))*0.003)))) {
            resisted = true;
            injury = random.injury();
            conditions.admit(robber, injury);
            injuredUser = robberId;
            hospitalUntil = robber.getHospitalUntil();
        } else if (random.chance(policeChance)) {
            caught = true;
        } else if (random.chance(EconomyCombatService.winChance(combat.strength(robberId), combat.strength(targetId), rules.getSuccessChance(), (batonUsed ? 0.20 : 0) + (legacyBoost ? 0.20 : 0), 0.10, 0.90))) {
            BigDecimal rate = rules.getMinStealRate().add(rules.getMaxStealRate().subtract(rules.getMinStealRate())
                    .multiply(BigDecimal.valueOf(random.fraction())));
            stolen = targetBefore.multiply(rate).min(rules.getMaxStolen()).min(new BigDecimal("5000"))
                    .min(targetBefore).setScale(2, RoundingMode.DOWN);
            if (stolen.signum() > 0) {
                ledgerService.transferUser(targetId, EconomyAccountScope.WALLET, robberId, EconomyAccountScope.WALLET,
                        stolen, operationId, EconomyLedgerReason.ROB_REWARD, robberId, "抢劫用户 " + targetId);
                success = true;
                combat.victory(robberId, "player-" + targetId);
                conditions.addCrime(robber, rules.getCrimePerSuccess());
            }
        }
        if (caught) {
            conditions.addCrime(robber, rules.getCrimePerArrest());
            conditions.incarcerate(robber);
            fine = ledgerService.debitUpTo(robberId, EconomyAccountScope.WALLET,
                    EconomyMoney.positive(properties.getRobFine()), operationId, EconomyLedgerReason.ROB_FINE,
                    robberId, "抢劫被捕罚款");
        }
        var record = new EconomyRobRecord();
        record.setRobberId(robberId);
        record.setTargetId(targetId);
        record.setSuccess(success);
        record.setAmount(success ? stolen : fine);
        record.setOutcome(assaulted ? (caught ? "ASSAULT_ARRESTED" : "ASSAULT") : caught ? "ARRESTED"
                : resisted ? "COUNTERED" : success ? "STOLEN" : poor ? "EMPTY" : "ESCAPED");
        record.setInjury(injury);
        record.setInjuredUserId(injuredUser == 0 ? null : injuredUser);
        record.setCrimeAfter(robber.getCrime());
        record.setOperationId(operationId);
        recordRepository.save(record);
        return new RobResult(success, poor, caught, resisted, legacyBoost, targetId, stolen, fine,
                ledgerService.balance(targetId, EconomyAccountScope.WALLET).balance(),
                ledgerService.balance(robberId, EconomyAccountScope.WALLET).balance(), assaulted, injuredUser,
                injury, hospitalUntil, robber.getJailUntil(), robber.getCrime(), batonUsed, batonEnergy);
    }

    public String describe(RobResult result, String targetName) {
        return EconomyRobNarrative.format(result, targetName, time.now());
    }

    public record RobResult(boolean success, boolean poorTarget, boolean caught, boolean resisted, boolean robBoostUsed,
                            long targetId, BigDecimal stolen, BigDecimal fine, BigDecimal targetBalanceAfter,
                            BigDecimal walletBalance, boolean victimAssaulted, long injuredUserId, int injury,
                            LocalDateTime hospitalUntil, LocalDateTime jailUntil, int crime,
                            boolean electricBatonUsed, Integer electricBatonEnergy) { }
}
