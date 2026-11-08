package fun.luqing.dmws.economy;

import fun.luqing.dmws.common.economy.EconomyException;
import fun.luqing.dmws.config.EconomyCatalogInitializer;
import fun.luqing.dmws.entity.dmw.fish.Fish;
import fun.luqing.dmws.repository.dmw.economy.EconomyFishRepository;
import fun.luqing.dmws.repository.dmw.economy.EconomyFishingCatchRepository;
import fun.luqing.dmws.repository.dmw.economy.EconomyUserProfileRepository;
import fun.luqing.dmws.service.dmw.economy.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional(transactionManager = "dmwTransactionManager")
class EconomyFishCatalogTests {
    @Autowired EconomyFishRepository fishRepository;
    @Autowired EconomyCatalogInitializer initializer;
    @Autowired EconomyFishingService fishing;
    @Autowired EconomyUserProfileService profiles;
    @Autowired EconomyUserProfileRepository profileRepository;
    @Autowired EconomyFishingCatchRepository catches;
    @Autowired EconomyAdminService admin;
    @MockitoBean EconomyFishingRandom random;

    @Test void bootstrapsEightyUniqueSpeciesIncludingEighteenSpecialCatches() {
        var fish = fishRepository.findAll();
        assertEquals(80, fish.size());
        assertEquals(80, fish.stream().map(Fish::getName).distinct().count());
        assertEquals(18, fish.stream().filter(Fish::getSpecial).count());
        assertTrue(fish.stream().allMatch(item -> item.getPrice() > 0 && item.getDimensionsMin() > 0
                && item.getDimensionsMax() >= item.getDimensionsMin() && !item.getDescription().isBlank()));
        assertTrue(fish.stream().anyMatch(item -> item.getName().equals("海绵宝宝") && item.getSpecial()));
        assertTrue(fish.stream().anyMatch(item -> item.getName().equals("年年有鱼") && item.getLevel() == 11));
        assertEquals(20, named("鲤鱼").getPrice());
        assertEquals("力气不小的河鱼", named("鲤鱼").getDescription());
    }

    @Test void restartingOnlyAddsMissingSpeciesAndPreservesDatabaseEditsIdsAndHistory() {
        var carp = named("鲤鱼");
        int id = carp.getId();
        carp.setPrice(73); carp.setSpecial(true); carp.setDescription("管理员自定义");
        carp.setLevel(7); carp.setDifficulty(44); carp.setDimensionsMin(22); carp.setDimensionsMax(33);
        fishRepository.saveAndFlush(carp);
        fishRepository.delete(named("沙丁鱼"));
        fishRepository.flush();
        long history = catches.count();
        initializer.run(null);
        initializer.run(null);
        var retained = named("鲤鱼");
        assertEquals(id, retained.getId());
        assertEquals(73, retained.getPrice());
        assertTrue(retained.getSpecial());
        assertEquals("管理员自定义", retained.getDescription());
        assertEquals(7, retained.getLevel());
        assertEquals(44, retained.getDifficulty());
        assertEquals(22, retained.getDimensionsMin());
        assertEquals(33, retained.getDimensionsMax());
        assertEquals(80, fishRepository.count());
        assertEquals(history, catches.count());
    }

    @Test void highTierAndDatabaseOnlySpeciesAreActuallyCatchable() {
        var custom = new Fish();
        custom.setName("数据库新增鱼"); custom.setDescription("无需修改代码即可出钓");
        custom.setLevel(12); custom.setPrice(80); custom.setDifficulty(30);
        custom.setDimensionsMin(20); custom.setDimensionsMax(20); custom.setSpecial(false);
        fishRepository.saveAndFlush(custom);
        assertFalse(EconomyFishingRules.unlocked(custom, 10));
        seed(94801L, 11);
        select(custom, 11);
        var result = fishing.fish(94801L, "钓鱼人", "catalog-database-only");
        assertEquals(custom.getName(), result.fishName());
        assertEquals(new BigDecimal("48.00"), result.reward());
        assertEquals(custom.getId(), fishing.recentCatches(94801L).getFirst().getFishId());
    }

