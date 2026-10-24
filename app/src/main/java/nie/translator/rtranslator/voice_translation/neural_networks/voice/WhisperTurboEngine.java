package nie.translator.rtranslator.voice_translation.neural_networks.voice;

import android.util.JsonReader;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import nie.translator.rtranslator.tools.AsrLanguageHint;
import nie.translator.rtranslator.tools.AsrLanguages;
import nie.translator.rtranslator.tools.AsrRepetition;

import ai.onnxruntime.OnnxJavaType;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;

/**
 * onnx-community Whisper runner (merged decoder).
 * int4 is the q4 graph: int4 weights, float32 compute. q4f16 is not offered;
 * it adds a float16 cast on every matmul. File names follow the selected quant.
 */
public class WhisperTurboEngine {
    private static final int SAMPLE_RATE = 16000;
    private static final int N_FFT = 400;
    private static final int HOP = 160;
    private static final int HEAD_DIM = 64;

    private final OrtEnvironment env;
    private final OrtSession encoder;
    private final OrtSession decoder;
    private final String encoderInput;
    private final OnnxJavaType encoderInputType;
    private final String logitsName;
    private final int fixedFrames;
    private final int nMels;
    private final int nHeads;
    private final HashMap<Integer, String> idToToken = new HashMap<>();
    private final HashMap<String, Integer> tokenToId = new HashMap<>();
    private int sot = 50258;
    private int eos = 50257;
    private int transcribe = 50360;
    private int noTimestamps = 50364;
    private int textLimit = 50257;
    private int firstLanguage = 50259;
    private int lastLanguage = 50358;
    private final boolean largeV3;
    private final int[] byteToUnicode = new int[256];
    private final HashMap<Integer, Integer> unicodeToByte = new HashMap<>();
    private final float[] cosTable;
    private final float[] sinTable;
    private final float[][] melBank;

