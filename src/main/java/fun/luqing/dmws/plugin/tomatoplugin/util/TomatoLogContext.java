package fun.luqing.dmws.plugin.tomatoplugin.util;

import org.slf4j.Logger;

/**
 * 区分番茄定时任务与手动命令的日志级别。
 */
public final class TomatoLogContext {

    private static final ThreadLocal<Boolean> SCHEDULED = ThreadLocal.withInitial(() -> false);

    private TomatoLogContext() {
    }

    public static void enterScheduled() {
        SCHEDULED.set(true);
    }

    public static void exitScheduled() {
        SCHEDULED.remove();
    }

    public static boolean isScheduled() {
        return Boolean.TRUE.equals(SCHEDULED.get());
    }

    public static void info(Logger log, String format, Object... args) {
        if (isScheduled()) {
            log.debug(format, args);
        } else {
            log.info(format, args);
        }
    }
}
