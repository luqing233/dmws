package fun.luqing.dmws.service.dmw.tomato;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
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
import java.util.UUID;
import java.util.Locale;

@Service
@Slf4j
public class TomatoBookChartService {

    private final TomatoBookContentService tomatoBookContentService;
    private final Path imageDirectory;

    @Autowired
    public TomatoBookChartService(TomatoBookContentService contentService) {
        this(contentService, Path.of("temp/images"));
    }

    TomatoBookChartService(TomatoBookContentService contentService, Path imageDirectory) {
        this.tomatoBookContentService = contentService;
        this.imageDirectory = imageDirectory;
    }

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final Font BASE_FONT = loadHarmonyFont();
    static final int WIDTH = 1000;
    static final int HEIGHT = 292;
    static final int GRID_LEFT = 76;
    static final int GRID_TOP = 122;
    static final int CELL_SIZE = 12;
    static final int CELL_STEP = 16;
    private static final Color BACKGROUND = new Color(13, 17, 23);
    private static final Color TITLE_COLOR = new Color(240, 246, 252);
    private static final Color MUTED_COLOR = new Color(139, 148, 158);

    public byte[] generateBookUpdateHeatmap(String bookId, String bookName) {
        return generateHeatmap(bookId, bookName).image();
    }

    /** Pure cached-data rendering: never wait on a downloader or a write-task lock. */
    public HeatmapResult generateHeatmap(String bookId, String bookName) {
        List<Object[]> rows = tomatoBookContentService.findStatsLast365Days(bookId);

        Map<LocalDate, DayStats> statsByDate = new HashMap<>();
        for (Object[] r : rows) {
            LocalDate date = parseDate(String.valueOf(r[0]));
            int chapterCount = ((Number) r[1]).intValue();
            long characterCount = ((Number) r[2]).longValue();
            int missing = r.length > 3 && r[3] != null ? ((Number) r[3]).intValue() : 0;
            statsByDate.put(date, new DayStats(date, chapterCount, characterCount, missing));
        }

        LocalDate endDate = statsByDate.keySet().stream().max(LocalDate::compareTo).orElse(LocalDate.now());
        LocalDate startDate = endDate.minusDays(364);

        List<DayStats> days = new ArrayList<>();
        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            days.add(statsByDate.getOrDefault(date, new DayStats(date, 0, 0, 0)));
        }

