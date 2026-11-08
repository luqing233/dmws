package fun.luqing.dmws.economy;

import fun.luqing.dmws.common.economy.EconomyException;
import fun.luqing.dmws.common.economy.DuplicateEconomyOperationException;
import fun.luqing.dmws.service.dmw.economy.*;
import fun.luqing.dmws.repository.dmw.economy.EconomyElectricBatonRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {"dmws.economy.sign-min=100", "dmws.economy.sign-max=100"})
@ActiveProfiles("test")
@Transactional(transactionManager = "dmwTransactionManager")
class EconomyEquipmentTests {
    private static final LocalDateTime START = LocalDateTime.of(2026, 11, 5, 12, 0);
    @MockitoBean private EconomyGameTime time;
    @MockitoBean private EconomyRobRandom random;
    @Autowired private EconomyItemService items;
    @Autowired private EconomyEquipmentService equipment;
    @Autowired private EconomyConditionService conditions;
    @Autowired private EconomyUserProfileService profiles;
    @Autowired private EconomyRobService rob;
    @Autowired private EconomyAdminService admin;
    @Autowired private EconomyElectricBatonRepository batons;
    @Autowired private fun.luqing.dmws.repository.dmw.economy.EconomyUserItemRepository inventory;
    @Autowired @Qualifier("dmwTransactionManager") private PlatformTransactionManager transactions;

    @BeforeEach void setup() { at(START); }
    private void at(LocalDateTime value) { when(time.now()).thenReturn(value); when(time.today()).thenReturn(value.toLocalDate()); }
    private void grant(long id, String code, int count) { profiles.getOrCreateForUpdate(id,"玩家"); items.addItem(id,code,count); }
    private int stock(long id, String code) { return items.backpack(id).stream().filter(i->i.getItemCode().equals(code)).mapToInt(i->i.getQuantity()).sum(); }
    private void target(long id) { admin.adjust(id,id,"目标",new BigDecimal("1000"),true,"equip-seed-"+id); }
    private void tx(Runnable action) { new TransactionTemplate(transactions).executeWithoutResult(s->action.run()); }

    @Test void aBatonSupportsFiveAttemptsAndDifferentBoostsStackOnceEach() {
        long id=98101L, victim=98102L;grant(id,"electric-baton",1);target(victim);
        profiles.getOrCreateForUpdate(id,"玩家").setRobBoostCharges(3);
        when(random.chance(anyDouble())).thenReturn(false);
        for (int i=0;i<5;i++) {
            at(START.plusMinutes(5L*i));var result=rob.rob(id,"玩家",victim,"equip-five-"+i);
            assertTrue(result.electricBatonUsed());assertEquals(80-20*i,result.electricBatonEnergy());
            assertEquals(Math.max(0,2-i),profiles.get(id).getRobBoostCharges());
        }
        at(START.plusMinutes(25));var fallback=rob.rob(id,"玩家",victim,"equip-fallback");
        assertFalse(fallback.electricBatonUsed());assertFalse(fallback.robBoostUsed());
        assertEquals(0,profiles.get(id).getRobBoostCharges());assertEquals(1,stock(id,"electric-baton"));
        assertEquals(0,equipment.view(id).batons().getFirst().energy());
    }

    @Test void counterattackStillUsesEnergyButInvalidCooldownDuplicateAndEmptyWalletDoNot() {
        long id=98103L,victim=98104L;grant(id,"electric-baton",1);target(victim);
        when(random.chance(anyDouble())).thenReturn(true);when(random.injury()).thenReturn(20);
        var result=rob.rob(id,"玩家",victim,"equip-counter");assertTrue(result.resisted());
        assertEquals(80,result.electricBatonEnergy());
        assertTrue(rob.describe(result,"群友").contains("耗电20"));
        assertThrows(DuplicateEconomyOperationException.class,()->rob.rob(id,"玩家",victim,"equip-counter"));
        assertThrows(EconomyException.class,()->rob.rob(id,"玩家",victim,"equip-hospital"));
        assertEquals(80,equipment.view(id).batons().getFirst().energy());
        long free=98105L;grant(free,"electric-baton",1);profiles.getOrCreateForUpdate(98106L,"空袋子");
        when(random.chance(anyDouble())).thenReturn(false);
        assertFalse(rob.rob(free,"玩家",98106L,"equip-poor").electricBatonUsed());
        assertEquals(100,equipment.view(free).batons().getFirst().energy());
        assertThrows(EconomyException.class,()->rob.rob(free,"玩家",victim,"equip-cooldown"));
        assertEquals(100,equipment.view(free).batons().getFirst().energy());
    }

