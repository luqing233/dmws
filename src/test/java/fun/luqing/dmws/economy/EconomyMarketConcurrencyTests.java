package fun.luqing.dmws.economy;

import fun.luqing.dmws.common.economy.EconomyException;
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
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {"dmws.economy.market.enabled=true", "dmws.economy.market.scheduler-enabled=false",
        "spring.datasource.dmw.url=jdbc:h2:mem:fxconcurrency;MODE=MySQL;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
class EconomyMarketConcurrencyTests {
    @Autowired EconomyMarketService market;
    @Autowired EconomyMarketPositionRepository positions;
    @Autowired EconomyMarketQuoteRepository quotes;
    @Autowired EconomyMarketStateRepository states;
    @Autowired EconomyOperationRepository operations;
    @Autowired EconomyMarketNoticeRepository notices;
    @Autowired EconomyLedgerService ledger;
    @Autowired EconomyLedgerQueryService queries;
    @Autowired EconomyAdminService admin;
    @Autowired EconomyBankingService banking;
    @Autowired EconomyPrivateBankService privateBanks;
    @MockitoBean EconomyMarketRandom random;
    @MockitoBean EconomyGameTime time;
    static final AtomicInteger NEXT = new AtomicInteger();
    long user;
    LocalDateTime now;

    @BeforeEach void setup() {
        int index = NEXT.incrementAndGet(); user = 99300 + index;
        now = LocalDateTime.of(2026, 11, 9, 10, 0).plusDays(index);
        when(time.now()).thenReturn(now); when(time.today()).thenReturn(now.toLocalDate());
        when(random.nextReturn(anyString(), anyLong(), any())).thenReturn(BigDecimal.ZERO);
        market.tick();
        notices.deleteAll();
    }
    void seed(String value) { admin.adjust(user, user, "并发交易测试", new BigDecimal(value), true, "seed-" + user); }
    EconomyMarketService.ActionResult open(String id, String amount, int leverage) {
        return market.open(user, "", EconomyFxPair.EUR_USD, Side.LONG, new BigDecimal(amount), leverage, id);
    }
    void expireCall() {
        var expiry = time.now().plusMinutes(5);
        when(time.now()).thenReturn(expiry);
        market.tick();
    }

    long pendingCall() {
        long id = market.openInGroup(user,"",EconomyFxPair.EUR_USD,Side.LONG,new BigDecimal("1000"),100,"pending-"+user,456L).positionId();
        when(time.now()).thenReturn(now.plusMinutes(10));
        when(random.nextReturn(anyString(),anyLong(),any())).thenReturn(new BigDecimal("-.008"));
        market.tick(); return id;
    }

    @Test void sameTopupInParallelFreezesCoinsOnlyOnce() throws Exception {
        seed("3000"); long id = pendingCall();
        var results = race(6, i -> market.addMargin(user,"",id,new BigDecimal("500"),"same-topup-"+user));
        assertEquals(1, results.stream().distinct().count());
        assertEquals(new BigDecimal("1500.00"), positions.findById(id).orElseThrow().getMargin());
        assertEquals(new BigDecimal("1445.00"), ledger.balance(user,EconomyAccountScope.WALLET).balance());
        assertEquals(new BigDecimal("100000.00"), positions.findById(id).orElseThrow().getNotional());
    }

    @Test void topupRacingManualCloseCannotDoubleReturnCollateral() throws Exception {
        seed("3000"); long id = pendingCall();
        race(2, i -> {
            if (i==0) return market.close(user,"",id,"close-topup-race-"+user);
            try { return market.addMargin(user,"",id,new BigDecimal("500"),"topup-close-race-"+user); }
            catch(EconomyException e) { assertEquals("POSITION_CLOSED",e.getCode()); return e.getCode(); }
        });
        assertEquals(new BigDecimal("2145.00"), ledger.balance(user,EconomyAccountScope.WALLET).balance());
        assertEquals(new BigDecimal("0.00"), queries.accountBalance(EconomyMarketService.escrowKey(user),EconomyAccountScope.PLUGIN));
        assertNotEquals(Status.OPEN, positions.findById(id).orElseThrow().getStatus());
    }

    @Test void expiredTopupRacingScheduledSettlementPaysOnlyOnce() throws Exception {
        seed("3000"); long id = pendingCall(); when(time.now()).thenReturn(now.plusMinutes(15));
        race(2, i -> {
            if(i==0) return market.tick();
            try { return market.addMargin(user,"",id,new BigDecimal("500"),"deadline-race-"+user); }
            catch(EconomyException e) { assertEquals("POSITION_CLOSED",e.getCode()); return e.getCode(); }
        });
        assertEquals(Status.LIQUIDATED, positions.findById(id).orElseThrow().getStatus());
        assertEquals(new BigDecimal("2145.00"), ledger.balance(user,EconomyAccountScope.WALLET).balance());
    }

    @Test void failedTopupRollsBackAndCannotMoveThePersistentDeadline() {
        seed("1055"); long id = pendingCall();
        var deadline = positions.findById(id).orElseThrow().getMarginCallDeadline();
        String op="failed-margin-"+user;
        assertThrows(EconomyException.class,()->market.addMargin(user,"",id,new BigDecimal("500"),op));
        assertFalse(operations.existsById(op));
        var position=positions.findById(id).orElseThrow();
        assertEquals(deadline,position.getMarginCallDeadline()); assertEquals(new BigDecimal("1000.00"),position.getMargin());
        when(time.now()).thenReturn(deadline); market.tick();
        assertEquals(Status.LIQUIDATED,positions.findById(id).orElseThrow().getStatus());
        assertEquals(new BigDecimal("200.00"),ledger.balance(user,EconomyAccountScope.WALLET).balance());
    }

    @Test void multipleDispatchersCannotClaimOrReplayAnUncertainNotification() throws Exception {
        seed("3000"); pendingCall();
        // Discard any obsolete notices left by earlier independent integration cases.
        var results=race(6,i->market.claimNotice());
        var sent=results.stream().filter(Objects::nonNull).map(EconomyMarketService.Notice.class::cast).toList();
        assertEquals(1,sent.stream().filter(n->n.userId()==user).count());
        assertNull(market.claimNotice());
        var own=sent.stream().filter(n->n.userId()==user).findFirst().orElseThrow();
        market.finishNotice(own.id(),"UNKNOWN",null); assertNull(market.claimNotice());
        assertEquals("UNKNOWN",notices.findById(own.id()).orElseThrow().getStatus());
    }
    List<Object> race(int count, java.util.function.IntFunction<Object> action) throws Exception {
        try (var pool = Executors.newFixedThreadPool(count)) {
            var gate = new CountDownLatch(1); List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) { int index = i; futures.add(pool.submit(() -> { gate.await(); return action.apply(index); })); }
            gate.countDown(); var results = new ArrayList<>();
            for (var future : futures) results.add(future.get(20, TimeUnit.SECONDS));
            return results;
        }
    }

    @Test void insufficientFeeRollsBackMarginEscrowPositionAndOperation() {
        seed("500"); String op = "rollback-" + user;
        assertThrows(EconomyException.class, () -> open(op, "500", 20));
        assertEquals(new BigDecimal("500.00"), ledger.balance(user, EconomyAccountScope.WALLET).balance());
        assertEquals(new BigDecimal("0.00"), queries.accountBalance(EconomyMarketService.escrowKey(user), EconomyAccountScope.PLUGIN));
        assertTrue(positions.findByUserIdAndStatusOrderByIdAsc(user, Status.OPEN).isEmpty());
        assertFalse(operations.existsById(op));
        admin.adjust(user, user, "", new BigDecimal("2"), true, "fee-fund-" + user);
        assertNotNull(open(op, "500", 20).positionId());
    }

    @Test void simultaneousOpensCannotSpendSameCoinsTwice() throws Exception {
        seed("1000");
        var results = race(8, index -> {
            try { return open("parallel-" + user + "-" + index, "500", 20); }
            catch (EconomyException e) { return e.getCode(); }
        });
        assertEquals(1, results.stream().filter(v -> v instanceof EconomyMarketService.ActionResult).count());
        assertEquals(1, positions.findByUserIdAndStatusOrderByIdAsc(user, Status.OPEN).size());
        assertEquals(new BigDecimal("498.50"), ledger.balance(user, EconomyAccountScope.WALLET).balance());
    }

    @Test void duplicatesAndConcurrentCloseReturnReceiptsWithoutDoublePayments() throws Exception {
        seed("1000");
        var opened = race(6, i -> open("same-" + user, "500", 20));
        assertEquals(1, opened.stream().distinct().count());
        long position = ((EconomyMarketService.ActionResult) opened.getFirst()).positionId();
        race(6, i -> market.close(user, "", position, "close-" + user + "-" + i));
        assertEquals(new BigDecimal("998.50"), ledger.balance(user, EconomyAccountScope.WALLET).balance());
        assertEquals(new BigDecimal("0.00"), queries.accountBalance(EconomyMarketService.escrowKey(user), EconomyAccountScope.PLUGIN));
        assertEquals(Status.CLOSED, positions.findById(position).orElseThrow().getStatus());
    }

    @Test void failedTickRollsBackSnapshotSequenceAndReservationThenRecovers() {
        long sequence = states.findById(1).orElseThrow().getSequence(), count = quotes.count();
        when(time.now()).thenReturn(now.plusMinutes(10));
        when(random.nextReturn(anyString(), anyLong(), any())).thenReturn(new BigDecimal(".01")).thenThrow(new IllegalStateException("random fault"));
        assertThrows(IllegalStateException.class, market::tick);
        assertEquals(sequence, states.findById(1).orElseThrow().getSequence()); assertEquals(count, quotes.count());
        assertFalse(operations.existsById("market-tick:" + (sequence + 1)));
        doReturn(BigDecimal.ZERO).when(random).nextReturn(anyString(), anyLong(), any());
        assertEquals(sequence + 1, market.tick()); assertEquals(sequence + 1, market.tick());
    }

    @Test void liquidationRacingManualCloseSettlesOneTime() throws Exception {
        seed("1055"); long id = open("race-liquidate-" + user, "1000", 100).positionId();
        when(time.now()).thenReturn(now.plusMinutes(10)); when(random.nextReturn(anyString(), anyLong(), any())).thenReturn(new BigDecimal("-.02"));
        race(2, i -> {
            if (i == 0) return market.tick();
            try { return market.close(user, "", id, "manual-race-" + user); }
            catch (EconomyException e) { assertEquals("MARKET_STALE", e.getCode()); return e.getCode(); }
        });
        expireCall();
        assertEquals(Status.LIQUIDATED, positions.findById(id).orElseThrow().getStatus());
        assertEquals(new BigDecimal("1000.00"), market.portfolio(user).debt());
        assertEquals(new BigDecimal("0.00"), ledger.balance(user, EconomyAccountScope.WALLET).balance());
        market.close(user, "", id, "after-race-" + user);
        assertEquals(new BigDecimal("1000.00"), market.portfolio(user).debt());
    }

    @Test void insufficientRepaymentLeavesDebtAndCoinsUnchanged() {
        seed("1055"); open("repay-fail-open-" + user, "1000", 100);
        when(time.now()).thenReturn(now.plusMinutes(10)); when(random.nextReturn(anyString(), anyLong(), any())).thenReturn(new BigDecimal("-.02")); market.tick(); expireCall();
        admin.adjust(user, user, "", new BigDecimal("100"), true, "repay-fund-" + user);
        String op = "repay-fail-" + user;
        assertThrows(EconomyException.class, () -> market.repay(user, "", null, op));
        assertEquals(new BigDecimal("1000.00"), market.portfolio(user).debt());
        assertEquals(new BigDecimal("100.00"), ledger.balance(user, EconomyAccountScope.WALLET).balance());
        assertFalse(operations.existsById(op));
    }

    @Test void failedTickRetainsItsDrawEvenWhenRetryHappensInALaterSlot() {
        long sequence = states.findById(1).orElseThrow().getSequence();
        var previous = quotes.findById(sequence).orElseThrow();
        var draws = new EnumMap<EconomyFxPair, BigDecimal>(EconomyFxPair.class);
        var calls = new AtomicInteger(); var generator = new EconomyMarketRandom();
        when(time.now()).thenReturn(now.plusMinutes(10));
        doAnswer(inv -> {
            EconomyFxPair pair = inv.getArgument(2);
            BigDecimal value = generator.nextReturn(inv.getArgument(0), inv.getArgument(1), pair);
            draws.put(pair, value);
            if (calls.incrementAndGet() == 4) throw new IllegalStateException("tick transaction failed");
            return value;
        }).when(random).nextReturn(anyString(), anyLong(), any());
        assertThrows(IllegalStateException.class, market::tick);
        var failedDraws = Map.copyOf(draws);
        assertEquals(sequence, states.findById(1).orElseThrow().getSequence());
        when(time.now()).thenReturn(now.plusMinutes(50));
        market.tick();
        assertEquals(failedDraws, draws);
        var quote = quotes.findById(sequence + 1).orElseThrow();
        for (var pair : EconomyFxPair.values()) assertEquals(previous.price(pair).multiply(BigDecimal.ONE.add(failedDraws.get(pair)))
                .setScale(8, java.math.RoundingMode.HALF_UP), quote.price(pair));
    }

    @Test void loanAndOpenRaceCannotLeaveBothLoanAndPosition() throws Exception {
        seed("1000"); long owner = user + 1000;
        admin.adjust(owner, owner, "", new BigDecimal("100000"), true, "bank-owner-" + user);
        banking.deposit(owner, "", new BigDecimal("100000"), "bank-fund-" + user);
        String code = "fx" + user;
        privateBanks.create(owner, "", code, "并发银行" + user, "bank-create-" + user);
        var results = race(2, i -> {
            try {
                if (i == 0) return open("open-loan-race-" + user, "500", 20);
                return privateBanks.borrow(user, "", code, new BigDecimal("100"), 1, "borrow-open-race-" + user);
            } catch (EconomyException e) { return e.getCode(); }
        });
        assertEquals(1, results.stream().filter(r -> r instanceof String).count());
        assertTrue(results.contains("MARKET_DEBT_OUTSTANDING") || results.contains("MARKET_CREDIT_RESTRICTED"));
    }

    @Test void parallelRepaymentsCannotEraseMoreDebtThanTheyActuallyCharge() throws Exception {
        seed("1055"); open("parallel-repay-open-" + user, "1000", 100);
        when(time.now()).thenReturn(now.plusMinutes(10)); when(random.nextReturn(anyString(),anyLong(),any())).thenReturn(new BigDecimal("-.02")); market.tick(); expireCall();
        admin.adjust(user, user, "", new BigDecimal("1200"), true, "parallel-repay-fund-" + user);
        race(2, i -> market.repay(user, "", new BigDecimal("700"), "parallel-repay-" + user + "-" + i));
        assertEquals(new BigDecimal("0.00"), market.portfolio(user).debt());
        assertEquals(new BigDecimal("200.00"), ledger.balance(user, EconomyAccountScope.WALLET).balance());
    }
}
