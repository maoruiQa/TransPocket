package nie.translator.rtranslator.voice_translation.neural_networks.voice;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ClauseAudioStartTest {
    private static final int LENGTH = 480000;
    /** Default voice-anticipation setting: 1300 ms at 16 kHz. */
    private static final int PAD = 1300 * ClauseVadSegmenter.SAMPLE_RATE / 1000;
    /** The old start, which kept only the three confirming frames. */
    private static final int ONSET = ClauseVadSegmenter.ONSET_FRAMES * ClauseVadSegmenter.FRAME_SAMPLES;

    @Test
    public void firstClauseKeepsAudioBeforeTheVadConfirms() {
        int end = 40000;
        int start = ClauseAudioStart.index(LENGTH, end, PAD, 0, -1);
        assertEquals(end - PAD, start);
        assertTrue(start < end - ONSET);
    }

    @Test
    public void shortRecordingKeepsEverythingStillInTheBuffer() {
        int end = 4000;
        assertEquals(0, ClauseAudioStart.index(LENGTH, end, PAD, 0, -1));
    }

    @Test
    public void nextClauseStopsAtThePreviousEmit() {
        int boundary = 10000;
        int end = boundary + 6000;
        int start = ClauseAudioStart.index(LENGTH, end, PAD, 0, boundary);
        assertEquals(boundary, start);
    }

    @Test
    public void longGapUsesThePadInsteadOfTheOldClause() {
        int boundary = 1000;
        int end = boundary + PAD + 8000;
        int start = ClauseAudioStart.index(LENGTH, end, PAD, 0, boundary);
        assertEquals(end - PAD, start);
    }

    @Test
    public void rewindWrapsAcrossTheBufferEnd() {
        int length = 1000;
        int head = 800;
        int end = 100;
        int start = ClauseAudioStart.index(length, end, 250, head, -1);
        assertEquals(850, start);
    }

    @Test
    public void wrappedBoundaryIsNotCrossed() {
        int length = 1000;
        int head = 800;
        int boundary = 900;
        int end = 100;
        int start = ClauseAudioStart.index(length, end, 250, head, boundary);
        assertEquals(boundary, start);
    }

    @Test
    public void overwrittenBoundaryIsIgnored() {
        int length = 1000;
        int head = 400;
        int end = 700;
        int staleBoundary = 100;
        int start = ClauseAudioStart.index(length, end, 100, head, staleBoundary);
        assertEquals(600, start);
    }
}
