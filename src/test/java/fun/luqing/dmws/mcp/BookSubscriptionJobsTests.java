package fun.luqing.dmws.mcp;

import fun.luqing.dmws.client.TomatoApiClient;
import fun.luqing.dmws.client.TomatoUpdateRecord;
import fun.luqing.dmws.entity.dmw.tomato.TomatoSubscriptionGroup;
import fun.luqing.dmws.service.dmw.tomato.TomatoBookListService;
import fun.luqing.dmws.service.dmw.tomato.TomatoSubscriptionGroupService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BookSubscriptionJobsTests {
    private final TomatoBookListService books = mock(TomatoBookListService.class);
    private final TomatoSubscriptionGroupService subscriptions = mock(TomatoSubscriptionGroupService.class);
    private final TomatoApiClient client = mock(TomatoApiClient.class);
    private final BookUpdateJobs updates = mock(BookUpdateJobs.class);

    private ObjectNode awaitDone(BookSubscriptionJobs jobs, String id) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!jobs.get(id).path("done").asBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        var result = jobs.get(id);
        assertTrue(result.path("done").asBoolean());
        return result;
    }

    @Test
    void returnsBeforeSlowDownloaderAndDeduplicatesRetry() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(client.getBookDetail("book")).thenAnswer(call -> {
            started.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return new TomatoUpdateRecord("书名", "章节", "chapter", "2026-11-04", 100L);
        });
        when(books.addBook(any())).thenAnswer(call -> call.getArgument(0));
        when(subscriptions.addSubscription(any())).thenAnswer(call -> {
            TomatoSubscriptionGroup sub = call.getArgument(0);
            sub.setId(8L);
            return sub;
        });
        when(updates.submit("book", "书名")).thenReturn(new ObjectMapper().createObjectNode().put("job_id", "data-job"));
        var jobs = new BookSubscriptionJobs(books, subscriptions, client, updates);
        try {
            var queued = jobs.submit(1, "book", 2, false);
            String id = queued.path("job_id").asText();
            assertFalse(queued.path("done").asBoolean());
            assertTrue(id.startsWith("sub-"));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertEquals(id, jobs.submit(1, "book", 2, false).path("job_id").asText());
            verify(subscriptions, never()).addSubscription(any());
            release.countDown();
            var result = awaitDone(jobs, id);
            assertTrue(result.path("ok").asBoolean());
            assertEquals("completed", result.path("status").asText());
            assertEquals(8, result.path("subscription_id").asInt());
            assertEquals("data-job", result.path("data_job_id").asText());
            verify(subscriptions, times(1)).addSubscription(any());
            verify(client, times(1)).getBookDetail("book");
        } finally {
            release.countDown();
            jobs.close();
        }
    }

    @Test
    void existingSubscriptionReturnsReceiptWithoutRepeatingWork() {
        var sub = new TomatoSubscriptionGroup();
        sub.setId(8L);
        sub.setEnable(true);
        when(subscriptions.findByGroupIdAndBookId(1, "book")).thenReturn(Optional.of(sub));
        var jobs = new BookSubscriptionJobs(books, subscriptions, client, updates);
        try {
            var receipt = jobs.submit(1, "book", 2, false);
            assertTrue(receipt.path("done").asBoolean());
            assertEquals(8, receipt.path("subscription_id").asInt());
            assertTrue(receipt.path("already_subscribed").asBoolean());
            verifyNoInteractions(books, client, updates);
            verify(subscriptions, never()).addSubscription(any());
        } finally { jobs.close(); }
    }

    @Test
    void missingBookDoesNotReportSuccessfulSubscription() throws Exception {
        var jobs = new BookSubscriptionJobs(books, subscriptions, client, updates);
        try {
            var result = awaitDone(jobs, jobs.submit(1, "book", 2, false).path("job_id").asText());
            assertEquals("failed", result.path("status").asText());
            assertFalse(result.path("ok").asBoolean());
            verify(subscriptions, never()).addSubscription(any());
            verifyNoInteractions(updates);
        } finally { jobs.close(); }
    }
}
