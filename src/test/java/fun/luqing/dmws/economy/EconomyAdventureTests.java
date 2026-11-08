package fun.luqing.dmws.economy;

import fun.luqing.dmws.common.economy.DuplicateEconomyOperationException;
import fun.luqing.dmws.common.economy.EconomyException;
import fun.luqing.dmws.enums.EconomyAccountScope;
import fun.luqing.dmws.enums.EconomyLedgerReason;
import fun.luqing.dmws.repository.dmw.economy.EconomyPlayerConditionRepository;
import fun.luqing.dmws.service.dmw.economy.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {"dmws.economy.sign-min=100", "dmws.economy.sign-max=100"})
@ActiveProfiles("test")
@Transactional(transactionManager = "dmwTransactionManager")
class EconomyAdventureTests {
    private static final LocalDateTime START = LocalDateTime.of(2026, 11, 4, 12, 0);
    @MockitoBean private EconomyGameTime time;
    @MockitoBean private EconomyRobRandom random;
    @Autowired private EconomySignService signs;
    @Autowired private EconomyItemService items;
    @Autowired private EconomyUserProfileService profiles;
    @Autowired private EconomyConditionService conditions;
    @Autowired private EconomyRobService rob;
    @Autowired private EconomyAdminService admin;
    @Autowired private EconomyBankingService bank;
    @Autowired private EconomyLedgerService ledger;
    @Autowired private EconomyOperationService operations;
    @Autowired private EconomyFishingService fishing;
    @Autowired private EconomyFarmService farm;
    @Autowired private EconomyRaffleService raffle;
    @Autowired private EconomyPlayerConditionRepository conditionRepository;
    @Autowired @Qualifier("dmwTransactionManager") private PlatformTransactionManager txManager;

    @BeforeEach
    void setTimeAndRandom() {
        at(START);
        when(random.chance(anyDouble())).thenReturn(false);
        when(random.injury()).thenReturn(70);
        when(random.fraction()).thenReturn(0.5);
    }

    @Test
    void monthlyCardActivatesOnceForThirtyCalendarDaysAndPreservesSpareCards() {
        long id = 97001L;
        profiles.getOrCreateForUpdate(id, "月卡用户");
        items.addItem(id, "sign-monthly-card", 2);
        assertEquals(new BigDecimal("500.00"), signs.sign(id, "月卡用户", "month-day-0").reward());
        assertEquals(START.toLocalDate().plusDays(30), profiles.get(id).getMonthlySignExpiresOn());
        assertEquals(1, quantity(id, "sign-monthly-card"));
        at(START.plusDays(24));
        assertFalse(signs.sign(id, "月卡用户", "month-day-24").bonusText().contains("快过期"));
        at(START.plusDays(25));
        assertTrue(signs.sign(id, "月卡用户", "month-day-25").bonusText().contains("还剩 5 天"));
        at(START.plusDays(29));
        assertTrue(signs.sign(id, "月卡用户", "month-day-29").bonusText().contains("还剩 1 天"));
        assertEquals(1, quantity(id, "sign-monthly-card"));
        at(START.plusDays(30));
        assertEquals(new BigDecimal("500.00"), signs.sign(id, "月卡用户", "month-day-30").reward());
        assertEquals(0, quantity(id, "sign-monthly-card"));
        assertEquals(START.toLocalDate().plusDays(60), profiles.get(id).getMonthlySignExpiresOn());
    }

    @Test
    void expiredMonthFallsBackToSingleUseCardsWithoutStackingBonuses() {
        long id = 97002L;
        var profile = profiles.getOrCreateForUpdate(id, "过期用户");
        profile.setMonthlySignExpiresOn(START.toLocalDate());
        items.addItem(id, "double-sign-card", 1);
        assertEquals(new BigDecimal("200.00"), signs.sign(id, "过期用户", "expired-month").reward());
        assertEquals(0, quantity(id, "double-sign-card"));
    }

    @Test
    void activeMonthDoesNotConsumeTripleOrDoubleCards() {
        long id = 97003L;
        var profile = profiles.getOrCreateForUpdate(id, "月卡用户");
        profile.setMonthlySignExpiresOn(START.toLocalDate().plusDays(1));
        items.addItem(id, "triple-sign-card", 1);
        items.addItem(id, "double-sign-card", 1);
        assertEquals(new BigDecimal("500.00"), signs.sign(id, "月卡用户", "active-month").reward());
        assertEquals(1, quantity(id, "triple-sign-card"));
        assertEquals(1, quantity(id, "double-sign-card"));
    }

