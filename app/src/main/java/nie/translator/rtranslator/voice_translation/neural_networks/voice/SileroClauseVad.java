package nie.translator.rtranslator.voice_translation.neural_networks.voice;

import android.util.Log;

import java.io.File;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.Map;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

/**
 * Silero VAD v6.2.1 for simultaneous-interpretation clause cuts.
 * The session is not safe to run from two threads. Recorder calls
 * {@link #probability(short[])} and {@link #resetState()} only on the audio
 * thread, and closes the session after that thread has left the model.
 * LSTM state and the 64-sample context stay across clause cuts and are
 * cleared when the speaker stops.
 */
public final class SileroClauseVad {
    private static final String TAG = "silero-vad";
    private static final int STATE_SIZE = 2 * 128;

    private final OrtEnvironment environment;
    private OrtSession session;
    private final float[] state = new float[STATE_SIZE];
    private final SileroVadWindow window = new SileroVadWindow();

    private SileroClauseVad(File model) throws OrtException {
        environment = OrtEnvironment.getEnvironment();
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(1);
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT);
            session = environment.createSession(model.getAbsolutePath(), options);
        }
    }

    /**
     * @return a session, or null when the file is missing or the model will not load
     */
    public static SileroClauseVad open(File model) {
        if (model == null || !model.isFile() || model.length() == 0L) {
            return null;
        }
        try {
            return new SileroClauseVad(model);
        } catch (Exception e) {
            Log.w(TAG, "could not open clause VAD", e);
            return null;
        }
    }

    /**
     * Speech probability for one 512-sample 16-bit frame.
     * Samples use the same scale as the recorder: full scale is 32768.
     */
    public float probability(short[] frame) throws OrtException {
        if (session == null) {
            throw new OrtException("clause VAD session is closed");
        }
        if (frame == null || frame.length != ClauseVadSegmenter.FRAME_SAMPLES) {
            throw new OrtException("clause VAD frame must be 512 samples");
        }
        float[] audio = new float[ClauseVadSegmenter.FRAME_SAMPLES];
        for (int i = 0; i < audio.length; i++) {
            audio[i] = frame[i] / 32768f;
        }
        float[] modelInput = window.assemble(audio);
        Map<String, OnnxTensor> inputs = new HashMap<>();
        try (OnnxTensor input = OnnxTensor.createTensor(environment, FloatBuffer.wrap(modelInput), new long[]{1, SileroVadWindow.MODEL_SAMPLES});
             OnnxTensor stateTensor = OnnxTensor.createTensor(environment, FloatBuffer.wrap(state), new long[]{2, 1, 128});
             OnnxTensor sampleRate = OnnxTensor.createTensor(environment, LongBuffer.wrap(new long[]{ClauseVadSegmenter.SAMPLE_RATE}), new long[0])) {
            inputs.put("input", input);
            inputs.put("state", stateTensor);
            inputs.put("sr", sampleRate);
            try (OrtSession.Result result = session.run(inputs)) {
                if (!result.get("output").isPresent() || !result.get("stateN").isPresent()) {
                    throw new OrtException("missing clause VAD output");
                }
                float probability = readProbability(result.get("output").get().getValue());
                copyState(result.get("stateN").get().getValue());
                window.advance(audio);
                return probability;
            }
        }
    }

    public void resetState() {
        for (int i = 0; i < state.length; i++) {
            state[i] = 0f;
        }
        window.reset();
    }

    public void close() {
        OrtSession open = session;
        session = null;
        if (open == null) {
            return;
        }
        try {
            open.close();
        } catch (Exception e) {
            Log.w(TAG, "close failed", e);
        }
    }

    private static float readProbability(Object value) throws OrtException {
        if (value instanceof float[][]) {
            float[][] matrix = (float[][]) value;
            if (matrix.length > 0 && matrix[0].length > 0) {
                return matrix[0][0];
            }
        }
        if (value instanceof float[]) {
            float[] vector = (float[]) value;
            if (vector.length > 0) {
                return vector[0];
            }
        }
        throw new OrtException("unexpected clause VAD output");
    }

    private void copyState(Object value) throws OrtException {
        if (!(value instanceof float[][][])) {
            throw new OrtException("unexpected clause VAD state");
        }
        float[][][] next = (float[][][]) value;
        if (next.length != 2 || next[0].length != 1 || next[0][0].length != 128 || next[1].length != 1 || next[1][0].length != 128) {
            throw new OrtException("unexpected clause VAD state shape");
        }
        System.arraycopy(next[0][0], 0, state, 0, 128);
        System.arraycopy(next[1][0], 0, state, 128, 128);
    }
}
