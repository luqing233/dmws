package fun.luqing.dmws.config;

import fun.luqing.dmws.entity.dmw.economy.EconomyItemDefinition;
import fun.luqing.dmws.entity.dmw.economy.EconomyFarmCrop;
import fun.luqing.dmws.entity.dmw.economy.EconomyRafflePrize;
import fun.luqing.dmws.entity.dmw.economy.EconomyTitleDefinition;
import fun.luqing.dmws.entity.dmw.fish.Fish;
import fun.luqing.dmws.enums.EconomyItemEffectType;
import fun.luqing.dmws.enums.EconomyPrizeType;
import fun.luqing.dmws.repository.dmw.economy.EconomyItemDefinitionRepository;
import fun.luqing.dmws.repository.dmw.economy.EconomyFarmCropRepository;
import fun.luqing.dmws.repository.dmw.economy.EconomyFishRepository;
import fun.luqing.dmws.repository.dmw.economy.EconomyRafflePrizeRepository;
import fun.luqing.dmws.repository.dmw.economy.EconomyTitleDefinitionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "dmws.economy", name = "enabled", havingValue = "true", matchIfMissing = true)
public class EconomyCatalogInitializer implements ApplicationRunner {

    private final EconomyItemDefinitionRepository itemRepository;
    private final EconomyTitleDefinitionRepository titleRepository;
    private final EconomyRafflePrizeRepository rafflePrizeRepository;
    private final EconomyFishRepository fishRepository;
    private final EconomyFarmCropRepository farmCropRepository;

    @Override
    @Transactional(transactionManager = "dmwTransactionManager")
    public void run(ApplicationArguments args) {
        seedItems();
        seedTitles();
        seedRafflePrizes();
        seedFish();
        seedFarmCrops();
        log.info("经济基础数据已就绪");
    }

    private void seedItems() {
        saveItem("double-sign-card", "双倍签到卡", "下一次签到时自动消耗，奖励翻倍。",
                new BigDecimal("88"), EconomyItemEffectType.SIGN_BONUS, new BigDecimal("2"), null);
        saveItem("triple-sign-card", "三倍签到卡", "下一次签到时自动消耗，奖励三倍。",
                new BigDecimal("188"), EconomyItemEffectType.SIGN_BONUS, new BigDecimal("3"), null);
        saveItem("sign-in-card", "补签卡", "使用后恢复最近一次断签的连续天数。",
                new BigDecimal("123"), EconomyItemEffectType.SIGN_STREAK_RESTORE, BigDecimal.ZERO, null);
        saveItem("sign-monthly-card", "签到月卡", "下一次签到时自动消耗，本次签到奖励五倍。",
                new BigDecimal("9999"), EconomyItemEffectType.SIGN_BONUS, new BigDecimal("5"), null);
        saveItem("electric-baton", "便携电棍", "使用后下一次抢劫成功率提高 20%。",
                new BigDecimal("1888"), EconomyItemEffectType.ROB_BOOST, BigDecimal.ONE, null);
        saveItem("red-bull", "红牛", "使用后下一次抢劫成功率提高 20%。",
                new BigDecimal("888"), EconomyItemEffectType.ROB_BOOST, BigDecimal.ONE, null);
        saveItem("health-card", "医保卡", "来自壶言经济的纪念收藏品，当前版本无额外效果。",
                new BigDecimal("5888"), EconomyItemEffectType.NONE, BigDecimal.ZERO, null);
        saveItem("rename-card", "改名卡", "来自壶言经济的纪念收藏品，当前版本无额外效果。",
                new BigDecimal("2333"), EconomyItemEffectType.NONE, BigDecimal.ZERO, null);
        saveItem("fishing-luck-bait", "幸运鱼饵", "使用后下一次钓鱼成功率提高 20%。",
                new BigDecimal("300"), EconomyItemEffectType.FISHING_LUCK, BigDecimal.ONE, null);
        saveItem("star-token", "星愿币", "抽奖常见奖品，可用于装饰背包。",
                new BigDecimal("50"), EconomyItemEffectType.NONE, BigDecimal.ZERO, null);
        saveItem("star-title-card", "星尘称号卡", "使用后获得称号「星尘旅人」。",
                new BigDecimal("1200"), EconomyItemEffectType.TITLE_UNLOCK, BigDecimal.ZERO, "star-traveler");
    }

    private void saveItem(
            String code,
            String name,
            String description,
            BigDecimal price,
            EconomyItemEffectType effectType,
            BigDecimal effectValue,
            String effectText
    ) {
        EconomyItemDefinition item = itemRepository.findById(code)
                .orElseGet(EconomyItemDefinition::new);
        item.setCode(code);
        item.setName(name);
        item.setDescription(description);
        item.setPrice(price);
        item.setEffectType(effectType);
        item.setEffectValue(effectValue);
        item.setEffectText(effectText);
        item.setEnabled(true);
        itemRepository.save(item);
    }