    @Test
    void stealingUsesWalletPercentageAndNeverExceedsFiveThousand() {
        long robber = 97004L, target = 97005L;
        fund(target, "100000", "rich-target");
        when(random.chance(anyDouble())).thenReturn(false, false, true);
        var result = rob.rob(robber, "抢劫者", target, "rob-rich");
        assertTrue(result.success());
        assertEquals(new BigDecimal("5000.00"), result.stolen());
        assertEquals(new BigDecimal("95000.00"), result.targetBalanceAfter());
        assertEquals(new BigDecimal("5000.00"), result.walletBalance());
        assertThrows(DuplicateEconomyOperationException.class, () -> rob.rob(robber, "抢劫者", target, "rob-rich"));
        assertEquals(new BigDecimal("95000.00"), bank.balances(target).wallet());
    }

    @Test
    void ordinaryTheftDoesNotTouchBankAndUsesConfiguredRate() {
        long robber = 97006L, target = 97007L;
        fund(target, "2000", "small-target");
        bank.deposit(target, "目标", new BigDecimal("1000"), "small-target-deposit");
        when(random.chance(anyDouble())).thenReturn(false, false, true);
        var result = rob.rob(robber, "抢劫者", target, "rob-small");
        assertEquals(new BigDecimal("200.00"), result.stolen());
        assertEquals(new BigDecimal("1000.00"), bank.balances(target).bank());
    }

    @Test
    void counterAttackHospitalizesRobberAndBlocksOutdoorGamesButNotSignIn() {
        long robber = 97008L, target = 97009L;
        fund(target, "1000", "counter-target");
        when(random.chance(anyDouble())).thenReturn(true);
        var result = rob.rob(robber, "抢劫者", target, "rob-counter");
        assertTrue(result.resisted());
        assertEquals(robber, result.injuredUserId());
        assertEquals(70, result.injury());
        assertEquals(START.plusHours(24), result.hospitalUntil());
        assertEquals("IN_HOSPITAL", assertThrows(EconomyException.class,
                () -> fishing.fish(robber, "抢劫者", "hospital-fish")).getCode());
        assertEquals("IN_HOSPITAL", assertThrows(EconomyException.class,
                () -> farm.plant(robber, "抢劫者", "wheat", 1, "hospital-farm")).getCode());
        assertEquals("IN_HOSPITAL", assertThrows(EconomyException.class,
                () -> raffle.draw(robber, "抢劫者", 1, "hospital-raffle")).getCode());
        assertEquals(new BigDecimal("100.00"), signs.sign(robber, "抢劫者", "hospital-sign").reward());
        assertEquals("IN_HOSPITAL", assertThrows(EconomyException.class,
                () -> rob.rob(target, "目标", robber, "rob-hospitalized-target")).getCode());
    }

    @Test
    void attackingEmptyWalletCanInjureVictimAndIncreaseCrimeBeforeArrest() {
        long robber = 97010L, target = 97011L;
        fund(robber, "100", "poor-robber");
        when(random.chance(anyDouble())).thenReturn(true, true);
        var result = rob.rob(robber, "劫匪", target, "rob-poor");
        assertTrue(result.poorTarget());
        assertTrue(result.victimAssaulted());
        assertTrue(result.caught());
        assertEquals(target, result.injuredUserId());
        assertEquals(30, result.crime());
        assertEquals(START.plusMinutes(15), result.jailUntil());
        assertEquals(new BigDecimal("0.00"), result.stolen());
        assertEquals(START.plusHours(24), conditions.status(target, "").hospitalUntil());
    }

    @Test
    void jailTermStartsAtTenMinutesAndCapsAtThirty() {
        long first = 97012L, repeat = 97013L;
        var newcomer = conditions.lock(first, "新玩家");
        conditions.addCrime(newcomer, 15);
        conditions.incarcerate(newcomer);
        assertEquals(START.plusMinutes(10), newcomer.getJailUntil());
        var habitual = conditions.lock(repeat, "惯犯");
        conditions.addCrime(habitual, 1000);
        conditions.incarcerate(habitual);
        assertEquals(100, habitual.getCrime());
        assertEquals(START.plusMinutes(30), habitual.getJailUntil());
        at(START.plusMinutes(30));
        assertNull(conditions.status(repeat, "惯犯").jailUntil());
    }