    @Test void specialCatchIsRareAndUsesTheExistingSizeRewardAndHistoryRules() {
        var special = named("RTX 4090Ti 显卡");
        assertEquals(1, EconomyFishingRules.selectionWeight(special));
        assertTrue(EconomyFishingRules.selectionWeight(named("鲤鱼")) > 1);
        seed(94802L, 100);
        select(special, 100);
        var result = fishing.fish(94802L, "钓鱼人", "catalog-special");
        assertEquals(special.getName(), result.fishName());
        assertEquals(new BigDecimal("30.00"), result.size());
        assertEquals(new BigDecimal("596.00"), result.reward());
        assertTrue(EconomyFishingText.receipt(result).contains("描述：")); assertEquals(4,EconomyFishingText.receipt(result).lines().count());
        assertEquals(special.getId(), fishing.recentCatches(94802L).getFirst().getFishId());
    }

    @Test void rodUnlocksRetainEarlyPoolsWithoutAThreeTierCeilingOrIntegerOverflow() {
        assertTrue(EconomyFishingRules.unlocked(named("金鱼"), 1));
        assertFalse(EconomyFishingRules.unlocked(named("锦鲤"), 1));
        assertTrue(EconomyFishingRules.unlocked(named("锦鲤"), 2));
        assertFalse(EconomyFishingRules.unlocked(named("年年有鱼"), 9));
        assertTrue(EconomyFishingRules.unlocked(named("年年有鱼"), 10));
        assertTrue(EconomyFishingRules.unlocked(named("年年有鱼"), Integer.MAX_VALUE));
    }

    @Test void encyclopediaPagesReadTheDatabaseAndDoNotCreateAPlayer() {
        var text = fishing.describeCatalog(94803L, 1);
        assertTrue(text.contains("80 种，其中彩蛋 18 种"));
        assertTrue(text.contains("[彩蛋]"));
        assertTrue(text.contains("未解锁"));
        assertTrue(text.contains("基础价"));
        assertTrue(fishing.describeCatalog(94803L, 6).contains("年年有鱼"));
        assertEquals("INVALID_PAGE", assertThrows(EconomyException.class,
                () -> fishing.describeCatalog(94803L, 7)).getCode());
        assertFalse(profileRepository.existsById(94803L));
        assertEquals(80, fishRepository.count());
    }

    private Fish named(String name) {
        return fishRepository.findAll().stream().filter(item -> item.getName().equals(name)).findFirst().orElseThrow();
    }

    @Autowired EconomyItemService items;
    private void seed(long user, int level) {
        items.addItem(user,"fishing-bait-white",1);
        admin.adjust(user, user, "钓鱼人", new BigDecimal("1000"), true, "catalog-seed-" + user);
        var profile = profiles.getOrCreateForUpdate(user, "钓鱼人");
        profile.setFishingRodLevel(level); profile.setFishingRodPurchased(true);
    }

    private void select(Fish target, int level) {
        var pool = fishRepository.findAllByOrderByLevelAscPriceDesc().stream()
                .filter(item -> EconomyFishingRules.unlocked(item, level))
                .sorted(Comparator.comparing(item -> Boolean.TRUE.equals(item.getSpecial()))).toList();
        var probabilities=EconomyFishingRules.probabilities(pool,level,EconomyBaitQuality.WHITE);
        double cursor=0;
        for(int i=0;i<pool.size();i++) {
            if(pool.get(i).getId().equals(target.getId())) {cursor+=probabilities[i]/2;break;}
            cursor+=probabilities[i];
        }
        when(random.weight(anyLong())).thenReturn((long)(cursor*1_000_000_000L));
        when(random.chance(anyDouble())).thenReturn(true);
        when(random.fraction()).thenReturn(0.5);
    }
}
