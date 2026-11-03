package fun.luqing.dmws.service.tomatoservice;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class TomatoBookChartService {

    private final TomatoBookContentService tomatoBookContentService;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final Font BASE_FONT = loadHarmonyFont();
    private static final double TARGET_CHAPTERS = 10.0;
    private static final double TARGET_WORDS = 30000.0;
    private static final double NORMALIZE_LOW_PERCENTILE = 0.05;
    private static final double NORMALIZE_HIGH_PERCENTILE = 0.95;
    private static final double LEVEL_1_MAX = 0.25;
    private static final double LEVEL_2_MAX = 0.50;
    private static final double LEVEL_3_MAX = 0.75;
    private static final Color BACKGROUND = new Color(13, 17, 23);
    private static final Color TITLE_COLOR = new Color(240, 246, 252);
    private static final Color MUTED_COLOR = new Color(139, 148, 158);

    public byte[] generateBookUpdateHeatmap(String bookId, String bookName) {
        // 图表统计依赖正文字数，缺失时先补齐再生成，避免空正文被误计为有效更新。
        tomatoBookContentService.ensureWordCountsForChart(bookId);
        List<Object[]> rows = tomatoBookContentService.findStatsLast365Days(bookId);

        Map<LocalDate, DayStats> statsByDate = new HashMap<>();
        for (Object[] r : rows) {
            LocalDate date = parseDate(String.valueOf(r[0]));
            int chapterCount = ((Number) r[1]).intValue();
            int wordCount = ((Number) r[2]).intValue();
            statsByDate.put(date, new DayStats(date, chapterCount, wordCount));
        }

        LocalDate endDate = statsByDate.keySet().stream().max(LocalDate::compareTo).orElse(LocalDate.now());
        LocalDate startDate = endDate.minusDays(364);

        List<DayStats> days = new ArrayList<>();
        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            days.add(statsByDate.getOrDefault(date, new DayStats(date, 0, 0)));
        }

        BufferedImage image = renderGithubHeatmap(bookName, startDate, endDate, days);
        String filename = "tomato_heatmap_" + bookId + "_" + System.currentTimeMillis() + ".png";

        try {
            Path imageDir = Path.of("temp/images").toAbsolutePath().normalize();
            Files.createDirectories(imageDir);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(image, "png", output);
            Files.write(imageDir.resolve(filename), output.toByteArray());
            return output.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("生成更新热力图失败", e);
        }
    }

    /**
     * 按 GitHub 贡献图的 53 周 × 7 天结构渲染。
     */
    private BufferedImage renderGithubHeatmap(String bookName, LocalDate startDate, LocalDate endDate,
                                               List<DayStats> days) {
        int width = 1000;
        int height = 340;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        g.setColor(BACKGROUND);
        g.fillRect(0, 0, width, height);

        int left = 88;
        int top = 150;
        int cellSize = 12;
        int gap = 4;
        int rows = 7;
        int totalChapters = days.stream().mapToInt(DayStats::chapterCount).sum();
        int totalWords = days.stream().mapToInt(DayStats::wordCount).sum();

        LocalDate gridStart = startDate.with(DayOfWeek.MONDAY);
        int columns = (int) ChronoUnit.WEEKS.between(gridStart, endDate) + 1;
        columns = Math.max(1, Math.min(53, columns));

        drawText(g, "《" + bookName + "》近一年更新热力图", 52, 52, 24, true, TITLE_COLOR);
        drawText(g, "共更新 " + totalChapters + " 章 · " + totalWords + " 字 · "
                        + startDate.format(DATE_FORMATTER) + " 至 " + endDate.format(DATE_FORMATTER),
                52, 94, 14, false, MUTED_COLOR);

        Font labelFont = font(Font.PLAIN, 11);
        g.setFont(labelFont);
        g.setColor(MUTED_COLOR);
        drawWeekLabel(g, "Mon", left - 40, top, cellSize, gap);
        drawWeekLabel(g, "Wed", left - 40, top, cellSize, gap);
        drawWeekLabel(g, "Fri", left - 40, top, cellSize, gap);

        drawMonthLabels(g, gridStart, columns, left, top, cellSize, gap);

        Map<LocalDate, Integer> levels = buildIntensityLevels(days);
        for (DayStats day : days) {
            int column = (int) ChronoUnit.WEEKS.between(gridStart, day.date());
            if (column < 0 || column >= columns) {
                continue;
            }
            int row = day.date().getDayOfWeek().getValue() - 1;
            int x = left + column * (cellSize + gap);
            int y = top + row * (cellSize + gap);
            drawCell(g, x, y, cellSize, levels.getOrDefault(day.date(), 0));
        }

        int gridRight = left + columns * (cellSize + gap);
        drawLegend(g, gridRight, height - 52);
        g.dispose();
        return image;
    }

    private void drawCell(Graphics2D g, int x, int y, int size, int level) {
        RoundRectangle2D rect = new RoundRectangle2D.Float(x, y, size, size, 3, 3);
        g.setColor(cellColor(level));
        g.fill(rect);
    }

    private void drawLegend(Graphics2D g, int right, int y) {
        int swatch = 10;
        int gap = 3;
        int textGap = 8;
        String less = "Less";
        String more = "More";

        g.setFont(font(Font.PLAIN, 12));
        FontMetrics metrics = g.getFontMetrics();
        int legendWidth = metrics.stringWidth(less) + textGap
                + 5 * swatch + 4 * gap
                + textGap + metrics.stringWidth(more);
        int cursor = right - legendWidth;
        int baseline = y + 10;

        g.setColor(MUTED_COLOR);
        g.drawString(less, cursor, baseline);
        cursor += metrics.stringWidth(less) + textGap;

        for (int i = 0; i < 5; i++) {
            g.setColor(cellColor(i));
            g.fillRoundRect(cursor, y, swatch, swatch, 2, 2);
            cursor += swatch + gap;
        }

        cursor += textGap - gap;
        g.setColor(MUTED_COLOR);
        g.drawString(more, cursor, baseline);
    }

    private void drawWeekLabel(Graphics2D g, String label, int x, int top, int cellSize, int gap) {
        int row = switch (label) {
            case "Mon" -> 0;
            case "Wed" -> 2;
            default -> 4;
        };
        g.drawString(label, x, top + row * (cellSize + gap) + 10);
    }

    private void drawMonthLabels(Graphics2D g, LocalDate gridStart, int columns,
                                 int left, int top, int cellSize, int gap) {
        g.setFont(font(Font.PLAIN, 11));
        g.setColor(MUTED_COLOR);
        int previousMonth = -1;
        int lastLabelColumn = -10;
        for (int column = 0; column < columns; column++) {
            LocalDate weekDate = gridStart.plusWeeks(column);
            int month = weekDate.getMonthValue();
            if (month != previousMonth && column - lastLabelColumn >= 3) {
                int x = left + column * (cellSize + gap);
                g.drawString(month + "月", x, top - 14);
                lastLabelColumn = column;
                previousMonth = month;
            } else if (month != previousMonth) {
                previousMonth = month;
            }
        }
    }

    private Map<LocalDate, Integer> buildIntensityLevels(List<DayStats> days) {
        List<Double> activeScores = days.stream()
                .filter(day -> day.chapterCount() > 0 && day.wordCount() > 0)
                .map(this::rawScore)
                .sorted()
                .toList();

        double p05 = percentile(activeScores, NORMALIZE_LOW_PERCENTILE);
        double p95 = percentile(activeScores, NORMALIZE_HIGH_PERCENTILE);
        double range = p95 - p05;

        Map<LocalDate, Integer> levels = new HashMap<>();
        for (DayStats day : days) {
            if (day.chapterCount() <= 0 || day.wordCount() <= 0) {
                levels.put(day.date(), 0);
                continue;
            }
            double normalized = range <= 1e-9
                    ? 0.5
                    : Math.max(0.0, Math.min(1.0, (rawScore(day) - p05) / range));
            levels.put(day.date(), normalizedLevel(normalized));
        }
        return levels;
    }

    private int normalizedLevel(double normalized) {
        if (normalized <= 0) {
            return 0;
        }
        if (normalized < LEVEL_1_MAX) {
            return 1;
        }
        if (normalized < LEVEL_2_MAX) {
            return 2;
        }
        if (normalized < LEVEL_3_MAX) {
            return 3;
        }
        return 4;
    }

    /**
     * 对数加权综合值：章节数占 55%，字数占 45%。
     */
    private double rawScore(DayStats day) {
        double chapterScore = Math.min(day.chapterCount() / TARGET_CHAPTERS, 1.0);
        double wordScore = Math.min(
                Math.log1p(day.wordCount()) / Math.log1p(TARGET_WORDS), 1.0);
        return 0.55 * chapterScore + 0.45 * wordScore;
    }

    private double percentile(List<Double> sortedValues, double percentile) {
        if (sortedValues.isEmpty()) {
            return 0;
        }
        double index = (sortedValues.size() - 1) * percentile;
        int lower = (int) Math.floor(index);
        int upper = (int) Math.ceil(index);
        if (lower == upper) {
            return sortedValues.get(lower);
        }
        double fraction = index - lower;
        return sortedValues.get(lower)
                + (sortedValues.get(upper) - sortedValues.get(lower)) * fraction;
    }

    private Color cellColor(int level) {
        return switch (level) {
            case 0 -> new Color(22, 27, 34);
            case 1 -> new Color(14, 68, 41);
            case 2 -> new Color(0, 109, 50);
            case 3 -> new Color(38, 166, 65);
            default -> new Color(57, 211, 83);
        };
    }

    private void drawText(Graphics2D g, String text, int x, int y, int size, boolean bold, Color color) {
        g.setFont(font(bold ? Font.BOLD : Font.PLAIN, size));
        g.setColor(color);
        g.drawString(text, x, y);
    }

    private static Font font(int style, int size) {
        return BASE_FONT.deriveFont(style, (float) size);
    }

    /**
     * 打包 HarmonyOS Sans SC，避免服务器或容器缺少中文字体时出现方框。
     */
    private static Font loadHarmonyFont() {
        try (InputStream input = TomatoBookChartService.class.getResourceAsStream(
                "/fonts/HarmonyOS_Sans_SC_Regular.ttf")) {
            if (input != null) {
                return Font.createFont(Font.TRUETYPE_FONT, input);
            }
        } catch (Exception ignored) {
            // 字体资源异常时退回逻辑字体，保证图表仍能生成
        }
        return new Font(Font.SANS_SERIF, Font.PLAIN, 12);
    }

    private LocalDate parseDate(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.length() >= 10) {
            value = value.substring(0, 10);
        }
        return LocalDate.parse(value, DATE_FORMATTER);
    }

    private record DayStats(LocalDate date, int chapterCount, int wordCount) {
    }
}
