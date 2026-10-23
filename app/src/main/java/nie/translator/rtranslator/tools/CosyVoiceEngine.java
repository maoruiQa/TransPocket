package nie.translator.rtranslator.tools;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import ai.onnxruntime.OnnxJavaType;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

/**
 * Fun-CosyVoice 3 streaming session on ONNX Runtime.
 * Text arrives a clause at a time. About every 25 speech tokens, the new mel
 * frames are vocoded and handed to the player. The published graph is the
 * FP16 ONNX export; a smaller quantized pack is not published for this runtime.
 */
public final class CosyVoiceEngine {
    public static final int SAMPLE_RATE = 24000;
    private static final int HIDDEN = 896;
    private static final int SOS = 6561;
    private static final int EOS = 6562;
    private static final int TASK = 6563;
    private static final int TOKEN_MEL = 2;
    private static final int FLOW_STEPS = 10;
    private static final int CHUNK_TOKENS = 25;

    private final OrtEnvironment env = OrtEnvironment.getEnvironment();
    private final File dir;
    private final Map<String, OrtSession> sessions = new HashMap<>();
    private Qwen2Tokenizer tokenizer;
    private volatile boolean cancelled;

    public interface PcmSink {
        /** @return false to stop synthesis */
        boolean onSamples(float[] samples);
    }

    public CosyVoiceEngine(File dir) throws Exception {
        this.dir = dir;
        open("text", "text_embedding_fp32.onnx");
        open("llm_initial", "llm_backbone_initial_fp16.onnx");
        open("llm_decode", "llm_backbone_decode_fp16.onnx");
        open("llm_decoder", "llm_decoder_fp16.onnx");
        open("speech_embed", "llm_speech_embedding_fp16.onnx");
        open("flow_embed", "flow_token_embedding_fp16.onnx");
        open("flow_spk", "flow_speaker_projection_fp16.onnx");
        open("flow_lookahead", "flow_pre_lookahead_fp16.onnx");
        open("flow", "flow.decoder.estimator.fp16.onnx");
        open("hift_f0", "hift_f0_predictor_fp32.onnx");
        open("hift_source", "hift_source_generator_fp32.onnx");
        open("hift", "hift_decoder_fp32.onnx");
        File vocab = LiveDraftVoices.findNamed(dir, "vocab.json");
        File merges = LiveDraftVoices.findNamed(dir, "merges.txt");
        if (vocab == null || merges == null) {
            throw new IOException("CosyVoice tokenizer is missing");
        }
        tokenizer = Qwen2Tokenizer.load(vocab, merges);
    }

    public void cancel() {
        cancelled = true;
    }

    public void close() {
        cancelled = true;
        for (OrtSession session : sessions.values()) {
            try {
                session.close();
            } catch (Throwable ignored) {
            }
        }
        sessions.clear();
    }

