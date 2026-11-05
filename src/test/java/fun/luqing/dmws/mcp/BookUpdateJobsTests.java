package fun.luqing.dmws.mcp;

import fun.luqing.dmws.service.dmw.tomato.TomatoBookContentService;
import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BookUpdateJobsTests {
    @Test
    void returnsImmediatelyReusesActiveJobAndReportsPartialProgress() throws Exception {
        var service = mock(TomatoBookContentService.class);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(call -> {
            java.util.function.Consumer<TomatoBookContentService.UpdateResult> progress = call.getArgument(2);
            progress.accept(new TomatoBookContentService.UpdateResult(10, 2, 0, 5));
            started.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return new TomatoBookContentService.UpdateResult(10, 4, 1, 5);
        }).when(service).updateAllChapters(eq("book"), eq(false), any());
        var jobs = new BookUpdateJobs(service);
        try {
            var job = jobs.submit("book", "书名");
            String id = job.path("job_id").asText();
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertEquals(id, jobs.submit("book", "书名").path("job_id").asText());
            assertEquals(7, jobs.get(id).path("processed").asInt());
            release.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!jobs.get(id).path("done").asBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(jobs.get(id).path("done").asBoolean());
            assertEquals("partial", jobs.get(id).path("status").asText());
            assertEquals(10, jobs.get(id).path("processed").asInt());
            verify(service, times(1)).updateAllChapters(eq("book"), eq(false), any());
            job.put("status", "tampered");
            assertEquals("partial", jobs.get(id).path("status").asText());
            assertThrows(McpToolException.class, () -> jobs.get("missing"));
        } finally {
            release.countDown();
            jobs.close();
        }
    }

    @Test
    void recordsBackgroundFailure() throws Exception {
        var service = mock(TomatoBookContentService.class);
        when(service.updateAllChapters(eq("book"), eq(false), any())).thenThrow(new IllegalStateException("offline"));
        var jobs = new BookUpdateJobs(service);
        try {
            String id = jobs.submit("book", "书名").path("job_id").asText();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!jobs.get(id).path("done").asBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(jobs.get(id).path("done").asBoolean());
            assertEquals("failed", jobs.get(id).path("status").asText());
        } finally { jobs.close(); }
    }
}