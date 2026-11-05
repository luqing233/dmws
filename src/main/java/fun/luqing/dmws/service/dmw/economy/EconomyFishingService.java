package fun.luqing.dmws.service.dmw.economy;

import fun.luqing.dmws.common.economy.EconomyException;
import fun.luqing.dmws.common.economy.EconomyMoney;
import fun.luqing.dmws.config.EconomyProperties;
import fun.luqing.dmws.entity.dmw.economy.EconomyFishingCatch;
import fun.luqing.dmws.entity.dmw.economy.EconomyUserProfile;
import fun.luqing.dmws.entity.dmw.fish.Fish;
import fun.luqing.dmws.enums.EconomyAccountScope;
import fun.luqing.dmws.enums.EconomyLedgerReason;
import fun.luqing.dmws.repository.dmw.economy.EconomyFishRepository;
import fun.luqing.dmws.repository.dmw.economy.EconomyFishingCatchRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;

@Service
@RequiredArgsConstructor
public class EconomyFishingService {

    private final EconomyProperties properties;
    private final EconomyOperationService operationService;
    private final EconomyUserProfileService profileService;
    private final EconomyLedgerService ledgerService;
    private final EconomyFishRepository fishRepository;
    private final EconomyFishingCatchRepository catchRepository;
    private final EconomyConditionService conditions;
    private final EconomyFishingRandom random;

    @Transactional(transactionManager = "dmwTransactionManager")
    public FishingResult fish(long userId, String nickname, String operationId) {
        EconomyUserProfile profile = profileService.getOrCreateForUpdate(userId, nickname);
        conditions.requireAvailable(userId, nickname);
        if (!Boolean.TRUE.equals(profile.getFishingRodPurchased())) {
            throw new EconomyException("FISHING_ROD_REQUIRED", "请先购买鱼竿");
        }
        LocalDateTime now = LocalDateTime.now(properties.zoneId());
        checkCooldown(profile.getLastFishedAt(), now, properties.getFishingCooldownSeconds(), "钓鱼");
        boolean luckUsed = profile.getFishingLuckCharges() > 0;
        if (luckUsed) {
            profile.setFishingLuckCharges(profile.getFishingLuckCharges() - 1);
        }

        operationService.start(operationId, "FISHING", userId, "钓鱼");
        BigDecimal cost = EconomyMoney.positive(properties.getFishingCost());
        ledgerService.debitUser(
                userId,
                EconomyAccountScope.WALLET,
                cost,
                operationId,
                EconomyLedgerReason.FISHING_COST,
                userId,
                "钓鱼消耗"
        );

        List<Fish> fishPool = fishRepository.findAllByOrderByLevelAscPriceDesc().stream()
                .filter(fish -> fish.getLevel() <= Math.min(3, profile.getFishingRodLevel() + 1))
                .toList();
        if (fishPool.isEmpty()) {
            throw new EconomyException("FISH_POOL_EMPTY", "当前鱼竿等级没有可钓鱼类");
        }

        Fish fish = selectFish(fishPool);
        profile.setLastFishedAt(now);
        double chance = Math.min(1, Math.max(0.25, 0.9 - fish.getDifficulty() / 150.0
                - Math.max(0, fish.getLevel() - profile.getFishingRodLevel()) * 0.15
                + (luckUsed ? 0.20 : 0.0)));
        boolean success = random.chance(chance);
        BigDecimal reward = EconomyMoney.zero();
        BigDecimal size = BigDecimal.ZERO;
        if (success) {
            int min = Math.max(1, fish.getDimensionsMin());
            int max = Math.max(min, fish.getDimensionsMax());
            size = BigDecimal.valueOf(min + (max - min) * random.fraction())
                    .setScale(2, RoundingMode.HALF_UP);
            reward = EconomyMoney.positive(EconomyFishingRules.reward(fish.getPrice(), size, min, max, profile.getFishingRodLevel()));
            ledgerService.creditUser(
                    userId,
                    EconomyAccountScope.WALLET,
                    reward,
                    operationId,
                    EconomyLedgerReason.FISHING_REWARD,
                    userId,
                    "钓到 " + fish.getName()
            );

            EconomyFishingCatch record = new EconomyFishingCatch();
            record.setUserId(userId);
            record.setFishId(fish.getId());
            record.setFishName(fish.getName());
            record.setSizeCm(size);
            record.setReward(reward);
            catchRepository.save(record);
        }
        BigDecimal balance = ledgerService.balance(userId, EconomyAccountScope.WALLET).balance();
        return new FishingResult(
                success,
                fish.getName(),
                size,
                reward,
                cost,
                balance,
                profile.getFishingRodLevel(),
                luckUsed,
                fish.getDescription(),
                EconomyFishingRules.rodMultiplier(profile.getFishingRodLevel())
        );
    }

