package nie.translator.rtranslator.voice_translation.neural_networks.voice;

import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

/**
 * FireRedVAD streaming model for simultaneous-interpretation clause cuts.
 * The exported graph takes one 80-bin fbank frame per 10 ms and a packed
 * 8x128x19 FSMN cache, and returns the speech probability plus the next
 * cache. Recorder feeds 512-sample frames (32 ms), so one call runs the
 * 3-4 model frames that fit and returns their highest probability.
 * <p>
 * The session is not safe to run from two threads. Recorder calls
 * {@link #probability(short[])} and {@link #resetState()} only on the audio
 * thread, and closes the session after that thread has left the model.
 */
public final class FireRedClauseVad implements ClauseVad {
    private static final String TAG = "firered-vad";
    private static final int CACHE_COUNT = 8;
    private static final int CACHE_CHANNELS = 128;
    private static final int CACHE_LENGTH = 19;
    /** A 512-sample call yields at most 4 model frames after a 399-sample remainder. */
    private static final int MAX_FRAMES_PER_CALL = 4;
    private static final int PENDING_CAPACITY = 2048;
    private static final byte[] KALDI_MATRIX_MAGIC = new byte[]{0, 'B', 'D', 'M', ' '};

    private final OrtEnvironment environment;
    private OrtSession session;
    private final FireRedFbank fbank = new FireRedFbank();
    private final float[] mean = new float[FireRedFbank.MEL_BINS];
    private final float[] inverseStd = new float[FireRedFbank.MEL_BINS];
    private final float[] cache = new float[CACHE_COUNT * CACHE_CHANNELS * CACHE_LENGTH];
    private final short[] pending = new short[PENDING_CAPACITY];
    private final float[] features = new float[MAX_FRAMES_PER_CALL * FireRedFbank.MEL_BINS];
    private int pendingFill;

