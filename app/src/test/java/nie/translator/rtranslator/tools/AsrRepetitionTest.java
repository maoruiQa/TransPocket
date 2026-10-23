package nie.translator.rtranslator.tools;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class AsrRepetitionTest {
    @Test
    public void keepsTextThatDoesNotRepeat() {
        assertEquals(4, AsrRepetition.keepCount(new int[]{1, 2, 3, 4}));
    }

    @Test
    public void dropsARepeatedTokenTail() {
        assertEquals(2, AsrRepetition.keepCount(new int[]{1, 2, 5, 5, 5, 5}));
    }

    @Test
    public void dropsARepeatedPhrase() {
        assertEquals(2, AsrRepetition.keepCount(new int[]{1, 2, 8, 9, 8, 9, 8, 9}));
    }

    @Test
    public void repeatedOutputWithNoBodyIsEmpty() {
        assertEquals(0, AsrRepetition.keepCount(new int[]{8, 9, 8, 9, 8, 9}));
    }

    @Test
    public void twoCopiesAreKept() {
        assertEquals(2, AsrRepetition.keepCount(new int[]{1, 1}));
    }

    @Test
    public void nullIsEmpty() {
        assertEquals(0, AsrRepetition.keepCount((int[]) null));
    }
}
