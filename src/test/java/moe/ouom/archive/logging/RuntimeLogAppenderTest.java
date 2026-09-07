package moe.ouom.archive.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeLogAppenderTest {
    @Test void boundsHistoryAndReturnsNewestMatchingEntries() {
        var appender = new RuntimeLogAppender();
        appender.start();
        for (int i=0;i<1100;i++) {
            var event = new LoggingEvent();
            event.setLevel(Level.INFO); event.setLoggerName("bounded-test");
            event.setMessage("bounded-marker " + i); event.setTimeStamp(i);
            appender.doAppend(event);
        }
        var rows = RuntimeLogAppender.recent("INFO", "bounded-marker");
        assertEquals(500, rows.size());
        assertEquals("bounded-marker 1099", rows.getFirst().message());
        assertTrue(RuntimeLogAppender.recent("ALL", "bounded-marker 0").isEmpty());
        appender.stop();
    }
    @Test void redactsCredentialsAndResourceUrls() {
        String text = RuntimeLogAppender.redact("MUSIC_U=abc; token=def https://example.com/signed\nCookie: secret\nAuthorization: Bearer secret");
        assertFalse(text.contains("abc")); assertFalse(text.contains("def"));
        assertFalse(text.contains("example.com")); assertFalse(text.contains("secret"));
    }
}
