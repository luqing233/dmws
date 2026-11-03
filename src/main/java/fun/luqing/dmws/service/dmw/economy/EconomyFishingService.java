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
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
public class EconomyFishingService {

    private final EconomyProperties properties;
    private final EconomyOperationService operationService;
    private final EconomyUserProfileService profileService;
    private final EconomyLedgerService ledgerService;
    private final EconomyFishRepository fishRepository;
    private final EconomyFishingCatchRepository catchRepository;

    @Transactional(transactionManager = "dmwTransactionManager")
    public FishingResult fish(long userId, String nickname, String operationId) {
        EconomyUserProfile profile = profileService.getOrCreateForUpdate(userId, nickname);
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
        double chance = Math.max(0.25, 0.9 - fish.getDifficulty() / 150.0
                - Math.max(0, fish.getLevel() - profile.getFishingRodLevel()) * 0.15
                + (luckUsed ? 0.20 : 0.0));
        boolean success = ThreadLocalRandom.current().nextDouble() < chance;
        BigDecimal reward = EconomyMoney.zero();
        BigDecimal size = BigDecimal.ZERO;
        if (success) {
            int min = Math.max(1, fish.getDimensionsMin());
            int max = Math.max(min, fish.getDimensionsMax());
            size = BigDecimal.valueOf(ThreadLocalRandom.current().nextDouble(min, max))
                    .setScale(2, RoundingMode.HALF_UP);
            BigDecimal sizeFactor = BigDecimal.valueOf(0.75 + ThreadLocalRandom.current().nextDouble(0, 0.5));
            reward = EconomyMoney.positive(
                    BigDecimal.valueOf(fish.getPrice())
                            .multiply(sizeFactor)
                            .setScale(2, RoundingMode.HALF_UP)
            );
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
                luckUsed
        );
    }

    @Transactional(transactionManager = "dmwTransactionManager")
    public int buyRod(long userId, String nickname, String operationId) {
        EconomyUserProfile profile = profileService.getOrCreateForUpdate(userId, nickname);
        if (Boolean.TRUE.equals(profile.getFishingRodPurchased())) {
            throw new EconomyException("FISHING_ROD_OWNED", "你已经拥有鱼竿");
        }
        operationService.start(operationId, "FISHING_ROD_BUY", userId, "购买鱼竿");
        ledgerService.debitUser(
                userId,
                EconomyAccountScope.WALLET,
                EconomyMoney.positive(properties.getFishingRodPrice()),
                operationId,
                EconomyLedgerReason.FISHING_ROD_UPGRADE,
                userId,
                "购买鱼竿"
        );
        profile.setFishingRodPurchased(true);
        profile.setFishingRodLevel(1);
        return 1;
    }

    @Transactional(transactionManager = "dmwTransactionManager")
    public int upgradeRod(long userId, String nickname, String operationId) {
        EconomyUserProfile profile = profileService.getOrCreateForUpdate(userId, nickname);
        if (!Boolean.TRUE.equals(profile.getFishingRodPurchased())) {
            throw new EconomyException("FISHING_ROD_REQUIRED", "请先购买鱼竿");
        }
        if (profile.getFishingRodLevel() >= 5) {
            throw new EconomyException("ROD_MAX_LEVEL", "鱼竿已经达到最高等级");
        }
        operationService.start(operationId, "FISHING_ROD_UPGRADE", userId, "升级鱼竿");
        BigDecimal cost = EconomyMoney.positive(
                BigDecimal.valueOf(profile.getFishingRodLevel()).multiply(new BigDecimal("1000"))
        );
        ledgerService.debitUser(
                userId,
                EconomyAccountScope.WALLET,
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
                .mapToLong(fish -> Math.max(1, 120 - fish.getDifficulty()))
                .sum();
        long selected = ThreadLocalRandom.current().nextLong(total);
        long cursor = 0;
        for (Fish fish : fishPool) {
            cursor += Math.max(1, 120 - fish.getDifficulty());
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
            boolean luckUsed
    ) {
    }

    public record LeaderboardEntry(
            long userId,
            String fishName,
            BigDecimal size,
            BigDecimal reward
    ) {
    }
}