    private FireRedClauseVad(File model, File cmvn) throws IOException, OrtException {
        readCmvn(cmvn);
        environment = OrtEnvironment.getEnvironment();
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(1);
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT);
            session = environment.createSession(model.getAbsolutePath(), options);
        }
    }

    /**
     * @return a session, or null when a file is missing or will not load
     */
    public static FireRedClauseVad open(File model, File cmvn) {
        if (model == null || !model.isFile() || model.length() == 0L) {
            return null;
        }
        if (cmvn == null || !cmvn.isFile() || cmvn.length() == 0L) {
            return null;
        }
        try {
            return new FireRedClauseVad(model, cmvn);
        } catch (Exception e) {
            Log.w(TAG, "could not open clause VAD", e);
            return null;
        }
    }

    @Override
    public float probability(short[] frame) throws OrtException {
        if (session == null) {
            throw new OrtException("FireRed VAD session is closed");
        }
        if (frame == null || frame.length != ClauseVadSegmenter.FRAME_SAMPLES) {
            throw new OrtException("clause VAD frame must be 512 samples");
        }
        System.arraycopy(frame, 0, pending, pendingFill, frame.length);
        pendingFill += frame.length;

        int frames = 0;
        while (pendingFill >= FireRedFbank.FRAME_LENGTH && frames < MAX_FRAMES_PER_CALL) {
            int outOffset = frames * FireRedFbank.MEL_BINS;
            fbank.compute(pending, 0, features, outOffset);
            for (int bin = 0; bin < FireRedFbank.MEL_BINS; bin++) {
                features[outOffset + bin] = (features[outOffset + bin] - mean[bin]) * inverseStd[bin];
            }
            frames++;
            System.arraycopy(pending, FireRedFbank.FRAME_SHIFT, pending, 0, pendingFill - FireRedFbank.FRAME_SHIFT);
            pendingFill -= FireRedFbank.FRAME_SHIFT;
        }
        if (frames == 0) {
            return 0f;
        }
        return runSession(frames);
    }

    @Override
    public void warmup() {
        short[] silence = new short[ClauseVadSegmenter.FRAME_SAMPLES];
        try {
            probability(silence);
        } catch (Exception e) {
            Log.w(TAG, "clause VAD warmup failed", e);
        } finally {
            resetState();
        }
    }

    @Override
    public void resetState() {
        Arrays.fill(cache, 0f);
        pendingFill = 0;
    }

    @Override
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

    private float runSession(int frames) throws OrtException {
        float[] featData = new float[frames * FireRedFbank.MEL_BINS];
        System.arraycopy(features, 0, featData, 0, featData.length);
        Map<String, OnnxTensor> inputs = new HashMap<>();
        try (OnnxTensor feat = OnnxTensor.createTensor(environment, FloatBuffer.wrap(featData), new long[]{1, frames, FireRedFbank.MEL_BINS});
             OnnxTensor cacheIn = OnnxTensor.createTensor(environment, FloatBuffer.wrap(cache), new long[]{CACHE_COUNT, 1, CACHE_CHANNELS, CACHE_LENGTH})) {
            inputs.put("feat", feat);
            inputs.put("caches_in", cacheIn);
            try (OrtSession.Result result = session.run(inputs)) {
                if (!result.get("probs").isPresent() || !result.get("caches_out").isPresent()) {
                    throw new OrtException("missing FireRed VAD output");
                }
                float[] probs = OnnxFloats.toFloats((OnnxTensor) result.get("probs").get());
                float[] nextCache = OnnxFloats.toFloats((OnnxTensor) result.get("caches_out").get());
                if (nextCache.length != cache.length) {
                    throw new OrtException("unexpected FireRed VAD cache shape");
                }
                float highest = 0f;
                for (float probability : probs) {
                    highest = Math.max(highest, probability);
                }
                System.arraycopy(nextCache, 0, cache, 0, cache.length);
                return highest;
            }
        }
    }

    /**
     * The model ships cmvn.ark as a Kaldi binary double matrix: 2 rows of
     * 81 values. Row 0 holds the feature sums, row 1 the squared sums, and
     * the last column of row 0 the frame count.
     */
    private void readCmvn(File file) throws IOException {
        byte[] data = Files.readAllBytes(file.toPath());
        if (data.length < 15) {
            throw new IOException("cmvn.ark is too short");
        }
        for (int i = 0; i < KALDI_MATRIX_MAGIC.length; i++) {
            if (data[i] != KALDI_MATRIX_MAGIC[i]) {
                throw new IOException("cmvn.ark is not a Kaldi double matrix");
            }
        }
        int rows = readDimension(data, 5);
        int cols = readDimension(data, 10);
        int offset = 15;
        if (rows != 2 || cols != FireRedFbank.MEL_BINS + 1 || data.length != offset + rows * cols * 8) {
            throw new IOException("unexpected cmvn.ark shape");
        }
        ByteBuffer values = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        double count = values.getDouble(offset + FireRedFbank.MEL_BINS * 8);
        if (count <= 0) {
            throw new IOException("cmvn.ark has no frames");
        }
        for (int bin = 0; bin < FireRedFbank.MEL_BINS; bin++) {
            double sum = values.getDouble(offset + bin * 8);
            double squareSum = values.getDouble(offset + (cols + bin) * 8);
            double average = sum / count;
            double variance = squareSum / count - average * average;
            mean[bin] = (float) average;
            inverseStd[bin] = (float) (1.0 / Math.sqrt(Math.max(variance, 1e-20)));
        }
    }

    /** Reads the length-prefixed int32 that cmvn.ark uses for its two dimensions. */
    private static int readDimension(byte[] data, int offset) throws IOException {
        int length = data[offset] & 0xff;
        if (length != 4 || offset + 5 > data.length) {
            throw new IOException("unexpected cmvn.ark dimension");
        }
        return ByteBuffer.wrap(data, offset + 1, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }
}
