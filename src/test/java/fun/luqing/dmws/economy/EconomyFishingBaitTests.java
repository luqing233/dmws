package fun.luqing.dmws.economy;

import fun.luqing.dmws.common.economy.EconomyException;
import fun.luqing.dmws.entity.dmw.economy.*;
import fun.luqing.dmws.enums.EconomyAccountScope;
import fun.luqing.dmws.repository.dmw.economy.*;
import fun.luqing.dmws.service.dmw.economy.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest @ActiveProfiles("test") @Transactional(transactionManager="dmwTransactionManager")
class EconomyFishingBaitTests {
    @Autowired EconomyFishingService fishing;
    @jakarta.persistence.PersistenceContext(unitName="dmw") jakarta.persistence.EntityManager em;
    @Autowired EconomyFishingBaitService bait;
    @Autowired EconomyItemService items;
    @Autowired EconomyUserProfileService profiles;
    @Autowired EconomyUserProfileRepository profileRows;
    @Autowired EconomyFishingBaitStateRepository states;
    @Autowired EconomyFishingAttemptRepository attempts;
    @Autowired EconomyFishingEventRepository events;
    @Autowired EconomyFishRepository fish;
    @Autowired EconomyCombatService combat;
    @Autowired EconomyLedgerService ledger;
    @Autowired EconomyAdminService admin;
    @Autowired EconomyBankingService banking;
    @Autowired EconomyItemDefinitionRepository definitions;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("dmwTransactionManager") org.springframework.transaction.PlatformTransactionManager tx;
    @MockitoBean EconomyFishingRandom random;
    @MockitoBean EconomyGameTime time;
    static final long USER=98901;
    static final LocalDateTime START=LocalDateTime.of(2026,11,9,12,0);
    @BeforeEach void setup() { when(time.now()).thenReturn(START);when(time.today()).thenReturn(START.toLocalDate()); }
    void seed(long user) {
        admin.adjust(user,user,"鱼饵测试",new BigDecimal("1000"),true,"bait-seed-"+user);
        var p=profiles.getOrCreateForUpdate(user,"鱼饵测试");p.setFishingRodPurchased(true);p.setFishingRodLevel(100);
        combat.profile(user).setFishingAttempts(10);
        when(random.weight(10000)).thenReturn(9999L);
    }
    void next(int cast) {when(time.now()).thenReturn(START.plusHours(cast));}
    @Test void onePackageBuysExactlyFiveCastsAndNoCastFeeEvenWhenFishEscape() {
        seed(USER);items.buy(USER,"玩家","fishing-bait-white",1,"bait-buy");
        for(int i=0;i<5;i++) {
            next(i);var r=fishing.fish(USER,"玩家","bait-five-"+i);
            assertEquals(new BigDecimal("0.00"),r.netProfit());
            assertEquals(4,EconomyFishingText.receipt(r).lines().count());
            assertEquals(i==4,EconomyFishingText.receipt(r).contains("鱼饵已耗尽"));
        }
        assertEquals(new BigDecimal("990.00"),ledger.balance(USER,EconomyAccountScope.WALLET).balance());
        assertEquals(0,states.findById(USER).orElseThrow().getWhiteUses());
        next(6);var sixth=fishing.fish(USER,"玩家","bait-sixth");
        assertEquals(new BigDecimal("10.00"),sixth.cost());
        assertEquals(new BigDecimal("-10.00"),sixth.netProfit());
        assertEquals(new BigDecimal("980.00"),ledger.balance(USER,EconomyAccountScope.WALLET).balance());
        assertEquals(4,states.findById(USER).orElseThrow().getWhiteUses());
        assertThrows(RuntimeException.class,()->fishing.fish(USER,"玩家","bait-sixth"));
        assertEquals(new BigDecimal("980.00"),ledger.balance(USER,EconomyAccountScope.WALLET).balance());
        assertEquals(6,attempts.findAll().stream().filter(a->a.getUserId()==USER).count());
    }
    @Test void firstCastDefaultsToWhiteAndBuysOnePackage() {
        seed(USER);
        assertFalse(states.existsById(USER));
        var result=fishing.fish(USER,"玩家","first-auto-bait");
        var state=states.findById(USER).orElseThrow();
        assertEquals(EconomyBaitQuality.WHITE,state.getSelected());assertEquals(4,state.getWhiteUses());
        assertEquals(new BigDecimal("990.00"),result.walletBalance());
        assertEquals(new BigDecimal("-10.00"),result.netProfit());
        String receipt=EconomyFishingText.receipt(result);
        assertTrue(receipt.contains("已自动购买幸运鱼饵（白）1包，花费10.00金币。"));
        assertEquals(4,receipt.lines().count());
        assertEquals(receipt,attempts.findById("first-auto-bait").orElseThrow().getReceipt());
    }
    @Test void selectedDefaultBuysAtShopPriceAndUsesBankFallbackOnce() {
        seed(USER);banking.deposit(USER,"玩家",new BigDecimal("1000"),"auto-bait-bank");
        var definition=definitions.findById("fishing-luck-bait").orElseThrow();definition.setPrice(new BigDecimal("91.00"));
        bait.select(USER,"玩家","蓝","auto-default-blue");
        var result=fishing.fish(USER,"玩家","auto-blue-cast");
        assertEquals(new BigDecimal("91.00"),result.cost());assertEquals(new BigDecimal("-91.00"),result.netProfit());
        assertEquals(new BigDecimal("0.00"),result.walletBalance());
        assertEquals(new BigDecimal("909.00"),ledger.balance(USER,EconomyAccountScope.MAIN_BANK).balance());
        next(1);assertEquals(new BigDecimal("0.00"),fishing.fish(USER,"玩家","auto-blue-next").cost());
        assertEquals(3,states.findById(USER).orElseThrow().getBlueUses());
        assertEquals(new BigDecimal("909.00"),ledger.balance(USER,EconomyAccountScope.MAIN_BANK).balance());
    }
    @Test void temporaryMissingQualityDoesNotBuyOrChangeDefaultAndDelistedDefaultCannotBuy() {
        seed(USER);
        assertEquals("BAIT_REQUIRED",assertThrows(EconomyException.class,()->fishing.fish(USER,"玩家","no-auto-red",null,null,"红")).getCode());
        assertEquals(new BigDecimal("1000.00"),ledger.balance(USER,EconomyAccountScope.WALLET).balance());
        assertEquals(0,states.findById(USER).orElseThrow().getRedUses());
        definitions.findById("fishing-bait-white").orElseThrow().setEnabled(false);
        assertEquals("ITEM_NOT_FOUND",assertThrows(EconomyException.class,()->fishing.fish(USER,"玩家","delisted-auto")).getCode());
        assertEquals(new BigDecimal("1000.00"),ledger.balance(USER,EconomyAccountScope.WALLET).balance());
    }
    @Test @Transactional(transactionManager="dmwTransactionManager",propagation=Propagation.NOT_SUPPORTED)
    void failedAutoPurchaseOrLaterOutcomeRollsBackChargeAndRequestAndAllowsRetry() {
        long user=98920;
        var template=new org.springframework.transaction.support.TransactionTemplate(tx);
        template.executeWithoutResult(s->{seed(user);admin.adjust(user,user,"玩家",new BigDecimal("995"),false,"auto-poor-seed");});
        assertEquals("INSUFFICIENT_BALANCE",assertThrows(EconomyException.class,()->fishing.fish(user,"玩家","auto-retry")).getCode());
        assertFalse(states.existsById(user));assertFalse(attempts.existsById("auto-retry"));
        assertEquals(new BigDecimal("5.00"),ledger.balance(user,EconomyAccountScope.WALLET).balance());
        template.executeWithoutResult(s->admin.adjust(user,user,"玩家",new BigDecimal("95"),true,"auto-refund-seed"));
        doThrow(new IllegalStateException("after auto purchase")).when(random).chance(anyDouble());
        assertThrows(IllegalStateException.class,()->fishing.fish(user,"玩家","auto-retry"));
        assertFalse(states.existsById(user));assertFalse(attempts.existsById("auto-retry"));
        assertEquals(new BigDecimal("100.00"),ledger.balance(user,EconomyAccountScope.WALLET).balance());
        doReturn(false).when(random).chance(anyDouble());
        assertEquals(new BigDecimal("10.00"),fishing.fish(user,"玩家","auto-retry").cost());
        assertEquals(new BigDecimal("90.00"),ledger.balance(user,EconomyAccountScope.WALLET).balance());
        assertThrows(RuntimeException.class,()->fishing.fish(user,"玩家","auto-retry"));
        assertEquals(new BigDecimal("90.00"),ledger.balance(user,EconomyAccountScope.WALLET).balance());
        assertEquals(4,states.findById(user).orElseThrow().getWhiteUses());
    }
    @Test void autoPurchaseUsesNewProbabilityAndPersistsSnapshotAcrossLaterUpgrades() {
        seed(USER);profiles.getOrCreateForUpdate(USER,"玩家").setFishingRodLevel(45);
        bait.select(USER,"玩家","红","new-curve-default");
        when(random.chance(anyDouble())).thenReturn(true);when(random.fraction()).thenReturn(.5);
        var result=fishing.fish(USER,"玩家","new-curve-auto");
        assertEquals(new BigDecimal("400.00"),result.cost());
        assertEquals(result.reward().subtract(new BigDecimal("400.00")),result.netProfit());
        assertEquals(new BigDecimal("600.00").add(result.reward()),result.walletBalance());
        var expected=EconomyFishingRules.chanceBreakdown(fish.findAll().stream().filter(f->f.getName().equals(result.fishName())).findFirst().orElseThrow(),45,EconomyBaitQuality.RED);
        verify(random).chance(doubleThat(value->Math.abs(value-expected.total())<1e-12));
        profiles.getOrCreateForUpdate(USER,"玩家").setFishingRodLevel(100);
        em.flush();em.clear();
        var row=attempts.findById("new-curve-auto").orElseThrow();
        assertEquals(45,row.getRodLevel());assertNotNull(row.getFishId());assertNotNull(row.getFishDifficulty());
        assertEquals(expected.base(),row.getBaseChance(),1e-12);
        assertEquals(expected.rodBonus(),row.getRodChanceBonus(),1e-12);
        assertEquals(.10,row.getBaitChanceBonus(),1e-12);
        assertEquals(expected.total(),row.getSuccessChance(),1e-12);
        assertEquals(4,states.findById(USER).orElseThrow().getRedUses());
        assertEquals(4,row.getReceipt().lines().count());
    }