    public void synthesize(String text, LiveDraftVoices.Speaker voice, PcmSink sink) throws Exception {
        cancelled = false;
        if (text == null || text.trim().isEmpty() || voice == null) {
            return;
        }
        Prompt prompt = prompt(voice);
        int[] textIds = tokenizer.encode(text);
        if (textIds.length == 0 || cancelled) {
            return;
        }
        int minLen = Math.max(10, textIds.length * 2);
        int maxLen = Math.min(500, Math.max(minLen, textIds.length * 20));
        float[] textEmb = embedText(textIds);
        float[] sos = embedSpeech(new long[]{SOS});
        float[] task = embedSpeech(new long[]{TASK});
        float[] promptSpeech = embedSpeech(prompt.tokens);
        float[] lm = concat(new float[][]{sos, textEmb, task, promptSpeech}, HIDDEN);
        int initial = lm.length / HIDDEN;
        OnnxTensor cache = null;
        OnnxTensor inputs = null;
        OnnxTensor mask = null;
        OrtSession.Result initialResult = null;
        try {
            inputs = floats(lm, new long[]{1, initial, HIDDEN});
            mask = floats(ones(initial), new long[]{1, initial});
            Map<String, OnnxTensor> feed = new HashMap<>();
            feed.put("inputs_embeds", inputs);
            feed.put("attention_mask", mask);
            initialResult = sessions.get("llm_initial").run(feed);
            float[] hidden = lastStep(tensor(initialResult, 0), HIDDEN);
            cache = copyTensor(tensor(initialResult, 1));
            float[] logits = row(tensor(runDecoder(hidden), 0));
            close(initialResult);
            initialResult = null;
            long[] spoken = new long[maxLen];
            int count = 0;
            int voicedTokens = 0;
            Random random = new Random();
            for (int i = 0; i < maxLen && !cancelled; i++) {
                int token = sample(logits, 25, random);
                if (token == EOS && i >= minLen) {
                    break;
                }
                spoken[count++] = token;
                if (count - voicedTokens >= CHUNK_TOKENS) {
                    if (!vocodeNew(prompt, spoken, count, voicedTokens, sink)) {
                        return;
                    }
                    voicedTokens = count;
                }
                float[] next = embedSpeech(new long[]{token});
                int total = initial + count;
                OnnxTensor stepIn = floats(next, new long[]{1, 1, HIDDEN});
                OnnxTensor stepMask = floats(ones(total), new long[]{1, total});
                Map<String, OnnxTensor> step = new HashMap<>();
                step.put("inputs_embeds", stepIn);
                step.put("attention_mask", stepMask);
                step.put("past_key_values", cache);
                OrtSession.Result decoded = null;
                try {
                    decoded = sessions.get("llm_decode").run(step);
                    hidden = lastStep(tensor(decoded, 0), HIDDEN);
                    OnnxTensor nextCache = copyTensor(tensor(decoded, 1));
                    cache.close();
                    cache = nextCache;
                } finally {
                    stepIn.close();
                    stepMask.close();
                    close(decoded);
                }
                OrtSession.Result logitResult = runDecoder(hidden);
                try {
                    logits = row(tensor(logitResult, 0));
                } finally {
                    close(logitResult);
                }
            }
            if (!cancelled && count > voicedTokens) {
                vocodeNew(prompt, spoken, count, voicedTokens, sink);
            }
        } finally {
            close(initialResult);
            if (inputs != null) {
                inputs.close();
            }
            if (mask != null) {
                mask.close();
            }
            if (cache != null) {
                cache.close();
            }
        }
    }

    private boolean vocodeNew(Prompt prompt, long[] spoken, int count, int already, PcmSink sink) throws Exception {
        long[] generated = Arrays.copyOf(spoken, count);
        float[] mel = flow(prompt, generated);
        int frames = mel.length / 80;
        int previous = Math.max(0, (already * TOKEN_MEL));
        if (frames <= previous) {
            return true;
        }
        float[] slice = Arrays.copyOfRange(mel, previous * 80, frames * 80);
        float[] audio = hift(slice, frames - previous);
        return sink == null || sink.onSamples(audio);
    }

