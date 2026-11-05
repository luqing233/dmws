package fun.luqing.dmws.service.dmw.economy;

import fun.luqing.dmws.common.economy.EconomyException;
import fun.luqing.dmws.common.economy.EconomyMoney;
import fun.luqing.dmws.config.EconomyProperties;
import fun.luqing.dmws.entity.dmw.economy.EconomyRafflePrize;
import fun.luqing.dmws.entity.dmw.economy.EconomyRaffleRecord;
import fun.luqing.dmws.entity.dmw.economy.EconomyUserProfile;
import fun.luqing.dmws.entity.dmw.economy.EconomyUserTitle;
import fun.luqing.dmws.enums.EconomyAccountScope;
import fun.luqing.dmws.enums.EconomyLedgerReason;
import fun.luqing.dmws.enums.EconomyPrizeType;
import fun.luqing.dmws.repository.dmw.economy.EconomyRafflePrizeRepository;
import fun.luqing.dmws.repository.dmw.economy.EconomyRaffleRecordRepository;
import fun.luqing.dmws.repository.dmw.economy.EconomyUserTitleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EconomyRaffleService {

    private final EconomyProperties properties;
    private final EconomyOperationService operationService;
    private final EconomyUserProfileService profileService;
    private final EconomyLedgerService ledgerService;
    private final EconomyTitleService titleService;
    private final EconomyItemService itemService;
    private final EconomyRafflePrizeRepository prizeRepository;
    private final EconomyRaffleRecordRepository recordRepository;
    private final EconomyUserTitleRepository userTitleRepository;
    private final EconomyConditionService conditions;

    @Transactional(transactionManager = "dmwTransactionManager")
    public DrawResult draw(
            long userId,
            String nickname,
            int times,
            String operationId
    ) {
        if (times != 1 && times != 10) {
            throw new EconomyException("INVALID_RAFFLE_TIMES", "只支持单抽或十连");
        }

        EconomyUserProfile profile = profileService.getOrCreateForUpdate(userId, nickname);
        conditions.requireAvailable(userId, nickname);
        LocalDateTime now = LocalDateTime.now(properties.zoneId());
        checkCooldown(profile, times, now);

        operationService.start(
                operationId,
                times == 1 ? "RAFFLE_SINGLE" : "RAFFLE_TEN",
                userId,
                times == 1 ? "单抽" : "十连抽"
        );

        BigDecimal price = times == 1
                ? EconomyMoney.positive(properties.getRafflePrice())
                : EconomyMoney.positive(properties.getRaffleTenPrice());
        ledgerService.debitUser(
                userId,
                EconomyAccountScope.WALLET,
                price,
                operationId,
                EconomyLedgerReason.RAFFLE_PAYMENT,
                userId,
                times == 1 ? "抽奖单抽" : "抽奖十连"
        );

        List<EconomyRafflePrize> prizes = new ArrayList<>(availablePrizes(userId));
        List<PrizeView> results = new ArrayList<>(times);
        for (int index = 1; index <= times; index++) {
            EconomyRafflePrize prize = EconomyPrizeSelector.select(prizes);
            takeStock(prize);
            if ((prize.getStock() != null && prize.getStock() == 0)
                    || prize.getType() == EconomyPrizeType.TITLE) {
                prizes.remove(prize);
            }
            String rewardOperationId = operationId + ":reward:" + index;
            results.add(applyPrize(userId, prize, rewardOperationId));
        }

        if (times == 1) {
            profile.setLastRaffleAt(now);
        } else {
            profile.setLastTenRaffleAt(now);
        }
        BigDecimal wallet = ledgerService.balance(userId, EconomyAccountScope.WALLET).balance();
        return new DrawResult(times, price, wallet, results);
    }

    @Transactional(readOnly = true, transactionManager = "dmwTransactionManager")
    public List<EconomyRafflePrize> prizes() {
        return prizeRepository.findAll().stream()
                .filter(EconomyRafflePrize::getEnabled)
                .toList();
    }

    private List<EconomyRafflePrize> availablePrizes(long userId) {
        Set<String> ownedTitles = userTitleRepository.findByUserIdOrderByAcquiredAtAsc(userId)
                .stream()
                .map(EconomyUserTitle::getTitleCode)
                .collect(Collectors.toSet());

        return prizeRepository.findAllEnabledForUpdate().stream()
                .filter(prize -> prize.getWeight() != null && prize.getWeight() > 0)
                .filter(prize -> prize.getStock() == null || prize.getStock() < 0 || prize.getStock() > 0)
                .filter(prize -> prize.getType() != EconomyPrizeType.TITLE
                        || prize.getTitleCode() == null
                        || !ownedTitles.contains(prize.getTitleCode()))
                .toList();
    }

    private void takeStock(EconomyRafflePrize prize) {
        if (prize.getStock() != null && prize.getStock() >= 0) {
            prize.setStock(prize.getStock() - 1);
            prizeRepository.save(prize);
        }
    }

    private PrizeView applyPrize(long userId, EconomyRafflePrize prize, String operationId) {
        BigDecimal actualReward = EconomyMoney.zero();
        switch (prize.getType()) {
            case MONEY -> {
                operationService.start(operationId, "RAFFLE_PRIZE_MONEY", userId, prize.getCode());
                actualReward = EconomyMoney.positive(prize.getMoneyAmount());
                ledgerService.creditUser(
                        userId,
                        EconomyAccountScope.WALLET,
                        actualReward,
                        operationId,
                        EconomyLedgerReason.RAFFLE_PRIZE,
                        userId,
                        "抽奖奖励: " + prize.getName()
                );
            }
            case FINE -> {
                operationService.start(operationId, "RAFFLE_PRIZE_FINE", userId, prize.getCode());
                actualReward = ledgerService.debitUpTo(
                        userId,
                        EconomyAccountScope.WALLET,
                        EconomyMoney.positive(prize.getMoneyAmount()),
                        operationId,
                        EconomyLedgerReason.RAFFLE_FINE,
                        userId,
                        "抽奖惩罚: " + prize.getName()
                );
            }
            case TITLE -> {
                if (prize.getTitleCode() == null || prize.getTitleCode().isBlank()) {
                    throw new EconomyException("PRIZE_CONFIG_INVALID", "称号奖品缺少称号编码");
                }
                titleService.grant(userId, prize.getTitleCode());
            }
            case ITEM -> {
                if (prize.getItemCode() == null || prize.getItemCode().isBlank()) {
                    throw new EconomyException("PRIZE_CONFIG_INVALID", "道具奖品缺少道具编码");
                }
                itemService.addItem(userId, prize.getItemCode(), Math.max(1, prize.getItemQuantity()));
            }
        }

        EconomyRaffleRecord record = new EconomyRaffleRecord();
        record.setOperationId(operationId);
        record.setUserId(userId);
        record.setPrizeCode(prize.getCode());
        record.setPrizeName(prize.getName());
        record.setPrizeType(prize.getType());
        record.setRewardAmount(actualReward);
        recordRepository.save(record);
        return new PrizeView(
                prize.getCode(),
                prize.getName(),
                prize.getDescription(),
                prize.getType(),
                actualReward
        );
    }

    private void checkCooldown(EconomyUserProfile profile, int times, LocalDateTime now) {
        LocalDateTime last = times == 1 ? profile.getLastRaffleAt() : profile.getLastTenRaffleAt();
        if (last == null) {
            return;
        }
        long cooldown = times == 1
                ? properties.getRaffleCooldownSeconds()
                : properties.getRaffleTenCooldownSeconds();
        long elapsed = Duration.between(last, now).toSeconds();
        if (elapsed < cooldown) {
            throw new EconomyException(
                    "RAFFLE_COOLDOWN",
                    "抽奖冷却中，还需等待 " + (cooldown - Math.max(0, elapsed)) + " 秒"
            );
        }
    }

    public record PrizeView(
            String code,
            String name,
            String description,
            EconomyPrizeType type,
            BigDecimal reward
    ) {
    }

    public record DrawResult(
            int times,
            BigDecimal price,
            BigDecimal walletBalance,
            List<PrizeView> prizes
    ) {
    }
}
