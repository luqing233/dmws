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
import org.springframework.core.io.ClassPathResource;
import org.json.JSONArray;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;

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
        saveItem("sign-monthly-card", "签到月卡", "首次签到自动激活，连续30个自然日签到五倍；最后5天签到提醒，到期后再启用下一张。",
                new BigDecimal("9999"), EconomyItemEffectType.SIGN_BONUS, new BigDecimal("5"), null);
        saveItem("electric-baton", "便携电棍", "可重复使用，满电100；抢劫时自动耗电20，最终成功判定增加20个百分点，可与一次红牛叠加，同种道具每次仅生效一次。充电中够20电就自动停止充电并使用，不足则继续充电。",
                new BigDecimal("1888"), EconomyItemEffectType.ELECTRIC_BATON, BigDecimal.ONE, null);
        saveItem("electric-baton-charger", "电棍充电器", "可重复使用，与电棍同价；使用后为电量最低的一根充电，每分钟恢复1电，空电100分钟充满。抢劫时够20电即拔电使用，不足继续充电；每人同时充一根，离线继续计时。",
                new BigDecimal("1888"), EconomyItemEffectType.BATON_CHARGER, BigDecimal.ONE, null);
        saveItem("golden-basin", "金盆", "金盆洗手：一次清零全部犯罪值，每次一个，每个自然日最多使用2个；不缩短服刑或住院时间，犯罪值为0不消耗。",
                new BigDecimal("1288"), EconomyItemEffectType.CRIME_REDUCTION, new BigDecimal("100"), null);
        saveItem("red-bull", "红牛", "增加一次抢劫增益：最终成功判定增加20个百分点；次数累积，同次只消耗一次；可与一次电棍叠加，反打/被捕也消耗。",
                new BigDecimal("888"), EconomyItemEffectType.ROB_BOOST, BigDecimal.ONE, null);
        saveItem("health-card", "医保卡", "来自壶言经济的纪念收藏品，当前版本无额外效果。",
                new BigDecimal("5888"), EconomyItemEffectType.NONE, BigDecimal.ZERO, null);
        saveItem("rename-card", "改名卡", "来自壶言经济的纪念收藏品，当前版本无额外效果。",
                new BigDecimal("2333"), EconomyItemEffectType.NONE, BigDecimal.ZERO, null);
        for(var bait:fun.luqing.dmws.service.dmw.economy.EconomyBaitQuality.values()) {
            saveItem(bait.code,bait.displayName(),bait.description(),bait.price,EconomyItemEffectType.FISHING_LUCK,new BigDecimal("5"),bait.name());
        }
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
        // The resource bootstraps missing rows only. Runtime prices/pools come from fish.
        try (var input = new ClassPathResource("economy/fish-catalog.json").getInputStream()) {
            var entries = new JSONArray(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            var names = new HashSet<String>();
            var fish = new ArrayList<Fish>();
            for (int i = 0; i < entries.length(); i++) {
                var entry = entries.getJSONObject(i);
                var item = new Fish();
                item.setName(entry.getString("name"));
                item.setDescription(entry.getString("description"));
                item.setLevel(entry.getInt("level"));
                item.setPrice(entry.getInt("price"));
                item.setDifficulty(entry.getInt("difficulty"));
                item.setDimensionsMin(entry.getInt("dimensions_min"));
                item.setDimensionsMax(entry.getInt("dimensions_max"));
                item.setSpecial(entry.getBoolean("special"));
                if (item.getName().isBlank() || !names.add(item.getName()) || item.getDescription().isBlank()
                        || item.getLevel() < 1 || item.getPrice() < 1 || item.getDifficulty() < 0
                        || item.getDimensionsMin() < 1 || item.getDimensionsMax() < item.getDimensionsMin()) {
                    throw new IllegalStateException("无效或重复的默认鱼种：" + item.getName());
                }
                fish.add(item);
            }
            for (var item : fish) {
                if (!fishRepository.existsByName(item.getName())) fishRepository.save(item);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取默认鱼种目录", exception);
        }
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