    @Test void chargingIsReusableOnePointPerMinuteSurvivesReloadAndDoesNotMutateOnRead() {
        long id=98107L;grant(id,"electric-baton",1);grant(id,"electric-baton-charger",1);
        for(int i=0;i<5;i++)equipment.useForRobbery(id);
        assertTrue(items.use(id,"玩家","electric-baton-charger",1,"charge-start").contains("100 分钟"));
        at(START.plusSeconds(59));assertEquals(0,equipment.view(id).batons().getFirst().energy());
        at(START.plusSeconds(61));assertEquals(1,equipment.view(id).batons().getFirst().energy());
        assertNull(equipment.useForRobbery(id));
        assertEquals(0,batons.findByUserIdOrderByIdAsc(id).getFirst().getEnergy());
        assertEquals(START,batons.findByUserIdOrderByIdAsc(id).getFirst().getChargingSince());
        // A fresh service has no in-memory progress; the persisted anchor is sufficient after restart.
        var reloaded=new EconomyEquipmentService(batons,inventory,time);
        at(START.plusMinutes(50));assertEquals(50,reloaded.view(id).batons().getFirst().energy());
        at(START.plusMinutes(100));assertEquals(100,equipment.view(id).batons().getFirst().energy());
        assertFalse(equipment.view(id).batons().getFirst().charging());assertEquals(80,equipment.useForRobbery(id));
        assertTrue(items.use(id,"玩家","electric-baton-charger",1,"charge-again").contains("20 分钟"));
        assertEquals(1,stock(id,"electric-baton-charger"));assertEquals(1,stock(id,"electric-baton"));
    }

    @Test void multipleHistoricalBatonsRetainSeparateFullBatteriesAndOnlyOneCanChargeAtATime() {
        long id=98108L;grant(id,"electric-baton",2);grant(id,"electric-baton-charger",1);
        assertEquals(2,equipment.view(id).batons().size());assertTrue(batons.findByUserIdOrderByIdAsc(id).isEmpty());
        for(int i=0;i<5;i++)equipment.useForRobbery(id);
        items.use(id,"玩家","electric-baton-charger",1,"multi-charge");
        assertEquals(80,equipment.useForRobbery(id));
        assertEquals("ALREADY_CHARGING",assertThrows(EconomyException.class,()->items.use(id,"玩家","electric-baton-charger",1,"multi-again")).getCode());
        assertEquals(2,stock(id,"electric-baton"));assertEquals(1,stock(id,"electric-baton-charger"));
    }

    @Test void nineteenEnergyKeepsChargingAndExactlyTwentyStopsAndSpendsOnce() {
        long id=98120L;grant(id,"electric-baton",1);grant(id,"electric-baton-charger",1);
        for(int i=0;i<5;i++)equipment.useForRobbery(id);
        items.use(id,"玩家","electric-baton-charger",1,"threshold-charge");
        at(START.plusMinutes(20).minusNanos(1));
        assertEquals(19,equipment.view(id).batons().getFirst().energy());assertNull(equipment.useForRobbery(id));
        assertEquals(START,batons.findByUserIdOrderByIdAsc(id).getFirst().getChargingSince());
        assertTrue(equipment.view(id).text().contains("不足20电，继续充电"));
        at(START.plusMinutes(20));assertTrue(equipment.view(id).text().contains("拔电使用"));
        assertEquals(20,equipment.view(id).batons().getFirst().energy());
        assertEquals(0,equipment.useForRobbery(id));
        assertNull(batons.findByUserIdOrderByIdAsc(id).getFirst().getChargingSince());
        at(START.plusHours(3));assertEquals(0,equipment.view(id).batons().getFirst().energy());
        assertFalse(equipment.view(id).batons().getFirst().charging());assertEquals(1,stock(id,"electric-baton"));
    }

