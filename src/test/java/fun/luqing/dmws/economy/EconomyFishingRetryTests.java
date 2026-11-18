package fun.luqing.dmws.economy;

import fun.luqing.dmws.entity.dmw.economy.EconomyFishingCast.Status;
import fun.luqing.dmws.repository.dmw.economy.*;
import fun.luqing.dmws.service.dmw.economy.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
class EconomyFishingRetryTests {
    @Autowired EconomyFishingService fishing;
    @Autowired EconomyFishingQueue queue;
    @Autowired EconomyUserProfileService profiles;
    @Autowired EconomyFishingCastRepository casts;
    @Autowired EconomyFishingEventRepository events;
    @Autowired EconomyFishingAttemptRepository attempts;
    @Autowired EconomyFishingTextRepository texts;
    @Autowired EconomyFishingBaitStateRepository baitStates;
    @Autowired EconomyAdminService admin;
    @Autowired EconomyLedgerQueryService ledger;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoSpyBean EconomyFishingRandom random;

    @Test
    void invalidDatabaseReceiptRollsBackPaymentBaitAndAttemptThenCanRetry() {
        long user = 9931005;
        var tx = new TransactionTemplate(transactionManager);
        var originalTickets = events.findAll().stream().collect(java.util.stream.Collectors.toMap(
                fun.luqing.dmws.entity.dmw.economy.EconomyFishingEvent::getCode,
                fun.luqing.dmws.entity.dmw.economy.EconomyFishingEvent::getTickets));
        tx.executeWithoutResult(s -> {
            events.findAll().forEach(event -> { event.setTickets(event.getCode().equals("boots") ? 10000 : 0); events.save(event); });
            admin.adjust(user, user, "钓鱼人", new BigDecimal("1000"), true, "invalid-text-seed");
            profiles.getOrCreateForUpdate(user, "钓鱼人").setFishingRodPurchased(true);
            var row = new fun.luqing.dmws.entity.dmw.economy.EconomyFishingTextTemplate();
            row.setCode("rollback-receipt"); row.setOwnerCode("boots"); row.setScene("RECEIPT");
            row.setTemplate("缺少必要字段{net}"); texts.save(row);
        });
        try {
            var ticket = fishing.cast(user, "钓鱼人", "invalid-text-operation", 0, null, null, null, null);
            assertThrows(fun.luqing.dmws.common.economy.EconomyException.class,
                    () -> fishing.reel(user, "invalid-text-operation", ticket.dueAt()));
            assertEquals(new BigDecimal("1000.00"), ledger.balance(user, fun.luqing.dmws.enums.EconomyAccountScope.WALLET).balance());
            assertFalse(baitStates.existsById(user));
            assertFalse(attempts.existsById("invalid-text-operation"));
            assertEquals(Status.WAITING, casts.findById("invalid-text-operation").orElseThrow().getStatus());
            texts.deleteById("rollback-receipt");
            var result = fishing.reel(user, "invalid-text-operation", ticket.dueAt());
            assertEquals(new BigDecimal("10.00"), result.penalty());
            assertTrue(result.cost().signum() > 0);
            assertEquals(new BigDecimal("1000.00").add(result.netProfit()),
                    ledger.balance(user, fun.luqing.dmws.enums.EconomyAccountScope.WALLET).balance());
            assertEquals(4, baitStates.findById(user).orElseThrow().remaining(EconomyBaitQuality.WHITE));
            assertNull(fishing.reel(user, "invalid-text-operation", ticket.dueAt()));
        } finally {
            tx.executeWithoutResult(s -> {
                if (texts.existsById("rollback-receipt")) texts.deleteById("rollback-receipt");
                originalTickets.forEach((code, value) -> {
                    var event = events.findById(code).orElseThrow(); event.setTickets(value); events.save(event);
                });
            });
        }
    }

    @Test
    void rollbackAndRetryUseThePersistedSeedWithoutDrawingANewFishOrSize() {
        long user = 9931004;
        var tx = new TransactionTemplate(transactionManager);
        var enabled = events.findByEnabledTrueOrderByCodeAsc().stream().map(e -> e.getCode()).toList();
        tx.executeWithoutResult(s -> {
            events.findAll().forEach(event -> { event.setEnabled(false); events.save(event); });
            admin.adjust(user, user, "钓鱼人", new BigDecimal("1000"), true, "retry-seed");
            profiles.getOrCreateForUpdate(user, "钓鱼人").setFishingRodPurchased(true);
        });
        try {
            var ticket = fishing.cast(user, "钓鱼人", "persistent-draw", 0, null, null, null, null);
            String seed = casts.findById("persistent-draw").orElseThrow().getRandomSeed();
            assertEquals(64, seed.length());
            var sizes = new ArrayList<Double>();
            var weights = new ArrayList<Long>();
            var failOnce = new AtomicBoolean(true);
            doAnswer(call -> {
                long value = (long) call.callRealMethod();
                weights.add(value);
                return value;
            }).when(random).weight(anyLong());
            doReturn(true).when(random).chance(anyDouble());
            doAnswer(call -> {
                double value = (double) call.callRealMethod();
                sizes.add(value);
                if (failOnce.getAndSet(false)) throw new IllegalStateException("failure after drawing size");
                return value;
            }).when(random).fraction();
            assertThrows(IllegalStateException.class,
                    () -> fishing.reel(user, "persistent-draw", ticket.dueAt()));
            assertEquals(Status.WAITING, casts.findById("persistent-draw").orElseThrow().getStatus());
            assertFalse(attempts.existsById("persistent-draw"));
            assertEquals(new BigDecimal("1000.00"),
                    ledger.balance(user, fun.luqing.dmws.enums.EconomyAccountScope.WALLET).balance());
            queue.recordFailure("persistent-draw", ticket.dueAt(), null);
            var result = fishing.reel(user, "persistent-draw", ticket.dueAt().plusSeconds(5));
            assertNotNull(result);
            assertEquals(sizes.get(0), sizes.get(1));
            assertEquals(weights.subList(0, 2), weights.subList(2, 4));
            assertEquals(seed, casts.findById("persistent-draw").orElseThrow().getRandomSeed());
            assertNull(fishing.reel(user, "persistent-draw", ticket.dueAt().plusSeconds(10)));
        } finally {
            tx.executeWithoutResult(s -> enabled.forEach(code -> {
                var event = events.findById(code).orElseThrow();
                event.setEnabled(true);
                events.save(event);
            }));
        }
    }
}
