package fun.luqing.dmws.economy;

import fun.luqing.dmws.common.economy.EconomyException;
import fun.luqing.dmws.entity.dmw.economy.EconomyFishingCatch;
import fun.luqing.dmws.enums.EconomyAccountScope;
import fun.luqing.dmws.repository.dmw.economy.*;
import fun.luqing.dmws.service.dmw.economy.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional(transactionManager = "dmwTransactionManager")
class EconomyFishingProgressionTests {
    @Autowired EconomyFishingService fishing;
    @Autowired EconomyItemService items;
    @Autowired EconomyFishingBaitService bait;
    @Autowired EconomyFishingBaitStateRepository baitStates;
    @Autowired EconomyUserProfileService profiles;
    @Autowired EconomyAdminService admin;
    @Autowired EconomyBankingService banking;
    @Autowired EconomyLedgerService ledger;
    @Autowired EconomyFishingCatchRepository catches;
    @Autowired EconomyFishRepository fishRepository;
    @MockitoBean EconomyFishingRandom random;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("dmwTransactionManager")
    org.springframework.transaction.PlatformTransactionManager txManager;

    private void seed(long owner, int level, boolean purchased, String amount) {
        admin.adjust(owner, owner, "玩家", new BigDecimal(amount), true, "progression-seed-" + owner);
        var p = profiles.getOrCreateForUpdate(owner, "玩家");
        p.setFishingRodLevel(level); p.setFishingRodPurchased(purchased);
        items.addItem(owner,"fishing-bait-white",1);
    }

    @Test void buyingEquipmentPreservesAnUnownedHistoricalLevel() {
        seed(94601L,45,false,"1000");
        assertEquals(45,fishing.buyRod(94601L,"玩家","buy-historical-rod"));
        assertEquals(45,profiles.get(94601L).getFishingRodLevel());
        assertEquals(new BigDecimal("500.00"),ledger.balance(94601L,EconomyAccountScope.WALLET).balance());
        assertTrue(profiles.get(94601L).getFishingRodPurchased());
    }

    @Test void historicalLevelCanUpgradePastFiveUsingWalletAndBankWithoutResetting() {
        seed(94602L,45,true,"50000");
        banking.deposit(94602L,"玩家",new BigDecimal("49000"),"rod-upgrade-bank");
        assertEquals(46,fishing.upgradeRod(94602L,"玩家","upgrade-legacy-45"));
        assertEquals(new BigDecimal("0.00"),ledger.balance(94602L,EconomyAccountScope.WALLET).balance());
        assertEquals(new BigDecimal("5000.00"),ledger.balance(94602L,EconomyAccountScope.MAIN_BANK).balance());
        assertTrue(fishing.describeRod(94602L).contains("0.95"));
    }

    @Test void levelFiveCanProgressAndLevelOneHundredOrHigherDoesNotLoseItsHistory() {
        seed(94603L,5,true,"6000");
        assertEquals(6,fishing.upgradeRod(94603L,"玩家","upgrade-five"));
        seed(94604L,100,true,"1000");
        assertEquals("ROD_MAX_LEVEL",assertThrows(EconomyException.class,()->fishing.upgradeRod(94604L,"玩家","max-upgrade")).getCode());
        assertEquals(100,profiles.get(94604L).getFishingRodLevel());
        assertEquals(new BigDecimal("1000.00"),ledger.balance(94604L,EconomyAccountScope.WALLET).balance());
    }