    @Test void partiallyChargedEnergyIsSettledBeforeUnplugAndASeparateChargeStartsFromRemainingEnergy() {
        long id=98121L;grant(id,"electric-baton",1);grant(id,"electric-baton-charger",1);
        for(int i=0;i<5;i++)equipment.useForRobbery(id);
        items.use(id,"玩家","electric-baton-charger",1,"partial-charge");
        at(START.plusMinutes(45).plusSeconds(30));
        var reloaded=new EconomyEquipmentService(batons,inventory,time);
        assertEquals(25,reloaded.useForRobbery(id));
        assertEquals(25,batons.findByUserIdOrderByIdAsc(id).getFirst().getEnergy());
        assertNull(batons.findByUserIdOrderByIdAsc(id).getFirst().getChargingSince());
        at(START.plusHours(2));assertEquals(25,reloaded.view(id).batons().getFirst().energy());
        assertTrue(items.use(id,"玩家","electric-baton-charger",1,"partial-recharge").contains("75 分钟"));
        at(START.plusHours(2).plusSeconds(59));assertEquals(25,equipment.view(id).batons().getFirst().energy());
        at(START.plusHours(2).plusMinutes(1));assertEquals(26,equipment.view(id).batons().getFirst().energy());
    }

    @Test void insufficientChargingBatonIsSkippedWithoutInterruptingItOrSpendingTwoBoosts() {
        long id=98122L,victim=98123L;grant(id,"electric-baton",2);grant(id,"electric-baton-charger",1);target(victim);
        for(int i=0;i<5;i++)equipment.useForRobbery(id);
        items.use(id,"玩家","electric-baton-charger",1,"skip-partial-charge");
        profiles.getOrCreateForUpdate(id,"玩家").setRobBoostCharges(2);
        at(START.plusMinutes(19));when(random.chance(anyDouble())).thenReturn(false);
        var result=rob.rob(id,"玩家",victim,"skip-charging-rob");
        assertTrue(result.electricBatonUsed());assertEquals(80,result.electricBatonEnergy());
        assertEquals(1,profiles.get(id).getRobBoostCharges());
        assertEquals(START,batons.findByUserIdOrderByIdAsc(id).getFirst().getChargingSince());
        at(START.plusMinutes(24));var next=rob.rob(id,"玩家",victim,"interrupt-charge-rob");
        assertTrue(next.electricBatonUsed());assertEquals(4,next.electricBatonEnergy());
        assertNull(batons.findByUserIdOrderByIdAsc(id).getFirst().getChargingSince());
        assertEquals(80,batons.findByUserIdOrderByIdAsc(id).get(1).getEnergy());
    }

    @Test @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void rollbackRestoresChargingAnchorAndEnergyBeforeARetry() {
        long id=98124L,victim=98125L;
        tx(()->{grant(id,"electric-baton",1);grant(id,"electric-baton-charger",1);target(victim);
            for(int i=0;i<5;i++)equipment.useForRobbery(id);
            items.use(id,"玩家","electric-baton-charger",1,"rollback-charge");});
        at(START.plusMinutes(20));when(random.chance(anyDouble())).thenReturn(false);
        assertThrows(IllegalStateException.class,()->tx(()->{
            assertEquals(0,rob.rob(id,"玩家",victim,"rollback-interrupt-rob").electricBatonEnergy());
            throw new IllegalStateException("Simulated transaction failure after energy consumption");
        }));
        assertTrue(equipment.view(id).batons().getFirst().charging());assertEquals(20,equipment.view(id).batons().getFirst().energy());
        assertEquals(START,batons.findByUserIdOrderByIdAsc(id).getFirst().getChargingSince());
        assertEquals(0,rob.rob(id,"玩家",victim,"rollback-interrupt-rob").electricBatonEnergy());
        assertFalse(equipment.view(id).batons().getFirst().charging());assertEquals(1,stock(id,"electric-baton"));
    }

    @Test void chargerCostsTheSameAsBatonAndDoesNotPermitWastefulDuplicatePurchases() {
        long id=98109L;target(id);
        assertEquals(items.definition("electric-baton").getPrice(),items.definition("electric-baton-charger").getPrice());
        grant(id,"electric-baton-charger",1);
        assertEquals("CHARGER_ALREADY_OWNED",assertThrows(EconomyException.class,()->items.buy(id,"玩家","electric-baton-charger",1,"duplicate-charger")).getCode());
        assertEquals("electric-baton",items.resolve("电棍").getCode());
        assertEquals("golden-basin",items.resolve("金盆").getCode());
    }

