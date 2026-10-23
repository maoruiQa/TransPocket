package nie.translator.rtranslator.voice_translation.neural_networks.voice;

/**
 * Turns per-frame speech probabilities into simultaneous-interpretation
 * clause boundaries. One frame is {@link #FRAME_SAMPLES} samples at
 * {@link #SAMPLE_RATE}, which is 32 ms. No Android or ONNX types, so the
 * thresholds can be checked with plain probabilities.
 */
public final class ClauseVadSegmenter {
    public static final int SAMPLE_RATE = 16000;
    public static final int FRAME_SAMPLES = 512;
    public static final int FRAME_MILLIS = FRAME_SAMPLES * 1000 / SAMPLE_RATE;
    public static final float SPEECH_THRESHOLD = 0.5f;
    public static final float SILENCE_THRESHOLD = 0.35f;
    /** About 96 ms. */
    public static final int ONSET_FRAMES = 3;
    /** About 256 ms, the shortest clause a pause may close. */
    public static final int MIN_SPEECH_FRAMES = 8;
    /** About 320 ms of silence closes a clause and keeps listening. */
    public static final int END_SILENCE_FRAMES = 10;
    public static final int MAX_CLAUSE_MILLIS = 7000;

    private boolean inClause;
    private boolean session;
    private boolean lastSpeech;
    private int consecutiveSpeech;
    private int speechFrames;
    private int clauseFrames;
    private int silenceFrames;

    public FrameDecision onFrame(float probability, boolean amplitudePassed, int speechTimeoutMillis) {
        boolean speech = classify(probability, amplitudePassed);
        lastSpeech = speech;
        if (speech) {
            silenceFrames = 0;
            consecutiveSpeech++;
            if (!inClause) {
                if (consecutiveSpeech >= ONSET_FRAMES) {
                    inClause = true;
                    session = true;
                    speechFrames = consecutiveSpeech;
                    clauseFrames = consecutiveSpeech;
                    return new FrameDecision(true, true, false, false, false, false);
                }
                return FrameDecision.none();
            }
            speechFrames++;
            clauseFrames++;
            if (clauseFrames * FRAME_MILLIS >= MAX_CLAUSE_MILLIS) {
                speechFrames = 0;
                clauseFrames = 0;
                return new FrameDecision(true, false, true, true, false, false);
            }
            return new FrameDecision(true, false, false, false, false, false);
        }
        consecutiveSpeech = 0;
        if (!session) {
            return FrameDecision.none();
        }
        silenceFrames++;
        if (inClause) {
            clauseFrames++;
        }
        int silenceMs = silenceFrames * FRAME_MILLIS;
        if (inClause && speechFrames >= MIN_SPEECH_FRAMES && silenceFrames >= END_SILENCE_FRAMES && silenceMs < speechTimeoutMillis) {
            inClause = false;
            speechFrames = 0;
            clauseFrames = 0;
            return new FrameDecision(false, false, true, false, false, false);
        }
        if (silenceMs >= speechTimeoutMillis) {
            boolean emit = inClause;
            reset();
            return new FrameDecision(false, false, false, false, true, emit);
        }
        return FrameDecision.none();
    }

    /** Closes an open session immediately. Used when the microphone is virtually muted. */
    public FrameDecision forceEnd() {
        boolean emit = inClause;
        boolean wasOpen = session;
        reset();
        if (!wasOpen) {
            return FrameDecision.none();
        }
        return new FrameDecision(false, false, false, false, true, emit);
    }

    public void reset() {
        inClause = false;
        session = false;
        lastSpeech = false;
        consecutiveSpeech = 0;
        speechFrames = 0;
        clauseFrames = 0;
        silenceFrames = 0;
    }

    private boolean classify(float probability, boolean amplitudePassed) {
        if (!amplitudePassed || probability < SILENCE_THRESHOLD) {
            return false;
        }
        if (probability >= SPEECH_THRESHOLD) {
            return true;
        }
        return lastSpeech;
    }

    public static final class FrameDecision {
        public final boolean speech;
        public final boolean speechStarted;
        public final boolean emitClause;
        public final boolean stillVoiced;
        public final boolean endSession;
        /** Session end should still send the unsent audio. A pause cut uses {@link #emitClause} instead. */
        public final boolean emitOnEnd;

        public FrameDecision(boolean speech, boolean speechStarted, boolean emitClause, boolean stillVoiced, boolean endSession, boolean emitOnEnd) {
            this.speech = speech;
            this.speechStarted = speechStarted;
            this.emitClause = emitClause;
            this.stillVoiced = stillVoiced;
            this.endSession = endSession;
            this.emitOnEnd = emitOnEnd;
        }

        public static FrameDecision none() {
            return new FrameDecision(false, false, false, false, false, false);
        }
    }
}
