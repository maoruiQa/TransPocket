package nie.translator.rtranslator.voice_translation._live_translation._simultaneous;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class StreamingClauseTest {
    @Test
    public void nothingIsCommittedFromASinglePartial() {
        StreamingClause clause = new StreamingClause();
        assertNull(clause.offer("I arrived yesterday, and the hotel was full.", true));
    }

    @Test
    public void agreedTextIsCommittedAtPunctuation() {
        StreamingClause clause = new StreamingClause();
        assertNull(clause.offer("I arrived yesterday, and the hot", true));
        assertEquals("I arrived yesterday,", clause.offer("I arrived yesterday, and the hotel was", true));
        assertEquals("and the hotel was", clause.uncommitted("I arrived yesterday, and the hotel was"));
        assertEquals("and the hotel was full.", clause.finish("I arrived yesterday, and the hotel was full."));
    }

    @Test
    public void shortPiecesWait() {
        StreamingClause clause = new StreamingClause();
        clause.offer("Yes, I th", true);
        assertNull(clause.offer("Yes, I think so", true));
    }

    @Test
    public void longUnpunctuatedSpeechIsCutAtAWordBoundary() {
        StreamingClause clause = new StreamingClause();
        clause.offer("so we took the train from the city to the old harb", true);
        String piece = clause.offer("so we took the train from the city to the old harbour and", true);
        assertEquals("so we took the train from the city to the old", piece);
    }

    @Test
    public void chineseIsCutOnlyAtPunctuation() {
        StreamingClause clause = new StreamingClause();
        clause.offer("我想去机场。", true);
        assertNull(clause.offer("我想去机场，然后回家。", true));
        assertEquals("我想去机场，", clause.offer("我想去机场，然后回家吃饭。", true));
        assertEquals("然后回家吃饭。", clause.finish("我想去机场，然后回家吃饭。"));
    }

    @Test
    public void decimalPointIsNotABoundary() {
        StreamingClause clause = new StreamingClause();
        clause.offer("The price is 3.5 euros per", true);
        assertNull(clause.offer("The price is 3.5 euros per kilo", true));
    }

    @Test
    public void revisedFinalKeepsOnlyTheNewTail() {
        StreamingClause clause = new StreamingClause();
        clause.offer("We met in Paris, then we", true);
        assertEquals("We met in Paris,", clause.offer("We met in Paris, then we went", true));
        assertEquals("then we went home.", clause.finish("We met in Paris, then we went home."));
        assertFalse(clause.hasCommitted());
    }

    @Test
    public void disabledCommitStillTracksTheDraft() {
        StreamingClause clause = new StreamingClause();
        clause.offer("I arrived yesterday, and", false);
        assertNull(clause.offer("I arrived yesterday, and the", false));
        assertFalse(clause.hasCommitted());
        assertEquals("I arrived yesterday, and the", clause.uncommitted("I arrived yesterday, and the"));
    }

    @Test
    public void rollbackTranslatesTheWholeClauseInThePieceMessage() {
        StreamingClause clause = new StreamingClause();
        clause.offer("We met in Paris, then we", true);
        assertEquals("We met in Paris,", clause.offer("We met in Paris, then we went", true));
        clause.rollback(42);
        assertFalse(clause.hasCommitted());
        assertEquals(42, clause.reuseId);
        assertEquals("We met in Paris, then we went home.", clause.finish("We met in Paris, then we went home."));
        clause.reset();
        assertEquals(-1, clause.reuseId);
    }

    @Test
    public void unspacedScriptsAreDetected() {
        assertTrue(StreamingClause.hasUnspacedScript("今天 Apple 发布"));
        assertFalse(StreamingClause.hasUnspacedScript("Привет, как дела"));
    }
}