    @Test
    void allInjuryGradesWaitForManualDischargeUntilTheSameTwentyFourHourLimit() {
        int[] severity = {30, 31, 60, 61, 100};
        for (int index = 0; index < severity.length; index++) {
            var state = conditions.lock(97100L + index, "伤者");
            conditions.admit(state, severity[index]);
            assertEquals(START.plusHours(24), state.getHospitalUntil());
        }
        at(START.plusHours(8));
        assertEquals("HOSPITAL", conditions.location(97100L, "伤者").location());
        assertEquals("HOSPITAL", conditions.location(97101L, "伤者").location());
        var discharged = conditions.discharge(97100L, "伤者", "manual-light-discharge");
        assertEquals(new BigDecimal("540.00"), discharged.lastHospitalFee());
        assertEquals("FREE", conditions.location(97100L, "伤者").location());
        at(START.plusHours(24));
        assertEquals("FREE", conditions.location(97101L, "伤者").location());
        assertEquals(new BigDecimal("2230.00"), conditions.status(97101L, "伤者").lastHospitalFee());
    }

    @Test
    void locationsReflectJailExpiryAndHospitalPriorityWithoutBalanceFields() {
        long id = 97040L;
        assertEquals("FREE", conditions.location(id, "玩家").location());
        var state = conditions.lock(id, "玩家");
        conditions.incarcerate(state);
        assertEquals("JAIL", conditions.location(id, "玩家").location());
        assertEquals("监狱", conditions.location(id, "玩家").locationName());
        at(START.plusMinutes(10));
        assertEquals("FREE", conditions.location(id, "玩家").location());
        conditions.admit(conditions.lock(id, "玩家"), 40);
        var location = conditions.location(id, "玩家");
        assertEquals("医院", location.locationName());
        assertTrue(conditions.describeLocation(location, "玩家").contains("本人发送“出院”"));
        assertFalse(conditions.describeLocation(location, "玩家").contains("余额"));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void overdueHospitalSettlesOnceAtDeadlineEvenAfterLongDowntime() {
        long id = 97201L;
        inTransaction(() -> conditions.admit(conditions.lock(id, "伤者"), 70));
        at(START.plusDays(7));
        new EconomyConditionScheduler(conditionRepository, conditions, time).settleExpired();
        var result = conditions.status(id, "伤者");
        assertNull(result.hospitalUntil());
        assertEquals(START.plusHours(24), result.lastDischargedAt());
        assertEquals(new BigDecimal("4300.00"), result.lastHospitalFee());
        assertEquals(new BigDecimal("4300.00"), result.lastMedicalLoan());
        assertEquals(new BigDecimal("-4300.00"), bank.balances(id).bank());
        conditions.settleUser(id);
        conditions.discharge(id, "伤者", "already-discharged");
        assertEquals(new BigDecimal("-4300.00"), bank.balances(id).bank());
        assertEquals(1, ledger.recentEntries(id, EconomyAccountScope.MAIN_BANK, 100).stream()
                .filter(entry -> entry.getReason() == EconomyLedgerReason.HOSPITAL_FEE).count());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void earlyDischargeBillsElapsedTimeAndAllowsPartialMedicalDebtRepayment() {
        long id = 97202L;
        fund(id, "600", "early-hospital");
        bank.deposit(id, "伤者", new BigDecimal("100"), "early-bank");
        inTransaction(() -> conditions.admit(conditions.lock(id, "伤者"), 70));
        at(START.plusMinutes(75));
        var result = conditions.discharge(id, "伤者", "early-discharge");
        assertEquals(new BigDecimal("887.50"), result.lastHospitalFee());
        assertEquals(new BigDecimal("287.50"), result.lastMedicalLoan());
        assertEquals(new BigDecimal("0.00"), bank.balances(id).wallet());
        assertEquals(new BigDecimal("-287.50"), bank.balances(id).bank());
        fund(id, "100", "repayment-income");
        bank.deposit(id, "伤者", new BigDecimal("100"), "medical-partial-repayment");
        assertEquals(new BigDecimal("-187.50"), bank.balances(id).bank());
        assertThrows(EconomyException.class,
                () -> bank.withdraw(id, "伤者", new BigDecimal("0.01"), "withdraw-overdraft"));
        assertEquals(new BigDecimal("-187.50"), bank.balances(id).bank());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void simultaneousDischargeRequestsCannotDoubleBill() throws Exception {
        long id = 97203L;
        inTransaction(() -> conditions.admit(conditions.lock(id, "伤者"), 30));
        at(START.plusHours(1));
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(); return conditions.discharge(id, "伤者", "discharge-race-1"); });
            var second = executor.submit(() -> { start.await(); return conditions.discharge(id, "伤者", "discharge-race-2"); });
            start.countDown();
            assertEquals(new BigDecimal("330.00"), first.get(20, TimeUnit.SECONDS).lastHospitalFee());
            assertEquals(new BigDecimal("330.00"), second.get(20, TimeUnit.SECONDS).lastHospitalFee());
        }
        assertEquals(new BigDecimal("-330.00"), bank.balances(id).bank());
    }

