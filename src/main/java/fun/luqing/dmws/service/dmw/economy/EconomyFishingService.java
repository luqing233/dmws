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
import java.util.Comparator;

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
    private final EconomyFishingEventService events;
    private final fun.luqing.dmws.repository.dmw.economy.EconomyFishingAttemptRepository attempts;
    private final EconomyGameTime time;
    private final EconomyFishingBaitService bait;

    @Transactional(transactionManager = "dmwTransactionManager")
    public FishingResult fish(long userId, String nickname, String operationId) {
        return fish(userId,nickname,operationId,null,null);
    }

    @Transactional(transactionManager = "dmwTransactionManager")
    public FishingResult fish(long userId, String nickname, String operationId, Long groupId, String groupName) {
        return fish(userId,nickname,operationId,groupId,groupName,null);
    }

    @Transactional(transactionManager = "dmwTransactionManager")
    public FishingResult fish(long userId,String nickname,String operationId,Long groupId,String groupName,String quality) {
        operationService.start(operationId, "FISHING", userId, "钓鱼");
        EconomyUserProfile profile = profileService.getOrCreateForUpdate(userId, nickname);
        conditions.requireAvailable(userId, nickname);
        if (!Boolean.TRUE.equals(profile.getFishingRodPurchased())) {
            throw new EconomyException("FISHING_ROD_REQUIRED", "请先购买鱼竿");
        }
        LocalDateTime now = time.now();
        checkCooldown(profile.getLastFishedAt(), now, properties.getFishingCooldownSeconds(), "钓鱼");
        var used=bait.consume(profile,quality,operationId);
        boolean luckUsed=used.quality()!=EconomyBaitQuality.WHITE;
        BigDecimal cost=used.purchaseCost();

        List<Fish> fishPool = fishRepository.findAllByOrderByLevelAscPriceDesc().stream()
                .filter(fish -> EconomyFishingRules.unlocked(fish, profile.getFishingRodLevel()))
                .sorted(Comparator.comparing(fish -> Boolean.TRUE.equals(fish.getSpecial())))
                .toList();
        if (fishPool.isEmpty()) {
            throw new EconomyException("FISH_POOL_EMPTY", "当前鱼竿等级没有可钓鱼类");
        }

        profile.setLastFishedAt(now);
        var event=events.encounter(profile,used.quality(),operationId);
        if(event!=null) {
            var result=new FishingResult(event.won(),event.event().getName(),BigDecimal.ZERO,event.reward(),cost,
                    ledgerService.balance(userId,EconomyAccountScope.WALLET).balance(),profile.getFishingRodLevel(),luckUsed,
                    event.event().getDescription(),EconomyFishingRules.rodMultiplier(profile.getFishingRodLevel()),event.text(),event.penalty()).withBaitNotice(used);
            recordAttempt(operationId,userId,groupId,event.event().getCode(),event.injury(),event.redBull(),event.batonEnergy(),result,used);
            return result;
        }
        Fish fish = selectFish(fishPool,profile.getFishingRodLevel(),used.quality());
        var chance=EconomyFishingRules.chanceBreakdown(fish,profile.getFishingRodLevel(),used.quality());
        boolean success = random.chance(chance.total());
        BigDecimal reward = EconomyMoney.zero();
        BigDecimal size = BigDecimal.ZERO;
        int min = Math.max(1, fish.getDimensionsMin());
        int max = Math.max(min, fish.getDimensionsMax());
        if (success) {
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
            record.setUserId(userId);record.setCaughtAt(now);
            record.setGroupId(groupId);record.setGroupName(shortText(groupName,255));record.setNickname(shortText(nickname,255));
            record.setFishId(fish.getId());
            record.setFishName(fish.getName());
            record.setSizeCm(size);
            record.setReward(reward);
            catchRepository.save(record);
        }
        BigDecimal balance = ledgerService.balance(userId, EconomyAccountScope.WALLET).balance();
        var result = new FishingResult(
                success,
                fish.getName(),
                size,
                reward,
                cost,
                balance,
                profile.getFishingRodLevel(),
                luckUsed,
                fish.getDescription(),
                EconomyFishingRules.rodMultiplier(profile.getFishingRodLevel()),
                null, BigDecimal.ZERO,
                EconomyFishingRules.reward(fish.getPrice(), BigDecimal.valueOf(min), min, max, profile.getFishingRodLevel()),
                EconomyFishingRules.reward(fish.getPrice(), BigDecimal.valueOf(max), min, max, profile.getFishingRodLevel())
        );
        result=result.withBaitNotice(used);
        var audit=recordAttempt(operationId,userId,groupId,success?"CATCH":"ESCAPED",0,false,null,result,used);
        audit.setFishId(fish.getId());audit.setFishLevel(fish.getLevel());audit.setFishDifficulty(fish.getDifficulty());
        audit.setBaseChance(chance.base());audit.setRodChanceBonus(chance.rodBonus());
        audit.setBaitChanceBonus(chance.baitBonus());audit.setSuccessChance(chance.total());
        return result;
    }

    private String shortText(String text,int limit) {return text==null?null:text.substring(0,Math.min(text.length(),limit));}
    private fun.luqing.dmws.entity.dmw.economy.EconomyFishingAttempt recordAttempt(String operation,long user,Long group,String code,int injury,boolean red,Integer energy,FishingResult result,EconomyFishingBaitService.Use used) {
        var row=new fun.luqing.dmws.entity.dmw.economy.EconomyFishingAttempt();
        row.setOperationId(operation);row.setUserId(user);row.setGroupId(group);row.setEventCode(code);row.setOccurredAt(time.now());
        row.setReward(result.reward());row.setPenalty(result.penalty());row.setInjury(injury);row.setRedBullUsed(red);row.setBatonEnergy(energy);
        row.setRodLevel(result.rodLevel());row.setBaitQuality(used.quality().name());row.setReceipt(EconomyFishingText.receipt(result));return attempts.save(row);
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
        text += "\n可钓鱼种等级：≤" + ((long) Math.max(1, level) + 1);
        if (level < EconomyFishingRules.MAX_LEVEL) text += "\n升级至 " + (level + 1) + " 级需 " + EconomyMoney.format(EconomyFishingRules.upgradeCost(level)) + " 金币";
        else text += "\n已达本版升级上限";
        return text;
    }

    @Transactional(readOnly = true, transactionManager = "dmwTransactionManager")
    public List<EconomyFishingCatch> recentCatches(long userId) {
        return catchRepository.findTop20ByUserIdOrderByIdDesc(userId);
    }

    @Transactional(readOnly = true, transactionManager = "dmwTransactionManager")
    public String describeCatalog(long userId, int page) {
        var fish = fishRepository.findAllByOrderByLevelAscPriceDesc();
        int pages = Math.max(1, (fish.size() + 14) / 15);
        if (page < 1 || page > pages) throw new EconomyException("INVALID_PAGE", "图鉴页码范围：1–" + pages);
        int rodLevel = profileService.get(userId).getFishingRodLevel();
        long special = fish.stream().filter(item -> Boolean.TRUE.equals(item.getSpecial())).count();
        var text = new StringBuilder("鱼类图鉴：共 ").append(fish.size()).append(" 种，其中彩蛋 ")
                .append(special).append(" 种（").append(page).append("/").append(pages).append("页）");
        for (var item : fish.subList((page - 1) * 15, Math.min(page * 15, fish.size()))) {
            text.append("\n").append(Boolean.TRUE.equals(item.getSpecial()) ? "[彩蛋] " : "")
                    .append(item.getName()).append(" · ").append(item.getLevel()).append("级")
                    .append(EconomyFishingRules.unlocked(item, rodLevel) ? "" : "（未解锁）")
                    .append(" · ").append(item.getDimensionsMin()).append("–").append(item.getDimensionsMax()).append(" cm")
                    .append(" · 基础价 ").append(item.getPrice()).append(" 金币")
                    .append("\n  ").append(item.getDescription());
        }
        text.append("\n售价按实际尺寸和鱼竿等级结算；彩蛋低概率出现。");
        if (pages > 1) text.append("\n翻页：鱼类图鉴 ").append(page < pages ? page + 1 : 1);
        return text.toString();
    }

    @Transactional(readOnly = true, transactionManager = "dmwTransactionManager")
    public List<LeaderboardEntry> leaderboard(int limit) {
        return catchRepository.findBestPerPlayer(org.springframework.data.domain.PageRequest.of(0,Math.max(1,Math.min(10,limit)))).stream()
                .limit(Math.max(0,limit)).map(item -> new LeaderboardEntry(item.getUserId(),item.getFishName(),item.getSizeCm(),item.getReward(),
                        item.getGroupId(),item.getGroupName(),item.getNickname())).toList();
    }

    private Fish selectFish(List<Fish> pool,int level,EconomyBaitQuality quality) {
        var probabilities=EconomyFishingRules.probabilities(pool,level,quality);
        double selected=random.weight(1_000_000_000L)/1_000_000_000.0, cumulative=0;
        for(int i=0;i<pool.size();i++) {
            cumulative+=probabilities[i];
            if(selected<cumulative) return pool.get(i);
        }
        return pool.getLast();
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
            BigDecimal rodMultiplier,
            String outcomeText,
            BigDecimal penalty,
            BigDecimal estimatedValueMin,
            BigDecimal estimatedValueMax,
            String baitNotice
    ) {
        public FishingResult(boolean success,String fishName,BigDecimal size,BigDecimal reward,BigDecimal cost,BigDecimal walletBalance,int rodLevel,boolean luckUsed,String fishDescription,BigDecimal rodMultiplier,String outcomeText,BigDecimal penalty,BigDecimal estimatedValueMin,BigDecimal estimatedValueMax) {
            this(success,fishName,size,reward,cost,walletBalance,rodLevel,luckUsed,fishDescription,rodMultiplier,outcomeText,penalty,estimatedValueMin,estimatedValueMax,null);
        }
        public FishingResult withBaitNotice(EconomyFishingBaitService.Use use) {
            String notice=use.purchaseCost().signum()>0?"已自动购买"+use.quality().displayName()+"1包，花费"+EconomyMoney.format(use.purchaseCost())+"金币。":"";
            if(use.exhausted()) notice+=use.quality().label+"色鱼饵已耗尽，"+(use.defaultQuality()?"下次钓鱼自动购买。":"请购买或切换。");
            return new FishingResult(success,fishName,size,reward,cost,walletBalance,rodLevel,luckUsed,fishDescription,rodMultiplier,outcomeText,penalty,estimatedValueMin,estimatedValueMax,
                    notice.isEmpty()?null:notice);
        }

        public FishingResult(boolean success,String fishName,BigDecimal size,BigDecimal reward,BigDecimal cost,BigDecimal walletBalance,int rodLevel,boolean luckUsed,String fishDescription,BigDecimal rodMultiplier) {
            this(success,fishName,size,reward,cost,walletBalance,rodLevel,luckUsed,fishDescription,rodMultiplier,null,BigDecimal.ZERO);
        }
        public FishingResult(boolean success,String fishName,BigDecimal size,BigDecimal reward,BigDecimal cost,BigDecimal walletBalance,int rodLevel,boolean luckUsed,String fishDescription,BigDecimal rodMultiplier,String outcomeText,BigDecimal penalty) {
            this(success,fishName,size,reward,cost,walletBalance,rodLevel,luckUsed,fishDescription,rodMultiplier,outcomeText,penalty,null,null);
        }
        public BigDecimal netProfit() { return reward.subtract(cost).subtract(penalty).setScale(2, RoundingMode.HALF_UP); }
    }

    public record LeaderboardEntry(
            long userId,
            String fishName,
            BigDecimal size,
            BigDecimal reward,
            Long groupId,
            String groupName,
            String nickname
    ) { }
}