    private void seedTitles() {
        saveTitle("newcomer", "初见", "第一次进入壶言世界的纪念称号。", new BigDecimal("100"));
        saveTitle("star-traveler", "星尘旅人", "来自星愿奖池的稀有称号。", new BigDecimal("1200"));
        saveTitle("tycoon", "大富翁", "钱包余额达到十万后购买的称号。", new BigDecimal("5000"));
        saveTitle("sign-master", "签到大师", "坚持签到的人值得拥有。", new BigDecimal("3000"));
    }

    private void saveTitle(String code, String name, String description, BigDecimal price) {
        if (titleRepository.existsById(code)) {
            return;
        }
        EconomyTitleDefinition title = new EconomyTitleDefinition();
        title.setCode(code);
        title.setName(name);
        title.setDescription(description);
        title.setPrice(price);
        title.setEnabled(true);
        titleRepository.save(title);
    }

    private void seedRafflePrizes() {
        savePrize("money-100", "零花钱", "获得 100 金币", EconomyPrizeType.MONEY,
                180, -1, new BigDecimal("100"), null, null, 0);
        savePrize("money-500", "小红包", "获得 500 金币", EconomyPrizeType.MONEY,
                80, -1, new BigDecimal("500"), null, null, 0);
        savePrize("money-1000", "大红包", "获得 1000 金币", EconomyPrizeType.MONEY,
                25, -1, new BigDecimal("1000"), null, null, 0);
        savePrize("star-token-1", "星愿币", "获得 1 枚星愿币", EconomyPrizeType.ITEM,
                70, 500, BigDecimal.ZERO, null, "star-token", 1);
        savePrize("star-title", "星尘旅人", "获得稀有称号「星尘旅人」", EconomyPrizeType.TITLE,
                5, 10, BigDecimal.ZERO, "star-traveler", null, 0);
        savePrize("small-fine", "小惩大诫", "被罚 50 金币", EconomyPrizeType.FINE,
                20, -1, new BigDecimal("50"), null, null, 0);
    }

    private void seedFish() {
        saveFish("鲫鱼", "常见的小鱼", 1, 10, 20, 10, 30, false);
        saveFish("鲤鱼", "力气不小的河鱼", 1, 20, 30, 20, 50, false);
        saveFish("鲈鱼", "需要更好的鱼竿", 2, 60, 55, 25, 60, false);
        saveFish("金鱼", "漂亮但难以遇到", 2, 100, 70, 5, 15, true);
        saveFish("锦鲤", "传说中的幸运鱼", 3, 500, 90, 30, 80, true);
    }

    private void saveFish(
            String name,
            String description,
            int level,
            int price,
            int difficulty,
            int minSize,
            int maxSize,
            boolean special
    ) {
        if (fishRepository.existsByName(name)) {
            return;
        }
        Fish fish = new Fish();
        fish.setName(name);
        fish.setDescription(description);
        fish.setLevel(level);
        fish.setPrice(price);
        fish.setDifficulty(difficulty);
        fish.setDimensionsMin(minSize);
        fish.setDimensionsMax(maxSize);
        fish.setSpecial(special);
        fishRepository.save(fish);
    }

    private void seedFarmCrops() {
        saveCrop("wheat", "小麦", "成熟很快的基础作物", "20", 60, 2, 4, "8");
        saveCrop("carrot", "胡萝卜", "稳定收益的日常作物", "60", 300, 3, 6, "18");
        saveCrop("strawberry", "草莓", "需要耐心但利润更高", "200", 900, 4, 8, "50");
    }

    private void saveCrop(
            String code,
            String name,
            String description,
            String seedPrice,
            long growthSeconds,
            int yieldMin,
            int yieldMax,
            String sellPrice
    ) {
        if (farmCropRepository.existsById(code)) {
            return;
        }
        EconomyFarmCrop crop = new EconomyFarmCrop();
        crop.setCode(code);
        crop.setName(name);
        crop.setDescription(description);
        crop.setSeedPrice(new BigDecimal(seedPrice));
        crop.setGrowthSeconds(growthSeconds);
        crop.setYieldMin(yieldMin);
        crop.setYieldMax(yieldMax);
        crop.setSellPrice(new BigDecimal(sellPrice));
        crop.setEnabled(true);
        farmCropRepository.save(crop);
    }

    private void savePrize(
            String code,
            String name,
            String description,
            EconomyPrizeType type,
            int weight,
            int stock,
            BigDecimal money,
            String titleCode,
            String itemCode,
            int itemQuantity
    ) {
        if (rafflePrizeRepository.findByCode(code).isPresent()) {
            return;
        }
        EconomyRafflePrize prize = new EconomyRafflePrize();
        prize.setCode(code);
        prize.setName(name);
        prize.setDescription(description);
        prize.setType(type);
        prize.setWeight(weight);
        prize.setStock(stock);
        prize.setMoneyAmount(money);
        prize.setTitleCode(titleCode);
        prize.setItemCode(itemCode);
        prize.setItemQuantity(itemQuantity);
        prize.setEnabled(true);
        rafflePrizeRepository.save(prize);
    }
}
