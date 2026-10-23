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
import java.util.Locale;
import java.util.Map;

import nie.translator.rtranslator.tools.AsrLanguageHint;
import nie.translator.rtranslator.tools.AsrRepetition;

import ai.onnxruntime.OnnxJavaType;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;

/**
 * Whisper large-v3-turbo INT8 runner.
 * Encoder and merged decoder are the onnx-community INT8 graphs. INT8 keeps the
 * math in float32 on CPU; q4f16 is smaller but adds a float16 cast on every matmul.
 */
class WhisperTurboEngine {
    private static final int SAMPLE_RATE = 16000;
    private static final int N_FFT = 400;
    private static final int HOP = 160;
    private static final int N_MELS = 128;
    private static final int SOT = 50258;
    private static final int EOS = 50257;
    private static final int TRANSCRIBE = 50360;
    private static final int NO_TIMESTAMPS = 50364;
    private static final int FIRST_LANGUAGE = SOT + 1;
    private static final int LAST_LANGUAGE = TRANSCRIBE - 2;
    private static final int TEXT_LIMIT = 50257;
    private static final int HEADS = 20;
    private static final int HEAD_DIM = 64;

    private static final Map<String, Integer> LANGUAGE_IDS = new HashMap<>();

    static {
        String[] codes = {
                "en", "zh", "de", "es", "ru", "ko", "fr", "ja", "pt", "tr", "pl", "ca", "nl", "ar", "sv", "it", "id", "hi", "fi", "vi",
                "he", "uk", "el", "ms", "cs", "ro", "da", "hu", "ta", "no", "th", "ur", "hr", "bg", "lt", "la", "mi", "ml", "cy", "sk",
                "te", "fa", "lv", "bn", "sr", "az", "sl", "kn", "et", "mk", "br", "eu", "is", "hy", "ne", "mn", "bs", "kk", "sq", "sw",
                "gl", "mr", "pa", "si", "km", "sn", "yo", "so", "af", "oc", "ka", "be", "tg", "sd", "gu", "am", "yi", "lo", "uz", "fo",
                "ht", "ps", "tk", "nn", "mt", "sa", "lb", "my", "bo", "tl", "mg", "as", "tt", "haw", "ln", "ha", "ba", "jw", "su", "yue"
        };
        int[] ids = {
                50259, 50260, 50261, 50262, 50263, 50264, 50265, 50266, 50267, 50268, 50269, 50270, 50271, 50272, 50273, 50274, 50275, 50276, 50277, 50278,
                50279, 50280, 50281, 50282, 50283, 50284, 50285, 50286, 50287, 50288, 50289, 50290, 50291, 50292, 50293, 50294, 50295, 50296, 50297, 50298,
                50299, 50300, 50301, 50302, 50303, 50304, 50305, 50306, 50307, 50308, 50309, 50310, 50311, 50312, 50313, 50314, 50315, 50316, 50317, 50318,
                50319, 50320, 50321, 50322, 50323, 50324, 50325, 50326, 50327, 50328, 50329, 50330, 50331, 50332, 50333, 50334, 50335, 50336, 50337, 50338,
                50339, 50340, 50341, 50342, 50343, 50344, 50345, 50346, 50347, 50348, 50349, 50350, 50351, 50352, 50353, 50354, 50355, 50356, 50357, 50358
        };
        for (int i = 0; i < codes.length; i++) {
            LANGUAGE_IDS.put(codes[i], ids[i]);
        }
    }

    private final OrtEnvironment env;
    private final OrtSession encoder;
    private final OrtSession decoder;
    private final String encoderInput;
    private final String logitsName;
    private final int fixedFrames;
    private final HashMap<Integer, String> idToToken = new HashMap<>();
    private final int[] byteToUnicode = new int[256];
    private final HashMap<Integer, Integer> unicodeToByte = new HashMap<>();
    private final float[] cosTable;
    private final float[] sinTable;
    private final float[][] melBank;

