package fun.luqing.dmws.service.dmw.tomato;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.ArrayList;
import java.awt.image.BufferedImage;
import java.time.DayOfWeek;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TomatoBookChartServiceTests {
    @TempDir
    Path temporary;

    @Test
    void returnsPngFromCachedStatisticsWithoutDownloadingChapters() throws Exception {
        var content = mock(TomatoBookContentService.class);
        when(content.findStatsLast365Days("book")).thenReturn(List.<Object[]>of(
                new Object[]{LocalDate.now().toString(), 3L, 6000L, 0L}));
        var result = new TomatoBookChartService(content, temporary.resolve("images"))
                .generateHeatmap("book", "摇曳同行");
        var image = ImageIO.read(new ByteArrayInputStream(result.image()));
        assertNotNull(image);
        assertEquals(1000, image.getWidth());
        assertEquals(292, image.getHeight());
        assertEquals(0xff0d1117, image.getRGB(0, 0));
        assertTrue(result.dataComplete());
        verify(content, never()).ensureWordCountsForChart(anyString());
    }

    @Test
    void cacheIoFailureDoesNotDiscardImageAndReportsIncompleteStatistics() throws Exception {
        var content = mock(TomatoBookContentService.class);
        when(content.findStatsLast365Days("book")).thenReturn(List.<Object[]>of(
                new Object[]{LocalDate.now().toString(), 3L, 2000L, 2L}));
        Path blocked = temporary.resolve("not-a-directory");
        Files.writeString(blocked, "keep");
        var result = new TomatoBookChartService(content, blocked).generateHeatmap("book", "测试书籍");
        assertNotNull(ImageIO.read(new ByteArrayInputStream(result.image())));
        assertFalse(result.dataComplete());
        assertEquals(2, result.missingWordCountChapters());
        assertEquals("keep", Files.readString(blocked));
        verify(content, never()).ensureWordCountsForChart(anyString());
    }

    @Test
    void activeDatesIncludingMissingCharacterCountsNeverUseInactiveColor() {
        var renderer = new TomatoBookChartService(mock(TomatoBookContentService.class), temporary);
        LocalDate day = LocalDate.of(2026, 10, 12);
        var levels = renderer.buildIntensityLevels(List.of(
                new TomatoBookChartService.DayStats(day, 9, 18649, 0),
                new TomatoBookChartService.DayStats(day.plusDays(1), 3, 6166, 0),
                new TomatoBookChartService.DayStats(day.plusDays(2), 2, 4163, 0),
                new TomatoBookChartService.DayStats(day.plusDays(3), 3, 0, 3),
                new TomatoBookChartService.DayStats(day.plusDays(4), 0, 0, 0)));
        for (int offset = 0; offset < 4; offset++) assertTrue(levels.get(day.plusDays(offset)) >= 1);
        assertEquals(0, levels.get(day.plusDays(4)));
        assertTrue(levels.get(day) >= levels.get(day.plusDays(1)));
        assertTrue(levels.get(day.plusDays(1)) >= levels.get(day.plusDays(2)));
    }

    @Test
    void uniformAndEmptyActivityRemainDeterministic() {
        var renderer = new TomatoBookChartService(mock(TomatoBookContentService.class), temporary);
        LocalDate day = LocalDate.of(2026, 11, 4);
        assertTrue(renderer.buildIntensityLevels(List.of()).isEmpty());
        assertEquals(1, renderer.buildIntensityLevels(List.of(
                new TomatoBookChartService.DayStats(day, 3, 6000, 0))).get(day));
        var uniform = renderer.buildIntensityLevels(List.of(
                new TomatoBookChartService.DayStats(day, 1, 6000, 0),
                new TomatoBookChartService.DayStats(day.plusDays(1), 10, 6000, 0)));
        assertEquals(1, uniform.get(day));
        assertEquals(uniform.get(day), uniform.get(day.plusDays(1)));
        assertEquals(0, renderer.buildIntensityLevels(List.of(
                new TomatoBookChartService.DayStats(day, 0, 0, 0))).get(day));
    }

    @Test
    void characterQuartilesKeepEqualTotalsTogetherRegardlessOfChapterCount() {
        var renderer = new TomatoBookChartService(mock(TomatoBookContentService.class), temporary);
        LocalDate start = LocalDate.of(2026, 10, 1);
        var days = new ArrayList<TomatoBookChartService.DayStats>();
        long[] characters = {1000, 1000, 2000, 2000, 3000, 3000, 4000, 4000};
        for (int index = 0; index < characters.length; index++) {
            days.add(new TomatoBookChartService.DayStats(start.plusDays(index), index + 1, characters[index], 0));
        }
        var levels = renderer.buildIntensityLevels(days);
        for (int index = 0; index < characters.length; index++) {
            assertEquals(index / 2 + 1, levels.get(start.plusDays(index)));
        }
    }

    @Test
    void exactQuartileBoundariesBelongToTheLowerBand() {
        var renderer = new TomatoBookChartService(mock(TomatoBookContentService.class), temporary);
        LocalDate start = LocalDate.of(2026, 10, 1);
        var days = new ArrayList<TomatoBookChartService.DayStats>();
        for (int index = 0; index < 5; index++) {
            days.add(new TomatoBookChartService.DayStats(start.plusDays(index), 1, (index + 1) * 1000L, 0));
        }
        var levels = renderer.buildIntensityLevels(days);
        int[] expected = {1, 1, 2, 3, 4};
        for (int index = 0; index < expected.length; index++) {
            assertEquals(expected[index], levels.get(start.plusDays(index)));
        }
    }

    @Test
    void inactiveAndIncompleteDaysDoNotSkewCharacterQuartiles() {
        var renderer = new TomatoBookChartService(mock(TomatoBookContentService.class), temporary);
        LocalDate start = LocalDate.of(2026, 10, 1);
        var complete = List.of(
                new TomatoBookChartService.DayStats(start, 1, 1000, 0),
                new TomatoBookChartService.DayStats(start.plusDays(1), 1, 2000, 0),
                new TomatoBookChartService.DayStats(start.plusDays(2), 1, 3000, 0),
                new TomatoBookChartService.DayStats(start.plusDays(3), 1, 1000000, 0));
        var mixed = new ArrayList<>(complete);
        mixed.add(new TomatoBookChartService.DayStats(start.plusDays(4), 0, 500000, 0));
        mixed.add(new TomatoBookChartService.DayStats(start.plusDays(5), 1, 0, 1));
        mixed.add(new TomatoBookChartService.DayStats(start.plusDays(6), 2, 9000000, 1));
        var expected = renderer.buildIntensityLevels(complete);
        var actual = renderer.buildIntensityLevels(mixed);
        expected.forEach((date, level) -> assertEquals(level, actual.get(date)));
        assertEquals(4, actual.get(start.plusDays(3)));
        assertEquals(0, actual.get(start.plusDays(4)));
        assertEquals(1, actual.get(start.plusDays(5)));
        assertEquals(1, actual.get(start.plusDays(6)));
    }

    @Test
    void longTitlesFitTheirHeaderWithoutSplittingSurrogatePairs() {
        var graphics = new BufferedImage(1000, 300, BufferedImage.TYPE_INT_ARGB).createGraphics();
        try {
            String longName = "测试书名很长😀".repeat(60);
            var fitted = TomatoBookChartService.fitText(graphics, longName, 660, 22, 16);
            assertTrue(graphics.getFontMetrics(fitted.font()).stringWidth(fitted.text()) <= 660);
            assertTrue(fitted.text().endsWith("…"));
            assertFalse(Character.isHighSurrogate(fitted.text().charAt(fitted.text().length() - 2)));
            var shortTitle = TomatoBookChartService.fitText(graphics, "摇曳同行", 660, 22, 16);
            assertEquals("摇曳同行", shortTitle.text());
            assertEquals(22, shortTitle.font().getSize());
        } finally { graphics.dispose(); }
    }

    @Test
    void auditedBookTotalsAndPreviouslyHiddenDaysRenderCorrectly() throws Exception {
        var mapper = new ObjectMapper();
        var fixture = mapper.readTree(getClass().getResourceAsStream("/tomato/heatmap-audit.json"));
        var rows = new ArrayList<Object[]>();
        int chapterCount = 0;
        long characterCount = 0;
        for (var day : fixture.path("daily")) {
            rows.add(new Object[]{day.path("date").asText(), day.path("chapters").asInt(),
                    day.path("characters").asLong(), 0});
            chapterCount += day.path("chapters").asInt();
            characterCount += day.path("characters").asLong();
        }
        assertEquals(80, chapterCount);
        assertEquals(165792, characterCount);
        var content = mock(TomatoBookContentService.class);
        when(content.findStatsLast365Days("book")).thenReturn(rows);
        var result = new TomatoBookChartService(content, temporary).generateHeatmap("book", fixture.path("book_name").asText());
        var image = ImageIO.read(new ByteArrayInputStream(result.image()));
        int[] palette = {0xff161b22, 0xff0e4429, 0xff006d32, 0xff26a641, 0xff39d353};
        for (int level = 0; level < palette.length; level++) {
            assertEquals(palette[level], image.getRGB(841 + level * 17, 251));
        }
        LocalDate gridStart = LocalDate.of(2025, 11, 5).with(DayOfWeek.MONDAY);
        int inactive = pixelForDate(image, gridStart, LocalDate.of(2026, 10, 1));
        for (String active : List.of("2026-10-15", "2026-10-16")) {
            assertNotEquals(inactive, pixelForDate(image, gridStart, LocalDate.parse(active)));
            assertEquals(0xff0e4429, pixelForDate(image, gridStart, LocalDate.parse(active)));
        }
        assertEquals(0xff006d32, pixelForDate(image, gridStart, LocalDate.of(2026, 11, 1)));
        assertEquals(0xff26a641, pixelForDate(image, gridStart, LocalDate.of(2026, 11, 4)));
        assertEquals(0xff39d353, pixelForDate(image, gridStart, LocalDate.of(2026, 10, 12)));
        String preview = System.getProperty("dmws.heatmap.preview");
        if (preview != null) {
            Path output = Path.of(preview);
            Files.createDirectories(output.toAbsolutePath().getParent());
            Files.write(output, result.image());
        }
    }

    private int pixelForDate(BufferedImage image, LocalDate gridStart, LocalDate date) {
        int column = (int) java.time.temporal.ChronoUnit.WEEKS.between(gridStart, date);
        int row = date.getDayOfWeek().getValue() - 1;
        return image.getRGB(TomatoBookChartService.GRID_LEFT + column * TomatoBookChartService.CELL_STEP + 7,
                TomatoBookChartService.GRID_TOP + row * TomatoBookChartService.CELL_STEP + 7);
    }
}
