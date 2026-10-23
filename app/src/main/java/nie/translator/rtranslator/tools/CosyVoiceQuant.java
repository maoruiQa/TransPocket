package nie.translator.rtranslator.tools;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Quantized Fun-CosyVoice 3 through the CrispASR CPU library.
 * The GGUF pack is Q4 LLM, Q8 flow, and F16 HiFT. The full FP16 ONNX graph is not loaded.
 */
final class CosyVoiceQuant {
    static final int SAMPLE_RATE = 24000;
    private static boolean attempted;
    private static boolean loaded;

    private CosyVoiceQuant() {
    }

    static boolean available() {
        if (!attempted) {
            attempted = true;
            try {
                // The emulator's arm64 translator aborts inside libomp while reading CPU affinity.
                if (android.os.Build.SUPPORTED_ABIS.length > 0
                        && android.os.Build.SUPPORTED_ABIS[0].startsWith("x86")) {
                    try {
                        android.system.Os.setenv("KMP_AFFINITY", "disabled", true);
                        android.system.Os.setenv("OMP_NUM_THREADS", "1", true);
                    } catch (android.system.ErrnoException ignored) {
                    }
                }
                System.loadLibrary("omp");
                System.loadLibrary("ggml-base");
                System.loadLibrary("ggml");
                System.loadLibrary("ggml-cpu");
                System.loadLibrary("crispasr");
                System.loadLibrary("livedraft_cosy");
                loaded = nativeProbe() != 0;
            } catch (UnsatisfiedLinkError error) {
                loaded = false;
            }
        }
        return loaded;
    }

    static final class Session implements AutoCloseable {
        private long handle;
        private volatile boolean cancelled;

        Session(File dir) {
            File model = LiveDraftVoices.findNamed(dir, "cosyvoice3-llm-q4_k.gguf");
            if (model == null) {
                throw new IllegalStateException("CosyVoice GGUF is missing");
            }
            handle = nativeOpen(model.getAbsolutePath());
            if (handle == 0L) {
                throw new IllegalStateException("CosyVoice failed to open");
            }
        }

        void prepare(String language, String gender) {
            nativeSetLanguage(handle, language == null ? "" : language);
            nativeSetVoice(handle, voiceName(language, gender), null);
        }

        void useUpload(File wav) {
            if (wav != null && wav.isFile()) {
                nativeSetVoice(handle, wav.getAbsolutePath(), null);
            }
        }

        void synthesize(String text, PcmSink sink) {
            cancelled = false;
            nativeSynthesize(handle, text == null ? "" : text, sink, new Cancel() {
                @Override
                public boolean cancelled() {
                    return cancelled;
                }
            });
        }

        void synthesizeToWav(String text, String language, String gender, File wav) throws IOException {
            prepare(language, gender);
            final float[][] parts = new float[1][];
            final int[] used = new int[1];
            synthesize(text, new PcmSink() {
                @Override
                public boolean onSamples(float[] samples) {
                    if (samples == null || samples.length == 0) {
                        return true;
                    }
                    float[] joined = new float[used[0] + samples.length];
                    if (parts[0] != null) {
                        System.arraycopy(parts[0], 0, joined, 0, used[0]);
                    }
                    System.arraycopy(samples, 0, joined, used[0], samples.length);
                    parts[0] = joined;
                    used[0] = joined.length;
                    return used[0] < SAMPLE_RATE * 12;
                }
            });
            if (parts[0] == null || used[0] == 0) {
                throw new IOException("CosyVoice produced no reference audio");
            }
            writeWav(wav, parts[0], used[0]);
        }

        void cancel() {
            cancelled = true;
        }

        @Override
        public void close() {
            if (handle != 0L) {
                nativeClose(handle);
                handle = 0L;
            }
        }
    }

    /**
     * The quantized bank has one clip per language, plus the Mandarin zero_shot prompt.
     * Male speech uses zero_shot. Female speech uses the language clip when the bank has one.
     */
    static String voiceName(String language, String gender) {
        if (LiveDraftVoices.GENDER_MALE.equals(gender)) {
            return "zero_shot";
        }
        if ("zh".equals(language) || "en".equals(language) || "de".equals(language) || "ja".equals(language)
                || "fr".equals(language) || "es".equals(language) || "ko".equals(language)) {
            return "fleurs-" + language;
        }
        return "fleurs-en";
    }

    static String sampleSentence(String language) {
        if ("zh".equals(language) || "yue".equals(language)) {
            return "今天天气很好，我们出去走走。";
        }
        if ("ja".equals(language)) {
            return "今日はいい天気なので、散歩に行きましょう。";
        }
        if ("ko".equals(language)) {
            return "오늘 날씨가 좋아서 산책하러 가요.";
        }
        if ("de".equals(language)) {
            return "Das Wetter ist schön, lass uns spazieren gehen.";
        }
        if ("es".equals(language)) {
            return "Hace buen tiempo, salgamos a caminar.";
        }
        if ("fr".equals(language)) {
            return "Il fait beau, allons nous promener.";
        }
        if ("it".equals(language)) {
            return "Oggi è una bella giornata, usciamo a camminare.";
        }
        if ("ru".equals(language)) {
            return "Сегодня хорошая погода, давай прогуляемся.";
        }
        return "The weather is nice, so let's go for a walk.";
    }

    private static void writeWav(File file, float[] samples, int count) throws IOException {
        int dataBytes = count * 2;
        ByteBuffer buffer = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(new byte[]{'R', 'I', 'F', 'F'});
        buffer.putInt(36 + dataBytes);
        buffer.put(new byte[]{'W', 'A', 'V', 'E'});
        buffer.put(new byte[]{'f', 'm', 't', ' '});
        buffer.putInt(16);
        buffer.putShort((short) 1);
        buffer.putShort((short) 1);
        buffer.putInt(SAMPLE_RATE);
        buffer.putInt(SAMPLE_RATE * 2);
        buffer.putShort((short) 2);
        buffer.putShort((short) 16);
        buffer.put(new byte[]{'d', 'a', 't', 'a'});
        buffer.putInt(dataBytes);
        for (int i = 0; i < count; i++) {
            float sample = samples[i];
            if (sample > 1f) {
                sample = 1f;
            } else if (sample < -1f) {
                sample = -1f;
            }
            buffer.putShort((short) (sample * 32767f));
        }
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Could not create " + parent);
        }
        FileOutputStream output = new FileOutputStream(file);
        try {
            output.write(buffer.array());
        } finally {
            output.close();
        }
    }

    interface Cancel {
        boolean cancelled();
    }

    private static native int nativeProbe();

    private static native long nativeOpen(String modelPath);

    private static native void nativeClose(long handle);

    private static native int nativeSetVoice(long handle, String voice, String referenceText);

    private static native void nativeSetLanguage(long handle, String language);

    private static native void nativeSynthesize(long handle, String text, PcmSink sink, Cancel cancel);
}
