package nie.translator.rtranslator.voice_translation.neural_networks.voice;

/**
 * Builds the Silero VAD v6.2.1 input. The ONNX graph scores 576 samples at
 * 16 kHz: 64 samples of context, then the current 512-sample frame.
 * Context starts at zero and then keeps the last 64 samples of the frame
 * that was actually scored. Call {@link #advance(float[])} only after that
 * run succeeds, so a failed session does not skip audio.
 */
public final class SileroVadWindow {
    public static final int CONTEXT_SAMPLES = 64;
    public static final int MODEL_SAMPLES = CONTEXT_SAMPLES + ClauseVadSegmenter.FRAME_SAMPLES;

    private final float[] context = new float[CONTEXT_SAMPLES];

    public float[] assemble(float[] frame) {
        if (frame == null || frame.length != ClauseVadSegmenter.FRAME_SAMPLES) {
            throw new IllegalArgumentException("frame must be 512 samples");
        }
        float[] input = new float[MODEL_SAMPLES];
        System.arraycopy(context, 0, input, 0, CONTEXT_SAMPLES);
        System.arraycopy(frame, 0, input, CONTEXT_SAMPLES, frame.length);
        return input;
    }

    public void advance(float[] frame) {
        if (frame == null || frame.length != ClauseVadSegmenter.FRAME_SAMPLES) {
            throw new IllegalArgumentException("frame must be 512 samples");
        }
        System.arraycopy(frame, frame.length - CONTEXT_SAMPLES, context, 0, CONTEXT_SAMPLES);
    }

    public void reset() {
        for (int i = 0; i < context.length; i++) {
            context[i] = 0f;
        }
    }
}