    private float[] flow(Prompt prompt, long[] generated) throws Exception {
        long[] all = new long[prompt.tokens.length + generated.length];
        System.arraycopy(prompt.tokens, 0, all, 0, prompt.tokens.length);
        System.arraycopy(generated, 0, all, prompt.tokens.length, generated.length);
        float[] norm = normalize(prompt.speaker);
        float[] spks = runFlat("flow_spk", "embedding", floats(norm, new long[]{1, norm.length}));
        float[] embedded = runFlat("flow_embed", "token", longs(all, new long[]{1, all.length}));
        int embedDim = embedded.length / all.length;
        float[] looked = runFlat("flow_lookahead", "token_embedded", floats(embedded, new long[]{1, all.length, embedDim}));
        int melLen = looked.length / embedDim;
        int promptFrames = prompt.tokens.length * TOKEN_MEL;
        float[] conds = new float[80 * melLen];
        if (promptFrames > 0 && prompt.melFrames > 0) {
            int use = Math.min(promptFrames, melLen);
            for (int frame = 0; frame < use; frame++) {
                int source = Math.min(prompt.melFrames - 1, frame * prompt.melFrames / Math.max(1, promptFrames));
                for (int bin = 0; bin < 80; bin++) {
                    conds[bin * melLen + frame] = prompt.mel[source * 80 + bin];
                }
            }
        }
        float[] mu = new float[embedDim * melLen];
        for (int frame = 0; frame < melLen; frame++) {
            for (int bin = 0; bin < embedDim; bin++) {
                mu[bin * melLen + frame] = looked[frame * embedDim + bin];
            }
        }
        float[] x = new float[80 * melLen];
        Random random = new Random(generated.length * 31L + prompt.tokens.length);
        for (int i = 0; i < x.length; i++) {
            x[i] = (float) random.nextGaussian();
        }
        float[] mask = ones(melLen);
        float[] xBatch = tile(x, 2);
        float[] maskBatch = tile(mask, 2);
        float[] muBatch = tile(mu, 2);
        float[] spkBatch = tile(spks, 2);
        float[] condBatch = tile(conds, 2);
        for (int step = 0; step < FLOW_STEPS && !cancelled; step++) {
            float t = step / (float) FLOW_STEPS;
            Map<String, OnnxTensor> feed = new HashMap<>();
            OnnxTensor xTensor = floats(xBatch, new long[]{2, 80, melLen});
            OnnxTensor maskTensor = floats(maskBatch, new long[]{2, 1, melLen});
            OnnxTensor muTensor = floats(muBatch, new long[]{2, embedDim, melLen});
            OnnxTensor tTensor = floats(new float[]{t, t}, new long[]{2});
            OnnxTensor spkTensor = floats(spkBatch, new long[]{2, spks.length});
            OnnxTensor condTensor = floats(condBatch, new long[]{2, 80, melLen});
            feed.put("x", xTensor);
            feed.put("mask", maskTensor);
            feed.put("mu", muTensor);
            feed.put("t", tTensor);
            feed.put("spks", spkTensor);
            feed.put("cond", condTensor);
            OrtSession.Result result = null;
            try {
                result = sessions.get("flow").run(feed);
                float[] velocity = flat(tensor(result, 0));
                float dt = 1f / FLOW_STEPS;
                int limit = Math.min(xBatch.length, velocity.length);
                for (int i = 0; i < limit; i++) {
                    xBatch[i] += velocity[i] * dt;
                }
            } finally {
                xTensor.close();
                maskTensor.close();
                muTensor.close();
                tTensor.close();
                spkTensor.close();
                condTensor.close();
                close(result);
            }
        }
        int generatedFrames = Math.max(0, melLen - promptFrames);
        float[] mel = new float[generatedFrames * 80];
        for (int frame = 0; frame < generatedFrames; frame++) {
            for (int bin = 0; bin < 80; bin++) {
                mel[frame * 80 + bin] = xBatch[bin * melLen + promptFrames + frame];
            }
        }
        return mel;
    }

    private float[] hift(float[] melFrames, int frames) throws Exception {
        if (frames <= 0) {
            return new float[0];
        }
        float[] mel = new float[80 * frames];
        for (int frame = 0; frame < frames; frame++) {
            for (int bin = 0; bin < 80; bin++) {
                mel[bin * frames + frame] = melFrames[frame * 80 + bin];
            }
        }
        float[] f0 = runFlat("hift_f0", "mel", floats(mel, new long[]{1, 80, frames}));
        float[] source = runFlat("hift_source", "f0", floats(f0, new long[]{1, 1, f0.length}));
        float[][] stft = stft(source);
        int time = (int) stft[2][0];
        float[] packed = new float[18 * time];
        System.arraycopy(stft[0], 0, packed, 0, Math.min(stft[0].length, 9 * time));
        System.arraycopy(stft[1], 0, packed, 9 * time, Math.min(stft[1].length, 9 * time));
        Map<String, OnnxTensor> feed = new HashMap<>();
        OnnxTensor melTensor = floats(mel, new long[]{1, 80, frames});
        OnnxTensor stftTensor = floats(packed, new long[]{1, 18, time});
        feed.put("mel", melTensor);
        feed.put("source_stft", stftTensor);
        OrtSession.Result result = null;
        try {
            result = sessions.get("hift").run(feed);
            float[] magnitude = flat(tensor(result, 0));
            float[] phase = flat(tensor(result, 1));
            int specTime = magnitude.length / 9;
            return istft(magnitude, phase, specTime);
        } finally {
            melTensor.close();
            stftTensor.close();
            close(result);
        }
    }

    private Prompt prompt(LiveDraftVoices.Speaker voice) throws Exception {
        File cache = new File(dir, "prompt-" + voice.id + ".bin");
        if (cache.isFile() && cache.length() > 0) {
            return readPrompt(cache);
        }
        File wav = LiveDraftVoices.findNamed(dir, voice.promptWav);
        if (wav == null) {
            throw new IOException("CosyVoice prompt is missing");
        }
        float[] audio16 = resample(readWav(wav), wavRate(wav), 16000);
        float[] audio24 = resample(readWav(wav), wavRate(wav), SAMPLE_RATE);
        float[] speaker = speakerEmbedding(audio16);
        long[] tokens = speechTokens(audio16);
        float[] mel = promptMel(audio24);
        Prompt prompt = new Prompt(speaker, tokens, mel, mel.length / 80);
        writePrompt(cache, prompt);
        closeSession("campplus");
        closeSession("speech_tokenizer");
        return prompt;
    }

