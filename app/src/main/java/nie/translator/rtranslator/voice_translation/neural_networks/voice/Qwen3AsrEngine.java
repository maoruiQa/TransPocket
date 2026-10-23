package nie.translator.rtranslator.voice_translation.neural_networks.voice;

import android.util.JsonReader;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

/**
 * Qwen3-ASR ONNX runner (encoder + decoder_init + decoder_step).
 * andrewleech export. int4 keeps decoder weights in MatMulNBits and the encoder
 * in float32. fp32 uses the same graphs with decoder_weights.data. Embeddings
 * stay float16 in both packages.
 * The graph contract matches transcribe-rs: encoder input "mel" [1, 128, T];
 * decoder_init takes input_ids, position_ids, audio_features and audio_offset;
 * decoder_step takes input_embeds plus the KV cache.
 */
public class Qwen3AsrEngine {
    static final int VARIANT_06 = 1;
    static final int VARIANT_17 = 2;

    private static final int SAMPLE_RATE = 16000;
    private static final int N_FFT = 400;
    private static final int HOP = 160;
    private static final int N_MELS = 128;
    private static final int IM_START = 151644;
    private static final int IM_END = 151645;
    private static final int AUDIO_START = 151669;
    private static final int AUDIO_END = 151670;
    private static final int AUDIO_PAD = 151676;
    private static final int ASR_TEXT = 151704;

    private final OrtEnvironment env;
    private final OrtSession encoder;
    private final OrtSession decoderInit;
    private final OrtSession decoderStep;
    private final int hiddenSize;
    private final long[] eosIds;
    private final HashMap<Integer, String> idToToken = new HashMap<>();
    private final HashMap<String, Integer> tokenToId = new HashMap<>();
    private final File embedFile;
    private final int[] byteToUnicode = new int[256];
    private final HashMap<Integer, Integer> unicodeToByte = new HashMap<>();
    private final float[] cosTable;
    private final float[] sinTable;
    private final float[][] melBank;

    public Qwen3AsrEngine(File modelDir, boolean fp32) throws IOException, OrtException {
        String encoderName = fp32 ? "encoder.onnx" : "encoder.int4.onnx";
        String initName = fp32 ? "decoder_init.onnx" : "decoder_init.int4.onnx";
        String stepName = fp32 ? "decoder_step.onnx" : "decoder_step.int4.onnx";
        String weightsName = fp32 ? "decoder_weights.data" : "decoder_weights.int4.data";
        File encoderFile = new File(modelDir, encoderName);
        File initFile = new File(modelDir, initName);
        File stepFile = new File(modelDir, stepName);
        embedFile = new File(modelDir, "embed_tokens.bin");
        File weightsFile = new File(modelDir, weightsName);
        File configFile = new File(modelDir, "config.json");
        File tokenizerFile = new File(modelDir, "tokenizer.json");
        if (!encoderFile.isFile() || !initFile.isFile() || !stepFile.isFile() || !weightsFile.isFile() || !embedFile.isFile() || !configFile.isFile() || !tokenizerFile.isFile()) {
            throw new IOException("Qwen3-ASR files are missing");
        }
        try {
            JSONObject config = new JSONObject(readUtf8(configFile));
            hiddenSize = config.getJSONObject("decoder").getInt("hidden_size");
            JSONArray eos = config.getJSONObject("special_tokens").getJSONArray("eos_token_ids");
            eosIds = new long[eos.length()];
            for (int i = 0; i < eos.length(); i++) {
                eosIds[i] = eos.getLong(i);
            }
        } catch (JSONException e) {
            throw new IOException("Qwen3-ASR config.json is not usable", e);
        }
        loadVocab(tokenizerFile);
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
        encoder = env.createSession(encoderFile.getAbsolutePath(), options);
        decoderInit = env.createSession(initFile.getAbsolutePath(), options);
        decoderStep = env.createSession(stepFile.getAbsolutePath(), options);
    }

    public void close() {
        closeSession(encoder);
        closeSession(decoderInit);
        closeSession(decoderStep);
    }

    public Transcript transcribe(float[] samples) throws OrtException, IOException {
        return transcribe(samples, Integer.MAX_VALUE);
    }

    /**
     * One short silent pass. The first real execution loads the session and is slow
     * enough to stall capture, so it runs before the microphone opens.
     */
    public void warmup() throws OrtException, IOException {
        transcribe(new float[SAMPLE_RATE], 2);
    }

