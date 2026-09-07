package moe.ouom.archive.logging;

import ch.qos.logback.core.AppenderBase;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import java.util.*;

/** Bounded, process-local log history. Never exposes a filesystem path supplied by a client. */
public class RuntimeLogAppender extends AppenderBase<ILoggingEvent> {
    public record Entry(long id, long timestamp, String level, String logger, String message) {}
    private static final Deque<Entry> EVENTS = new ArrayDeque<>();
    private static long sequence;
    public static String redact(String value) {
        String clean = value.replaceAll("https?://\\S+", "[资源地址]")
                .replaceAll("(?i)(cookie|authorization)\\s*[:=][^\\r\\n]*", "$1=[已隐藏]")
                .replaceAll("(?i)((?:MUSIC_U|__csrf|password|token|secret|encSecKey)\\s*[\"']?\\s*[:=]\\s*[\"']?)[^\\s,;\"']+", "$1[已隐藏]");
        return clean.substring(0, Math.min(12000, clean.length()));
    }
    @Override protected void append(ILoggingEvent event) {
        String message = event.getFormattedMessage();
        if (event.getThrowableProxy() != null) message += "\n" + ThrowableProxyUtil.asString(event.getThrowableProxy());
        synchronized (EVENTS) {
            EVENTS.addLast(new Entry(++sequence, event.getTimeStamp(), event.getLevel().toString(), event.getLoggerName(), redact(message)));
            while (EVENTS.size() > 1000) EVENTS.removeFirst();
        }
    }
    public static List<Entry> recent(String level, String query) {
        String needle = query.toLowerCase(Locale.ROOT);
        synchronized (EVENTS) {
            return EVENTS.stream().filter(e -> level.equals("ALL") || e.level().equals(level))
                    .filter(e -> (e.logger() + " " + e.message()).toLowerCase(Locale.ROOT).contains(needle))
                    .sorted(Comparator.comparingLong(Entry::id).reversed()).limit(500).toList();
        }
    }
}
