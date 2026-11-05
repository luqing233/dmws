package fun.luqing.dmws.economy;

import com.mikuac.shiro.dto.event.message.GroupMessageEvent;
import fun.luqing.dmws.common.economy.DuplicateEconomyOperationException;
import fun.luqing.dmws.common.economy.EconomyMessageOperation;
import fun.luqing.dmws.enums.EconomyAccountScope;
import fun.luqing.dmws.plugin.economy.EconomyCommandSupport;
import fun.luqing.dmws.service.dmw.economy.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class EconomyMessageIdempotencyTests {
    @Autowired EconomyItemService items;
    @Autowired EconomyAdminService admin;
    @Autowired EconomyLedgerService ledger;

    @Test void nativeAndMcpConcurrentPurchasesDebitAndGrantExactlyOnce() throws Exception {
        long owner = 995701L;
        admin.adjust(owner, owner, "并发测试", new BigDecimal("10000"), true, "concurrency-seed-995701");
        var event = new GroupMessageEvent(); event.setGroupId(706372110L);
        event.setUserId(owner); event.setMessageId(-1295435687);
        String nativeId = EconomyCommandSupport.operationId("item-buy", event, "fishing-luck-bait");
        String mcpId = EconomyMessageOperation.id("item-buy", 706372110L, -1295435687L, owner, "fishing-luck-bait");
        assertEquals(nativeId, mcpId);
        var ready = new CountDownLatch(6); var go = new CountDownLatch(1);
        var futures = new ArrayList<Future<Boolean>>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 6; i++) {
                String id = i % 2 == 0 ? nativeId : mcpId;
                futures.add(executor.submit(() -> {
                    ready.countDown(); go.await();
                    try { items.buy(owner, "并发测试", "fishing-luck-bait", 1, id); return true; }
                    catch (DuplicateEconomyOperationException expected) { return false; }
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS)); go.countDown();
            int successes = 0;
            for (var future : futures) if (future.get(30, TimeUnit.SECONDS)) successes++;
            assertEquals(1, successes);
        }
        assertEquals(new BigDecimal("9700.00"), ledger.balance(owner, EconomyAccountScope.WALLET).balance());
        assertEquals(1, items.backpack(owner).stream().filter(i -> i.getItemCode().equals("fishing-luck-bait")).findFirst().orElseThrow().getQuantity());
        assertEquals(1, ledger.recentEntries(owner, EconomyAccountScope.WALLET, 100).stream().filter(e -> e.getOperationId().equals(nativeId)).count());
        assertThrows(DuplicateEconomyOperationException.class, () -> items.buy(owner, "并发测试", "fishing-luck-bait", 20, mcpId));
    }

    @Test void separateMessagesActorsActionsAndItemsDoNotCollide() {
        String original = EconomyMessageOperation.id("item-buy", 1, 2, 3, "red-bull");
        assertNotEquals(original, EconomyMessageOperation.id("item-buy", 1, 4, 3, "red-bull"));
        assertNotEquals(original, EconomyMessageOperation.id("item-buy", 1, 2, 4, "red-bull"));
        assertNotEquals(original, EconomyMessageOperation.id("item-use", 1, 2, 3, "red-bull"));
        assertNotEquals(original, EconomyMessageOperation.id("item-buy", 1, 2, 3, "electric-baton"));
        assertTrue(original.length() <= 80);
    }
}
