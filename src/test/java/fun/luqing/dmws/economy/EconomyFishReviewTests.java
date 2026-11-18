package fun.luqing.dmws.economy;

import fun.luqing.dmws.common.security.FishingWebPassword;
import fun.luqing.dmws.entity.dmw.economy.EconomyFishSubmission.Status;
import fun.luqing.dmws.repository.dmw.economy.*;
import fun.luqing.dmws.service.dmw.economy.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;
import static fun.luqing.dmws.service.dmw.economy.FishReviewModels.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional(transactionManager = "dmwTransactionManager")
class EconomyFishReviewTests {
    @Autowired EconomyFishReviewService service;
    @Autowired EconomyFishSubmissionRepository submissions;
    @Autowired EconomyFishRepository fish;
    @Autowired EconomyUserProfileRepository profiles;
    @Autowired EconomyFishingAttemptRepository attempts;

    Draft draft(String name) { return new Draft(name, "{player}钓到了{fish}，售价{reward}金币。", 3, 120, 45, 20, 45, false); }
    String id() { return UUID.randomUUID().toString(); }
    Receipt submit(String name) { return service.submit(new Submit(id(), "投稿人", draft(name))); }

    @Test void pendingProposalsNeverEnterTheFishPoolAndRetriesAreRecognized() {
        long count = fish.count(); String request = id(); var command = new Submit(request, "投稿人", draft("月光审核鱼"));
        var first = service.submit(command); var repeated = service.submit(command);
        assertEquals(first, repeated); assertEquals(Status.PENDING, first.status()); assertEquals(count, fish.count());
        assertEquals(1, service.queue("PENDING", "月光审核鱼", 0).total());
        assertThrows(FishReviewException.class, () -> service.submit(new Submit(request, "投稿人", draft("另一条鱼"))));
        assertThrows(FishReviewException.class, () -> submit("月光审核鱼"));
    }

    @Test void approvalRetainsOriginalProposalAndPublishesExactlyOneReviewedFish() {
        long count = fish.count(); var receipt = submit("初稿鱼");
        var row = submissions.findByRequestId(receipt.requestId()).orElseThrow();
        var revised = new Draft("修订鱼", "审核后的说明", 5, 200, 55, 25, 30, true);
        var command = new Decision(id(), row.getVersion(), revised, "已调整价格与等级。");
        var result = service.decide(row.getId(), true, command, "审核员");
        assertEquals(Status.APPROVED, result.status()); assertEquals(count + 1, fish.count());
        assertEquals("初稿鱼", result.fish().name()); assertEquals(revised, result.reviewedFish());
        var published = fish.findById(result.publishedFishId()).orElseThrow();
        assertEquals("修订鱼", published.getName()); assertTrue(published.isEnabled()); assertTrue(published.getSpecial());
        assertEquals(result, service.decide(row.getId(), true, command, "审核员")); assertEquals(count + 1, fish.count());
        assertThrows(FishReviewException.class, () -> service.decide(row.getId(), false, new Decision(id(), 0L, null, "另一人驳回"), "另一个审核员"));
        assertEquals("已调整价格与等级。", service.progress(receipt.requestId()).note());
    }

    @Test void rejectionRequiresAReasonAndDoesNotCreateOrRemoveFish() {
        long count = fish.count(); var receipt = submit("驳回测试鱼");
        var row = submissions.findByRequestId(receipt.requestId()).orElseThrow();
        assertThrows(FishReviewException.class, () -> service.decide(row.getId(), false, new Decision(id(), row.getVersion(), null, " "), "审核员"));
        var result = service.decide(row.getId(), false, new Decision(id(), row.getVersion(), null, "价格太高，请调整。"), "审核员");
        assertEquals(Status.REJECTED, result.status()); assertNull(result.publishedFishId()); assertEquals(count, fish.count());
        assertEquals("价格太高，请调整。", service.progress(receipt.requestId()).note());
        assertDoesNotThrow(() -> submit("驳回测试鱼"));
    }

    @Test void approvalChecksTheCurrentCatalogAndOptimisticVersion() {
        var receipt = submit("不可重复审核鱼"); var row = submissions.findByRequestId(receipt.requestId()).orElseThrow();
        assertThrows(FishReviewException.class, () -> service.decide(row.getId(), true, new Decision(id(), 99L, draft("改名鱼"), ""), "审核员"));
        assertThrows(FishReviewException.class, () -> service.decide(row.getId(), true, new Decision(id(), row.getVersion(), draft("鲤鱼"), ""), "审核员"));
        assertEquals(Status.PENDING, row.getStatus()); assertNull(row.getPublishedFishId());
    }

    @Test void namesAreNormalizedAndPublishedNamesCannotBeSubmittedAgain() {
        submit("ｔｅｓｔ鱼");
        assertThrows(FishReviewException.class, () -> submit("TEST鱼"));
        assertThrows(FishReviewException.class, () -> submit("鲤鱼"));
        assertThrows(FishReviewException.class, () -> submit("隐\u200b形鱼"));
    }

    @ParameterizedTest
    @CsvSource({"0,120,45,20,45", "102,120,45,20,45", "3,0,45,20,45", "3,1000001,45,20,45", "3,120,-1,20,45", "3,120,45,0,45", "3,120,45,50,45"})
    void invalidDefinitionsAreRejected(int level, int price, int difficulty, int min, int max) {
        assertThrows(FishReviewException.class, () -> service.submit(new Submit(id(), "投稿人", new Draft("非法参数鱼", "描述", level, price, difficulty, min, max, false))));
    }

    @Test void previewUsesTheRealRewardAndTemplatesWithoutRegisteringOrFishing() {
        long playerCount = profiles.count(), history = attempts.count(), fishCount = fish.count(), proposals = submissions.count();
        var result = service.preview(new Draft("预览鱼", "{player}获得{reward}金币。", 3, 100, 45, 20, 40, false), 45);
        assertEquals("94.00", result.reward()); assertEquals("70.50", result.minimumReward()); assertEquals("117.50", result.maximumReward());
        assertEquals(4, result.receipt().lines().count()); assertTrue(result.receipt().contains("钓鱼人获得94.00金币。"));
        assertEquals(playerCount, profiles.count()); assertEquals(history, attempts.count()); assertEquals(fishCount, fish.count()); assertEquals(proposals, submissions.count());
    }

    @Test void historicalFishLevelsAndRowsRemainUntouched() {
        var carp = fish.findAll().stream().filter(f -> f.getName().equals("鲤鱼")).findFirst().orElseThrow();
        int originalId = carp.getId(); carp.setLevel(999);
        submit("新鱼不会改历史鱼"); service.catalog("鲤鱼", 0);
        assertEquals(999, fish.findById(originalId).orElseThrow().getLevel());
    }

    @Test void passwordHashMatchesTheIndependentPythonGeneratorFixture() {
        String hash = "pbkdf2-sha256$310000$AAECAwQFBgcICQoLDA0ODw$f92pJW53GA3iaug9jH_lQ5bt_2TQOfl3FU9a37PGVYg";
        assertTrue(FishingWebPassword.configured(hash)); assertTrue(FishingWebPassword.matches("fish-test-password", hash));
        assertFalse(FishingWebPassword.matches("wrong-password", hash)); assertFalse(FishingWebPassword.configured("Bearer mcp-token"));
        assertFalse(FishingWebPassword.matches("fish-test-password", hash.replace("310000", "99999999")));
    }
}