        BufferedImage image = renderGithubHeatmap(bookName, startDate, endDate, days);
        byte[] png;
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", output)) throw new IOException("PNG encoder unavailable");
            png = output.toByteArray();
        } catch (Exception e) {
            log.error("热力图编码失败: bookId={}", bookId, e);
            throw new IllegalStateException("生成更新热力图失败", e);
        }
        try {
            Files.createDirectories(imageDirectory);
            String safeId = bookId.replaceAll("[^A-Za-z0-9_-]", "_");
            Files.write(imageDirectory.resolve("tomato_heatmap_" + safeId + "_" + UUID.randomUUID() + ".png"), png);
        } catch (IOException | SecurityException e) {
            // The PNG is already available to both QQ and MCP; caching is optional.
            log.warn("热力图缓存保存失败，继续返回已生成图片: bookId={} directory={}", bookId, imageDirectory, e);
        }
        return new HeatmapResult(png, days.stream().mapToInt(DayStats::missingWords).sum());
    }

    /**
     * 按 GitHub 贡献图的 53 周 × 7 天结构渲染。
     */
    private BufferedImage renderGithubHeatmap(String bookName, LocalDate startDate, LocalDate endDate,
                                               List<DayStats> days) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(BACKGROUND);
            g.fillRect(0, 0, WIDTH, HEIGHT);
            String range = startDate.format(DATE_FORMATTER) + " — " + endDate.format(DATE_FORMATTER);
            drawRightAligned(g, range, 964, 36, 12, MUTED_COLOR);
            FittedText title = fitText(g, bookName, 660, 22, 16);
            g.setFont(title.font());
            g.setColor(TITLE_COLOR);
            g.drawString(title.text(), 36, 39);

            int chapters = days.stream().mapToInt(DayStats::chapterCount).sum();
            long characters = days.stream().mapToLong(DayStats::characterCount).sum();
            long activeDays = days.stream().filter(day -> day.chapterCount() > 0).count();
            int missing = days.stream().mapToInt(DayStats::missingWords).sum();
            String summary = number(chapters) + " 章更新   ·   正文字符数 " + number(characters)
                    + "   ·   " + number(activeDays) + " 个更新日";
            if (missing > 0) summary += "   ·   " + missing + " 章字符数暂缺";
            drawText(g, summary, 36, 67, 13, false, MUTED_COLOR);
            g.setColor(new Color(48, 54, 61));
            g.drawRoundRect(24, 86, 951, 181, 8, 8);

            LocalDate gridStart = startDate.with(DayOfWeek.MONDAY);
            int columns = (int) ChronoUnit.WEEKS.between(gridStart, endDate) + 1;
            drawMonthLabels(g, gridStart, columns);
            drawText(g, "周一", 36, GRID_TOP + 11, 11, false, MUTED_COLOR);
            drawText(g, "周三", 36, GRID_TOP + CELL_STEP * 2 + 11, 11, false, MUTED_COLOR);
            drawText(g, "周五", 36, GRID_TOP + CELL_STEP * 4 + 11, 11, false, MUTED_COLOR);
            Map<LocalDate, Integer> levels = buildIntensityLevels(days);
            for (DayStats day : days) {
                int column = (int) ChronoUnit.WEEKS.between(gridStart, day.date());
                int row = day.date().getDayOfWeek().getValue() - 1;
                drawCell(g, GRID_LEFT + column * CELL_STEP, GRID_TOP + row * CELL_STEP,
                        CELL_SIZE, levels.getOrDefault(day.date(), 0));
            }
            drawLegend(g);
        } finally {
            g.dispose();
        }
        return image;
    }

    private static String number(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    private void drawCell(Graphics2D g, int x, int y, int size, int level) {
        g.setColor(cellColor(level));
        g.fill(new RoundRectangle2D.Float(x, y, size, size, 4, 4));
    }

    private void drawLegend(Graphics2D g) {
        int y = 246;
        drawText(g, "少", 814, y + 9, 11, false, MUTED_COLOR);
        for (int level = 0; level <= 4; level++) {
            drawCell(g, 836 + level * 17, y, 10, level);
        }
        drawText(g, "多", 925, y + 9, 11, false, MUTED_COLOR);
    }

    private void drawMonthLabels(Graphics2D g, LocalDate gridStart, int columns) {
        int previousMonth = -1;
        int lastLabelColumn = -10;
        for (int column = 0; column < columns; column++) {
            LocalDate week = gridStart.plusWeeks(column);
            int month = week.getMonthValue();
            if (month != previousMonth && column - lastLabelColumn >= 3) {
                drawText(g, month + "月", GRID_LEFT + column * CELL_STEP, GRID_TOP - 15,
                        11, false, MUTED_COLOR);
                lastLabelColumn = column;
            }
            previousMonth = month;
        }
    }

    private void drawRightAligned(Graphics2D g, String text, int right, int baseline, int size, Color color) {
        g.setFont(font(Font.PLAIN, size));
        g.setColor(color);
        g.drawString(text, right - g.getFontMetrics().stringWidth(text), baseline);
    }

    static FittedText fitText(Graphics2D g, String text, int maxWidth, int initialSize, int minSize) {
        String label = text == null || text.isBlank() ? "未命名书籍" : text;
        int size = initialSize;
        Font selected = font(Font.BOLD, size);
        while (size > minSize && g.getFontMetrics(selected).stringWidth(label) > maxWidth) {
            selected = font(Font.BOLD, --size);
        }
        if (g.getFontMetrics(selected).stringWidth(label) > maxWidth) {
            while (!label.isEmpty() && g.getFontMetrics(selected).stringWidth(label + "…") > maxWidth) {
                label = label.substring(0, label.offsetByCodePoints(label.length(), -1));
            }
            label += "…";
        }
        return new FittedText(label, selected);
    }

    record FittedText(String text, Font font) {}

    Map<LocalDate, Integer> buildIntensityLevels(List<DayStats> days) {
        // Only complete, positive daily totals determine quartiles. Missing counts must
        // not lower the thresholds; equal totals always receive the same color.
        List<Long> activeCharacters = days.stream()
                .filter(day -> day.chapterCount() > 0 && day.characterCount() > 0 && day.missingWords() == 0)
                .map(DayStats::characterCount)
                .sorted()
                .toList();

        double q25 = percentile(activeCharacters, 0.25);
        double q50 = percentile(activeCharacters, 0.50);
        double q75 = percentile(activeCharacters, 0.75);

        Map<LocalDate, Integer> levels = new HashMap<>();
        for (DayStats day : days) {
            if (day.chapterCount() <= 0) {
                levels.put(day.date(), 0);
                continue;
            }
            if (day.characterCount() <= 0 || day.missingWords() > 0) {
                levels.put(day.date(), 1);
                continue;
            }
            long characters = day.characterCount();
            int level = characters <= q25 ? 1 : characters <= q50 ? 2 : characters <= q75 ? 3 : 4;
            levels.put(day.date(), level);
        }
        return levels;
    }

    private double percentile(List<Long> sortedValues, double percentile) {
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

    public record HeatmapResult(byte[] image, int missingWordCountChapters) {
        public boolean dataComplete() { return missingWordCountChapters == 0; }
    }

    record DayStats(LocalDate date, int chapterCount, long characterCount, int missingWords) {
    }
}
