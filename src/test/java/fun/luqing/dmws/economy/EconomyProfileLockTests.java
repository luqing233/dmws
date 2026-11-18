package fun.luqing.dmws.economy;

import fun.luqing.dmws.repository.dmw.economy.EconomyUserProfileRepository;
import fun.luqing.dmws.service.dmw.economy.EconomyUserProfileService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class EconomyProfileLockTests {
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired EconomyUserProfileService profiles;
    @Autowired EconomyUserProfileRepository repository;

    private TransactionTemplate transaction() {
        var tx = new TransactionTemplate(transactionManager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return tx;
    }

    @Test
    void cachedProfileIsRefreshedAfterAnotherTransactionCommits() throws Exception {
        long user = 9931001;
        transaction().executeWithoutResult(s -> profiles.getOrCreateForUpdate(user, "原昵称"));
        var cached = new CountDownLatch(1);
        var committed = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var reader = executor.submit(() -> transaction().execute(s -> {
                assertEquals(0, repository.findById(user).orElseThrow().getRobBoostCharges());
                cached.countDown();
                try {
                    assertTrue(committed.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                return profiles.getOrCreateForUpdate(user, "新昵称").getRobBoostCharges();
            }));
            assertTrue(cached.await(10, TimeUnit.SECONDS));
            try {
                transaction().executeWithoutResult(s -> {
                    var profile = profiles.getOrCreateForUpdate(user, "使用道具");
                    profile.setRobBoostCharges(7);
                    profile.setFishingRodLevel(45);
                });
            } finally {
                committed.countDown();
            }
            assertEquals(7, reader.get(10, TimeUnit.SECONDS));
        }
        var profile = repository.findById(user).orElseThrow();
        assertEquals(7, profile.getRobBoostCharges());
        assertEquals(45, profile.getFishingRodLevel());
        assertEquals("新昵称", profile.getNickname());
    }

    @Test
    void repeatedLockAndGroupRegistrationPreserveUncommittedChanges() {
        long user = 9931002;
        transaction().executeWithoutResult(s -> {
            var first = profiles.getOrCreateForUpdate(user, "玩家");
            first.setFishingLuckCharges(3);
            first.setRobBoostCharges(7);
            var second = profiles.getOrCreateForUpdate(user, "玩家");
            assertSame(first, second);
            assertEquals(3, second.getFishingLuckCharges());
            profiles.rememberGroup(user, "群名片", 123, "鱼塘");
            assertEquals(7, second.getRobBoostCharges());
        });
        var saved = repository.findById(user).orElseThrow();
        assertEquals(3, saved.getFishingLuckCharges());
        assertEquals(7, saved.getRobBoostCharges());
        assertEquals(123L, saved.getRegisterGroupId());
    }
}