    @Test void switchingAndOneOffOverridePreserveEachQualityAndLegacyChargesConvertOnce() {
        seed(USER);items.addItem(USER,"fishing-luck-bait",2);items.addItem(USER,"fishing-bait-red",1);
        profiles.getOrCreateForUpdate(USER,"玩家").setFishingLuckCharges(2);
        bait.select(USER,"玩家","蓝","bait-select-blue");
        fishing.fish(USER,"玩家","bait-blue-one");
        next(1);fishing.fish(USER,"玩家","bait-red-override",null,null,"红");
        var s=states.findById(USER).orElseThrow();assertEquals(EconomyBaitQuality.BLUE,s.getSelected());
        assertEquals(1,s.getBlueUses());assertEquals(4,s.getRedUses());assertEquals(0,profiles.get(USER).getFishingLuckCharges());
        next(2);fishing.fish(USER,"玩家","bait-blue-two");next(3);fishing.fish(USER,"玩家","bait-blue-open");
        assertEquals(4,s.getBlueUses());assertEquals(1,items.backpack(USER).stream().filter(i->i.getItemCode().equals("fishing-luck-bait")).findFirst().orElseThrow().getQuantity());
    }
    @Test void readOnlyViewDoesNotAllocateOrMigrate() {
        assertFalse(profileRows.existsById(98999L));assertFalse(states.existsById(98999L));
        assertTrue(bait.describe(98999L).contains("幸运鱼饵（白）"));
        assertFalse(profileRows.existsById(98999L));assertFalse(states.existsById(98999L));
        seed(USER);profiles.getOrCreateForUpdate(USER,"玩家").setFishingLuckCharges(3);
        assertTrue(bait.describe(USER).contains("已启用余3次"));assertEquals(3,profiles.get(USER).getFishingLuckCharges());
        assertFalse(states.existsById(USER));
    }
    @Test void shopSeparatesBaitAndAllSixPricesAndNamesResolve() {
        int[] prices={10,35,75,150,250,400};int index=0;
        assertEquals(6,items.fishingShop().size());
        for(var q:EconomyBaitQuality.values()) {
            assertEquals(q.code,items.resolve("幸运鱼饵 "+q.label).getCode());
            assertEquals(0,items.definition(q.code).getPrice().compareTo(BigDecimal.valueOf(prices[index++])));
            assertTrue(items.shop().stream().noneMatch(i->i.getCode().equals(q.code)));
        }
    }
    @ParameterizedTest @CsvSource({"0,FUN","799,FUN","800,REWARD","1399,REWARD","1400,CROCODILE","1799,CROCODILE","1800,FINE","1999,FINE","2000,ESCAPED","9999,ESCAPED"})
    void redBaitUsesExactEventBoundariesAndConsumesOneUse(long roll,String kind) {
        seed(USER);items.addItem(USER,"fishing-bait-red",1);when(random.weight(10000)).thenReturn(roll);
        var r=fishing.fish(USER,"玩家","red-boundary",null,null,"红");
        String code=attempts.findById("red-boundary").orElseThrow().getEventCode();
        if(kind.equals("ESCAPED")) {
            assertEquals(kind,code);assertNotNull(attempts.findById("red-boundary").orElseThrow().getSuccessChance());
        } else {
            assertEquals(kind,events.findById(code).orElseThrow().getKind());
            assertNull(attempts.findById("red-boundary").orElseThrow().getSuccessChance());
        }
        assertEquals(4,states.findById(USER).orElseThrow().getRedUses());
        assertEquals(4,EconomyFishingText.receipt(r).lines().count());
        if(kind.equals("FUN")||kind.equals("REWARD")) assertTrue(r.reward().signum()>0);
    }
    @Test void newPlayersReceiveRedFunButRemainProtectedFromCrocodiles() {
        seed(USER);combat.profile(USER).setFishingAttempts(0);items.addItem(USER,"fishing-bait-red",1);
        when(random.weight(10000)).thenReturn(0L);assertTrue(fishing.fish(USER,"玩家","new-fun",null,null,"红").reward().signum()>0);
        next(1);when(random.weight(10000)).thenReturn(1400L);var r=fishing.fish(USER,"玩家","new-safe",null,null,"红");
        assertNull(r.outcomeText());
    }
    @Test void probabilityDistributionsStayNormalizedAndImproveHighTierFishWithoutDilutingSpecials() {
        var pool=fish.findAllByOrderByLevelAscPriceDesc();
        double previous=0;
        for(int level:new int[]{10,25,50,75,100}) {
            var p=EconomyFishingRules.probabilities(pool,level,EconomyBaitQuality.WHITE);
            double total=0,high=0,special=0;
            for(int i=0;i<p.length;i++) {assertTrue(p[i]>=0);total+=p[i];if(pool.get(i).getSpecial())special+=p[i];else if(pool.get(i).getLevel()>=7)high+=p[i];}
            assertEquals(1,total,1e-10);assertTrue(high>previous);previous=high;
            assertEquals(18.0/pool.stream().mapToLong(EconomyFishingRules::selectionWeight).sum(),special,1e-10);
        }
        var red=EconomyFishingRules.probabilities(pool,100,EconomyBaitQuality.RED);
        double special=0;for(int i=0;i<red.length;i++)if(pool.get(i).getSpecial())special+=red[i];
        assertEquals(Math.min(.1,3*18.0/pool.stream().mapToLong(EconomyFishingRules::selectionWeight).sum()),special,1e-10);
        for(var f:pool) for(var q:EconomyBaitQuality.values()) {
            double last=0;
            for(int l=1;l<=100;l++) {double chance=EconomyFishingRules.successChance(f,l,q);assertTrue(chance>=last&&chance<=.90);last=chance;}
        }
    }
    @Test @Transactional(transactionManager="dmwTransactionManager",propagation=Propagation.NOT_SUPPORTED)
    void downstreamFailureRollsBackOpenedPackageAndOperationAndAllowsSameRequestRetry() {
        long user=98908;
        var template=new org.springframework.transaction.support.TransactionTemplate(tx);
        template.executeWithoutResult(s->{seed(user);items.addItem(user,"fishing-bait-white",1);});
        doThrow(new IllegalStateException("simulate failure after bait consumption")).when(random).chance(anyDouble());
        assertThrows(IllegalStateException.class,()->fishing.fish(user,"玩家","bait-rollback"));
        assertFalse(states.existsById(user));assertFalse(attempts.existsById("bait-rollback"));
        assertEquals(1,items.backpack(user).getFirst().getQuantity());
        doReturn(false).when(random).chance(anyDouble());
        fishing.fish(user,"玩家","bait-rollback");
        assertEquals(4,states.findById(user).orElseThrow().getWhiteUses());
        assertThrows(RuntimeException.class,()->fishing.fish(user,"玩家","bait-rollback"));
        assertEquals(4,states.findById(user).orElseThrow().getWhiteUses());
    }
}
