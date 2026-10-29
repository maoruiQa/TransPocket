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
    public void historyOnlyGrowsUntilItIsCutBack() {
        ClauseContextWindow window = new ClauseContextWindow();
        for (int i = 0; i < ClauseContextWindow.MAX_HISTORY_CLAUSES; i++) {
            window.add("s" + i, "t" + i, "a" + i, ClauseConfidence.HIGH, i * 1000L);
            List<ClauseContextWindow.Entry> history = window.history(i * 1000L);
            assertEquals(i + 1, history.size());
            assertEquals("s0", history.get(0).source);
            assertEquals("a" + i, history.get(i).answer);
        }
        long now = ClauseContextWindow.MAX_HISTORY_CLAUSES * 1000L;
        window.add("last", "ultimo", "answer", ClauseConfidence.HIGH, now);
        List<ClauseContextWindow.Entry> history = window.history(now);
        assertEquals(ClauseContextWindow.KEEP_HISTORY_CLAUSES, history.size());
        assertEquals("last", history.get(history.size() - 1).source);
        assertEquals(ClauseContextWindow.MAX_CLAUSES, window.trusted(now).size());
    }

    @Test
    public void historyCharacterBudgetCutsBack() {
        ClauseContextWindow window = new ClauseContextWindow();
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < ClauseContextWindow.MAX_HISTORY_CHARS / 3; i++) {
            text.append('a');
        }
        for (int i = 0; i < 4; i++) {
            window.add(text.toString() + i, "", ClauseConfidence.HIGH, i * 1000L);
        }
        List<ClauseContextWindow.Entry> history = window.history(4000);
        assertEquals(ClauseContextWindow.KEEP_HISTORY_CLAUSES, history.size());
        assertTrue(history.get(1).source.endsWith("3"));
    }

    @Test
    public void historyKeepsDoubtfulAndDropsLow() {
        ClauseContextWindow window = new ClauseContextWindow();
        window.add("first", "primo", ClauseConfidence.HIGH, 0);
        window.add("doubtful", "dubbio", ClauseConfidence.MEDIUM, 1000);
        window.add("garbled", "x", ClauseConfidence.LOW, 2000);
        assertEquals(2, window.history(3000).size());
        window.clear();
        assertTrue(window.history(3000).isEmpty());
        window.add("again", "ancora", ClauseConfidence.HIGH, 4000);
        assertEquals("again", window.history(4000).get(0).source);
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