    private float[] speakerEmbedding(float[] audio) throws Exception {
        float[][] filters = melBank(16000, 400, 80, 20f, 7600f);
        float[] mel = logMel(audio, 16000, 400, 160, filters, false);
        int frames = mel.length / 80;
        for (int bin = 0; bin < 80; bin++) {
            float mean = 0f;
            for (int frame = 0; frame < frames; frame++) {
                mean += mel[frame * 80 + bin];
            }
            mean /= Math.max(1, frames);
            for (int frame = 0; frame < frames; frame++) {
                mel[frame * 80 + bin] -= mean;
            }
        }
        ensure("campplus", "campplus.onnx");
        String input = sessions.get("campplus").getInputNames().iterator().next();
        return runFlat("campplus", input, floats(mel, new long[]{1, frames, 80}));
    }

    private long[] speechTokens(float[] audio) throws Exception {
        float[][] filters = melBank(16000, 400, 128, 0f, 8000f);
        float[] mel = logMel(audio, 16000, 400, 160, filters, true);
        int frames = mel.length / 128;
        float[] channelFirst = new float[mel.length];
        for (int frame = 0; frame < frames; frame++) {
            for (int bin = 0; bin < 128; bin++) {
                channelFirst[bin * frames + frame] = mel[frame * 128 + bin];
            }
        }
        ensure("speech_tokenizer", "speech_tokenizer_v3.onnx");
        OrtSession session = sessions.get("speech_tokenizer");
        String features = session.getInputNames().iterator().next();
        String lengthName = null;
        for (String name : session.getInputNames()) {
            if (!name.equals(features)) {
                lengthName = name;
            }
        }
        OnnxTensor feat = floats(channelFirst, new long[]{1, 128, frames});
        OnnxTensor length = OnnxTensor.createTensor(env, new int[]{frames});
        Map<String, OnnxTensor> feed = new HashMap<>();
        feed.put(features, feat);
        if (lengthName != null) {
            feed.put(lengthName, length);
        }
        OrtSession.Result result = null;
        try {
            result = session.run(feed);
            float[] values = flat(tensor(result, 0));
            long[] tokens = new long[values.length];
            for (int i = 0; i < values.length; i++) {
                tokens[i] = Math.round(values[i]);
            }
            return tokens;
        } finally {
            feat.close();
            length.close();
            close(result);
        }
    }

    private float[] promptMel(float[] audio) {
        float[][] filters = melBank(SAMPLE_RATE, 1024, 80, 0f, 12000f);
        return logMel(audio, SAMPLE_RATE, 1024, 256, filters, false);
    }

    private float[] embedText(int[] ids) throws Exception {
        long[] values = new long[ids.length];
        for (int i = 0; i < ids.length; i++) {
            values[i] = ids[i];
        }
        return runFlat("text", "input_ids", longs(values, new long[]{1, values.length}));
    }

    private float[] embedSpeech(long[] tokens) throws Exception {
        if (tokens.length == 0) {
            return new float[0];
        }
        return runFlat("speech_embed", "token", longs(tokens, new long[]{1, tokens.length}));
    }

    private OrtSession.Result runDecoder(float[] hidden) throws OrtException {
        OnnxTensor tensor = floats(hidden, new long[]{1, 1, hidden.length});
        Map<String, OnnxTensor> feed = new HashMap<>();
        feed.put("hidden_state", tensor);
        try {
            return sessions.get("llm_decoder").run(feed);
        } finally {
            tensor.close();
        }
    }

    private float[] runFlat(String name, String input, OnnxTensor tensor) throws OrtException {
        Map<String, OnnxTensor> feed = new HashMap<>();
        feed.put(input, tensor);
        OrtSession.Result result = null;
        try {
            result = sessions.get(name).run(feed);
            return flat(tensor(result, 0));
        } finally {
            tensor.close();
            close(result);
        }
    }

    private void open(String name, String file) throws OrtException {
        ensure(name, file);
    }

