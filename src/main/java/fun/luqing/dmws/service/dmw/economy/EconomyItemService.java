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
    private final EconomyEquipmentService equipment;
    private final EconomyConditionService conditions;
    private final EconomyFishingBaitService bait;

    @Transactional(readOnly = true, transactionManager = "dmwTransactionManager")
    public List<EconomyItemDefinition> shop() {
        return itemRepository.findAllByEnabledTrueOrderByPriceAsc().stream().filter(i->i.getEffectType()!=EconomyItemEffectType.FISHING_LUCK).toList();
    }

    @Transactional(readOnly = true, transactionManager = "dmwTransactionManager")
    public List<EconomyUserItem> backpack(long userId) {
        return userItemRepository.findByUserIdAndQuantityGreaterThanOrderByItemCodeAsc(userId, 0);
    }

    @Transactional(transactionManager = "dmwTransactionManager")
    public PurchaseResult buy(
            long userId,
            String nickname,
            String itemCode,
            int quantity,
            String operationId
    ) {
        if (quantity <= 0 || quantity > 999) {
            throw new EconomyException("INVALID_QUANTITY", "购买数量必须在 1 到 999 之间");
        }
        EconomyItemDefinition item = itemRepository.findById(itemCode)
                .filter(EconomyItemDefinition::getEnabled)
                .orElseThrow(() -> new EconomyException("ITEM_NOT_FOUND", "道具不存在或已下架"));

        operationService.start(operationId, "SHOP_PURCHASE", userId, "购买道具 " + itemCode);
        profileService.getOrCreateForUpdate(userId, nickname);
        if (item.getEffectType() == EconomyItemEffectType.BATON_CHARGER
                && (quantity != 1 || userItemRepository.findByUserIdAndItemCode(userId, itemCode)
                        .map(i -> i.getQuantity() > 0).orElse(false))) {
            throw new EconomyException("CHARGER_ALREADY_OWNED", "充电器可重复使用，每人购买一个即可");
        }
        BigDecimal total = EconomyMoney.positive(item.getPrice().multiply(BigDecimal.valueOf(quantity)));
        EconomyLedgerService.BankFallbackDebit charge = ledgerService.debitUserWithBankFallback(
                userId,
                total,
                operationId,
                EconomyLedgerReason.SHOP_PURCHASE,
                userId,
                "购买 " + item.getName() + " x" + quantity
        );
        addItem(userId, itemCode, quantity);
        return new PurchaseResult(
                item.getCode(),
                item.getName(),
                quantity,
                total,
                EconomyMoney.normalize(charge.bankTopUp()),
                EconomyMoney.normalize(charge.charge().balance()),
                EconomyMoney.normalize(charge.bankBalance())
        );
    }

    @Transactional(transactionManager = "dmwTransactionManager")
    public String use(long userId, String nickname, String itemCode, int quantity, String operationId) {
        if (quantity <= 0 || quantity > 999) {
            throw new EconomyException("INVALID_QUANTITY", "使用数量必须在 1 到 999 之间");
        }
        EconomyItemDefinition definition = itemRepository.findById(itemCode)
                .filter(EconomyItemDefinition::getEnabled)
                .orElseThrow(() -> new EconomyException("ITEM_NOT_FOUND", "道具不存在"));
        if (definition.getEffectType() == EconomyItemEffectType.SIGN_BONUS) {
            throw new EconomyException("PASSIVE_ITEM", "该道具会在签到时自动消耗，无需手动使用");
        }

        // Reserve the request before stock checks so retrying a consumed last item
        // still reports a duplicate. Match buy's profile-before-inventory lock order.
        operationService.start(operationId, "ITEM_USE", userId, "使用道具 " + itemCode);
        EconomyUserProfile profile = profileService.getOrCreateForUpdate(userId, nickname);
        EconomyUserItem userItem = userItemRepository.findForUpdate(userId, itemCode)
                .filter(item -> item.getQuantity() >= quantity)
                .orElseThrow(() -> new EconomyException("ITEM_NOT_ENOUGH", "背包中的道具不足"));
        boolean reusable = definition.getEffectType() == EconomyItemEffectType.ELECTRIC_BATON
                || definition.getEffectType() == EconomyItemEffectType.BATON_CHARGER;
        if (reusable && quantity != 1) throw new EconomyException("INVALID_QUANTITY", "可重复使用的装备每次操作一个即可");
        if (!reusable) consume(userItem, quantity);

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
                yield "使用成功，增加 " + quantity + " 次抢劫增益（剩余 " + profile.getRobBoostCharges() + " 次）；每次最终成功判定增加20个百分点，同种增益每次仅生效一次，可与一次电棍叠加；抢劫或鳄鱼搏斗失败也会消耗";
            }
            case FISHING_LUCK -> bait.open(profile, EconomyBaitQuality.parse(definition.getCode()), quantity);
            case ELECTRIC_BATON -> equipment.view(userId).text() + "\n抢劫时会自动使用，无需手动激活。";
            case BATON_CHARGER -> equipment.startCharging(userId);
            case CRIME_REDUCTION -> conditions.reduceCrime(userId, nickname, quantity);
            case NONE -> "使用成功，但它只是一件纪念品";
            case SIGN_BONUS -> throw new EconomyException("PASSIVE_ITEM", "该道具无需手动使用");
        };
    }

    @Transactional(readOnly=true,transactionManager="dmwTransactionManager")
    public List<EconomyItemDefinition> fishingShop() {
        return itemRepository.findAllByEnabledTrueOrderByPriceAsc().stream().filter(i->i.getEffectType()==EconomyItemEffectType.FISHING_LUCK).toList();
    }
    public String baitStatus(long user) { return bait.describe(user); }

    public EconomyEquipmentService.EquipmentView equipment(long userId) {
        return equipment.view(userId);
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
        if ("电棍".equals(codeOrName)) codeOrName = "electric-baton";
        if ("充电器".equals(codeOrName)) codeOrName = "electric-baton-charger";
        if("幸运鱼饵".equals(codeOrName)) codeOrName=EconomyBaitQuality.WHITE.code;
        else if(codeOrName.startsWith("幸运鱼饵")) codeOrName=EconomyBaitQuality.parse(codeOrName).code;
        final String query = codeOrName;
        return itemRepository.findById(query)
                .or(() -> itemRepository.findFirstByEnabledTrueAndName(query))
                .filter(EconomyItemDefinition::getEnabled)
                .orElseThrow(() -> new EconomyException("ITEM_NOT_FOUND", "道具不存在: " + query));
    }

    private void consume(EconomyUserItem item, int quantity) {
        item.setQuantity(item.getQuantity() - quantity);
        item.setUpdatedAt(LocalDateTime.now());
        userItemRepository.save(item);
    }

    public record PurchaseResult(
            String itemCode,
            String itemName,
            int quantity,
            BigDecimal totalCost,
            BigDecimal bankTopUp,
            BigDecimal walletBalance,
            BigDecimal bankBalance
    ) {
    }
}
