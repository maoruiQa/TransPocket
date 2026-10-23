package nie.translator.rtranslator.tools;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class SimultaneousTextTest {
    @Test
    public void stripOverlapDropsSharedEnding() {
        assertEquals("today", SimultaneousText.stripOverlap("hello world", "world today"));
    }

    @Test
    public void stripOverlapKeepsShortAccidentalPrefix() {
        assertEquals("cat sits", SimultaneousText.stripOverlap("a cat", "cat sits"));
    }

    @Test
    public void stripOverlapDropsTwoWords() {
        assertEquals("a test", SimultaneousText.stripOverlap("hello this is", "this is a test"));
    }

    @Test
    public void stripOverlapOfEntireClauseIsEmpty() {
        assertEquals("", SimultaneousText.stripOverlap("hello world", "hello world"));
    }

    @Test
    public void stripContextTranslationDropsLeadingCopy() {
        assertEquals("next sentence", SimultaneousText.stripContextTranslation("Previous line", "Previous line next sentence"));
    }

    @Test
    public void stripContextTranslationOfExactCopyIsEmpty() {
        assertEquals("", SimultaneousText.stripContextTranslation("Previous line", "Previous line"));
    }

    @Test
    public void stripContextTranslationKeepsUnrelatedOutput() {
        assertEquals("only this", SimultaneousText.stripContextTranslation("something else", "only this"));
    }

    @Test
    public void takeSpeakableWaitsForABoundary() {
        assertNull(SimultaneousText.takeSpeakable("short text", false));
    }

    @Test
    public void takeSpeakableCutsAtPunctuation() {
        assertEquals("你好，", SimultaneousText.takeSpeakable("你好，世界", false));
    }

    @Test
    public void takeSpeakableSpeaksTheRemainderWhenFinal() {
        assertEquals(" tail ", SimultaneousText.takeSpeakable(" tail ", true));
    }

    @Test
    public void takeSpeakableCutsALongUnpunctuatedChunk() {
        assertEquals("1234567890123456", SimultaneousText.takeSpeakable("12345678901234567890", false));
    }
}