    public WhisperTurboEngine(File modelDir, String encoderName, String decoderName, int nMels, int nHeads) throws IOException, OrtException {
        this.nMels = nMels;
        this.nHeads = nHeads;
        File encoderFile = new File(modelDir, encoderName);
        File decoderFile = new File(modelDir, decoderName);
        File vocabFile = new File(modelDir, "vocab.json");
        if (!encoderFile.isFile() || !decoderFile.isFile() || !vocabFile.isFile()) {
            throw new IOException("Whisper files are missing");
        }
        loadVocab(vocabFile);
        // vocab.json is the base BPE only (ids 0..50256). large-v3-turbo inserts
        // <|yue|> at 50358, so <|transcribe|> is 50360 and <|notimestamps|> is 50364.
        // whisper-small has no yue token and still uses 50359 / 50363. The small ids
        // make turbo read the prompt as translate + nospeech and emit <|transcribe|>.
        largeV3 = nMels >= 128;
        sot = tokenId("<|startoftranscript|>", 50258);
        eos = tokenId("<|endoftext|>", 50257);
        transcribe = tokenId("<|transcribe|>", largeV3 ? 50360 : 50359);
        noTimestamps = tokenId("<|notimestamps|>", largeV3 ? 50364 : 50363);
        textLimit = eos;
        firstLanguage = sot + 1;
        lastLanguage = transcribe - 2;
        buildByteMap();
        cosTable = new float[N_FFT * (N_FFT / 2 + 1)];
        sinTable = new float[N_FFT * (N_FFT / 2 + 1)];
        for (int k = 0; k <= N_FFT / 2; k++) {
            for (int n = 0; n < N_FFT; n++) {
                double angle = 2.0 * Math.PI * k * n / N_FFT;
                cosTable[k * N_FFT + n] = (float) Math.cos(angle);
                sinTable[k * N_FFT + n] = (float) Math.sin(angle);
            }
        }
        melBank = buildSlaneyBank(SAMPLE_RATE, N_FFT, nMels, 0f, 8000f);
        env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT);
        options.setIntraOpNumThreads(Math.min(4, Runtime.getRuntime().availableProcessors()));
        // An arena keeps the peak allocation after the first utterance.
        options.setCPUArenaAllocator(false);
        options.setMemoryPatternOptimization(false);
        encoder = env.createSession(encoderFile.getAbsolutePath(), options);
        decoder = env.createSession(decoderFile.getAbsolutePath(), options);
        encoderInput = encoderInputName();
        TensorInfo encoderInfo = (TensorInfo) encoder.getInputInfo().get(encoderInput).getInfo();
        encoderInputType = encoderInfo.type;
        long[] encoderShape = encoderInfo.getShape();
        fixedFrames = encoderShape.length > 2 && encoderShape[2] > 0 ? (int) encoderShape[2] : -1;
        logitsName = logitsOutputName();
    }

    public void close() {
        closeSession(encoder);
        closeSession(decoder);
    }

    public Transcripts transcribe(float[] samples, String languageCode, String languageCode2, boolean softLanguage) throws OrtException, IOException {
        return transcribe(samples, languageCode, languageCode2, softLanguage, -1, Integer.MAX_VALUE);
    }

    /**
     * @param allowedSwitchId absolute token the soft prompt may switch to, usually the
     *                        target language. Any other language keeps the requested one.
     */
    public Transcripts transcribe(float[] samples, String languageCode, String languageCode2,
                                  boolean softLanguage, int allowedSwitchId) throws OrtException, IOException {
        return transcribe(samples, languageCode, languageCode2, softLanguage, allowedSwitchId, Integer.MAX_VALUE);
    }

    /**
     * One short silent pass. The first real execution loads the session and is slow
     * enough to stall capture, so it runs before the microphone opens.
     */
    public void warmup() throws OrtException, IOException {
        transcribe(new float[SAMPLE_RATE], "en", null, false, -1, 1);
    }

    private Transcripts transcribe(float[] samples, String languageCode, String languageCode2, boolean softLanguage,
                                   int allowedSwitchId, int decoderSteps) throws OrtException, IOException {
        if (samples == null || samples.length < HOP) {
            return new Transcripts("", 0, null, 0);
        }
        float[] mel = logMel(fitLength(samples));
        int frames = mel.length / nMels;
        if (fixedFrames > 0 && frames != fixedFrames) {
            mel = fitMel(mel, frames, fixedFrames);
            frames = fixedFrames;
        }
        android.util.Log.i("performance", "Whisper turbo mel frames: " + frames + " fixedFrames: " + fixedFrames);
        OnnxTensor features = floatTensor(mel, new long[]{1, nMels, frames}, encoderInputType);
        OnnxTensor hidden = null;
        OrtSession.Result encoded = null;
        try {
            Map<String, OnnxTensor> encInputs = new LinkedHashMap<>();
            encInputs.put(encoderInput, features);
            encoded = encoder.run(encInputs);
            hidden = copyTensor((OnnxTensor) encoded.get(0));
        } finally {
            features.close();
            if (encoded != null) {
                encoded.close();
            }
        }
        try {
            boolean hintLanguage = softLanguage && languageCode2 == null;
            int encoderFrames = encoderFrames(hidden);
            Hypothesis first = decode(hidden, frames, encoderFrames, hintLanguage ? languageHint(languageCode) : languageId(languageCode),
                    hintLanguage, hintLanguage ? allowedSwitchId : -1, decoderSteps);
            if (languageCode2 == null) {
                return new Transcripts(first.text, first.score, null, 0);
            }
            Hypothesis second = decode(hidden, frames, encoderFrames, languageId(languageCode2), false, -1, decoderSteps);
            return new Transcripts(first.text, first.score, second.text, second.score);
        } finally {
            hidden.close();
        }
    }

    private Hypothesis decode(OnnxTensor hidden, int frames, int encoderFrames, int languageId, boolean softLanguage,
                              int allowedSwitchId, int decoderSteps) throws OrtException, IOException {
        ArrayList<Integer> produced = new ArrayList<>();
        Map<String, OnnxTensor> cache = new HashMap<>();
        int position = 0;
        int[] prompt;
        if (softLanguage) {
            Step sotStep = runDecoder(hidden, encoderFrames, new int[]{sot}, 0, cache, true);
            cache = sotStep.cache;
            position = 1;
            boolean allowSwitch = allowedSwitchId >= firstLanguage && allowedSwitchId <= lastLanguage;
            int chosen = AsrLanguageHint.choose(sotStep.languageLogits, firstLanguage, languageId, allowSwitch, allowedSwitchId);
            if (chosen < firstLanguage || chosen > lastLanguage) {
                chosen = languageId >= firstLanguage && languageId <= lastLanguage ? languageId : firstLanguage;
            }
            Log.i("asrLanguage", "model=" + (largeV3 ? "whisper-turbo" : "whisper-small")
                    + " soft=true requested=" + AsrLanguages.whisperCode(languageId, largeV3)
                    + " chosen=" + AsrLanguages.whisperCode(chosen, largeV3)
                    + " allowed=" + (allowSwitch ? AsrLanguages.whisperCode(allowedSwitchId, largeV3) : "none")
                    + " top2=" + topTwo(sotStep.languageLogits));
            prompt = new int[]{chosen, transcribe, noTimestamps};
        } else {
            prompt = new int[]{sot, languageId, transcribe, noTimestamps};
        }
        double score = 0;
        int scored = 0;
        int limit = tokenCap(frames);
        if (decoderSteps > 0 && decoderSteps < limit) {
            limit = decoderSteps;
        }
        try {
            for (int step = 0; step < limit; step++) {
                int[] ids = step == 0 ? prompt : new int[]{produced.get(produced.size() - 1)};
                Step stepResult = runDecoder(hidden, encoderFrames, ids, position, cache, false);
                position += ids.length;
                cache = stepResult.cache;
                int token = stepResult.token;
                score += stepResult.logProb;
                scored++;
                if (token == eos || token >= noTimestamps || token < 0 || token >= textLimit) {
                    break;
                }
                produced.add(token);
                if (AsrRepetition.keepCount(produced) < produced.size()) {
                    break;
                }
            }
        } finally {
            closeTensors(cache);
        }
        int keep = AsrRepetition.keepCount(produced);
        if (keep < produced.size()) {
            if (keep <= 0) {
                produced.clear();
            } else {
                produced.subList(keep, produced.size()).clear();
            }
        }
        double mean = scored == 0 ? 0 : score / scored;
        return new Hypothesis(decodeIds(produced), mean);
    }

    private Step runDecoder(OnnxTensor hidden, int encoderFrames, int[] ids, int position, Map<String, OnnxTensor> cache, boolean captureLanguage) throws OrtException, IOException {
        ArrayList<OnnxTensor> created = new ArrayList<>();
        Map<String, OnnxTensor> feeds = new LinkedHashMap<>();
        for (Map.Entry<String, ai.onnxruntime.NodeInfo> entry : decoder.getInputInfo().entrySet()) {
            String name = entry.getKey();
            TensorInfo info = (TensorInfo) entry.getValue().getInfo();
            OnnxTensor cached = cache.get(name);
            if (cached != null) {
                feeds.put(name, cached);
                continue;
            }
            OnnxTensor tensor = inputTensor(name, info, hidden, encoderFrames, ids, position, cache.isEmpty());
            feeds.put(name, tensor);
            if (tensor != hidden) {
                created.add(tensor);
            }
        }
        OrtSession.Result result = decoder.run(feeds);
        for (OnnxTensor tensor : created) {
            tensor.close();
        }
        try {
            OnnxTensor logits = (OnnxTensor) result.get(logitsName).get();
            float[] row = lastRow(logits);
            int token = argmaxText(row);
            double logProb = logProb(row, token);
            float[] languageLogits = null;
            if (captureLanguage && row.length > lastLanguage) {
                languageLogits = new float[lastLanguage - firstLanguage + 1];
                System.arraycopy(row, firstLanguage, languageLogits, 0, languageLogits.length);
            }
            Map<String, OnnxTensor> next = new HashMap<>();
            for (String name : decoder.getOutputInfo().keySet()) {
                if (!name.startsWith("present")) {
                    continue;
                }
                String pastName = "past_key_values." + name.substring("present.".length());
                // use_cache returns an empty encoder cache (shape [0, heads, 1, dim]).
                // Cross-attention has to keep the cache computed on the first step.
                if (pastName.contains("encoder")) {
                    OnnxTensor existing = cache.remove(pastName);
                    if (existing != null) {
                        next.put(pastName, existing);
                        continue;
                    }
                }
                next.put(pastName, copyTensor((OnnxTensor) result.get(name).get()));
            }
            closeTensors(cache);
            return new Step(token, logProb, next, languageLogits);
        } finally {
            result.close();
        }
    }

    private OnnxTensor inputTensor(String name, TensorInfo info, OnnxTensor hidden, int encoderFrames, int[] ids, int position, boolean first) throws OrtException {
        if ("input_ids".equals(name)) {
            return OnnxTensor.createTensor(env, LongBuffer.wrap(toLong(ids)), new long[]{1, ids.length});
        }
        if ("encoder_hidden_states".equals(name)) {
            if (info.type != hidden.getInfo().type && (info.type == OnnxJavaType.FLOAT || info.type == OnnxJavaType.FLOAT16)) {
                return floatTensor(OnnxFloats.toFloats(hidden), hidden.getInfo().getShape(), info.type);
            }
            return hidden;
        }
        if ("use_cache_branch".equals(name)) {
            // The merged decoder declares this bool as shape [1]. A scalar is rejected.
            return boolTensor(!first, info.getShape());
        }
        if ("cache_position".equals(name)) {
            long[] positions = new long[ids.length];
            for (int i = 0; i < ids.length; i++) {
                positions[i] = position + i;
            }
            long[] shape = info.getShape().length <= 1 ? new long[]{ids.length} : new long[]{1, ids.length};
            return OnnxTensor.createTensor(env, LongBuffer.wrap(positions), shape);
        }
        if (name.contains("attention_mask")) {
            int length = name.contains("encoder") ? encoderFrames : position + ids.length;
            return ones(info, length);
        }
        if (name.startsWith("past_key_values")) {
            return zeros(pastShape(name, info, encoderFrames), info.type);
        }
        throw new OrtException("Whisper input is not handled: " + name);
    }

    private long[] pastShape(String name, TensorInfo info, int encoderFrames) {
        long[] declared = info.getShape();
        long[] shape = new long[declared.length];
        boolean encoderCache = name.contains("encoder");
        for (int i = 0; i < declared.length; i++) {
            long dim = declared[i];
            if (dim > 0) {
                shape[i] = dim;
            } else if (i == 0) {
                shape[i] = 1;
            } else if (i == 1) {
                shape[i] = nHeads;
            } else if (i == declared.length - 1) {
                shape[i] = HEAD_DIM;
            } else {
                shape[i] = encoderCache ? encoderFrames : 0;
            }
        }
        return shape;
    }

    private OnnxTensor zeros(long[] shape, OnnxJavaType type) throws OrtException {
        int count = 1;
        for (long dim : shape) {
            count *= (int) dim;
        }
        if (type == OnnxJavaType.FLOAT16) {
            java.nio.ByteBuffer data = java.nio.ByteBuffer.allocateDirect(Math.max(count, 0) * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            return OnnxTensor.createTensor(env, data, shape, OnnxJavaType.FLOAT16);
        }
        return OnnxTensor.createTensor(env, FloatBuffer.allocate(Math.max(count, 0)), shape);
    }

    private OnnxTensor ones(TensorInfo info, int length) throws OrtException {
        long[] shape = info.getShape().length <= 1 ? new long[]{length} : new long[]{1, length};
        if (info.type == OnnxJavaType.INT32) {
            int[] data = new int[length];
            for (int i = 0; i < length; i++) {
                data[i] = 1;
            }
            return OnnxTensor.createTensor(env, IntBuffer.wrap(data), shape);
        }
        if (info.type == OnnxJavaType.FLOAT) {
            float[] data = new float[length];
            for (int i = 0; i < length; i++) {
                data[i] = 1f;
            }
            return OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape);
        }
        long[] data = new long[length];
        for (int i = 0; i < length; i++) {
            data[i] = 1L;
        }
        return OnnxTensor.createTensor(env, LongBuffer.wrap(data), shape);
    }

    private OnnxTensor boolTensor(boolean value, long[] declared) throws OrtException {
        long[] shape;
        int count = 1;
        if (declared == null || declared.length == 0) {
            shape = new long[0];
        } else {
            shape = new long[declared.length];
            for (int i = 0; i < declared.length; i++) {
                long dim = declared[i] > 0 ? declared[i] : 1;
                shape[i] = dim;
                count *= (int) dim;
            }
        }
        byte[] data = new byte[count];
        byte bit = (byte) (value ? 1 : 0);
        for (int i = 0; i < data.length; i++) {
            data[i] = bit;
        }
        return OnnxTensor.createTensor(env, java.nio.ByteBuffer.wrap(data), shape, OnnxJavaType.BOOL);
    }

    private int argmaxText(float[] row) {
        int best = eos;
        float bestValue = -Float.MAX_VALUE;
        for (int i = 0; i < row.length; i++) {
            float value = row[i];
            if (value > bestValue) {
                bestValue = value;
                best = i;
            }
        }
        return best;
    }

    private static double logProb(float[] row, int token) {
        if (token < 0 || token >= row.length) {
            return -20;
        }
        float max = -Float.MAX_VALUE;
        for (float value : row) {
            if (value > max) {
                max = value;
            }
        }
        double sum = 0;
        for (float value : row) {
            sum += Math.exp(value - max);
        }
        return (row[token] - max) - Math.log(sum);
    }

    private float[] fitLength(float[] samples) {
        int target = samples.length;
        if (target % HOP != 0) {
            target += HOP - (target % HOP);
        }
        if (fixedFrames > 0) {
            // large-v3-turbo encoder input is fixed at 3000 frames (30 s). Pad or trim.
            target = fixedFrames * HOP;
        }
        if (target == samples.length) {
            return samples;
        }
        if (target < samples.length) {
            float[] trimmed = new float[target];
            System.arraycopy(samples, 0, trimmed, 0, target);
            return trimmed;
        }
        float[] padded = new float[target];
        System.arraycopy(samples, 0, padded, 0, samples.length);
        return padded;
    }

    /** Mel is stored as [n_mels, frames]. Some encoder inputs use a fixed frame count. */
    private float[] fitMel(float[] mel, int frames, int target) {
        float[] fitted = new float[nMels * target];
        int copy = Math.min(frames, target);
        for (int melIndex = 0; melIndex < nMels; melIndex++) {
            System.arraycopy(mel, melIndex * frames, fitted, melIndex * target, copy);
        }
        return fitted;
    }

    private int languageId(String language) throws IOException {
        if (language == null || language.isEmpty()) {
            return AsrLanguages.whisperToken("en", largeV3);
        }
        int id = AsrLanguages.whisperToken(language, largeV3);
        if (id < firstLanguage || id > lastLanguage) {
            throw new IOException("Whisper has no language " + language);
        }
        return id;
    }

    /** Hint only. An unknown code stays unset so the decoder can pick the language token. */
    private int languageHint(String language) {
        if (language == null || language.isEmpty()) {
            return -1;
        }
        int id = AsrLanguages.whisperToken(language, largeV3);
        return id < firstLanguage || id > lastLanguage ? -1 : id;
    }

    /** Top two language scores for the diagnostics log. No transcript content. */
    private String topTwo(float[] logits) {
        if (logits == null || logits.length == 0) {
            return "na";
        }
        int first = -1;
        int second = -1;
        for (int i = 0; i < logits.length; i++) {
            if (first < 0 || logits[i] > logits[first]) {
                second = first;
                first = i;
            } else if (second < 0 || logits[i] > logits[second]) {
                second = i;
            }
        }
        StringBuilder text = new StringBuilder();
        text.append(AsrLanguages.whisperCode(firstLanguage + first, largeV3)).append(':').append(score(logits[first]));
        if (second >= 0) {
            text.append(',').append(AsrLanguages.whisperCode(firstLanguage + second, largeV3)).append(':').append(score(logits[second]));
        }
        return text.toString();
    }

    private static String score(float value) {
        return Float.toString(Math.round(value * 10f) / 10f);
    }

    private String encoderInputName() throws OrtException {
        for (String name : encoder.getInputInfo().keySet()) {
            if (name.contains("feature")) {
                return name;
            }
        }
        return encoder.getInputInfo().keySet().iterator().next();
    }

    private String logitsOutputName() throws OrtException {
        for (String name : decoder.getOutputInfo().keySet()) {
            if ("logits".equals(name) || name.endsWith("logits")) {
                return name;
            }
        }
        return decoder.getOutputInfo().keySet().iterator().next();
    }

    private static int tokenCap(int frames) {
        int seconds = Math.max(1, frames * HOP / SAMPLE_RATE);
        return Math.min(224, Math.max(24, seconds * 6 + 12));
    }

    private float[] logMel(float[] samples) {
        int pad = N_FFT / 2;
        float[] padded = reflectPad(samples, pad);
        int stftFrames = 1 + (padded.length - N_FFT) / HOP;
        int frames = Math.max(0, stftFrames - 1);
        float[] mel = new float[nMels * frames];
        float[] windowed = new float[N_FFT];
        float[] power = new float[N_FFT / 2 + 1];
        float max = -Float.MAX_VALUE;
        for (int frame = 0; frame < frames; frame++) {
            int start = frame * HOP;
            for (int n = 0; n < N_FFT; n++) {
                double w = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * n / N_FFT);
                windowed[n] = (float) (padded[start + n] * w);
            }
            for (int k = 0; k <= N_FFT / 2; k++) {
                float real = 0;
                float imag = 0;
                int table = k * N_FFT;
                for (int n = 0; n < N_FFT; n++) {
                    real += windowed[n] * cosTable[table + n];
                    imag -= windowed[n] * sinTable[table + n];
                }
                power[k] = real * real + imag * imag;
            }
            for (int melIndex = 0; melIndex < nMels; melIndex++) {
                double energy = 0;
                for (int k = 0; k <= N_FFT / 2; k++) {
                    energy += melBank[melIndex][k] * power[k];
                }
                float value = (float) Math.log10(Math.max(energy, 1e-10));
                mel[melIndex * frames + frame] = value;
                if (value > max) {
                    max = value;
                }
            }
        }
        float floor = max - 8f;
        for (int i = 0; i < mel.length; i++) {
            mel[i] = (Math.max(mel[i], floor) + 4f) / 4f;
        }
        return mel;
    }

    private static float[] reflectPad(float[] samples, int pad) {
        float[] padded = new float[samples.length + pad * 2];
        if (samples.length > pad) {
            for (int i = 0; i < pad; i++) {
                padded[i] = samples[pad - i];
            }
            System.arraycopy(samples, 0, padded, pad, samples.length);
            int length = samples.length;
            for (int i = 0; i < pad; i++) {
                padded[pad + length + i] = samples[length - 2 - i];
            }
        } else {
            System.arraycopy(samples, 0, padded, pad, samples.length);
        }
        return padded;
    }

    private static float[][] buildSlaneyBank(int sampleRate, int nFft, int nMels, float fmin, float fmax) {
        int nFreqs = nFft / 2 + 1;
        double[] hz = new double[nMels + 2];
        double melMin = hzToMel(fmin);
        double melMax = hzToMel(fmax);
        for (int i = 0; i < hz.length; i++) {
            hz[i] = melToHz(melMin + (melMax - melMin) * i / (nMels + 1.0));
        }
        float[][] bank = new float[nMels][nFreqs];
        for (int i = 0; i < nMels; i++) {
            double left = hz[i];
            double center = hz[i + 1];
            double right = hz[i + 2];
            double enorm = 2.0 / (right - left);
            for (int k = 0; k < nFreqs; k++) {
                double freq = k * (double) sampleRate / nFft;
                double weight = 0;
                if (freq >= left && freq <= center && center > left) {
                    weight = (freq - left) / (center - left);
                } else if (freq > center && freq <= right && right > center) {
                    weight = (right - freq) / (right - center);
                }
                bank[i][k] = (float) (weight * enorm);
            }
        }
        return bank;
    }

    private static double hzToMel(double freq) {
        double fSp = 200.0 / 3.0;
        double minLogHz = 1000.0;
        double minLogMel = minLogHz / fSp;
        double logstep = Math.log(6.4) / 27.0;
        if (freq < minLogHz) {
            return freq / fSp;
        }
        return minLogMel + Math.log(freq / minLogHz) / logstep;
    }

    private static double melToHz(double mel) {
        double fSp = 200.0 / 3.0;
        double minLogHz = 1000.0;
        double minLogMel = minLogHz / fSp;
        double logstep = Math.log(6.4) / 27.0;
        if (mel < minLogMel) {
            return mel * fSp;
        }
        return minLogHz * Math.exp(logstep * (mel - minLogMel));
    }

    private void loadVocab(File vocabFile) throws IOException {
        try (JsonReader reader = new JsonReader(new InputStreamReader(new FileInputStream(vocabFile), StandardCharsets.UTF_8))) {
            reader.beginObject();
            while (reader.hasNext()) {
                String token = reader.nextName();
                int id = reader.nextInt();
                idToToken.put(id, token);
                tokenToId.put(token, id);
            }
            reader.endObject();
        }
        if (idToToken.isEmpty()) {
            throw new IOException("Whisper large-v3-turbo vocab.json is empty");
        }
    }

    private String decodeIds(ArrayList<Integer> ids) {
        StringBuilder pieces = new StringBuilder();
        for (int id : ids) {
            String piece = idToToken.get(id);
            if (piece != null && !piece.startsWith("<|")) {
                pieces.append(piece);
            }
        }
        byte[] bytes = new byte[pieces.length()];
        int n = 0;
        for (int i = 0; i < pieces.length(); i++) {
            Integer b = unicodeToByte.get((int) pieces.charAt(i));
            if (b != null && n < bytes.length) {
                bytes[n++] = (byte) (b & 0xff);
            }
        }
        return new String(bytes, 0, n, StandardCharsets.UTF_8);
    }

    private void buildByteMap() {
        ArrayList<Integer> bytes = new ArrayList<>();
        for (int b = '!'; b <= '~'; b++) {
            bytes.add(b);
        }
        for (int b = 0xA1; b <= 0xAC; b++) {
            bytes.add(b);
        }
        for (int b = 0xAE; b <= 0xFF; b++) {
            bytes.add(b);
        }
        int extra = 0;
        boolean[] seen = new boolean[256];
        for (int b : bytes) {
            seen[b] = true;
        }
        for (int b = 0; b < 256; b++) {
            if (!seen[b]) {
                bytes.add(b);
                byteToUnicode[b] = 256 + extra;
                extra++;
            } else {
                byteToUnicode[b] = b;
            }
        }
        for (int b = 0; b < 256; b++) {
            unicodeToByte.put(byteToUnicode[b], b);
        }
    }

    private int tokenId(String token, int fallback) {
        Integer id = tokenToId.get(token);
        return id == null ? fallback : id;
    }

    private static int encoderFrames(OnnxTensor hidden) throws OrtException {
        long[] shape = hidden.getInfo().getShape();
        if (shape.length == 3 && shape[1] > 0) {
            return (int) shape[1];
        }
        if (shape.length == 2 && shape[0] > 0) {
            return (int) shape[0];
        }
        return 1;
    }

    private float[] lastRow(OnnxTensor logits) throws OrtException {
        long[] shape = logits.getInfo().getShape();
        int vocab = (int) shape[shape.length - 1];
        int seq = shape.length >= 2 ? (int) shape[shape.length - 2] : 1;
        float[] flat = OnnxFloats.toFloats(logits);
        float[] row = new float[vocab];
        int offset = Math.max(0, seq - 1) * vocab;
        System.arraycopy(flat, offset, row, 0, vocab);
        return row;
    }

    private OnnxTensor copyTensor(OnnxTensor tensor) throws OrtException {
        long[] shape = tensor.getInfo().getShape();
        OnnxJavaType type = tensor.getInfo().type;
        if (type == OnnxJavaType.FLOAT16) {
            int count = 1;
            for (long dim : shape) {
                count *= (int) dim;
            }
            java.nio.ByteBuffer raw = tensor.getByteBuffer().duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN);
            raw.position(0);
            java.nio.ByteBuffer copy = java.nio.ByteBuffer.allocateDirect(count * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < count; i++) {
                copy.putShort(raw.getShort());
            }
            copy.rewind();
            return OnnxTensor.createTensor(env, copy, shape, OnnxJavaType.FLOAT16);
        }
        float[] data = OnnxFloats.toFloats(tensor);
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape);
    }

    private OnnxTensor floatTensor(float[] data, long[] shape, OnnxJavaType type) throws OrtException {
        if (type == OnnxJavaType.FLOAT16) {
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocateDirect(data.length * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            for (float value : data) {
                buffer.putShort(floatToHalf(value));
            }
            buffer.rewind();
            return OnnxTensor.createTensor(env, buffer, shape, OnnxJavaType.FLOAT16);
        }
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape);
    }

    private static short floatToHalf(float value) {
        int bits = Float.floatToIntBits(value);
        int sign = (bits >>> 16) & 0x8000;
        int mantissa = bits & 0x7fffff;
        int exponent = (bits >>> 23) & 0xff;
        if (exponent == 255) {
            return (short) (sign | 0x7c00 | (mantissa == 0 ? 0 : 0x200));
        }
        if (exponent == 0) {
            return (short) sign;
        }
        int halfExponent = exponent - 127 + 15;
        if (halfExponent >= 31) {
            return (short) (sign | 0x7c00);
        }
        if (halfExponent <= 0) {
            if (halfExponent < -10) {
                return (short) sign;
            }
            mantissa |= 0x800000;
            return (short) (sign | (mantissa >> (14 - halfExponent)));
        }
        return (short) (sign | (halfExponent << 10) | (mantissa >> 13));
    }

    private static long[] toLong(int[] values) {
        long[] out = new long[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = values[i];
        }
        return out;
    }

    private static void closeTensors(Map<String, OnnxTensor> tensors) {
        if (tensors == null) {
            return;
        }
        for (OnnxTensor tensor : tensors.values()) {
            if (tensor != null) {
                tensor.close();
            }
        }
        tensors.clear();
    }

    private static void closeSession(OrtSession session) {
        if (session != null) {
            try {
                session.close();
            } catch (OrtException ignored) {
            }
        }
    }

    public static final class Transcripts {
        public final String firstText;
        public final double firstScore;
        public final String secondText;
        public final double secondScore;

        Transcripts(String firstText, double firstScore, String secondText, double secondScore) {
            this.firstText = firstText;
            this.firstScore = firstScore;
            this.secondText = secondText;
            this.secondScore = secondScore;
        }
    }

    private static final class Hypothesis {
        final String text;
        final double score;

        Hypothesis(String text, double score) {
            this.text = text;
            this.score = score;
        }
    }

    private static final class Step {
        final int token;
        final double logProb;
        final Map<String, OnnxTensor> cache;
        final float[] languageLogits;

        Step(int token, double logProb, Map<String, OnnxTensor> cache, float[] languageLogits) {
            this.token = token;
            this.logProb = logProb;
            this.cache = cache;
            this.languageLogits = languageLogits;
        }
    }
}