    @Test void goldenBasinHasDailyLimitsAndPreservesJailAndHospitalDeadlines() {
        long id=98110L;grant(id,"golden-basin",5);var state=conditions.lock(id,"玩家");
        conditions.addCrime(state,60);conditions.incarcerate(state);var deadline=state.getJailUntil();
        String result=items.use(id,"玩家","golden-basin",1,"basin-use");
        assertTrue(result.contains("降低 60"));assertEquals(0,state.getCrime());assertEquals(deadline,state.getJailUntil());
        assertEquals(4,stock(id,"golden-basin"));
        assertThrows(DuplicateEconomyOperationException.class,()->items.use(id,"玩家","golden-basin",1,"basin-use"));
        conditions.addCrime(state,100);
        at(START.plusDays(1));conditions.status(id,"玩家");conditions.addCrime(state,100);assertTrue(items.use(id,"玩家","golden-basin",1,"basin-next-day").contains("当前 0/100"));
        long patient=98111L;grant(patient,"golden-basin",1);var hospital=conditions.lock(patient,"玩家");
        conditions.addCrime(hospital,5);conditions.admit(hospital,50);var hospitalUntil=hospital.getHospitalUntil();
        assertTrue(items.use(patient,"玩家","golden-basin",1,"basin-small-crime").contains("降低 5"));
        assertEquals(hospitalUntil,hospital.getHospitalUntil());
    }

    @Test @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentRetriesOfOneRobberyOnlySpendTwentyEnergyOnce() throws Exception {
        long id=98114L,victim=98115L;
        tx(()->{grant(id,"electric-baton",1);grant(id,"electric-baton-charger",1);target(victim);
            for(int i=0;i<5;i++)equipment.useForRobbery(id);
            items.use(id,"玩家","electric-baton-charger",1,"concurrent-partial-charge");});
        at(START.plusMinutes(20));
        when(random.chance(anyDouble())).thenReturn(false);
        var latch=new CountDownLatch(1);
        try(var workers=Executors.newFixedThreadPool(2)) {
            Callable<String> request=()->{latch.await();try{rob.rob(id,"玩家",victim,"baton-retried-robbery");return "ok";}
                catch(DuplicateEconomyOperationException e){return "duplicate";}};
            var first=workers.submit(request);var second=workers.submit(request);latch.countDown();
            assertEquals(java.util.Set.of("ok","duplicate"),java.util.Set.of(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS)));
        }
        assertEquals(0,equipment.view(id).batons().getFirst().energy());assertEquals(1,stock(id,"electric-baton"));
        assertFalse(equipment.view(id).batons().getFirst().charging());
    }

    @Test @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void failedOrDuplicateReliefNeverConsumesStockAndDailyLimitIsSerialized() throws Exception {
        long id=98112L;
        tx(()->{grant(id,"golden-basin",5);conditions.addCrime(conditions.lock(id,"玩家"),60);});
        var latch=new CountDownLatch(1);
        try(var workers=Executors.newFixedThreadPool(3)) {
            var tasks=new java.util.ArrayList<Future<String>>();
            for(int i=0;i<3;i++){int n=i;tasks.add(workers.submit(()->{
                latch.await();try{tx(()->{conditions.addCrime(conditions.lock(id,"玩家"),60);items.use(id,"玩家","golden-basin",1,"basin-concurrent-"+n);});return "ok";}
                catch(EconomyException e){return e.getCode();}
            }));}
            latch.countDown();var outcomes=new java.util.ArrayList<String>();
            for(var f:tasks)outcomes.add(f.get(15,TimeUnit.SECONDS));
            assertEquals(2,outcomes.stream().filter("ok"::equals).count());assertTrue(outcomes.contains("CRIME_RELIEF_LIMIT"));
        }
        assertEquals(3,stock(id,"golden-basin"));assertEquals(0,conditions.status(id,"玩家").crime());
        long clean=98113L;tx(()->grant(clean,"golden-basin",2));
        assertThrows(EconomyException.class,()->items.use(clean,"玩家","golden-basin",1,"basin-no-crime"));
        assertEquals(2,stock(clean,"golden-basin"));
        tx(()->conditions.addCrime(conditions.lock(clean,"玩家"),5));
        assertThrows(EconomyException.class,()->items.use(clean,"玩家","golden-basin",2,"basin-excess"));
        assertEquals(2,stock(clean,"golden-basin"));
    }
}
