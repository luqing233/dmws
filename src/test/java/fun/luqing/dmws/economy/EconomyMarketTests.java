package fun.luqing.dmws.economy;

import fun.luqing.dmws.common.economy.EconomyException;
import fun.luqing.dmws.config.EconomyMarketProperties;
import fun.luqing.dmws.entity.dmw.economy.EconomyMarketPosition;
import fun.luqing.dmws.entity.dmw.economy.EconomyMarketPosition.Side;
import fun.luqing.dmws.entity.dmw.economy.EconomyMarketPosition.Status;
import fun.luqing.dmws.enums.*;
import fun.luqing.dmws.repository.dmw.economy.*;
import fun.luqing.dmws.service.dmw.economy.*;
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

@SpringBootTest(properties = {"dmws.economy.market.enabled=true", "dmws.economy.market.scheduler-enabled=false",
        "spring.datasource.dmw.url=jdbc:h2:mem:fxmarket;MODE=MySQL;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
@Transactional(transactionManager = "dmwTransactionManager")
class EconomyMarketTests {
    @Autowired EconomyMarketService market;
    @Autowired EconomyMarketHistoryService priceHistory;
    @Autowired EconomyMarketProperties rules;
    @Autowired fun.luqing.dmws.config.DmwConfig config;
    @Autowired EconomyMarketStateRepository states;
    @Autowired EconomyMarketQuoteRepository quotes;
    @Autowired EconomyMarketPositionRepository positions;
    @Autowired EconomyMarketReceiptRepository receipts;
    @Autowired EconomyMarketNoticeRepository notices;
    @Autowired EconomyUserProfileRepository profiles;
    @Autowired EconomyAccountRepository accounts;
    @Autowired EconomyOperationRepository operations;
    @Autowired EconomyLedgerQueryService queries;
    @Autowired EconomyLedgerService ledger;
    @Autowired EconomyAdminService admin;
    @Autowired EconomyBankingService bank;
    @Autowired EconomyConditionService conditions;
    @Autowired EconomyPrivateBankService privateBanks;
    @MockitoBean EconomyMarketRandom random;
    @MockitoBean EconomyGameTime time;
    static final long USER = 99201;
    static final LocalDateTime START = LocalDateTime.of(2026, 11, 9, 10, 0, 5);

    @BeforeEach void setup() {
        when(time.now()).thenReturn(START); when(time.today()).thenReturn(START.toLocalDate());
        when(random.nextReturn(anyString(), anyLong(), any())).thenReturn(BigDecimal.ZERO);
        rules.setAcceptingOpens(true); rules.setMaxPositions(5); rules.setDailyOpenLimit(20);
        rules.setTotalMarginLimit(new BigDecimal("20000")); rules.setNetIssuanceBudget(new BigDecimal("1000000"));
        market.tick();
    }
    @AfterEach void restore() {
        rules.setAcceptingOpens(true); rules.setMaxPositions(5); rules.setDailyOpenLimit(20);
        rules.setTotalMarginLimit(new BigDecimal("20000")); rules.setNetIssuanceBudget(new BigDecimal("1000000"));
    }
    void seed(String value) { admin.adjust(USER, USER, "交易测试", new BigDecimal(value), true, "fx-seed"); }
    EconomyMarketService.ActionResult open(String op, String amount, int leverage, Side side) {
        return market.open(USER, "交易测试", EconomyFxPair.EUR_USD, side, new BigDecimal(amount), leverage, op);
    }
    void tick(int minute, String change) {
        when(time.now()).thenReturn(START.plusMinutes(minute * 10L));
        when(random.nextReturn(anyString(), anyLong(), any())).thenReturn(new BigDecimal(change));
        market.tick();
        // Historical settlement tests assert final accounting AFTER the newly introduced response window.
        if (positions.findByUserIdAndStatusOrderByIdAsc(USER, Status.OPEN).stream().anyMatch(p -> p.getMarginCallDeadline() != null)) {
            var expiry = time.now().plusMinutes(5);
            when(time.now()).thenReturn(expiry);
            market.tick();
        }
    }
    BigDecimal wallet() { return ledger.balance(USER, EconomyAccountScope.WALLET).balance(); }
    BigDecimal escrow() { return queries.accountBalance(EconomyMarketService.escrowKey(USER), EconomyAccountScope.PLUGIN); }
    String code(Runnable action) { return assertThrows(EconomyException.class, action::run).getCode(); }

    EconomyMarketService.ActionResult groupOpen() {
        return market.openInGroup(USER, "交易测试", EconomyFxPair.EUR_USD, Side.LONG, new BigDecimal("1000"), 100, "group-open", 456L);
    }
    void riskTick(String change) {
        when(time.now()).thenReturn(START.plusMinutes(10));
        when(random.nextReturn(anyString(), anyLong(), any())).thenReturn(new BigDecimal(change));
        market.tick();
    }

    @Test void warningAtSixtyPercentIsDurableAndClaimedOnlyOnce() {
        seed("3000"); var opened = groupOpen(); riskTick("-.006");
        var position = positions.findById(opened.positionId()).orElseThrow();
        assertNull(position.getMarginCallDeadline()); assertTrue(position.isRiskWarned());
        assertEquals(1, notices.count()); market.tick(); assertEquals(1, notices.count());
        var notice = market.claimNotice();
        assertEquals(USER, notice.userId()); assertEquals(456, notice.groupId());
        assertTrue(notice.message().contains("60%")); assertNull(market.claimNotice());
        market.finishNotice(notice.id(), "SENT", "1234");
        assertNull(market.claimNotice()); assertEquals("SENT", notices.findById(notice.id()).orElseThrow().getStatus());
    }

    @Test void eightyPercentWaitsAndCollateralRescueDoesNotIncreaseExposureOrChargeAnotherFee() {
        seed("3000"); var opened = groupOpen(); riskTick("-.008");
        var position = positions.findById(opened.positionId()).orElseThrow();
        assertEquals(Status.OPEN, position.getStatus()); assertEquals(START.plusMinutes(15), position.getMarginCallDeadline());
        BigDecimal notional = position.getNotional(), entry = position.getEntryPrice(), fees = states.findById(1).orElseThrow().getFees();
        var result = market.addMargin(USER, "", opened.positionId(), new BigDecimal("500"), "rescue");
        assertEquals(result, market.addMargin(USER, "", opened.positionId(), new BigDecimal("500.00"), "rescue"));
        assertEquals("OPERATION_CONFLICT", code(() -> market.addMargin(USER, "", opened.positionId(), new BigDecimal("501"), "rescue")));
        assertNull(position.getMarginCallDeadline()); assertEquals(new BigDecimal("1500.00"), escrow());
        assertEquals(notional, position.getNotional()); assertEquals(entry, position.getEntryPrice());
        assertEquals(fees, states.findById(1).orElseThrow().getFees());
        assertNull(market.claimNotice()); // rescued before delivery: suppress obsolete call
        assertEquals("OBSOLETE", notices.findAll().getFirst().getStatus());
    }

    @Test void timeoutUsesTriggerQuoteBeforePublishingLaterFavorablePriceAndDoesNotRepeatSettlement() {
        seed("2000"); var opened = groupOpen(); riskTick("-.008");
        var deadline = positions.findById(opened.positionId()).orElseThrow().getMarginCallDeadline();
        market.tick(); assertEquals(deadline, positions.findById(opened.positionId()).orElseThrow().getMarginCallDeadline());
        when(time.now()).thenReturn(START.plusMinutes(20));
        when(random.nextReturn(anyString(), anyLong(), any())).thenReturn(new BigDecimal(".03"));
        market.tick();
        var position = positions.findById(opened.positionId()).orElseThrow();
        assertEquals(Status.LIQUIDATED, position.getStatus()); assertEquals(new BigDecimal("-800.00"), position.getPnl());
        assertEquals(new BigDecimal("1145.00"), wallet());
        market.tick(); market.close(USER,"",opened.positionId(),"timeout-again"); assertEquals(new BigDecimal("1145.00"), wallet());
        var notice = market.claimNotice(); assertTrue(notice.message().contains("已强制平仓")); assertNull(market.claimNotice());
    }

    @Test void inadequateCollateralSettlesImmediatelyAndCannotExtendDeadline() {
        seed("3000"); var opened = groupOpen(); riskTick("-.01");
        var result = market.addMargin(USER,"",opened.positionId(),new BigDecimal("100"),"too-little");
        assertTrue(result.displayText().contains("不足维持"));
        assertEquals(Status.LIQUIDATED, positions.findById(opened.positionId()).orElseThrow().getStatus());
        assertEquals(new BigDecimal("1945.00"), wallet()); assertEquals(new BigDecimal("0.00"), escrow());
        assertEquals(result, market.addMargin(USER,"",opened.positionId(),new BigDecimal("100"),"too-little"));
    }

    @Test void exactDeadlineRejectsTopupWithoutTakingItsMoneyAndKeepsReceiptIdempotent() {
        seed("3000"); var opened = groupOpen(); riskTick("-.008");
        when(time.now()).thenReturn(START.plusMinutes(15));
        var result = market.addMargin(USER,"",opened.positionId(),new BigDecimal("500"),"late-rescue");
        assertEquals(new BigDecimal("0.00"), result.amount()); assertTrue(result.displayText().contains("本次未扣"));
        assertEquals(new BigDecimal("2145.00"), wallet());
        assertEquals(result, market.addMargin(USER,"",opened.positionId(),new BigDecimal("500"),"late-rescue"));
    }

    @Test void topupsRespectOwnershipMarginLimitsAndStillWorkAfterAdministratorClosesMarket() {
        seed("10000"); var opened = groupOpen(); riskTick("-.008");
        assertEquals("POSITION_NOT_FOUND", code(() -> market.addMargin(USER+1,"",opened.positionId(),new BigDecimal("500"),"wrong-owner")));
        assertEquals("MARKET_POSITION_LIMIT", code(() -> market.addMargin(USER,"",opened.positionId(),new BigDecimal("5000"),"too-much")));
        var state = states.findById(1).orElseThrow(); state.setTradingEnabled(false);
        assertNotNull(market.addMargin(USER,"",opened.positionId(),new BigDecimal("500"),"closed-rescue"));
        assertEquals(Status.OPEN, positions.findById(opened.positionId()).orElseThrow().getStatus());
    }

    @Test void quoteAndPortfolioQueriesNeverCreateProfilesAccountsOrTicks() {
        long userCount = profiles.count(), accountCount = accounts.count(), quoteCount = quotes.count();
        assertEquals(4, market.market().quotes().size());
        assertTrue(market.portfolio(999777).positions().isEmpty());
        assertTrue(market.history(999777).isEmpty());
        assertEquals(userCount, profiles.count()); assertEquals(accountCount, accounts.count()); assertEquals(quoteCount, quotes.count());
        assertTrue(EconomyMarketText.market(market.market()).contains("非真实汇率"));
    }

    @Test void historicalPriceQueryReadsPersistedSnapshotsAndDoesNotAdvanceTheMarketOrRegisterPlayers() {
        tick(1,".001");
        long quoteCount=quotes.count(), profileCount=profiles.count(), accountCount=accounts.count(), operationCount=operations.count();
        var view=priceHistory.query(EconomyFxPair.EUR_USD,EconomyMarketHistoryService.Period.DAY);
        assertEquals(2,view.count());assertEquals(EconomyFxPair.EUR_USD.initial(),view.first());
        assertEquals(new BigDecimal("0.001000000000"),view.change());assertTrue(view.partial());
        assertEquals(quoteCount,quotes.count());assertEquals(profileCount,profiles.count());
        assertEquals(accountCount,accounts.count());assertEquals(operationCount,operations.count());
    }

    @Test void administratorSwitchPersistsAndDoesNotTrapExistingPositionsOrReplayOldSwitches() {
        long[] previousMasters = config.getMaster();
        boolean previousEnabled = rules.isEnabled();
        try {
            config.setMaster(new long[]{USER});
            assertEquals("PERMISSION_DENIED", code(() -> market.setTradingEnabled(USER + 1, false, "unauthorized-switch")));
            assertFalse(operations.existsById("unauthorized-switch"));
            seed("10000");
            var opened = open("before-switch", "500", 20, Side.LONG);
            var off = market.setTradingEnabled(USER, false, "switch-off");
            assertEquals(Boolean.FALSE, states.findById(1).orElseThrow().getTradingEnabled());
            assertEquals("MARKET_OPEN_PAUSED", code(() -> open("blocked-switch", "500", 20, Side.LONG)));
            tick(1, ".001");
            assertNotNull(market.close(USER, "", opened.positionId(), "close-after-switch"));
            // Administrator override also enables a server whose initial configuration was disabled.
            rules.setEnabled(false);
            market.setTradingEnabled(USER, true, "switch-on");
            assertTrue(market.available());
            assertNotNull(open("after-switch", "500", 20, Side.LONG));
            assertEquals(off, market.setTradingEnabled(USER, false, "switch-off"));
            assertEquals(Boolean.TRUE, states.findById(1).orElseThrow().getTradingEnabled());
            assertEquals("OPERATION_CONFLICT", code(() -> market.setTradingEnabled(USER, true, "switch-off")));
        } finally {
            config.setMaster(previousMasters);
            rules.setEnabled(previousEnabled);
        }
    }

    @Test void longProfitFeesAndFrozenMarginAreAuditedAndNotDoublePaid() {
        seed("10000"); var opened = open("fx-open", "500", 20, Side.LONG);
        assertEquals(new BigDecimal("9498.50"), wallet()); assertEquals(new BigDecimal("500.00"), escrow());
        assertEquals(opened, open("fx-open", "500", 20, Side.LONG));
        assertEquals(1, positions.count());
        tick(1, ".01");
        assertEquals(new BigDecimal("100.00"), market.portfolio(USER).floatingPnl());
        var closed = market.close(USER, "", opened.positionId(), "fx-close");
        assertEquals(new BigDecimal("10098.50"), wallet()); assertEquals(new BigDecimal("0.00"), escrow());
        assertTrue(closed.displayText().contains("+98.50")); assertFalse(closed.displayText().contains(Long.toString(USER)));
        assertEquals(closed, market.close(USER, "", opened.positionId(), "fx-close"));
        assertTrue(market.close(USER, "", opened.positionId(), "fx-close-another").displayText().contains("未重复收付"));
        assertEquals(new BigDecimal("10098.50"), wallet());
        assertEquals(new BigDecimal("98.50"), states.findById(1).orElseThrow().getNetIssued());
    }

    @Test void shortMakesMoneyOnFallingPricesAndStaysOpenOnQueries() {
        seed("1000"); var opened = open("short", "500", 20, Side.SHORT);
        tick(1, "-.01");
        var view = market.portfolio(USER);
        assertEquals(new BigDecimal("100.00"), view.floatingPnl());
        assertEquals(Status.OPEN, positions.findById(opened.positionId()).orElseThrow().getStatus());
        market.close(USER, "", opened.positionId(), "close-short");
        assertEquals(new BigDecimal("1098.50"), wallet());
    }

    @Test void marginAndFeeMayUsePositiveBankFunds() {
        seed("1000"); bank.deposit(USER, "", new BigDecimal("1000"), "fx-bank");
        open("bank-open", "500", 20, Side.LONG);
        assertEquals(new BigDecimal("0.00"), wallet());
        assertEquals(new BigDecimal("498.50"), ledger.balance(USER, EconomyAccountScope.MAIN_BANK).balance());
        assertEquals(new BigDecimal("500.00"), escrow());
    }

    @Test void eightyPercentLossLiquidatesOnceAfterResponseWindowAtTriggerQuote() {
        seed("2000"); var opened = open("liq-open", "1000", 100, Side.LONG);
        tick(1, "-.008");
        var pos = positions.findById(opened.positionId()).orElseThrow();
        assertEquals(Status.LIQUIDATED, pos.getStatus());
        assertEquals(new BigDecimal("-800.00"), pos.getPnl());
        assertEquals(new BigDecimal("200.00"), pos.getPayout());
        assertEquals(new BigDecimal("1145.00"), wallet());
        market.tick(); tick(2, "-.03");
        assertEquals(new BigDecimal("1145.00"), wallet());
        assertTrue(market.history(USER).getFirst().closeReceipt().contains("爆仓"));
    }

    @Test void gapBeyondMarginCreatesSeparateDebtWithoutMedicalOverdraftAndBlocksOpening() {
        seed("1055"); var opened = open("gap-open", "1000", 100, Side.LONG);
        tick(1, "-.02");
        var pos = positions.findById(opened.positionId()).orElseThrow();
        assertEquals(new BigDecimal("-2000.00"), pos.getPnl());
        assertEquals(new BigDecimal("1000.00"), pos.getNewDebt());
        assertEquals(new BigDecimal("0.00"), wallet());
        assertEquals(new BigDecimal("0.00"), ledger.balance(USER, EconomyAccountScope.MAIN_BANK).balance());
        assertEquals(new BigDecimal("1000.00"), market.portfolio(USER).debt());
        assertEquals(new BigDecimal("-1000.00"), market.portfolio(USER).tradingEquity());
        assertEquals(new BigDecimal("-1055.00"), states.findById(1).orElseThrow().getNetIssued());
        assertEquals("MARKET_DEBT_OUTSTANDING", code(() -> open("blocked-debt", "100", 1, Side.LONG)));
    }

    @Test void gapCollectsWalletThenBankBeforeRecordingUnpaidDebt() {
        seed("1800"); bank.deposit(USER, "", new BigDecimal("600"), "bank-gap");
        var opened = open("gap-with-money", "1000", 100, Side.LONG);
        tick(1, "-.02"); var pos = positions.findById(opened.positionId()).orElseThrow();
        assertEquals(new BigDecimal("745.00"), pos.getExtraPaid());
        assertEquals(new BigDecimal("255.00"), pos.getNewDebt());
        assertEquals(new BigDecimal("0.00"), wallet());
        assertEquals(new BigDecimal("0.00"), ledger.balance(USER, EconomyAccountScope.MAIN_BANK).balance());
    }

    @Test void futureCloseProceedsPayTradingDebtBeforeWallet() {
        seed("1556.50");
        var loser = open("loser", "1000", 100, Side.LONG);
        var winner = open("winner", "500", 20, Side.SHORT);
        tick(1, "-.02");
        assertEquals(Status.LIQUIDATED, positions.findById(loser.positionId()).orElseThrow().getStatus());
        assertEquals(new BigDecimal("1000.00"), market.portfolio(USER).debt());
        var closed = market.close(USER, "", winner.positionId(), "winner-close");
        assertEquals(new BigDecimal("300.00"), closed.debt()); assertEquals(new BigDecimal("0.00"), wallet());
        assertTrue(closed.displayText().contains("抵扣交易欠款 700.00"));
    }

    @Test void repaymentIsPartialOrFullAndReplayDoesNotRepayAgain() {
        seed("1055"); open("borrow", "1000", 100, Side.LONG); tick(1, "-.02");
        admin.adjust(USER, USER, "", new BigDecimal("1200"), true, "debt-cash");
        var first = market.repay(USER, "", new BigDecimal("400"), "repay-part");
        assertEquals(new BigDecimal("600.00"), first.debt());
        assertEquals(first, market.repay(USER, "", new BigDecimal("400"), "repay-part"));
        assertEquals(new BigDecimal("800.00"), wallet());
        market.repay(USER, "", null, "repay-all");
        assertEquals(new BigDecimal("200.00"), wallet()); assertEquals(new BigDecimal("0.00"), market.portfolio(USER).debt());
        open("after-repay", "100", 1, Side.LONG);
    }

    @Test void queriesAndStaleTradesNeverAdvanceRandomMarket() {
        seed("1000"); var opened = open("before-stale", "500", 20, Side.LONG);
        when(time.now()).thenReturn(START.plusMinutes(10));
        assertFalse(market.market().fresh()); assertFalse(market.portfolio(USER).fresh());
        assertEquals("MARKET_STALE", code(() -> market.close(USER, "", opened.positionId(), "stale-close")));
        assertEquals(1, quotes.count()); verifyNoInteractions(random);
    }

    @Test void restartGapCreatesOneFreshTickAndNeverReplaysMissedMinutes() {
        when(time.now()).thenReturn(START.plusDays(10));
        market.tick(); market.tick();
        assertEquals(2, quotes.count()); verify(random, times(4)).nextReturn(anyString(), anyLong(), any());
    }

    @Test void quotesOnlyAdvanceOnTenMinuteBoundaries() {
        when(time.now()).thenReturn(START.withMinute(9).withSecond(59));
        assertEquals(1, market.tick()); assertTrue(market.market().fresh()); verifyNoInteractions(random);
        when(time.now()).thenReturn(START.withMinute(10).withSecond(0));
        assertFalse(market.market().fresh());
        assertEquals(2, market.tick()); assertTrue(market.market().fresh());
        assertEquals(START.withMinute(10).withSecond(0), market.market().quotedAt());
        assertEquals(START.withMinute(50).withSecond(0), EconomyMarketService.quoteSlot(START.withMinute(59)));
    }

    @Test void everyPairHasSameDurableSequenceAndHistoricalQuotesNeverChange() {
        var initial = market.market(); tick(1, ".001"); tick(2, "-.001");
        assertEquals(3, market.market().sequence());
        assertEquals(initial.quotes().getFirst().price(), quotes.findById(1L).orElseThrow().getEurUsd());
        assertEquals(4, market.market().quotes().size());
    }

    @Test void capsAndPauseOnlyBlockNewPositions() {
        seed("2000"); var first = open("first", "500", 20, Side.LONG);
        rules.setMaxPositions(1);
        assertEquals("MARKET_POSITION_LIMIT", code(() -> open("too-many", "500", 20, Side.LONG)));
        rules.setAcceptingOpens(false);
        assertEquals("MARKET_OPEN_PAUSED", code(() -> open("paused", "100", 1, Side.LONG)));
        market.close(USER, "", first.positionId(), "close-paused");
        assertEquals(new BigDecimal("1998.50"), wallet());
    }

    @Test void closedPositionsStillCountTowardDailyOpenLimit() {
        seed("2000"); rules.setDailyOpenLimit(1);
        var first = open("daily-first", "500", 20, Side.LONG);
        market.close(USER, "", first.positionId(), "daily-close");
        assertEquals("MARKET_DAILY_LIMIT", code(() -> open("daily-second", "500", 20, Side.LONG)));
    }

    @Test void ownerCannotCloseAnotherPlayersPosition() {
        seed("1000"); var pos = open("private-position", "500", 20, Side.LONG);
        assertEquals("POSITION_NOT_FOUND", code(() -> market.close(USER + 1, "", pos.positionId(), "wrong-owner")));
        assertEquals(Status.OPEN, positions.findById(pos.positionId()).orElseThrow().getStatus());
    }

    @Test void invalidMarginPrecisionAndLeverageAreRejected() {
        seed("1000");
        assertEquals("INVALID_AMOUNT", code(() -> open("fraction", "100.001", 20, Side.LONG)));
        assertEquals("INVALID_MARGIN", code(() -> open("too-small", "99", 20, Side.LONG)));
        assertEquals("INVALID_LEVERAGE", code(() -> open("too-high", "100", 101, Side.LONG)));
        assertEquals(0, positions.count());
    }

    @Test void hospitalizationBlocksNewRiskButAllowsClosing() {
        seed("2000"); var pos = open("pre-hospital", "500", 20, Side.LONG);
        conditions.admit(conditions.lock(USER, ""), 70);
        assertEquals("IN_HOSPITAL", code(() -> open("hospital-open", "100", 1, Side.LONG)));
        market.close(USER, "", pos.positionId(), "hospital-close");
        assertEquals(Status.CLOSED, positions.findById(pos.positionId()).orElseThrow().getStatus());
    }

    @Test void tradingGapNeverIncreasesExistingMedicalBankDebt() {
        seed("1255"); open("medical-gap-open", "1000", 100, Side.LONG);
        ledger.chargeHospital(USER, new BigDecimal("400"), "medical-bill", "医疗账单");
        assertEquals(new BigDecimal("-200.00"), ledger.balance(USER, EconomyAccountScope.MAIN_BANK).balance());
        tick(1, "-.02");
        assertEquals(new BigDecimal("-200.00"), ledger.balance(USER, EconomyAccountScope.MAIN_BANK).balance());
        assertEquals(new BigDecimal("1000.00"), market.portfolio(USER).debt());
    }

    @Test void totalMarginLimitAndNetIssuanceBudgetBlockAdditionalRisk() {
        seed("5000"); rules.setTotalMarginLimit(new BigDecimal("500"));
        var first = open("limit-first", "500", 20, Side.LONG);
        assertEquals("MARKET_POSITION_LIMIT", code(() -> open("over-total", "100", 1, Side.LONG)));
        rules.setNetIssuanceBudget(new BigDecimal("0.01")); tick(1, ".01");
        market.close(USER, "", first.positionId(), "profit-to-budget");
        assertEquals("MARKET_OPEN_PAUSED", code(() -> open("budget-exhausted", "100", 1, Side.LONG)));
    }

    @Test void cannotBorrowAfterOpeningOrAfterIncurringTradingDebt() {
        long owner = USER + 100;
        admin.adjust(owner, owner, "行长", new BigDecimal("100000"), true, "audit-bank-seed");
        bank.deposit(owner, "", new BigDecimal("100000"), "audit-bank-deposit");
        privateBanks.create(owner, "", "fxaudit", "审计银行", "audit-bank-create");
        seed("1055"); open("audit-position", "1000", 100, Side.LONG);
        assertEquals("MARKET_CREDIT_RESTRICTED", code(() -> privateBanks.borrow(USER, "", "fxaudit", new BigDecimal("100"), 1, "audit-loan-open")));
        tick(1, "-.02");
        assertEquals("MARKET_CREDIT_RESTRICTED", code(() -> privateBanks.borrow(USER, "", "fxaudit", new BigDecimal("100"), 1, "audit-loan-debt")));
    }

    @Test void changedRequestWithSameOperationIsRejectedAndRepeatCloseReportsNoNewPayment() {
        seed("2000"); var first = open("audit-reuse", "500", 20, Side.LONG);
        assertEquals("OPERATION_CONFLICT", code(() -> open("audit-reuse", "1000", 20, Side.LONG)));
        market.close(USER, "", first.positionId(), "audit-close");
        assertEquals(new BigDecimal("0.00"), market.close(USER, "", first.positionId(), "audit-close-again").amount());
    }

    @Test void hedgedPositionsCannotCashOutProfitWhileLeavingSameAccountGapDebtUnpaid() {
        seed("2110"); open("hedge-long", "1000", 100, Side.LONG);
        var shortPosition = open("hedge-short", "1000", 100, Side.SHORT);
        tick(1, "-.02");
        assertEquals(new BigDecimal("1000.00"), market.portfolio(USER).debt());
        market.close(USER, "", shortPosition.positionId(), "hedge-close");
        assertEquals(new BigDecimal("2000.00"), wallet());
        assertEquals(new BigDecimal("0.00"), market.portfolio(USER).debt());
        assertEquals(new BigDecimal("-110.00"), states.findById(1).orElseThrow().getNetIssued());
    }

    @Test void sameQuoteRoundTripsOnlyLoseFeesAndCannotFarmRoundingProfit() {
        seed("10000"); BigDecimal totalFees = BigDecimal.ZERO;
        for (int leverage : new int[]{1,20,100}) for (Side side : Side.values()) {
            String op = "round-trip-" + leverage + "-" + side;
            var opened = open(op, "101.37", leverage, side);
            totalFees = totalFees.add(EconomyMarketService.openingFee(new BigDecimal("101.37"), leverage));
            market.close(USER, "", opened.positionId(), "close-" + op);
        }
        assertEquals(new BigDecimal("10000.00").subtract(totalFees), wallet());
        assertEquals(totalFees.negate(), states.findById(1).orElseThrow().getNetIssued());
        assertEquals(new BigDecimal("0.00"), escrow());
    }
}