    private Transcript transcribe(float[] samples, int decoderSteps) throws OrtException, IOException {
        if (samples == null || samples.length < HOP) {
            return new Transcript("", null);
        }
        float[] mel = logMel(samples);
        int frames = mel.length / N_MELS;
        OnnxTensor melTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(mel), new long[]{1, N_MELS, frames});
        Map<String, OnnxTensor> encInputs = new LinkedHashMap<>();
        encInputs.put("mel", melTensor);
        OrtSession.Result encOut = encoder.run(encInputs);
        melTensor.close();
        OnnxTensor featuresTensor = (OnnxTensor) encOut.get(0);
        TensorCopy features = copy(featuresTensor);
        encOut.close();
        int audioTokens = features.shape.length >= 3 ? (int) features.shape[1] : (features.shape.length == 2 ? (int) features.shape[0] : 0);
        if (audioTokens <= 0) {
            return new Transcript("", null);
        }

        int[] roles = roleIds();
        // Empty system turn, then the user audio turn. The ONNX decoder scatters
        // encoder features at the first <|audio_pad|>, so the offset is that index.
        long[] prompt = buildPrompt(audioTokens, roles[0], roles[1], roles[2], roles[3]);
        int promptLen = prompt.length;
        int audioOffset = 0;
        for (int i = 0; i < promptLen; i++) {
            if (prompt[i] == AUDIO_PAD) {
                audioOffset = i;
                break;
            }
        }
        long[] positions = new long[promptLen];
        for (int i = 0; i < promptLen; i++) {
            positions[i] = i;
        }
        OnnxTensor idsTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(prompt), new long[]{1, promptLen});
        OnnxTensor posTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(positions), new long[]{1, promptLen});
        OnnxTensor audioTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(features.data), features.shape);
        OnnxTensor offsetTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(new long[]{audioOffset}), new long[]{1});
        Map<String, OnnxTensor> initInputs = new LinkedHashMap<>();
        initInputs.put("input_ids", idsTensor);
        initInputs.put("position_ids", posTensor);
        initInputs.put("audio_features", audioTensor);
        initInputs.put("audio_offset", offsetTensor);
        OrtSession.Result initOut = decoderInit.run(initInputs);
        idsTensor.close();
        posTensor.close();
        audioTensor.close();
        offsetTensor.close();

        TensorCopy logits = copy(required(initOut, "logits"));
        TensorCopy pastKeys = copy(required(initOut, "present_keys"));
        TensorCopy pastValues = copy(required(initOut, "present_values"));
        initOut.close();

        int vocab = (int) logits.shape[logits.shape.length - 1];
        int last = (int) logits.shape[1] - 1;
        long token = argmax(logits.data, last * vocab, vocab);
        ArrayList<Long> generated = new ArrayList<>();
        generated.add(token);
        int cap = tokenCap(samples.length);
        long position = promptLen;
        boolean eos = isEos(token);
        int stepLimit = Math.min(cap, Math.max(1, decoderSteps));
        for (int step = 1; step < stepLimit && !eos; step++) {
            float[] embed = lookupEmbed(token);
            OnnxTensor embedTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(embed), new long[]{1, 1, hiddenSize});
            OnnxTensor stepPos = OnnxTensor.createTensor(env, LongBuffer.wrap(new long[]{position}), new long[]{1, 1});
            OnnxTensor keysTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(pastKeys.data), pastKeys.shape);
            OnnxTensor valuesTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(pastValues.data), pastValues.shape);
            Map<String, OnnxTensor> stepInputs = new LinkedHashMap<>();
            stepInputs.put("input_embeds", embedTensor);
            stepInputs.put("position_ids", stepPos);
            stepInputs.put("past_keys", keysTensor);
            stepInputs.put("past_values", valuesTensor);
            OrtSession.Result stepOut = decoderStep.run(stepInputs);
            embedTensor.close();
            stepPos.close();
            keysTensor.close();
            valuesTensor.close();
            TensorCopy stepLogits = copy(required(stepOut, "logits"));
            pastKeys = copy(required(stepOut, "present_keys"));
            pastValues = copy(required(stepOut, "present_values"));
            stepOut.close();
            int stepVocab = (int) stepLogits.shape[stepLogits.shape.length - 1];
            token = argmax(stepLogits.data, 0, stepVocab);
            position++;
            if (isEos(token)) {
                eos = true;
                break;
            }
            generated.add(token);
        }
        return decode(generated);
    }

    private Transcript decode(ArrayList<Long> generated) {
        int split = -1;
        for (int i = 0; i < generated.size(); i++) {
            if (generated.get(i) == ASR_TEXT) {
                split = i;
                break;
            }
        }
        String language = null;
        String text;
        if (split >= 0) {
            language = languageCode(decodeIds(generated, 0, split).trim());
            text = decodeIds(generated, split + 1, generated.size()).trim();
        } else {
            String raw = decodeIds(generated, 0, generated.size()).trim();
            if (raw.startsWith("language ")) {
                int newline = raw.indexOf('\n');
                if (newline > 9) {
                    language = languageCode(raw.substring(9, newline).trim());
                    text = raw.substring(newline + 1).trim();
                } else {
                    text = raw;
                }
            } else {
                text = raw;
            }
        }
        return new Transcript(text, language);
    }

    private String decodeIds(ArrayList<Long> ids, int from, int to) {
        StringBuilder pieces = new StringBuilder();
        for (int i = from; i < to; i++) {
            String piece = idToToken.get(ids.get(i).intValue());
            if (piece != null && !piece.startsWith("<|")) {
                pieces.append(piece);
            }
        }
        // Byte-level BPE pieces map back to UTF-8. A raw character that is not in
        // that map (some Qwen vocab entries) is already text and must be kept.
        java.io.ByteArrayOutputStream raw = new java.io.ByteArrayOutputStream();
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < pieces.length(); ) {
            int codePoint = pieces.codePointAt(i);
            i += Character.charCount(codePoint);
            Integer b = codePoint <= 0xffff ? unicodeToByte.get(codePoint) : null;
            if (b != null) {
                raw.write(b);
            } else {
                flushUtf8(raw, text);
                text.appendCodePoint(codePoint);
            }
        }
        flushUtf8(raw, text);
        return text.toString();
    }

    private static void flushUtf8(java.io.ByteArrayOutputStream raw, StringBuilder text) {
        if (raw.size() == 0) {
            return;
        }
        text.append(new String(raw.toByteArray(), StandardCharsets.UTF_8));
        raw.reset();
    }

    private String languageCode(String name) {
        if (name.startsWith("language ")) {
            name = name.substring(9).trim();
        }
        String[][] table = new String[][]{
                {"Chinese", "zh"}, {"English", "en"}, {"Cantonese", "yue"}, {"Arabic", "ar"},
                {"German", "de"}, {"French", "fr"}, {"Spanish", "es"}, {"Portuguese", "pt"},
                {"Indonesian", "id"}, {"Italian", "it"}, {"Korean", "ko"}, {"Russian", "ru"},
                {"Thai", "th"}, {"Vietnamese", "vi"}, {"Japanese", "ja"}, {"Turkish", "tr"},
                {"Hindi", "hi"}, {"Malay", "ms"}, {"Dutch", "nl"}, {"Swedish", "sv"},
                {"Danish", "da"}, {"Finnish", "fi"}, {"Polish", "pl"}, {"Czech", "cs"},
                {"Filipino", "fil"}, {"Persian", "fa"}, {"Greek", "el"}, {"Hungarian", "hu"},
                {"Macedonian", "mk"}, {"Romanian", "ro"}
        };
        for (String[] row : table) {
            if (row[0].equalsIgnoreCase(name)) {
                return row[1];
            }
        }
        return name.toLowerCase(Locale.US);
    }

    private float[] lookupEmbed(long tokenId) throws IOException {
        if (tokenId < 0) {
            throw new IOException("Bad token id");
        }
        long offset = tokenId * hiddenSize * 2L;
        byte[] raw = new byte[hiddenSize * 2];
        try (FileInputStream in = new FileInputStream(embedFile)) {
            long skipped = 0;
            while (skipped < offset) {
                long n = in.skip(offset - skipped);
                if (n <= 0) {
                    throw new IOException("embed_tokens.bin is shorter than the token table");
                }
                skipped += n;
            }
            int read = 0;
            while (read < raw.length) {
                int n = in.read(raw, read, raw.length - read);
                if (n < 0) {
                    throw new IOException("embed_tokens.bin ended inside a row");
                }
                read += n;
            }
        }
        ByteBuffer buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        float[] row = new float[hiddenSize];
        for (int i = 0; i < hiddenSize; i++) {
            row[i] = halfToFloat(buffer.getShort() & 0xffff);
        }
        return row;
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

    private int[] roleIds() {
        int system = tokenOr("system", 8948);
        int user = tokenOr("user", 872);
        int assistant = tokenOr("assistant", 77091);
        int newline = tokenToId.containsKey("Ċ") ? tokenToId.get("Ċ") : 198;
        return new int[]{system, user, newline, assistant};
    }

    /** Empty system turn plus the user audio turn. The first pad is index 9. */
    static long[] buildPrompt(int audioTokens, int systemId, int userId, int newlineId, int assistantId) {
        long[] prompt = new long[15 + audioTokens];
        int cursor = 0;
        prompt[cursor++] = IM_START;
        prompt[cursor++] = systemId;
        prompt[cursor++] = newlineId;
        prompt[cursor++] = IM_END;
        prompt[cursor++] = newlineId;
        prompt[cursor++] = IM_START;
        prompt[cursor++] = userId;
        prompt[cursor++] = newlineId;
        prompt[cursor++] = AUDIO_START;
        for (int i = 0; i < audioTokens; i++) {
            prompt[cursor++] = AUDIO_PAD;
        }
        prompt[cursor++] = AUDIO_END;
        prompt[cursor++] = IM_END;
        prompt[cursor++] = newlineId;
        prompt[cursor++] = IM_START;
        prompt[cursor++] = assistantId;
        prompt[cursor++] = newlineId;
        return prompt;
    }

    private int tokenOr(String token, int fallback) {
        Integer id = tokenToId.get(token);
        return id == null ? fallback : id;
    }

    private void loadVocab(File tokenizerFile) throws IOException {
        try (JsonReader reader = new JsonReader(new InputStreamReader(new FileInputStream(tokenizerFile), StandardCharsets.UTF_8))) {
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if ("model".equals(name)) {
                    reader.beginObject();
                    while (reader.hasNext()) {
                        String modelName = reader.nextName();
                        if ("vocab".equals(modelName)) {
                            reader.beginObject();
                            while (reader.hasNext()) {
                                String token = reader.nextName();
                                int id = reader.nextInt();
                                idToToken.put(id, token);
                                tokenToId.put(token, id);
                            }
                            reader.endObject();
                        } else {
                            reader.skipValue();
                        }
                    }
                    reader.endObject();
                } else {
                    reader.skipValue();
                }
            }
            reader.endObject();
        }
        if (idToToken.isEmpty()) {
            throw new IOException("Qwen3-ASR tokenizer has no vocabulary");
        }
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

    private boolean isEos(long token) {
        for (long eos : eosIds) {
            if (token == eos) {
                return true;
            }
        }
        return false;
    }

    private static int tokenCap(int sampleCount) {
        int seconds = Math.max(1, sampleCount / SAMPLE_RATE);
        return Math.min(192, Math.max(48, seconds * 12 + 16));
    }

    private static long argmax(float[] logits, int offset, int vocab) {
        int best = 0;
        float bestValue = -Float.MAX_VALUE;
        for (int i = 0; i < vocab; i++) {
            float value = logits[offset + i];
            if (value > bestValue) {
                bestValue = value;
                best = i;
            }
        }
        return best;
    }

    private static OnnxTensor required(OrtSession.Result result, String name) throws OrtException {
        if (result.get(name).isPresent()) {
            return (OnnxTensor) result.get(name).get();
        }
        throw new OrtException("Qwen3-ASR output missing: " + name);
    }

    private static TensorCopy copy(OnnxTensor tensor) throws OrtException {
        long[] shape = tensor.getInfo().getShape();
        return new TensorCopy(shape, OnnxFloats.toFloats(tensor));
    }

    private static void closeSession(OrtSession session) {
        if (session != null) {
            try {
                session.close();
            } catch (OrtException ignored) {
            }
        }
    }

    private static String readUtf8(File file) throws IOException {
        byte[] data = new byte[(int) file.length()];
        try (FileInputStream in = new FileInputStream(file)) {
            int read = 0;
            while (read < data.length) {
                int n = in.read(data, read, data.length - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
        }
        return new String(data, StandardCharsets.UTF_8);
    }

    private static float halfToFloat(int bits) {
        int sign = (bits & 0x8000) << 16;
        int exp = (bits >> 10) & 0x1f;
        int mant = bits & 0x3ff;
        if (exp == 0) {
            if (mant == 0) {
                return Float.intBitsToFloat(sign);
            }
            exp = 1;
            while ((mant & 0x400) == 0) {
                mant <<= 1;
                exp--;
            }
            mant &= 0x3ff;
            int outExp = exp + (127 - 15);
            return Float.intBitsToFloat(sign | (outExp << 23) | (mant << 13));
        }
        if (exp == 31) {
            return Float.intBitsToFloat(sign | 0x7f800000 | (mant << 13));
        }
        return Float.intBitsToFloat(sign | ((exp + (127 - 15)) << 23) | (mant << 13));
    }

    public static final class Transcript {
        public final String text;
        public final String languageCode;

        Transcript(String text, String languageCode) {
            this.text = text == null ? "" : text;
            this.languageCode = languageCode;
        }
    }

    private static final class TensorCopy {
        final long[] shape;
        final float[] data;

        TensorCopy(long[] shape, float[] data) {
            this.shape = shape;
            this.data = data;
        }
    }
}