    @Transactional(transactionManager = "dmwTransactionManager")
    public int buyRod(long userId, String nickname, String operationId) {
        EconomyUserProfile profile = profileService.getOrCreateForUpdate(userId, nickname);
        if (Boolean.TRUE.equals(profile.getFishingRodPurchased())) {
            throw new EconomyException("FISHING_ROD_OWNED", "你已经拥有鱼竿");
        }
        operationService.start(operationId, "FISHING_ROD_BUY", userId, "购买鱼竿");
        ledgerService.debitUserWithBankFallback(
                userId,
                EconomyMoney.positive(properties.getFishingRodPrice()),
                operationId,
                EconomyLedgerReason.FISHING_ROD_UPGRADE,
                userId,
                "购买鱼竿"
        );
        profile.setFishingRodPurchased(true);
        // Some imported accounts have a historical level but do not yet own a rod.
        // Buying equipment must not reset their earned level.
        int retained = Math.max(1, profile.getFishingRodLevel());
        profile.setFishingRodLevel(retained);
        return retained;
    }

    @Transactional(transactionManager = "dmwTransactionManager")
    public int upgradeRod(long userId, String nickname, String operationId) {
        EconomyUserProfile profile = profileService.getOrCreateForUpdate(userId, nickname);
        if (!Boolean.TRUE.equals(profile.getFishingRodPurchased())) {
            throw new EconomyException("FISHING_ROD_REQUIRED", "请先购买鱼竿");
        }
        if (profile.getFishingRodLevel() >= EconomyFishingRules.MAX_LEVEL) {
            throw new EconomyException("ROD_MAX_LEVEL", "已达到本版鱼竿升级上限 100 级；历史等级保留");
        }
        operationService.start(operationId, "FISHING_ROD_UPGRADE", userId, "升级鱼竿");
        BigDecimal cost = EconomyMoney.positive(EconomyFishingRules.upgradeCost(profile.getFishingRodLevel()));
        ledgerService.debitUserWithBankFallback(
                userId,
                cost,
                operationId,
                EconomyLedgerReason.FISHING_ROD_UPGRADE,
                userId,
                "升级鱼竿"
        );
        profile.setFishingRodLevel(profile.getFishingRodLevel() + 1);
        return profile.getFishingRodLevel();
    }

    @Transactional(readOnly = true, transactionManager = "dmwTransactionManager")
    public int rodLevel(long userId) {
        return profileService.get(userId).getFishingRodLevel();
    }

    @Transactional(readOnly = true, transactionManager = "dmwTransactionManager")
    public String describeRod(long userId) {
        var profile = profileService.get(userId);
        int level = profile.getFishingRodLevel();
        String text = "鱼竿等级：" + level;
        if (!Boolean.TRUE.equals(profile.getFishingRodPurchased())) text += "\n尚未购买鱼竿，购置后沿用此等级";
        text += "\n等级售价系数：×" + EconomyFishingRules.rodMultiplier(level).toPlainString();
        if (level > EconomyFishingRules.MAX_LEVEL) text += "（按100级生效，历史原始等级保留）";
        if (level < EconomyFishingRules.MAX_LEVEL) text += "\n升级至 " + (level + 1) + " 级需 " + EconomyMoney.format(EconomyFishingRules.upgradeCost(level)) + " 金币";
        else text += "\n已达本版升级上限";
        return text;
    }

    @Transactional(readOnly = true, transactionManager = "dmwTransactionManager")
    public List<EconomyFishingCatch> recentCatches(long userId) {
        return catchRepository.findTop20ByUserIdOrderByIdDesc(userId);
    }

    @Transactional(readOnly = true, transactionManager = "dmwTransactionManager")
    public List<LeaderboardEntry> leaderboard(int limit) {
        LinkedHashMap<Long, EconomyFishingCatch> bestByUser = new LinkedHashMap<>();
        for (EconomyFishingCatch item : catchRepository.findTop100ByOrderBySizeCmDesc()) {
            bestByUser.putIfAbsent(item.getUserId(), item);
        }
        return bestByUser.values().stream()
                .limit(Math.max(0, limit))
                .map(item -> new LeaderboardEntry(
                        item.getUserId(),
                        item.getFishName(),
                        item.getSizeCm(),
                        item.getReward()
                ))
                .toList();
    }

    private Fish selectFish(List<Fish> fishPool) {
        long total = fishPool.stream()
                .mapToLong(fish -> Math.max(1L, 120L - fish.getDifficulty()))
                .sum();
        long selected = random.weight(total);
        long cursor = 0;
        for (Fish fish : fishPool) {
            cursor += Math.max(1L, 120L - fish.getDifficulty());
            if (selected < cursor) {
                return fish;
            }
        }
        return fishPool.getLast();
    }

    private void checkCooldown(
            LocalDateTime lastAction,
            LocalDateTime now,
            long cooldownSeconds,
            String action
    ) {
        if (lastAction == null) {
            return;
        }
        long elapsed = Duration.between(lastAction, now).toSeconds();
        if (elapsed < cooldownSeconds) {
            throw new EconomyException(
                    "ACTION_COOLDOWN",
                    action + "冷却中，还需等待 " + (cooldownSeconds - Math.max(0, elapsed)) + " 秒"
            );
        }
    }

    public record FishingResult(
            boolean success,
            String fishName,
            BigDecimal size,
            BigDecimal reward,
            BigDecimal cost,
            BigDecimal walletBalance,
            int rodLevel,
            boolean luckUsed,
            String fishDescription,
            BigDecimal rodMultiplier
    ) {
        public BigDecimal netProfit() { return reward.subtract(cost).setScale(2, RoundingMode.HALF_UP); }
    }

    public record LeaderboardEntry(
            long userId,
            String fishName,
            BigDecimal size,
            BigDecimal reward
    ) {
    }
}
