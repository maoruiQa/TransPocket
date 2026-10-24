package nie.translator.rtranslator.voice_translation.neural_networks.voice;

/**
 * Clause-level speech probability for simultaneous interpretation.
 * One frame is {@link ClauseVadSegmenter#FRAME_SAMPLES} samples at
 * {@link ClauseVadSegmenter#SAMPLE_RATE}. The session is not thread safe:
 * Recorder calls {@link #probability(short[])}, {@link #resetState()} and
 * {@link #close()} only on the audio thread, after that thread has left the
 * model. Implementations may keep state across calls and return one
 * probability per frame.
 */
public interface ClauseVad {
    float probability(short[] frame) throws Exception;

    /** Runs one silent frame so the first real frame is not the session cold start. */
    void warmup();

    /** Clears the model state when the speaker stops. */
    void resetState();

    void close();
}
