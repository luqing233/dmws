package fun.luqing.dmws.service.dmw.economy;

import fun.luqing.dmws.common.economy.EconomyException;
import fun.luqing.dmws.common.economy.EconomyMoney;
import fun.luqing.dmws.entity.dmw.economy.EconomyItemDefinition;
import fun.luqing.dmws.entity.dmw.economy.EconomyUserProfile;
import fun.luqing.dmws.entity.dmw.economy.EconomyUserItem;
import fun.luqing.dmws.enums.EconomyAccountScope;
import fun.luqing.dmws.enums.EconomyItemEffectType;
import fun.luqing.dmws.enums.EconomyLedgerReason;
import fun.luqing.dmws.repository.dmw.economy.EconomyItemDefinitionRepository;
import fun.luqing.dmws.repository.dmw.economy.EconomyUserItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class EconomyItemService {

    private final EconomyItemDefinitionRepository itemRepository;
    private final EconomyUserItemRepository userItemRepository;
    private final EconomyOperationService operationService;
    private final EconomyUserProfileService profileService;
    private final EconomyLedgerService ledgerService;
    private final EconomyTitleService titleService;

    @Transactional(readOnly = true, transactionManager = "dmwTransactionManager")
    public List<EconomyItemDefinition> shop() {
        return itemRepository.findAllByEnabledTrueOrderByPriceAsc();
    }

    @Transactional(readOnly = true, transactionManager = "dmwTransactionManager")
    public List<EconomyUserItem> backpack(long userId) {
        return userItemRepository.findByUserIdAndQuantityGreaterThanOrderByItemCodeAsc(userId, 0);
    }

    @Transactional(transactionManager = "dmwTransactionManager")
    public void buy(long userId, String nickname, String itemCode, int quantity, String operationId) {
        if (quantity <= 0 || quantity > 999) {
            throw new EconomyException("INVALID_QUANTITY", "购买数量必须在 1 到 999 之间");
        }
        EconomyItemDefinition item = itemRepository.findById(itemCode)
                .filter(EconomyItemDefinition::getEnabled)
                .orElseThrow(() -> new EconomyException("ITEM_NOT_FOUND", "道具不存在或已下架"));

        operationService.start(operationId, "SHOP_PURCHASE", userId, "购买道具 " + itemCode);
        profileService.getOrCreateForUpdate(userId, nickname);
        BigDecimal total = EconomyMoney.positive(item.getPrice().multiply(BigDecimal.valueOf(quantity)));
        ledgerService.debitUser(
                userId,
                EconomyAccountScope.WALLET,
                total,
                operationId,
                EconomyLedgerReason.SHOP_PURCHASE,
                userId,
                "购买 " + item.getName() + " x" + quantity
        );
        addItem(userId, itemCode, quantity);
    }

    @Transactional(transactionManager = "dmwTransactionManager")
    public String use(long userId, String nickname, String itemCode, int quantity, String operationId) {
        if (quantity <= 0 || quantity > 999) {
            throw new EconomyException("INVALID_QUANTITY", "使用数量必须在 1 到 999 之间");
        }
        EconomyItemDefinition definition = itemRepository.findById(itemCode)
                .filter(EconomyItemDefinition::getEnabled)
                .orElseThrow(() -> new EconomyException("ITEM_NOT_FOUND", "道具不存在"));
        EconomyUserItem userItem = userItemRepository.findForUpdate(userId, itemCode)
                .filter(item -> item.getQuantity() >= quantity)
                .orElseThrow(() -> new EconomyException("ITEM_NOT_ENOUGH", "背包中的道具不足"));

        if (definition.getEffectType() == EconomyItemEffectType.SIGN_BONUS) {
            throw new EconomyException("PASSIVE_ITEM", "该道具会在签到时自动消耗，无需手动使用");
        }

        operationService.start(operationId, "ITEM_USE", userId, "使用道具 " + itemCode);
        EconomyUserProfile profile = profileService.getOrCreateForUpdate(userId, nickname);
        consume(userItem, quantity);

        return switch (definition.getEffectType()) {
            case MONEY -> {
                BigDecimal reward = EconomyMoney.positive(
                        definition.getEffectValue().multiply(BigDecimal.valueOf(quantity))
                );
                ledgerService.creditUser(
                        userId,
                        EconomyAccountScope.WALLET,
                        reward,
                        operationId,
                        EconomyLedgerReason.ITEM_USE,
                        userId,
                        "使用 " + definition.getName()
                );
                yield "使用成功，获得 " + EconomyMoney.format(reward) + " 金币";
            }
            case TITLE_UNLOCK -> {
                if (quantity != 1) {
                    throw new EconomyException("INVALID_QUANTITY", "称号卡一次只能使用一个");
                }
                titleService.grant(userId, definition.getEffectText());
                yield "使用成功，获得称号: " + titleService.titleName(definition.getEffectText());
            }
            case SIGN_STREAK_RESTORE -> {
                if (quantity != 1) {
                    throw new EconomyException("INVALID_QUANTITY", "补签卡一次只能使用一张");
                }
                if (profile.getOldSignNumber() <= 0) {
                    throw new EconomyException("NO_BROKEN_STREAK", "当前没有可补签的断签记录");
                }
                int restored = profile.getOldSignNumber();
                profile.setSignStreak(profile.getSignStreak() + restored);
                profile.setOldSignNumber(0);
                yield "补签成功，恢复连续签到 " + restored + " 天";
            }
            case ROB_BOOST -> {
                profile.setRobBoostCharges(profile.getRobBoostCharges() + quantity);
                yield "使用成功，下一次抢劫成功率提高 20%";
            }
            case FISHING_LUCK -> {
                profile.setFishingLuckCharges(profile.getFishingLuckCharges() + quantity);
                yield "使用成功，下一次钓鱼成功率提高 20%";
            }
            case NONE -> "使用成功，但它只是一件纪念品";
            case SIGN_BONUS -> throw new EconomyException("PASSIVE_ITEM", "该道具无需手动使用");
        };
    }

    @Transactional(propagation = Propagation.MANDATORY, transactionManager = "dmwTransactionManager")
    public void addItem(long userId, String itemCode, int quantity) {
        if (quantity <= 0) {
            return;
        }
        EconomyUserItem item = userItemRepository.findForUpdate(userId, itemCode)
                .orElseGet(() -> {
                    EconomyUserItem created = new EconomyUserItem();
                    created.setUserId(userId);
                    created.setItemCode(itemCode);
                    created.setQuantity(0);
                    return userItemRepository.saveAndFlush(created);
                });
        item.setQuantity(item.getQuantity() + quantity);
        item.setUpdatedAt(LocalDateTime.now());
        userItemRepository.save(item);
    }

    @Transactional(propagation = Propagation.MANDATORY, transactionManager = "dmwTransactionManager")
    public boolean consumeOneIfPresent(long userId, String itemCode) {
        return userItemRepository.findForUpdate(userId, itemCode)
                .filter(item -> item.getQuantity() > 0)
                .map(item -> {
                    consume(item, 1);
                    return true;
                })
                .orElse(false);
    }

    public EconomyItemDefinition definition(String code) {
        return itemRepository.findById(code)
                .orElseThrow(() -> new EconomyException("ITEM_NOT_FOUND", "道具不存在: " + code));
    }

    public EconomyItemDefinition resolve(String codeOrName) {
        return itemRepository.findById(codeOrName)
                .or(() -> itemRepository.findFirstByEnabledTrueAndName(codeOrName))
                .filter(EconomyItemDefinition::getEnabled)
                .orElseThrow(() -> new EconomyException("ITEM_NOT_FOUND", "道具不存在: " + codeOrName));
    }

    private void consume(EconomyUserItem item, int quantity) {
        item.setQuantity(item.getQuantity() - quantity);
        item.setUpdatedAt(LocalDateTime.now());
        userItemRepository.save(item);
    }
}
