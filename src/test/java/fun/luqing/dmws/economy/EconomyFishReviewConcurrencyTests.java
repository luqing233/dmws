package fun.luqing.dmws.economy;

import fun.luqing.dmws.repository.dmw.economy.*;
import fun.luqing.dmws.service.dmw.economy.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import static fun.luqing.dmws.service.dmw.economy.FishReviewModels.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class EconomyFishReviewConcurrencyTests {
    @Autowired EconomyFishReviewService service;
    @Autowired EconomyFishSubmissionRepository submissions;
    @Autowired EconomyFishRepository fish;
    @Autowired PlatformTransactionManager transactionManager;
    Draft draft(String name) { return new Draft(name, "并发审核测试", 1, 10, 20, 10, 20, false); }
    String id() { return UUID.randomUUID().toString(); }

    @Test void twoReviewersCannotPublishTheSameProposalOrNameTwice() throws Exception {
        long before = fish.count();
        var a = service.submit(new Submit(id(), "并发测试", draft("并发原稿甲")));
        var b = service.submit(new Submit(id(), "并发测试", draft("并发原稿乙")));
        var rowA = submissions.findByRequestId(a.requestId()).orElseThrow();
        var rowB = submissions.findByRequestId(b.requestId()).orElseThrow();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
            var futures = new ArrayList<Future<String>>();
            for (var row : List.of(rowA, rowB)) futures.add(executor.submit(() -> {
                ready.countDown(); start.await(10, TimeUnit.SECONDS);
                try { service.decide(row.getId(), true, new Decision(id(), row.getVersion(), draft("并发唯一入池鱼"), "通过"), "审核员"); return "APPROVED"; }
                catch (FishReviewException error) { return error.getCode(); }
            }));
            assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown();
            var outcomes = new ArrayList<String>(); for (var future : futures) outcomes.add(future.get(15, TimeUnit.SECONDS));
            assertEquals(1, Collections.frequency(outcomes, "APPROVED")); assertEquals(1, Collections.frequency(outcomes, "CONFLICT"));
            assertEquals(before + 1, fish.count());
            var approved = List.of(rowA.getId(), rowB.getId()).stream().map(key -> submissions.findById(key).orElseThrow())
                    .filter(saved -> saved.getStatus() == fun.luqing.dmws.entity.dmw.economy.EconomyFishSubmission.Status.APPROVED).findFirst();
            assertTrue(approved.isPresent());
            assertThrows(FishReviewException.class, () -> service.decide(approved.get().getId(), true,
                    new Decision(id(), approved.get().getVersion(), draft("另一个名字"), "重复审核"), "另一个审核员"));
        } finally {
            executor.shutdownNow();
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                for (var row : List.of(rowA, rowB)) submissions.findById(row.getId()).ifPresent(saved -> {
                    if (saved.getPublishedFishId() != null) fish.deleteById(saved.getPublishedFishId());
                    submissions.delete(saved);
                });
            });
        }
    }

    @Test void failureAfterFishInsertRollsBackPublicationAndKeepsProposalPending() {
        var receipt = service.submit(new Submit(id(), "回滚测试", draft("审核回滚鱼")));
        var row = submissions.findByRequestId(receipt.requestId()).orElseThrow(); long before = fish.count();
        try {
            assertThrows(FishReviewException.class, () -> service.decide(row.getId(), true,
                    new Decision(id(), row.getVersion(), draft("审核回滚鱼"), ""), ""));
            assertEquals(before, fish.count());
            assertEquals(fun.luqing.dmws.entity.dmw.economy.EconomyFishSubmission.Status.PENDING, submissions.findById(row.getId()).orElseThrow().getStatus());
            assertFalse(fish.existsByName("审核回滚鱼"));
        } finally { submissions.deleteById(row.getId()); }
    }
}
