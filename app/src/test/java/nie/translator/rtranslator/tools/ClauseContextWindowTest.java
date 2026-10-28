package nie.translator.rtranslator.tools;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ClauseContextWindowTest {
    @Test
    public void lowClauseIsDroppedButOlderContextStays() {
        ClauseContextWindow window = new ClauseContextWindow();
        window.add("first", "primo", ClauseConfidence.HIGH, 0);
        window.add("garbled", "x", ClauseConfidence.LOW, 1000);
        assertEquals("first", window.lastTrusted(2000).source);
        assertEquals(1, window.lines(2000).size());
    }

    @Test
    public void mediumClauseIsOnlyInLines() {
        ClauseContextWindow window = new ClauseContextWindow();
        window.add("first", "primo", ClauseConfidence.HIGH, 0);
        window.add("doubtful", "dubbio", ClauseConfidence.MEDIUM, 1000);
        List<ClauseContextWindow.Entry> lines = window.lines(2000);
        assertEquals(2, lines.size());
        assertTrue(lines.get(1).isDoubtful());
        assertEquals(1, window.trusted(2000).size());
        assertEquals("first", window.lastTrusted(2000).source);
    }

    @Test
    public void unknownCountsAsTrusted() {
        ClauseContextWindow window = new ClauseContextWindow();
        window.add("first", "primo", ClauseConfidence.UNKNOWN, 0);
        assertEquals("first", window.lastTrusted(100).source);
    }

    @Test
    public void keepsNewestClausesOldestFirst() {
        ClauseContextWindow window = new ClauseContextWindow();
        for (int i = 0; i < 5; i++) {
            window.add("s" + i, "t" + i, ClauseConfidence.HIGH, i * 1000L);
        }
        List<ClauseContextWindow.Entry> trusted = window.trusted(5000);
        assertEquals(ClauseContextWindow.MAX_CLAUSES, trusted.size());
        assertEquals("s2", trusted.get(0).source);
        assertEquals("s4", trusted.get(2).source);
    }

    @Test
    public void longPauseClearsContext() {
        ClauseContextWindow window = new ClauseContextWindow();
        window.add("first", "primo", ClauseConfidence.HIGH, 0);
        assertNull(window.lastTrusted(ClauseContextWindow.MAX_GAP_MS + 1));
    }

    @Test
    public void runOfDroppedClausesAgesContextOut() {
        ClauseContextWindow window = new ClauseContextWindow();
        window.add("first", "primo", ClauseConfidence.HIGH, 0);
        window.add("noise", "", ClauseConfidence.LOW, 10000);
        window.add("noise", "", ClauseConfidence.LOW, 20000);
        assertTrue(window.lines(20000).isEmpty());
    }

    @Test
    public void characterBudgetKeepsNewest() {
        ClauseContextWindow window = new ClauseContextWindow();
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < ClauseContextWindow.MAX_CHARS; i++) {
            longText.append('a');
        }
        window.add(longText.toString(), "", ClauseConfidence.HIGH, 0);
        window.add("short", "", ClauseConfidence.HIGH, 1000);
        List<ClauseContextWindow.Entry> trusted = window.trusted(2000);
        assertEquals(1, trusted.size());
        assertEquals("short", trusted.get(0).source);
    }

    @Test
    public void oversizedNewestClauseIsStillKept() {
        ClauseContextWindow window = new ClauseContextWindow();
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < ClauseContextWindow.MAX_CHARS * 2; i++) {
            longText.append('a');
        }
        window.add(longText.toString(), "", ClauseConfidence.HIGH, 0);
        assertEquals(1, window.trusted(100).size());
    }
}
