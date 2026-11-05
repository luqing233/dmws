package fun.luqing.dmws.mcp;

import fun.luqing.dmws.config.DmwConfig;
import fun.luqing.dmws.entity.dmw.tomato.TomatoBookContent;
import fun.luqing.dmws.entity.dmw.tomato.TomatoBookList;
import fun.luqing.dmws.service.dmw.tomato.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TomatoMcpToolsTests {
    @Test
    void statisticsExposeCharacterCountsWithoutBreakingLegacyFields() throws Exception {
        var mapper = new ObjectMapper();
        var content = mock(TomatoBookContentService.class);
        when(content.findStatsLast365Days("book")).thenReturn(List.<Object[]>of(
                new Object[]{"2026-11-04", 3L, 6222L, 0L}));
        var adapter = new TomatoMcpTools(new DmwConfig(), mock(TomatoBookListService.class), content,
                mock(TomatoSubscriptionGroupService.class), mock(TomatoBookChartService.class),
                mock(BookUpdateJobs.class), mock(BookSubscriptionJobs.class), mock(TomatoReadingService.class));
        var blocks = mapper.createArrayNode();
        adapter.execute("dmw_get_update_stats", mapper.createObjectNode().put("book_id", "book"), blocks);
        var result = mapper.readTree(blocks.get(0).path("text").asText());
        assertEquals(6222, result.path("total_characters").asLong());
        assertEquals(result.path("total_words"), result.path("total_characters"));
        assertEquals(result.path("days").get(0).path("word_count"),
                result.path("days").get(0).path("character_count"));
        assertEquals("body_utf16_characters", result.path("count_metric").asText());
    }

    @Test
    void heatmapDoesNotWaitForSlowChapterRequestAndReturnsImageWithCoverage() throws Exception {
        var mapper = new ObjectMapper();
        var books = mock(TomatoBookListService.class);
        var content = mock(TomatoBookContentService.class);
        var chart = mock(TomatoBookChartService.class);
        var reading = mock(TomatoReadingService.class);
        when(reading.read(anyString(), anyList(), anyBoolean(), anyInt(), anyInt())).thenReturn(mapper.createObjectNode().put("status","ready").set("chapters", mapper.createArrayNode()));
        var adapter = new TomatoMcpTools(new DmwConfig(), books, content,
                mock(TomatoSubscriptionGroupService.class), chart,
                mock(BookUpdateJobs.class), mock(BookSubscriptionJobs.class), reading);
        var book = new TomatoBookList();
        book.setBookId("chart");
        book.setBookName("测试图");
        when(books.findByBookId("chart")).thenReturn(Optional.of(book));
        when(chart.generateHeatmap("chart", "测试图"))
                .thenReturn(new TomatoBookChartService.HeatmapResult(new byte[]{1, 2, 3}, 2));
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(content.findAllByBookIdOrderByRealChapterOrderAsc("slow")).thenAnswer(call -> {
            started.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            var chapter = new TomatoBookContent();
            chapter.setChapterId("chapter");
            chapter.setContent("cached content");
            return List.of(chapter);
        });
        try (var executor = Executors.newFixedThreadPool(2)) {
            var slow = executor.submit(() -> adapter.execute("dmw_get_chapter_content",
                    mapper.createObjectNode().put("book_id", "slow").put("chapter_id", "chapter"),
                    mapper.createArrayNode()));
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS));
                var future = executor.submit(() -> {
                    var blocks = mapper.createArrayNode();
                    adapter.execute("dmw_get_update_heatmap", mapper.createObjectNode().put("book_id", "chart"), blocks);
                    return blocks;
                });
                var blocks = future.get(2, TimeUnit.SECONDS);
                var summary = mapper.readTree(blocks.get(0).path("text").asText());
                assertFalse(summary.path("data_complete").asBoolean());
                assertEquals(2, summary.path("missing_word_count_chapters").asInt());
                assertEquals("image", blocks.get(1).path("type").asText());
                assertEquals("AQID", blocks.get(1).path("data").asText());
            } finally { release.countDown(); }
            slow.get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void subscriptionJobsUseExistingProgressTool() {
        var mapper = new ObjectMapper();
        var subscriptions = mock(BookSubscriptionJobs.class);
        when(subscriptions.get("sub-test")).thenReturn(mapper.createObjectNode().put("done", true).put("subscription_id", 8));
        var updates = mock(BookUpdateJobs.class);
        var adapter = new TomatoMcpTools(new DmwConfig(), mock(TomatoBookListService.class),
                mock(TomatoBookContentService.class), mock(TomatoSubscriptionGroupService.class),
                mock(TomatoBookChartService.class), updates, subscriptions, mock(TomatoReadingService.class));
        var blocks = mapper.createArrayNode();
        adapter.execute("dmw_get_update_job", mapper.createObjectNode().put("job_id", "sub-test"), blocks);
        assertTrue(blocks.get(0).path("text").asText().contains("subscription_id"));
        verifyNoInteractions(updates);
    }
}
