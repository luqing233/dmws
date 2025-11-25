package fun.luqing.dmws.plugin.tomatoplugin.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class DateUtil {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static boolean earlierOrEqual(String t1, String t2) {
        return !LocalDateTime.parse(t1, FMT).isAfter(LocalDateTime.parse(t2, FMT));
    }
}
