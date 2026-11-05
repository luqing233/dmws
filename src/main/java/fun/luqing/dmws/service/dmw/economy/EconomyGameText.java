package fun.luqing.dmws.service.dmw.economy;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class EconomyGameText {
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("M月d日 HH:mm");
    private static final DateTimeFormatter FULL_DATE = DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm");
    private EconomyGameText() { }

    public static String time(LocalDateTime value, LocalDateTime now) {
        if (value == null) return "待确认";
        if (value.toLocalDate().equals(now.toLocalDate())) return "今天 " + value.format(CLOCK);
        if (value.toLocalDate().equals(now.toLocalDate().plusDays(1))) return "明天 " + value.format(CLOCK);
        return value.format(value.getYear() == now.getYear() ? DATE : FULL_DATE);
    }

    public static String injury(int score) {
        return score <= 30 ? "轻伤" : score <= 60 ? "中伤" : "重伤";
    }
}