    WhisperTurboEngine(File modelDir) throws IOException, OrtException {
        File encoderFile = new File(modelDir, "encoder_model_int8.onnx");
        File decoderFile = new File(modelDir, "decoder_model_merged_int8.onnx");
        File vocabFile = new File(modelDir, "vocab.json");
        if (!encoderFile.isFile() || !decoderFile.isFile() || !vocabFile.isFile()) {
            throw new IOException("Whisper large-v3-turbo files are missing");
        }
        loadVocab(vocabFile);
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
        melBank = buildSlaneyBank(SAMPLE_RATE, N_FFT, N_MELS, 0f, 8000f);
        env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT);
        options.setIntraOpNumThreads(Math.min(4, Runtime.getRuntime().availableProcessors()));
        // The encoder is 32 layers. An arena keeps the peak allocation after the first utterance.
        options.setCPUArenaAllocator(false);
        options.setMemoryPatternOptimization(false);
        encoder = env.createSession(encoderFile.getAbsolutePath(), options);
        decoder = env.createSession(decoderFile.getAbsolutePath(), options);
        encoderInput = encoderInputName();
        TensorInfo encoderInfo = (TensorInfo) encoder.getInputInfo().get(encoderInput).getInfo();
        long[] encoderShape = encoderInfo.getShape();
        fixedFrames = encoderShape.length > 2 && encoderShape[2] > 0 ? (int) encoderShape[2] : -1;
        logitsName = logitsOutputName();
    }

    void close() {
        closeSession(encoder);
        closeSession(decoder);
    }

    Transcripts transcribe(float[] samples, String languageCode, String languageCode2, boolean softLanguage) throws OrtException, IOException {
        if (samples == null || samples.length < HOP) {
            return new Transcripts("", 0, null, 0);
        }
        float[] mel = logMel(fitLength(samples));
        int frames = mel.length / N_MELS;
        android.util.Log.i("performance", "Whisper turbo encoder frames: " + frames + " fixedFrames: " + fixedFrames);
        OnnxTensor features = OnnxTensor.createTensor(env, FloatBuffer.wrap(mel), new long[]{1, N_MELS, frames});
        OnnxTensor hidden = null;
        OrtSession.Result encoded = null;
        try {
            Map<String, OnnxTensor> encInputs = new LinkedHashMap<>();
            encInputs.put(encoderInput, features);
            encoded = encoder.run(encInputs);
            hidden = copyFloat((OnnxTensor) encoded.get(0));
        } finally {
            features.close();
            if (encoded != null) {
                encoded.close();
            }
        }
        try {
            boolean hintLanguage = softLanguage && languageCode2 == null;
            Hypothesis first = decode(hidden, frames, hintLanguage ? languageHint(languageCode) : languageId(languageCode), hintLanguage);
            if (languageCode2 == null) {
                return new Transcripts(first.text, first.score, null, 0);
            }
            Hypothesis second = decode(hidden, frames, languageId(languageCode2), false);
            return new Transcripts(first.text, first.score, second.text, second.score);
        } finally {
            hidden.close();
        }
    }

    private Hypothesis decode(OnnxTensor hidden, int frames, int languageId, boolean softLanguage) throws OrtException, IOException {
        ArrayList<Integer> produced = new ArrayList<>();
        Map<String, OnnxTensor> cache = new HashMap<>();
        int position = 0;
        int[] prompt;
        if (softLanguage) {
            Step sotStep = runDecoder(hidden, frames, new int[]{SOT}, 0, cache, true);
            cache = sotStep.cache;
            position = 1;
            int chosen = AsrLanguageHint.choose(sotStep.languageLogits, FIRST_LANGUAGE, languageId);
            if (chosen < FIRST_LANGUAGE || chosen > LAST_LANGUAGE) {
                chosen = languageId >= FIRST_LANGUAGE && languageId <= LAST_LANGUAGE ? languageId : FIRST_LANGUAGE;
            }
            if (languageId >= FIRST_LANGUAGE && chosen != languageId) {
                Log.i("recognizer", "simultaneous language token " + languageId + " -> " + chosen);
            }
            prompt = new int[]{chosen, TRANSCRIBE, NO_TIMESTAMPS};
        } else {
            prompt = new int[]{SOT, languageId, TRANSCRIBE, NO_TIMESTAMPS};
        }
        double score = 0;
        int scored = 0;
        int limit = tokenCap(frames);
        try {
            for (int step = 0; step < limit; step++) {
                int[] ids = step == 0 ? prompt : new int[]{produced.get(produced.size() - 1)};
                Step stepResult = runDecoder(hidden, frames, ids, position, cache, false);
                position += ids.length;
                cache = stepResult.cache;
                int token = stepResult.token;
                score += stepResult.logProb;
                scored++;
                if (token == EOS || token >= NO_TIMESTAMPS || token < 0 || token >= TEXT_LIMIT) {
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

    private Step runDecoder(OnnxTensor hidden, int frames, int[] ids, int position, Map<String, OnnxTensor> cache, boolean captureLanguage) throws OrtException, IOException {
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
            OnnxTensor tensor = inputTensor(name, info, hidden, frames, ids, position, cache.isEmpty());
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
            float[][][] value = (float[][][]) logits.getValue();
            float[] row = value[0][value[0].length - 1];
            int token = argmaxText(row);
            double logProb = logProb(row, token);
            float[] languageLogits = null;
            if (captureLanguage && row.length > LAST_LANGUAGE) {
                languageLogits = new float[LAST_LANGUAGE - FIRST_LANGUAGE + 1];
                System.arraycopy(row, FIRST_LANGUAGE, languageLogits, 0, languageLogits.length);
            }
            Map<String, OnnxTensor> next = new HashMap<>();
            for (String name : decoder.getOutputInfo().keySet()) {
                if (!name.startsWith("present")) {
                    continue;
                }
                String pastName = "past_key_values." + name.substring("present.".length());
                next.put(pastName, copyFloat((OnnxTensor) result.get(name).get()));
            }
            closeTensors(cache);
            return new Step(token, logProb, next, languageLogits);
        } finally {
            result.close();
        }
    }

    private OnnxTensor inputTensor(String name, TensorInfo info, OnnxTensor hidden, int frames, int[] ids, int position, boolean first) throws OrtException {
        if ("input_ids".equals(name)) {
            return OnnxTensor.createTensor(env, LongBuffer.wrap(toLong(ids)), new long[]{1, ids.length});
        }
        if ("encoder_hidden_states".equals(name)) {
            return hidden;
        }
        if ("use_cache_branch".equals(name)) {
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
            int length = name.startsWith("encoder") ? frames : position + ids.length;
            return ones(info, length);
        }
        if (name.startsWith("past_key_values")) {
            return zeros(pastShape(info));
        }
        throw new OrtException("Whisper large-v3-turbo input is not handled: " + name);
    }

    private long[] pastShape(TensorInfo info) {
        long[] declared = info.getShape();
        long[] shape = new long[declared.length];
        for (int i = 0; i < declared.length; i++) {
            long dim = declared[i];
            if (dim > 0) {
                shape[i] = dim;
            } else if (i == 0) {
                shape[i] = 1;
            } else if (i == 1) {
                shape[i] = HEADS;
            } else if (i == declared.length - 1) {
                shape[i] = HEAD_DIM;
            } else {
                shape[i] = 0;
            }
        }
        return shape;
    }

    private OnnxTensor zeros(long[] shape) throws OrtException {
        return OnnxTensor.createTensor(env, FloatBuffer.allocate(0), shape);
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
        long[] shape = declared.length == 0 ? new long[]{} : new long[]{1};
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(new byte[]{(byte) (value ? 1 : 0)});
        return OnnxTensor.createTensor(env, buffer, shape, OnnxJavaType.BOOL);
    }

    private int argmaxText(float[] row) {
        int best = EOS;
        float bestValue = -Float.MAX_VALUE;
        int bestText = -1;
        float bestTextValue = -Float.MAX_VALUE;
        for (int i = 0; i < row.length; i++) {
            float value = row[i];
            if (value > bestValue) {
                bestValue = value;
                best = i;
            }
            if (i < TEXT_LIMIT && value > bestTextValue) {
                bestTextValue = value;
                bestText = i;
            }
        }
        if (best == EOS || best >= NO_TIMESTAMPS) {
            return best;
        }
        if (best >= TEXT_LIMIT) {
            return bestText;
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
            int required = fixedFrames * HOP;
            if (target < required) {
                target = required;
            }
        }
        if (target == samples.length) {
            return samples;
        }
        float[] padded = new float[target];
        System.arraycopy(samples, 0, padded, 0, samples.length);
        return padded;
    }

    private int languageId(String language) throws IOException {
        if (language == null || language.isEmpty()) {
            return LANGUAGE_IDS.get("en");
        }
        String code = language.toLowerCase(Locale.US);
        int dash = code.indexOf('-');
        if (dash > 0) {
            code = code.substring(0, dash);
        }
        Integer id = LANGUAGE_IDS.get(code);
        if (id == null) {
            throw new IOException("Whisper large-v3-turbo has no language " + language);
        }
        return id;
    }

    /** Hint only. An unknown code stays unset so the decoder can pick the language token. */
    private int languageHint(String language) {
        if (language == null || language.isEmpty()) {
            return -1;
        }
        Integer id = LANGUAGE_IDS.get(AsrLanguageHint.baseCode(language));
        return id == null ? -1 : id;
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
        float[] mel = new float[N_MELS * frames];
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
            for (int melIndex = 0; melIndex < N_MELS; melIndex++) {
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

    private OnnxTensor copyFloat(OnnxTensor tensor) throws OrtException {
        long[] shape = tensor.getInfo().getShape();
        FloatBuffer buffer = tensor.getFloatBuffer();
        float[] data = new float[buffer.remaining()];
        buffer.get(data);
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape);
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

    static final class Transcripts {
        final String firstText;
        final double firstScore;
        final String secondText;
        final double secondScore;

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
