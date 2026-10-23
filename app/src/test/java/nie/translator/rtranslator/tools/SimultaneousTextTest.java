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
    public void takeSpeakableWaitsForASentence() {
        assertNull(SimultaneousText.takeSpeakable("short text", false));
        assertNull(SimultaneousText.takeSpeakable("你好，世界", false));
    }

    @Test
    public void takeSpeakableCutsAtTheFirstSentence() {
        assertEquals("第一句。", SimultaneousText.takeSpeakable("第一句。第二句。第三句。", false));
        assertEquals("第一句。", SimultaneousText.takeSpeakable("第一句。第二句。", true));
        assertEquals("Value is 3.14.", SimultaneousText.takeSpeakable("Value is 3.14. Next.", false));
    }

    @Test
    public void takeSpeakableSpeaksTheRemainderWhenFinal() {
        assertEquals(" tail ", SimultaneousText.takeSpeakable(" tail ", true));
    }

    @Test
    public void takeSpeakableCutsALongUnpunctuatedChunk() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 90; i++) {
            text.append('a');
        }
        assertEquals(SimultaneousText.SPEAK_CHUNK_CHARS, SimultaneousText.takeSpeakable(text.toString(), false).length());
    }

    @Test
    public void takeSpeakableCutsALongSentenceAtTheLastClause() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            text.append('a');
        }
        text.append(',');
        for (int i = 0; i < 50; i++) {
            text.append('b');
        }
        assertEquals(41, SimultaneousText.takeSpeakable(text.toString(), false).length());
    }

    @Test
    public void takeSpeakableKeepsAFinishedSentenceWhole() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            text.append('a');
        }
        text.append(". Next.");
        assertEquals(101, SimultaneousText.takeSpeakable(text.toString(), false).length());
    }

    @Test
    public void takeSpeakableHardCapCutsALongSentence() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            text.append('a');
        }
        text.append(". Next.");
        String piece = SimultaneousText.takeSpeakable(text.toString(), false, SimultaneousText.SPEAK_CHUNK_CHARS, true);
        assertEquals(SimultaneousText.SPEAK_CHUNK_CHARS, piece.length());
        assertEquals(false, SimultaneousText.isAudible("。。！"));
        assertEquals(true, SimultaneousText.isAudible("下一句"));
    }

    @Test
    public void speakablePiecesKeepTheUnfinishedTailUntilFinal() {
        java.util.ArrayList<String> waiting = SimultaneousText.speakablePieces("第一句。第二句。尾巴", false);
        assertEquals(2, waiting.size());
        assertEquals("第一句。", waiting.get(0));
        assertEquals("第二句。", waiting.get(1));
        java.util.ArrayList<String> finished = SimultaneousText.speakablePieces("第一句。第二句。尾巴", true);
        assertEquals(3, finished.size());
        assertEquals("尾巴", finished.get(2));
    }
}