    private void ensure(String name, String file) throws OrtException {
        if (sessions.containsKey(name)) {
            return;
        }
        File model = LiveDraftVoices.findNamed(dir, file);
        if (model == null) {
            throw new OrtException("Missing " + file);
        }
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setIntraOpNumThreads(2);
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT);
        sessions.put(name, env.createSession(model.getAbsolutePath(), options));
    }

    private void closeSession(String name) {
        OrtSession session = sessions.remove(name);
        if (session != null) {
            try {
                session.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private OnnxTensor floats(float[] data, long[] shape) throws OrtException {
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape);
    }

    private OnnxTensor longs(long[] data, long[] shape) throws OrtException {
        return OnnxTensor.createTensor(env, LongBuffer.wrap(data), shape);
    }

    private OnnxTensor copyTensor(OnnxTensor tensor) throws OrtException {
        long[] shape = tensor.getInfo().getShape();
        int count = count(shape);
        if (tensor.getInfo().type == OnnxJavaType.FLOAT16) {
            ByteBuffer raw = tensor.getByteBuffer().duplicate().order(ByteOrder.LITTLE_ENDIAN);
            raw.position(0);
            ByteBuffer copy = ByteBuffer.allocateDirect(count * 2).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < count; i++) {
                copy.putShort(raw.getShort());
            }
            copy.rewind();
            return OnnxTensor.createTensor(env, copy, shape, OnnxJavaType.FLOAT16);
        }
        return floats(toFloats(tensor), shape);
    }

    private static OnnxTensor tensor(OrtSession.Result result, int index) throws OrtException {
        if (result == null) {
            throw new OrtException("CosyVoice produced no output");
        }
        OnnxValue value = result.get(index);
        if (!(value instanceof OnnxTensor)) {
            throw new OrtException("CosyVoice output " + index + " is missing");
        }
        return (OnnxTensor) value;
    }

    private static float[] flat(OnnxTensor tensor) throws OrtException {
        return toFloats(tensor);
    }

    private static float[] row(OnnxTensor tensor) throws OrtException {
        float[] data = toFloats(tensor);
        long[] shape = tensor.getInfo().getShape();
        if (shape.length == 0) {
            return data;
        }
        int width = (int) shape[shape.length - 1];
        if (width <= 0 || width >= data.length) {
            return data;
        }
        return Arrays.copyOfRange(data, data.length - width, data.length);
    }

    private static float[] lastStep(OnnxTensor tensor, int hidden) throws OrtException {
        float[] data = toFloats(tensor);
        if (data.length <= hidden) {
            return data;
        }
        return Arrays.copyOfRange(data, data.length - hidden, data.length);
    }

    private static float[] toFloats(OnnxTensor tensor) throws OrtException {
        long[] shape = tensor.getInfo().getShape();
        int count = count(shape);
        if (tensor.getInfo().type == OnnxJavaType.FLOAT) {
            FloatBuffer buffer = tensor.getFloatBuffer();
            float[] data = new float[buffer.remaining()];
            buffer.get(data);
            return data;
        }
        if (tensor.getInfo().type == OnnxJavaType.INT64) {
            LongBuffer buffer = tensor.getLongBuffer();
            float[] data = new float[buffer.remaining()];
            for (int i = 0; i < data.length; i++) {
                data[i] = buffer.get();
            }
            return data;
        }
        if (tensor.getInfo().type == OnnxJavaType.INT32) {
            java.nio.IntBuffer buffer = tensor.getIntBuffer();
            float[] data = new float[buffer.remaining()];
            for (int i = 0; i < data.length; i++) {
                data[i] = buffer.get();
            }
            return data;
        }
        if (tensor.getInfo().type == OnnxJavaType.FLOAT16) {
            ByteBuffer raw = tensor.getByteBuffer().duplicate().order(ByteOrder.LITTLE_ENDIAN);
            raw.position(0);
            float[] data = new float[count];
            for (int i = 0; i < count; i++) {
                data[i] = halfToFloat(raw.getShort() & 0xffff);
            }
            return data;
        }
        throw new OrtException("Unsupported CosyVoice tensor " + tensor.getInfo().type);
    }

    private static int sample(float[] logits, int k, Random random) {
        int limit = Math.min(k, logits.length);
        int[] index = new int[limit];
        float[] value = new float[limit];
        Arrays.fill(value, Float.NEGATIVE_INFINITY);
        for (int i = 0; i < logits.length; i++) {
            if (logits[i] <= value[limit - 1]) {
                continue;
            }
            int slot = limit - 1;
            while (slot > 0 && logits[i] > value[slot - 1]) {
                value[slot] = value[slot - 1];
                index[slot] = index[slot - 1];
                slot--;
            }
            value[slot] = logits[i];
            index[slot] = i;
        }
        float max = value[0];
        double sum = 0;
        double[] prob = new double[limit];
        for (int i = 0; i < limit; i++) {
            prob[i] = Math.exp(value[i] - max);
            sum += prob[i];
        }
        double draw = random.nextDouble() * sum;
        double cumulative = 0;
        for (int i = 0; i < limit; i++) {
            cumulative += prob[i];
            if (draw <= cumulative) {
                return index[i];
            }
        }
        return index[0];
    }

    private static float[] concat(float[][] parts, int hidden) {
        int time = 0;
        for (float[] part : parts) {
            time += part.length / hidden;
        }
        float[] joined = new float[time * hidden];
        int offset = 0;
        for (float[] part : parts) {
            System.arraycopy(part, 0, joined, offset, part.length);
            offset += part.length;
        }
        return joined;
    }

    private static float[] ones(int count) {
        float[] data = new float[count];
        Arrays.fill(data, 1f);
        return data;
    }

    private static float[] tile(float[] data, int copies) {
        float[] tiled = new float[data.length * copies];
        for (int i = 0; i < copies; i++) {
            System.arraycopy(data, 0, tiled, i * data.length, data.length);
        }
        return tiled;
    }

    private static float[] normalize(float[] data) {
        double sum = 0;
        for (float value : data) {
            sum += (double) value * value;
        }
        float scale = (float) Math.sqrt(sum);
        if (scale < 1e-8f) {
            scale = 1f;
        }
        float[] out = new float[data.length];
        for (int i = 0; i < data.length; i++) {
            out[i] = data[i] / scale;
        }
        return out;
    }

    private static float[][] stft(float[] source) {
        int nFft = 16;
        int hop = 4;
        int pad = nFft / 2;
        float[] padded = new float[source.length + pad * 2];
        for (int i = 0; i < source.length; i++) {
            padded[i + pad] = source[i];
        }
        for (int i = 0; i < pad; i++) {
            padded[pad - 1 - i] = i + 1 < source.length ? source[i + 1] : 0;
            int tail = source.length - 2 - i;
            padded[pad + source.length + i] = tail >= 0 ? source[tail] : 0;
        }
        int frames = 1 + Math.max(0, (padded.length - nFft) / hop);
        int bins = nFft / 2 + 1;
        float[] window = hann(nFft);
        float[] real = new float[bins * frames];
        float[] imag = new float[bins * frames];
        for (int frame = 0; frame < frames; frame++) {
            int start = frame * hop;
            for (int k = 0; k < bins; k++) {
                double re = 0;
                double im = 0;
                for (int n = 0; n < nFft; n++) {
                    double angle = -2 * Math.PI * k * n / nFft;
                    float sample = padded[start + n] * window[n];
                    re += sample * Math.cos(angle);
                    im += sample * Math.sin(angle);
                }
                real[k * frames + frame] = (float) re;
                imag[k * frames + frame] = (float) im;
            }
        }
        return new float[][]{real, imag, new float[]{frames}};
    }

    private static float[] istft(float[] magnitude, float[] phase, int frames) {
        int nFft = 16;
        int hop = 4;
        int bins = 9;
        float[] window = hann(nFft);
        int length = nFft + Math.max(0, frames - 1) * hop;
        float[] audio = new float[length];
        float[] windowSum = new float[length];
        for (int frame = 0; frame < frames; frame++) {
            float[] time = new float[nFft];
            for (int n = 0; n < nFft; n++) {
                double sum = 0;
                for (int k = 0; k < bins; k++) {
                    int index = k * frames + frame;
                    if (index >= magnitude.length || index >= phase.length) {
                        continue;
                    }
                    double mag = Math.min(magnitude[index], 100.0);
                    double real = mag * Math.cos(phase[index]);
                    double imag = mag * Math.sin(phase[index]);
                    double angle = 2 * Math.PI * k * n / nFft;
                    sum += real * Math.cos(angle) - imag * Math.sin(angle);
                    if (k > 0 && k < bins - 1) {
                        sum += real * Math.cos(angle) - imag * Math.sin(angle);
                    }
                }
                time[n] = (float) (sum / nFft);
            }
            int start = frame * hop;
            for (int n = 0; n < nFft && start + n < length; n++) {
                audio[start + n] += time[n] * window[n];
                windowSum[start + n] += window[n] * window[n];
            }
        }
        int trim = nFft / 2;
        int outLength = Math.max(0, length - trim * 2);
        float[] trimmed = new float[outLength];
        for (int i = 0; i < outLength; i++) {
            float weight = windowSum[i + trim];
            float sample = weight < 1e-8f ? 0f : audio[i + trim] / weight;
            if (sample > 0.99f) {
                sample = 0.99f;
            } else if (sample < -0.99f) {
                sample = -0.99f;
            }
            trimmed[i] = sample;
        }
        return trimmed;
    }

    private static float[] hann(int size) {
        float[] window = new float[size];
        for (int i = 0; i < size; i++) {
            window[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / size));
        }
        return window;
    }

    private static float[] logMel(float[] audio, int sampleRate, int nFft, int hop, float[][] filters, boolean whisper) {
        int frames = 1 + Math.max(0, (audio.length - nFft) / hop);
        if (frames <= 0) {
            frames = 1;
        }
        int mels = filters.length;
        float[] spec = new float[frames * mels];
        float[] window = hann(nFft);
        float max = -100f;
        for (int frame = 0; frame < frames; frame++) {
            int start = frame * hop;
            int bins = nFft / 2 + 1;
            float[] power = new float[bins];
            for (int k = 0; k < bins; k++) {
                double real = 0;
                double imag = 0;
                for (int n = 0; n < nFft; n++) {
                    float sample = start + n < audio.length ? audio[start + n] * window[n] : 0f;
                    double angle = -2 * Math.PI * k * n / nFft;
                    real += sample * Math.cos(angle);
                    imag += sample * Math.sin(angle);
                }
                power[k] = (float) (real * real + imag * imag);
            }
            for (int mel = 0; mel < mels; mel++) {
                float sum = 0f;
                for (int k = 0; k < bins; k++) {
                    sum += filters[mel][k] * power[k];
                }
                float value;
                if (whisper) {
                    value = (float) Math.log10(Math.max(sum, 1e-10f));
                } else {
                    value = (float) Math.log(Math.max(sum, 1e-10f));
                }
                spec[frame * mels + mel] = value;
                if (value > max) {
                    max = value;
                }
            }
        }
        if (whisper) {
            for (int i = 0; i < spec.length; i++) {
                float value = Math.max(spec[i], max - 8f);
                spec[i] = (value + 4f) / 4f;
            }
        }
        return spec;
    }

    private static float[][] melBank(int sampleRate, int nFft, int nMels, float fmin, float fmax) {
        int bins = nFft / 2 + 1;
        double melMin = 2595.0 * Math.log10(1.0 + fmin / 700.0);
        double melMax = 2595.0 * Math.log10(1.0 + fmax / 700.0);
        double[] points = new double[nMels + 2];
        for (int i = 0; i < points.length; i++) {
            double mel = melMin + (melMax - melMin) * i / (nMels + 1);
            double hz = 700.0 * (Math.pow(10.0, mel / 2595.0) - 1.0);
            points[i] = Math.floor((nFft + 1) * hz / sampleRate);
        }
        float[][] weights = new float[nMels][bins];
        for (int i = 0; i < nMels; i++) {
            double lower = points[i];
            double center = points[i + 1];
            double upper = points[i + 2];
            for (int k = 0; k < bins; k++) {
                if (k > lower && k < upper) {
                    if (k < center) {
                        weights[i][k] = (float) ((k - lower) / Math.max(1e-6, center - lower));
                    } else {
                        weights[i][k] = (float) ((upper - k) / Math.max(1e-6, upper - center));
                    }
                }
            }
        }
        return weights;
    }

    private static float[] readWav(File file) throws IOException {
        byte[] bytes = readAll(file);
        if (bytes.length < 44) {
            throw new IOException("Prompt audio is too small");
        }
        int channels = (bytes[22] & 0xff) | ((bytes[23] & 0xff) << 8);
        int rate = (bytes[24] & 0xff) | ((bytes[25] & 0xff) << 8) | ((bytes[26] & 0xff) << 16) | ((bytes[27] & 0xff) << 24);
        int bits = (bytes[34] & 0xff) | ((bytes[35] & 0xff) << 8);
        int data = 44;
        for (int i = 12; i + 8 < bytes.length; i++) {
            if (bytes[i] == 'd' && bytes[i + 1] == 'a' && bytes[i + 2] == 't' && bytes[i + 3] == 'a') {
                data = i + 8;
                break;
            }
        }
        if (bits != 16) {
            throw new IOException("Prompt audio must be 16-bit PCM");
        }
        int samples = (bytes.length - data) / 2 / Math.max(1, channels);
        float[] audio = new float[samples];
        for (int i = 0; i < samples; i++) {
            int offset = data + i * channels * 2;
            int low = bytes[offset] & 0xff;
            int high = bytes[offset + 1];
            audio[i] = (short) ((high << 8) | low) / 32768f;
        }
        stashRate(file, rate);
        return audio;
    }

    private static final Map<String, Integer> WAV_RATES = new HashMap<>();

    private static void stashRate(File file, int rate) {
        WAV_RATES.put(file.getAbsolutePath(), rate);
    }

    private static int wavRate(File file) {
        Integer rate = WAV_RATES.get(file.getAbsolutePath());
        return rate == null || rate <= 0 ? 24000 : rate;
    }

    private static float[] resample(float[] audio, int from, int to) {
        if (from == to || audio.length == 0) {
            return audio;
        }
        int length = (int) ((long) audio.length * to / from);
        float[] out = new float[Math.max(1, length)];
        for (int i = 0; i < out.length; i++) {
            double position = (double) i * from / to;
            int index = (int) position;
            float frac = (float) (position - index);
            float left = audio[Math.min(index, audio.length - 1)];
            float right = audio[Math.min(index + 1, audio.length - 1)];
            out[i] = left + (right - left) * frac;
        }
        return out;
    }

    private static byte[] readAll(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            int offset = 0;
            while (offset < data.length) {
                int count = in.read(data, offset, data.length - offset);
                if (count < 0) {
                    break;
                }
                offset += count;
            }
            return data;
        }
    }

    private static void writePrompt(File file, Prompt prompt) throws IOException {
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(file))) {
            out.writeInt(prompt.speaker.length);
            for (float value : prompt.speaker) {
                out.writeFloat(value);
            }
            out.writeInt(prompt.tokens.length);
            for (long value : prompt.tokens) {
                out.writeLong(value);
            }
            out.writeInt(prompt.melFrames);
            for (float value : prompt.mel) {
                out.writeFloat(value);
            }
        }
    }

    private static Prompt readPrompt(File file) throws IOException {
        try (DataInputStream in = new DataInputStream(new FileInputStream(file))) {
            float[] speaker = new float[in.readInt()];
            for (int i = 0; i < speaker.length; i++) {
                speaker[i] = in.readFloat();
            }
            long[] tokens = new long[in.readInt()];
            for (int i = 0; i < tokens.length; i++) {
                tokens[i] = in.readLong();
            }
            int frames = in.readInt();
            float[] mel = new float[frames * 80];
            for (int i = 0; i < mel.length; i++) {
                mel[i] = in.readFloat();
            }
            return new Prompt(speaker, tokens, mel, frames);
        }
    }

    private static void close(OrtSession.Result result) {
        if (result != null) {
            result.close();
        }
    }

    private static int count(long[] shape) throws OrtException {
        int count = 1;
        for (long dim : shape) {
            if (dim < 0 || dim > Integer.MAX_VALUE) {
                throw new OrtException("CosyVoice tensor shape is not concrete");
            }
            count = Math.multiplyExact(count, (int) dim);
        }
        return count;
    }

    private static float halfToFloat(int bits) {
        int sign = (bits & 0x8000) << 16;
        int exp = (bits >>> 10) & 0x1f;
        int mant = bits & 0x3ff;
        if (exp == 0) {
            if (mant == 0) {
                return Float.intBitsToFloat(sign);
            }
            while ((mant & 0x400) == 0) {
                mant <<= 1;
                exp--;
            }
            mant &= 0x3ff;
            return Float.intBitsToFloat(sign | ((exp + (127 - 15)) << 23) | (mant << 13));
        }
        if (exp == 31) {
            return Float.intBitsToFloat(sign | 0x7f800000 | (mant << 13));
        }
        return Float.intBitsToFloat(sign | ((exp + (127 - 15)) << 23) | (mant << 13));
    }

    private static final class Prompt {
        private final float[] speaker;
        private final long[] tokens;
        private final float[] mel;
        private final int melFrames;

        private Prompt(float[] speaker, long[] tokens, float[] mel, int melFrames) {
            this.speaker = speaker;
            this.tokens = tokens;
            this.mel = mel;
            this.melFrames = melFrames;
        }
    }
}
