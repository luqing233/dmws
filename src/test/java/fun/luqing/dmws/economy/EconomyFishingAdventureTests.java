package fun.luqing.dmws.economy;

import fun.luqing.dmws.entity.dmw.economy.*;
import fun.luqing.dmws.repository.dmw.economy.*;
import fun.luqing.dmws.service.dmw.economy.*;
import fun.luqing.dmws.enums.EconomyAccountScope;
import fun.luqing.dmws.common.economy.EconomyException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional(transactionManager="dmwTransactionManager")
class EconomyFishingAdventureTests {
    static final LocalDateTime START=LocalDateTime.of(2026,11,8,12,0);
    @Autowired EconomyFishingService fishing;
    @Autowired EconomyCombatService combat;
    @Autowired EconomyConditionService conditions;
    @Autowired EconomyFishingEventRepository events;
    @Autowired EconomyFishingAttemptRepository attempts;
    @Autowired EconomyFishingCatchRepository catches;
    @Autowired EconomyUserProfileService profiles;
    @Autowired EconomyAdminService admin;
    @Autowired EconomyLedgerService ledger;
    @Autowired EconomyItemService items;
    @Autowired EconomyEquipmentService equipment;
    @MockitoBean EconomyFishingRandom random;
    @MockitoBean EconomyGameTime time;
    @BeforeEach void setup() { at(START); }
    void at(LocalDateTime now) {when(time.now()).thenReturn(now);when(time.today()).thenReturn(now.toLocalDate());}
    void seed(long user,String amount) {
        admin.adjust(user,user,"钓鱼人",new BigDecimal(amount),true,"adventure-seed-"+user);
        var p=profiles.getOrCreateForUpdate(user,"钓鱼人");p.setFishingRodPurchased(true);
        combat.profile(user).setFishingAttempts(10);
        items.addItem(user,"fishing-bait-white",1);
    }
    void only(String code) {
        events.findAll().forEach(e->{e.setTickets(e.getCode().equals(code)?10000:0);events.save(e);});
    }
    @Test void corpsePersistsInvestigationWithoutAddingCrimeAndCannotBeRepeated() {
        seed(98701,"1000");only("corpse");
        conditions.addCrime(conditions.lock(98701,"玩家"),15);
        var r=fishing.fish(98701,"钓鱼人","corpse-once",123L,"测试鱼塘");
        assertEquals("尸体",r.fishName());assertEquals(new BigDecimal("0.00"),r.netProfit());
        var state=conditions.lock(98701,"玩家");assertEquals(START.plusMinutes(10),state.getJailUntil());assertEquals(15,state.getCrime());
        assertEquals(4,EconomyFishingText.receipt(r).lines().count());
        assertTrue(attempts.findById("corpse-once").orElseThrow().getReceipt().contains("协助调查"));
        assertThrows(RuntimeException.class,()->fishing.fish(98701,"玩家","corpse-once"));
        assertEquals(new BigDecimal("1000.00"),ledger.balance(98701,EconomyAccountScope.WALLET).balance());
        assertTrue(fishing.recentCatches(98701).isEmpty());
    }
    @Test void fineCannotOverdrawWalletOrTouchBankAndRecordsActualLoss() {
        seed(98702,"25");only("exam");
        var r=fishing.fish(98702,"玩家","fine-limited");
        assertEquals(new BigDecimal("25.00"),r.penalty());assertEquals(new BigDecimal("-25.00"),r.netProfit());
        assertEquals(new BigDecimal("0.00"),r.walletBalance());
        assertEquals(new BigDecimal("0.00"),ledger.balance(98702,EconomyAccountScope.MAIN_BANK).balance());
        assertEquals(new BigDecimal("25.00"),attempts.findById("fine-limited").orElseThrow().getPenalty());
    }
    @Test void successfulOrdinaryCatchStoresGroupAndNicknameSnapshots() {
        seed(98713,"1000");combat.profile(98713).setFishingAttempts(0);
        when(random.chance(anyDouble())).thenReturn(true);when(random.fraction()).thenReturn(0.5);
        fishing.fish(98713,"钓获时昵称","group-snapshot",123L,"钓获时群名");
        var row=fishing.recentCatches(98713).getFirst();
        assertEquals("钓获时群名",row.getGroupName());assertEquals("钓获时昵称",row.getNickname());
        assertEquals(123L,row.getGroupId());assertEquals(START,row.getCaughtAt());
    }
    @Test void crocodileVictoryStacksOneOfEachBoostPaysBonusAndNeverEntersFishRanking() {
        seed(98703,"1000");only("crocodile");
        items.addItem(98703,"electric-baton",2);profiles.getOrCreateForUpdate(98703,"玩家").setRobBoostCharges(3);
        when(random.chance(anyDouble())).thenReturn(true,false);
        var r=fishing.fish(98703,"玩家","crocodile-win");
        assertEquals(new BigDecimal("800.00"),r.reward());assertEquals(new BigDecimal("800.00"),r.netProfit());
        assertEquals(2,profiles.get(98703).getRobBoostCharges());
        assertEquals(80,equipment.view(98703).batons().get(0).energy());assertEquals(100,equipment.view(98703).batons().get(1).energy());
        verify(random).chance(doubleThat(value -> Math.abs(value-0.85)<1e-9));
        assertEquals(51,combat.strength(98703));assertTrue(fishing.recentCatches(98703).isEmpty());
        assertFalse(fishing.leaderboard(10).stream().anyMatch(e->e.userId()==98703));
        String text=EconomyFishingText.receipt(r);
        assertTrue(text.startsWith("你钓上了一条 尼罗鳄。\n描述：地面属于尼罗鳄!\n"));
        assertTrue(text.endsWith("收益：+800.00金币"));assertEquals(4,text.lines().count());
        var row=attempts.findById("crocodile-win").orElseThrow();assertTrue(row.isRedBullUsed());assertEquals(80,row.getBatonEnergy());
    }
    @Test void crocodileDefeatAdmitsSevereInjuryForTwentyFourHoursWithBothBoostsConsumed() {
        seed(98704,"1000");only("crocodile");
        items.addItem(98704,"electric-baton",1);profiles.getOrCreateForUpdate(98704,"玩家").setRobBoostCharges(1);
        when(random.weight(100)).thenReturn(99L);when(random.weight(40)).thenReturn(39L);
        when(random.chance(anyDouble())).thenReturn(false);
        var r=fishing.fish(98704,"玩家","crocodile-defeat");
        var state=conditions.lock(98704,"玩家");assertEquals(100,state.getInjury());assertEquals(START.plusHours(24),state.getHospitalUntil());
        assertEquals(0,profiles.get(98704).getRobBoostCharges());assertEquals(80,equipment.view(98704).batons().getFirst().energy());
        assertEquals(new BigDecimal("0.00"),r.netProfit());assertTrue(EconomyFishingText.receipt(r).contains("医疗费出院另结"));
    }
    @Test void victoriousButBittenPlayerReceivesFullRewardAndLightHospitalStay() {
        seed(98705,"1000");only("crocodile");when(random.chance(anyDouble())).thenReturn(true);
        fishing.fish(98705,"玩家","crocodile-bitten-win");
        var state=conditions.lock(98705,"玩家");assertEquals(1,state.getInjury());assertEquals(START.plusHours(24),state.getHospitalUntil());
        assertEquals(new BigDecimal("1800.00"),ledger.balance(98705,EconomyAccountScope.WALLET).balance());
    }
    @Test void blueBaitReducesEventProbabilityWithoutConsumingOtherQuality() {
        seed(98706,"1000");profiles.getOrCreateForUpdate(98706,"玩家").setFishingLuckCharges(2);
        when(random.weight(10000)).thenReturn(350L);
        fishing.fish(98706,"玩家","bait-reduction",null,null,"蓝");
        verify(random).weight(10000);assertEquals(0,profiles.get(98706).getFishingLuckCharges());
        assertEquals("ESCAPED",attempts.findById("bait-reduction").orElseThrow().getEventCode());
        assertEquals("BLUE",attempts.findById("bait-reduction").orElseThrow().getBaitQuality());
    }
    @Test void firstTenAttemptsAreProtectedAndInsufficientFundsDoNotAdvanceAttempts() {
        seed(98707,"1000");combat.profile(98707).setFishingAttempts(0);only("corpse");
        fishing.fish(98707,"玩家","newcomer");
        assertNull(conditions.lock(98707,"玩家").getJailUntil());verify(random,never()).weight(10000);
        assertEquals(1,combat.profile(98707).getFishingAttempts());
    }
    @Test void hourlyDecayKeepsRemainderAcrossReadsAndDoesNotBankFutureReductions() {
        var state=conditions.lock(98708,"玩家");conditions.addCrime(state,16);
        at(START.plusMinutes(59));assertEquals(16,conditions.status(98708,"玩家").crime());
        at(START.plusMinutes(60));assertEquals(11,conditions.status(98708,"玩家").crime());
        at(START.plusMinutes(119));assertEquals(11,conditions.status(98708,"玩家").crime());
        at(START.plusMinutes(120));assertEquals(6,conditions.status(98708,"玩家").crime());
        assertEquals(START.plusHours(2),state.getCrimeDecayAt());
        at(START.plusDays(2));assertEquals(0,conditions.status(98708,"玩家").crime());assertNull(state.getCrimeDecayAt());
        conditions.addCrime(state,8);assertEquals(START.plusDays(2),state.getCrimeDecayAt());
        at(START.plusDays(2).plusMinutes(59));assertEquals(8,conditions.status(98708,"玩家").crime());
    }
    @Test void policeAndCombatChanceIncreaseMonotonicallyWithTheirRelevantAttributes() {
        assertEquals(0.25,EconomyCombatService.policeChance(0.25,0));
        assertEquals(0.5,EconomyCombatService.policeChance(0.25,100));
        assertEquals(0.75,EconomyCombatService.policeChance(0.7,100));
        assertTrue(EconomyCombatService.winChance(70,50,0.35,0,0.1,0.9)>EconomyCombatService.winChance(50,50,0.35,0,0.1,0.9));
    }
    @Test void combatGrowthIsSharedLimitedToTwoPerDayAndRepeatedOpponentDoesNotFarmIt() {
        profiles.getOrCreateForUpdate(98709,"玩家");
        combat.victory(98709,"crocodile");combat.victory(98709,"crocodile");assertEquals(51,combat.strength(98709));
        combat.victory(98709,"player-1");combat.victory(98709,"player-2");assertEquals(52,combat.strength(98709));
        at(START.plusDays(1));combat.victory(98709,"crocodile");assertEquals(53,combat.strength(98709));
    }
    @Test void valueRankingDeduplicatesAllHistoryInsteadOfTakingTheFirstHundredRows() {
        for(int i=0;i<101;i++) catchRow(98710,"昂贵鱼",2000+i,10,"鱼塘甲");
        catchRow(98711,"小而贵",1900,1,"鱼塘乙");catchRow(98712,"大而便宜",1,1000,null);
        catches.flush();var list=fishing.leaderboard(10);
        assertEquals(3,list.stream().filter(e->e.userId()>=98710 && e.userId()<=98712).count());
        var first=list.stream().filter(e->e.userId()==98710).findFirst().orElseThrow();assertEquals(new BigDecimal("2100"),first.reward());
        assertEquals("鱼塘甲",first.groupName());assertTrue(list.indexOf(first)<list.indexOf(list.stream().filter(e->e.userId()==98711).findFirst().orElseThrow()));
    }
    void catchRow(long user,String name,int reward,int size,String group) {
        var c=new EconomyFishingCatch();c.setUserId(user);c.setFishId(1);c.setFishName(name);c.setReward(BigDecimal.valueOf(reward));
        c.setSizeCm(BigDecimal.valueOf(size));c.setGroupId(123L);c.setGroupName(group);c.setNickname("钓鱼人");catches.save(c);
    }
}
