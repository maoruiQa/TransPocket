package nie.translator.rtranslator.voice_translation.neural_networks.voice;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ClauseVadSegmenterTest {
    private static final int TIMEOUT_MILLIS = 1300;

    @Test
    public void onsetNeedsThreeSpeechFrames() {
        ClauseVadSegmenter segmenter = new ClauseVadSegmenter();
        ClauseVadSegmenter.FrameDecision first = segmenter.onFrame(0.9f, true, TIMEOUT_MILLIS);
        ClauseVadSegmenter.FrameDecision second = segmenter.onFrame(0.9f, true, TIMEOUT_MILLIS);
        ClauseVadSegmenter.FrameDecision third = segmenter.onFrame(0.9f, true, TIMEOUT_MILLIS);
        assertFalse(first.speechStarted);
        assertFalse(second.speechStarted);
        assertTrue(third.speechStarted);
        assertFalse(third.emitClause);
    }

    @Test
    public void shortPauseDoesNotCut() {
        ClauseVadSegmenter segmenter = speaking();
        ClauseVadSegmenter.FrameDecision pause = feed(segmenter, 9, 0.1f, true, TIMEOUT_MILLIS);
        assertFalse(pause.emitClause);
        assertFalse(pause.endSession);
    }

    @Test
    public void pauseAfterEnoughSpeechCutsAndContinues() {
        ClauseVadSegmenter segmenter = speaking();
        feed(segmenter, 9, 0.1f, true, TIMEOUT_MILLIS);
        ClauseVadSegmenter.FrameDecision cut = segmenter.onFrame(0.1f, true, TIMEOUT_MILLIS);
        assertTrue(cut.emitClause);
        assertFalse(cut.stillVoiced);
        assertFalse(cut.endSession);

        ClauseVadSegmenter.FrameDecision resumed = feed(segmenter, ClauseVadSegmenter.ONSET_FRAMES, 0.9f, true, TIMEOUT_MILLIS);
        assertTrue(resumed.speechStarted);
        assertFalse(resumed.endSession);
    }

    @Test
    public void shortClauseIsNotClosedByAPause() {
        ClauseVadSegmenter segmenter = new ClauseVadSegmenter();
        feed(segmenter, MIN_SPEECH_MINUS_ONE(), 0.9f, true, TIMEOUT_MILLIS);
        ClauseVadSegmenter.FrameDecision pause = feed(segmenter, ClauseVadSegmenter.END_SILENCE_FRAMES, 0.1f, true, TIMEOUT_MILLIS);
        assertFalse(pause.emitClause);
    }

    @Test
    public void longSpeechHitsTheHardCapWhileStillVoiced() {
        ClauseVadSegmenter segmenter = new ClauseVadSegmenter();
        int emits = 0;
        ClauseVadSegmenter.FrameDecision last = ClauseVadSegmenter.FrameDecision.none();
        for (int i = 0; i < 219; i++) {
            last = segmenter.onFrame(0.9f, true, TIMEOUT_MILLIS);
            if (last.emitClause) {
                emits++;
            }
        }
        assertEquals(1, emits);
        assertTrue(last.emitClause);
        assertTrue(last.stillVoiced);
        assertFalse(last.endSession);
        ClauseVadSegmenter.FrameDecision next = segmenter.onFrame(0.9f, true, TIMEOUT_MILLIS);
        assertFalse(next.emitClause);
        assertFalse(next.speechStarted);
    }

    @Test
    public void hysteresisBandKeepsTheCurrentState() {
        ClauseVadSegmenter segmenter = speaking();
        ClauseVadSegmenter.FrameDecision held = feed(segmenter, ClauseVadSegmenter.END_SILENCE_FRAMES, 0.42f, true, TIMEOUT_MILLIS);
        assertFalse(held.emitClause);
        ClauseVadSegmenter.FrameDecision silence = feed(segmenter, ClauseVadSegmenter.END_SILENCE_FRAMES, 0.2f, true, TIMEOUT_MILLIS);
        assertTrue(silence.emitClause);

        ClauseVadSegmenter quiet = new ClauseVadSegmenter();
        ClauseVadSegmenter.FrameDecision band = feed(quiet, ClauseVadSegmenter.ONSET_FRAMES, 0.42f, true, TIMEOUT_MILLIS);
        assertFalse(band.speechStarted);
    }

    @Test
    public void amplitudeFailureIsSilence() {
        ClauseVadSegmenter segmenter = new ClauseVadSegmenter();
        ClauseVadSegmenter.FrameDecision blocked = feed(segmenter, ClauseVadSegmenter.ONSET_FRAMES, 0.99f, false, TIMEOUT_MILLIS);
        assertFalse(blocked.speechStarted);
        assertFalse(blocked.speech);
    }

    @Test
    public void continuedSilenceEndsTheSessionWithoutAnotherClause() {
        ClauseVadSegmenter segmenter = speaking();
        ClauseVadSegmenter.FrameDecision cut = feed(segmenter, ClauseVadSegmenter.END_SILENCE_FRAMES, 0.1f, true, TIMEOUT_MILLIS);
        assertTrue(cut.emitClause);
        int remaining = 41 - ClauseVadSegmenter.END_SILENCE_FRAMES;
        ClauseVadSegmenter.FrameDecision beforeEnd = feed(segmenter, remaining - 1, 0.1f, true, TIMEOUT_MILLIS);
        assertFalse(beforeEnd.endSession);
        ClauseVadSegmenter.FrameDecision end = segmenter.onFrame(0.1f, true, TIMEOUT_MILLIS);
        assertTrue(end.endSession);
        assertFalse(end.emitClause);
        assertFalse(end.emitOnEnd);
    }

    @Test
    public void shortTimeoutEndsAndSendsTheUtterance() {
        ClauseVadSegmenter segmenter = speaking();
        ClauseVadSegmenter.FrameDecision early = feed(segmenter, 3, 0.1f, true, 100);
        assertFalse(early.endSession);
        ClauseVadSegmenter.FrameDecision end = segmenter.onFrame(0.1f, true, 100);
        assertTrue(end.endSession);
        assertTrue(end.emitOnEnd);
        assertFalse(end.emitClause);
    }

    private static ClauseVadSegmenter speaking() {
        ClauseVadSegmenter segmenter = new ClauseVadSegmenter();
        feed(segmenter, ClauseVadSegmenter.MIN_SPEECH_FRAMES, 0.9f, true, TIMEOUT_MILLIS);
        return segmenter;
    }

    private static int MIN_SPEECH_MINUS_ONE() {
        return ClauseVadSegmenter.MIN_SPEECH_FRAMES - 1;
    }

    private static ClauseVadSegmenter.FrameDecision feed(ClauseVadSegmenter segmenter, int frames, float probability, boolean amplitude, int timeout) {
        ClauseVadSegmenter.FrameDecision decision = ClauseVadSegmenter.FrameDecision.none();
        for (int i = 0; i < frames; i++) {
            decision = segmenter.onFrame(probability, amplitude, timeout);
        }
        return decision;
    }
}