    @Test
    void lowCrimeHelperPaysWalletThenBankToBailWithoutClearingCrime() {
        long helper = 97020L, prisoner = 97021L;
        fund(helper, "5000", "bail-helper");
        bank.deposit(helper, "担保人", new BigDecimal("4900"), "bail-helper-bank");
        var state = conditions.lock(prisoner, "囚犯");
        conditions.addCrime(state, 100);
        conditions.incarcerate(state);
        var result = conditions.bail(helper, "担保人", prisoner, "bail-success");
        assertEquals(new BigDecimal("2100.00"), result.fee());
        assertEquals(new BigDecimal("0.00"), result.walletBalance());
        assertEquals(new BigDecimal("2900.00"), result.bankBalance());
        assertEquals(100, result.prisonerCrime());
        assertNull(conditions.status(prisoner, "囚犯").jailUntil());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void ineligibleOrPoorHelperDoesNotFreePrisonerOrCreateDebt() {
        long helper = 97210L, prisoner = 97211L;
        inTransaction(() -> {
            var jailed = conditions.lock(prisoner, "囚犯");
            conditions.addCrime(jailed, 50);
            conditions.incarcerate(jailed);
            conditions.addCrime(conditions.lock(helper, "坏人"), 11);
        });
        assertEquals("BAIL_BAD_REPUTATION", assertThrows(EconomyException.class,
                () -> conditions.bail(helper, "坏人", prisoner, "bad-helper-bail")).getCode());
        inTransaction(() -> conditions.lock(helper, "好人").setCrime(0));
        assertEquals("INSUFFICIENT_BALANCE", assertThrows(EconomyException.class,
                () -> conditions.bail(helper, "好人", prisoner, "poor-helper-bail")).getCode());
        assertNotNull(conditions.status(prisoner, "囚犯").jailUntil());
        assertEquals(new BigDecimal("0.00"), bank.balances(helper).bank());
        assertFalse(operations.isProcessed("poor-helper-bail"));
        assertEquals("BAIL_SELF", assertThrows(EconomyException.class,
                () -> conditions.bail(prisoner, "囚犯", prisoner, "bail-self")).getCode());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void simultaneousBailAttemptsChargeOnlyOneHelper() throws Exception {
        long prisoner = 97220L, firstHelper = 97221L, secondHelper = 97222L;
        fund(firstHelper, "5000", "bail-race-first");
        fund(secondHelper, "5000", "bail-race-second");
        inTransaction(() -> {
            var state = conditions.lock(prisoner, "囚犯");
            conditions.addCrime(state, 50);
            conditions.incarcerate(state);
        });
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> attemptBail(start, firstHelper, prisoner, "bail-race-first-call"));
            var second = executor.submit(() -> attemptBail(start, secondHelper, prisoner, "bail-race-second-call"));
            start.countDown();
            var results = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            assertTrue(results.contains("OK"));
            assertTrue(results.contains("NOT_IN_JAIL"));
        }
        assertEquals(new BigDecimal("8900.00"), bank.balances(firstHelper).wallet().add(bank.balances(secondHelper).wallet()));
    }

    @Test
    void crimeDecaysByElapsedHoursButNeverBelowZero() {
        long id = 97030L;
        conditions.addCrime(conditions.lock(id, "玩家"), 11);
        at(START.plusHours(2));
        assertEquals(1, conditions.status(id, "玩家").crime());
        assertEquals(1, conditions.status(id, "玩家").crime());
        at(START.plusHours(3));
        assertEquals(0, conditions.status(id, "玩家").crime());
    }

    private void at(LocalDateTime now) {
        when(time.now()).thenReturn(now);
        when(time.today()).thenReturn(now.toLocalDate());
    }
    private int quantity(long id, String code) {
        return items.backpack(id).stream().filter(item -> item.getItemCode().equals(code)).mapToInt(item -> item.getQuantity()).sum();
    }
    private void fund(long id, String amount, String operation) {
        admin.adjust(id, id, "玩家", new BigDecimal(amount), true, operation);
    }
    private void inTransaction(Runnable action) { new TransactionTemplate(txManager).executeWithoutResult(status -> action.run()); }
    private String attemptBail(CountDownLatch start, long helper, long target, String operation) throws InterruptedException {
        start.await();
        try { conditions.bail(helper, "担保人", target, operation); return "OK"; }
        catch (EconomyException exception) { return exception.getCode(); }
    }
}
