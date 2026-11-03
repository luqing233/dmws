package fun.luqing.dmws.economy;

import fun.luqing.dmws.common.economy.DuplicateEconomyOperationException;
import fun.luqing.dmws.common.economy.EconomyException;
import fun.luqing.dmws.enums.EconomyAccountScope;
import fun.luqing.dmws.service.dmw.economy.EconomyAdminService;
import fun.luqing.dmws.service.dmw.economy.EconomyBankingService;
import fun.luqing.dmws.service.dmw.economy.EconomyLedgerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
class EconomyConcurrencyTests {

    @Autowired
    private EconomyAdminService adminService;

    @Autowired
    private EconomyBankingService bankingService;

    @Autowired
    private EconomyLedgerService ledgerService;

    @Test
    void concurrentTransfersDoNotLoseUpdates() throws Exception {
        long source = 91001L;
        adminService.adjust(source, source, "source", new BigDecimal("1000"), true, "concurrent-seed");

        int transfers = 8;
        ExecutorService executor = Executors.newFixedThreadPool(transfers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int index = 0; index < transfers; index++) {
            long target = 91100L + index;
            int sequence = index;
            futures.add(executor.submit(() -> {
                start.await();
                bankingService.transfer(
                        source,
                        "source",
                        target,
                        new BigDecimal("100"),
                        "concurrent-transfer-" + sequence
                );
                return null;
            }));
        }

        start.countDown();
        for (Future<?> future : futures) {
            future.get();
        }
        executor.shutdownNow();

        assertEquals(
                new BigDecimal("200.00"),
                ledgerService.balance(source, EconomyAccountScope.WALLET).balance()
        );
        for (int index = 0; index < transfers; index++) {
            assertEquals(
                    new BigDecimal("100.00"),
                    ledgerService.balance(91100L + index, EconomyAccountScope.WALLET).balance()
            );
        }
    }

    @Test
    void concurrentDuplicateOperationsApplyOnlyOnce() throws Exception {
        long userId = 92001L;
        String operationId = "concurrent-duplicate";

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int index = 0; index < 2; index++) {
            futures.add(executor.submit(() -> {
                start.await();
                try {
                    adminService.adjust(
                            userId,
                            userId,
                            "target",
                            new BigDecimal("100"),
                            true,
                            operationId
                    );
                    return true;
                } catch (DuplicateEconomyOperationException exception) {
                    return false;
                }
            }));
        }

        start.countDown();
        int succeeded = 0;
        for (Future<Boolean> future : futures) {
            succeeded += future.get() ? 1 : 0;
        }
        executor.shutdownNow();

        assertEquals(1, succeeded);
        assertEquals(
                new BigDecimal("100.00"),
                ledgerService.balance(userId, EconomyAccountScope.WALLET).balance()
        );
        assertTrue(ledgerService.recentEntries(userId, EconomyAccountScope.WALLET, 10).size() == 1);
    }

    @Test
    void concurrentTransfersToANewAccountCreateItOnlyOnce() throws Exception {
        long target = 93001L;
        int transfers = 4;
        for (int index = 0; index < transfers; index++) {
            long source = 93100L + index;
            adminService.adjust(
                    source,
                    source,
                    "source-" + index,
                    new BigDecimal("100"),
                    true,
                    "new-target-seed-" + index
            );
        }

        ExecutorService executor = Executors.newFixedThreadPool(transfers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int index = 0; index < transfers; index++) {
            long source = 93100L + index;
            int sequence = index;
            futures.add(executor.submit(() -> {
                start.await();
                bankingService.transfer(
                        source,
                        "source",
                        target,
                        new BigDecimal("100"),
                        "new-target-transfer-" + sequence
                );
                return null;
            }));
        }

        start.countDown();
        for (Future<?> future : futures) {
            future.get();
        }
        executor.shutdownNow();

        assertEquals(
                new BigDecimal("400.00"),
                ledgerService.balance(target, EconomyAccountScope.WALLET).balance()
        );
    }

    @Test
    void concurrentCreditsToANewUserCreateProfileAndAccountOnce() throws Exception {
        long userId = 94001L;
        int credits = 4;
        ExecutorService executor = Executors.newFixedThreadPool(credits);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int index = 0; index < credits; index++) {
            int sequence = index;
            futures.add(executor.submit(() -> {
                start.await();
                adminService.adjust(
                        userId,
                        userId,
                        "new-user",
                        new BigDecimal("100"),
                        true,
                        "new-user-credit-" + sequence
                );
                return null;
            }));
        }

        start.countDown();
        for (Future<?> future : futures) {
            future.get();
        }
        executor.shutdownNow();

        assertEquals(
                new BigDecimal("400.00"),
                ledgerService.balance(userId, EconomyAccountScope.WALLET).balance()
        );
    }
}