    @Test void successfulCatchBalancesNetIncomeAndPreservesOldCatchRecordsAndLevel() {
        long owner=94605L;seed(owner,45,true,"1000");
        var old=new EconomyFishingCatch();old.setUserId(owner);old.setFishId(1);old.setFishName("旧鱼");
        old.setSizeCm(new BigDecimal("77.77"));old.setReward(new BigDecimal("123.45"));catches.saveAndFlush(old);
        when(random.weight(anyLong())).thenReturn(0L);when(random.chance(anyDouble())).thenReturn(true);when(random.fraction()).thenReturn(0.5);
        var result=fishing.fish(owner,"玩家","new-level45-catch");
        assertEquals("鲤鱼",result.fishName()); assertEquals("力气不小的河鱼",result.fishDescription());
        assertEquals(new BigDecimal("35.00"),result.size()); assertEquals(new BigDecimal("18.80"),result.reward());
        assertEquals(new BigDecimal("18.80"),result.netProfit()); assertEquals(new BigDecimal("1018.80"),result.walletBalance());
        assertEquals(45,profiles.get(owner).getFishingRodLevel());
        assertEquals(new BigDecimal("123.45"),catches.findById(old.getId()).orElseThrow().getReward());
        assertEquals(2,fishing.recentCatches(owner).size());
        assertThrows(EconomyException.class,()->fishing.fish(owner,"玩家","new-level45-catch"));
        assertEquals(new BigDecimal("1018.80"),ledger.balance(owner,EconomyAccountScope.WALLET).balance());
    }

    @Test void failedCatchCostsOnlyTheFishingFeeAndConsumesOneQueuedBaitCharge() {
        long owner=94606L;seed(owner,100,true,"1000");
        profiles.getOrCreateForUpdate(owner,"玩家").setFishingLuckCharges(2);
        when(random.weight(anyLong())).thenReturn(0L);when(random.chance(anyDouble())).thenReturn(false);
        var result=fishing.fish(owner,"玩家","failed-catch",null,null,"蓝");
        assertEquals(new BigDecimal("0.00"),result.netProfit());assertEquals(new BigDecimal("1000.00"),result.walletBalance());
        assertEquals(new BigDecimal("22.35"),result.estimatedValueMin());
        assertEquals(new BigDecimal("37.25"),result.estimatedValueMax());
        assertTrue(EconomyFishingText.receipt(result).contains("据情报人员估价：22.35～37.25金币。"));
        assertEquals(0,profiles.get(owner).getFishingLuckCharges());assertEquals(1,baitStates.findById(owner).orElseThrow().getBlueUses());assertTrue(fishing.recentCatches(owner).isEmpty());
    }

    @Test
    @Transactional(transactionManager = "dmwTransactionManager", propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void unavailableSelectedBaitDoesNotConsumeOtherBaitOrReduceHistoricalLevel() {
        long owner=94608L;
        new org.springframework.transaction.support.TransactionTemplate(txManager).executeWithoutResult(status -> {
            seed(owner,45,true,"10");
            profiles.getOrCreateForUpdate(owner,"玩家").setFishingLuckCharges(2);
        });
        assertThrows(EconomyException.class,()->fishing.fish(owner,"玩家","unfunded-fish",null,null,"红"));
        assertEquals(2,profiles.get(owner).getFishingLuckCharges());
        assertEquals(45,profiles.get(owner).getFishingRodLevel());
        assertEquals(new BigDecimal("10.00"),ledger.balance(owner,EconomyAccountScope.WALLET).balance());
        assertTrue(fishing.recentCatches(owner).isEmpty());
    }

    @Test void fixedSizeFishDoesNotThrowAndHigherThanCapLevelsAreRetained() {
        long owner=94607L;seed(owner,125,true,"1000");
        var fish=fishRepository.findAllByOrderByLevelAscPriceDesc().stream().filter(item -> item.getName().equals("鲤鱼")).findFirst().orElseThrow();fish.setDimensionsMin(32);fish.setDimensionsMax(32);fishRepository.saveAndFlush(fish);
        when(random.weight(anyLong())).thenReturn(0L);when(random.chance(anyDouble())).thenReturn(true);
        var result=fishing.fish(owner,"玩家","fixed-size-catch");
        assertEquals(new BigDecimal("32.00"),result.size());assertEquals(new BigDecimal("29.80"),result.reward());
        assertEquals(125,profiles.get(owner).getFishingRodLevel());assertTrue(fishing.describeRod(owner).contains("历史原始等级保留"));
    }
}
