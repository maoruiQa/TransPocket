package nie.translator.rtranslator.tools;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsKittenModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsMatchaModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsPocketModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsZipVoiceModelConfig;

import java.io.File;
import java.util.ArrayDeque;

import kotlin.jvm.functions.Function1;

/**
 * Speaks with the selected Live Draft engine. Text is committed a piece at a time
 * and audio is played as each piece is synthesized.
 */
public final class LiveDraftSpeaker {
    private static final String TAG = "live_draft";
    private static final Object LOCK = new Object();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ArrayDeque<String> PIECES = new ArrayDeque<>();
    private static OfflineTts kokoro;
    private static CosyVoiceEngine cosyvoice;
    private static String loadedEngine;
    private static PcmStreamPlayer player;
    private static LiveDraftBuffer buffer = new LiveDraftBuffer();
    private static UtteranceProgressListener listener;
    private static String utteranceId = "";
    private static String language = "";
    private static String engineId;
    private static float speed = 1f;
    private static int voiceId;
    private static int generation;
    private static boolean inputDone;
    private static boolean failed;

    private LiveDraftSpeaker() {
    }

    public static boolean canSpeak(Context context, String language) {
        return LiveDraftVoices.canSpeak(context, language);
    }

    public static int speak(Context context, String text, String language, float speed, int queueMode, UtteranceProgressListener listener, String utteranceId) {
        if (text == null || text.length() == 0 || !canSpeak(context, language)) {
            return TextToSpeech.ERROR;
        }
        if (!start(context, language, speed, listener, utteranceId)) {
            return TextToSpeech.ERROR;
        }
        append(text);
        finish();
        return TextToSpeech.SUCCESS;
    }

    public static boolean start(Context context, String languageCode, float speechRate, UtteranceProgressListener progressListener, String id) {
        Context app = context.getApplicationContext();
        String code = LiveDraftVoices.normalize(languageCode);
        String engine = LiveDraftVoices.engineFor(app, code);
        if (engine == null) {
            return false;
        }
        float rate = speechRate;
        if (rate < 0.5f) {
            rate = 0.5f;
        } else if (rate > 2f) {
            rate = 2f;
        }
        int voice = LiveDraftVoices.voiceId(app, engine, code);
        int sampleRate;
        synchronized (LOCK) {
            stopLocked();
            listener = progressListener;
            utteranceId = id == null ? "" : id;
            language = code;
            engineId = engine;
            speed = rate;
            voiceId = voice;
            failed = false;
            inputDone = false;
            buffer = new LiveDraftBuffer();
            try {
                sampleRate = openEngine(app, engine);
            } catch (Throwable error) {
                Log.e(TAG, "Live Draft model failed to load", error);
                closeEnginesLocked();
                return false;
            }
            if (sampleRate <= 0) {
                return false;
            }
            final int token = generation;
            player = new PcmStreamPlayer(sampleRate, new PcmStreamPlayer.Listener() {
                @Override
                public void onStarted() {
                    notifyStart(token);
                }

                @Override
                public void onDrained() {
                    notifyDone(token);
                }
            });
            try {
                player.start();
            } catch (Throwable error) {
                Log.e(TAG, "AudioTrack failed", error);
                player = null;
                return false;
            }
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    synthesize(app, token);
                }
            }, "live-draft-tts");
            thread.start();
        }
        return true;
    }

    public static void append(String text) {
        synchronized (LOCK) {
            if (player == null || text == null || text.length() == 0) {
                return;
            }
            buffer.add(text);
            pullLocked(false);
            LOCK.notifyAll();
        }
    }

    public static void finish() {
        synchronized (LOCK) {
            if (player == null) {
                return;
            }
            inputDone = true;
            pullLocked(true);
            LOCK.notifyAll();
        }
    }

    public static void stop() {
        synchronized (LOCK) {
            stopLocked();
        }
    }

    public static void release() {
        stop();
        synchronized (LOCK) {
            closeEnginesLocked();
        }
    }

    private static void synthesize(Context context, int token) {
        try {
            while (true) {
                String piece;
                synchronized (LOCK) {
                    while (PIECES.isEmpty() && !inputDone && token == generation) {
                        LOCK.wait(200);
                    }
                    if (token != generation) {
                        return;
                    }
                    piece = PIECES.pollFirst();
                    if (piece == null) {
                        break;
                    }
                }
                speakPiece(context, piece, token);
                if (token != generation) {
                    return;
                }
            }
        } catch (Throwable error) {
            Log.e(TAG, "Live Draft synthesis failed", error);
            synchronized (LOCK) {
                if (token == generation) {
                    failed = true;
                }
            }
            notifyError(token);
        } finally {
            PcmStreamPlayer current;
            synchronized (LOCK) {
                current = token == generation ? player : null;
            }
            if (current != null) {
                current.finish();
            }
        }
    }

    private static void speakPiece(Context context, String text, final int token) throws Exception {
        if (LiveDraftVoices.ENGINE_COSYVOICE.equals(engineId)) {
            CosyVoiceEngine engine;
            LiveDraftVoices.Speaker voice;
            synchronized (LOCK) {
                engine = cosyvoice;
                voice = LiveDraftVoices.speaker(LiveDraftVoices.ENGINE_COSYVOICE, voiceId);
            }
            if (engine == null || voice == null) {
                throw new IllegalStateException("CosyVoice is not loaded");
            }
            engine.synthesize(text, voice, new CosyVoiceEngine.PcmSink() {
                @Override
                public boolean onSamples(float[] samples) {
                    return writeSamples(samples, token);
                }
            });
            return;
        }
        final OfflineTts tts;
        final int speaker;
        final float rate;
        synchronized (LOCK) {
            tts = kokoro;
            speaker = voiceId;
            rate = speed;
        }
        if (tts == null) {
            throw new IllegalStateException("Kokoro is not loaded");
        }
        tts.generateWithCallback(text, speaker, rate, new Function1<float[], Integer>() {
            @Override
            public Integer invoke(float[] samples) {
                return writeSamples(samples, token) ? 1 : 0;
            }
        });
    }

    private static boolean writeSamples(float[] samples, int token) {
        PcmStreamPlayer current;
        synchronized (LOCK) {
            if (token != generation) {
                return false;
            }
            current = player;
        }
        if (current == null || samples == null || samples.length == 0) {
            return token == currentGeneration();
        }
        try {
            current.write(samples);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
        return token == currentGeneration();
    }

    private static int openEngine(Context context, String engine) throws Exception {
        if (LiveDraftVoices.ENGINE_COSYVOICE.equals(engine)) {
            if (kokoro != null) {
                kokoro.release();
                kokoro = null;
            }
            if (cosyvoice == null || !LiveDraftVoices.ENGINE_COSYVOICE.equals(loadedEngine)) {
                if (cosyvoice != null) {
                    cosyvoice.close();
                }
                cosyvoice = new CosyVoiceEngine(LiveDraftVoices.modelDir(context, LiveDraftVoices.ENGINE_COSYVOICE));
                loadedEngine = LiveDraftVoices.ENGINE_COSYVOICE;
            }
            return CosyVoiceEngine.SAMPLE_RATE;
        }
        if (cosyvoice != null) {
            cosyvoice.close();
            cosyvoice = null;
        }
        if (kokoro == null || !LiveDraftVoices.ENGINE_KOKORO.equals(loadedEngine)) {
            if (kokoro != null) {
                kokoro.release();
                kokoro = null;
            }
            kokoro = loadKokoro(context);
            loadedEngine = LiveDraftVoices.ENGINE_KOKORO;
        }
        return kokoro.sampleRate();
    }

    private static OfflineTts loadKokoro(Context context) {
        File dir = LiveDraftVoices.modelDir(context, LiveDraftVoices.ENGINE_KOKORO);
        File model = LiveDraftVoices.findNamed(dir, "model.int8.onnx");
        if (model == null) {
            model = LiveDraftVoices.findNamed(dir, "model.onnx");
        }
        File voices = LiveDraftVoices.findNamed(dir, "voices.bin");
        File tokens = LiveDraftVoices.findNamed(dir, "tokens.txt");
        File dataDir = LiveDraftVoices.findDirectory(dir, "espeak-ng-data");
        File dict = LiveDraftVoices.findDirectory(dir, "dict");
        if (model == null || voices == null || tokens == null) {
            throw new IllegalStateException("Kokoro files are incomplete");
        }
        StringBuilder lexicon = new StringBuilder();
        File us = LiveDraftVoices.findNamed(dir, "lexicon-us-en.txt");
        File zh = LiveDraftVoices.findNamed(dir, "lexicon-zh.txt");
        if (us != null) {
            lexicon.append(us.getAbsolutePath());
        }
        if (zh != null) {
            if (lexicon.length() > 0) {
                lexicon.append(',');
            }
            lexicon.append(zh.getAbsolutePath());
        }
        OfflineTtsKokoroModelConfig kokoroConfig = new OfflineTtsKokoroModelConfig();
        kokoroConfig.setModel(model.getAbsolutePath());
        kokoroConfig.setVoices(voices.getAbsolutePath());
        kokoroConfig.setTokens(tokens.getAbsolutePath());
        kokoroConfig.setDataDir(dataDir == null ? "" : dataDir.getAbsolutePath());
        kokoroConfig.setLexicon(lexicon.toString());
        kokoroConfig.setDictDir(dict == null ? "" : dict.getAbsolutePath());
        kokoroConfig.setLengthScale(1f);
        OfflineTtsModelConfig modelConfig = new OfflineTtsModelConfig();
        modelConfig.setVits(new OfflineTtsVitsModelConfig());
        modelConfig.setMatcha(new OfflineTtsMatchaModelConfig());
        modelConfig.setKokoro(kokoroConfig);
        modelConfig.setZipvoice(new OfflineTtsZipVoiceModelConfig());
        modelConfig.setKitten(new OfflineTtsKittenModelConfig());
        modelConfig.setPocket(new OfflineTtsPocketModelConfig());
        modelConfig.setSupertonic(new OfflineTtsSupertonicModelConfig());
        modelConfig.setNumThreads(2);
        modelConfig.setDebug(false);
        modelConfig.setProvider("cpu");
        OfflineTtsConfig config = new OfflineTtsConfig();
        config.setModel(modelConfig);
        config.setRuleFsts(ruleFsts(dir));
        config.setRuleFars("");
        config.setMaxNumSentences(1);
        config.setSilenceScale(0.2f);
        return new OfflineTts(null, config);
    }

    private static String ruleFsts(File dir) {
        StringBuilder rules = new StringBuilder();
        String[] names = new String[]{"date-zh.fst", "phone-zh.fst", "number-zh.fst"};
        for (String name : names) {
            File file = LiveDraftVoices.findNamed(dir, name);
            if (file == null) {
                continue;
            }
            if (rules.length() > 0) {
                rules.append(',');
            }
            rules.append(file.getAbsolutePath());
        }
        return rules.toString();
    }

    private static void pullLocked(boolean finished) {
        String piece;
        while ((piece = buffer.pull(finished)) != null) {
            if (piece.trim().length() > 0) {
                PIECES.addLast(piece);
            }
        }
    }

    private static void stopLocked() {
        generation++;
        PIECES.clear();
        buffer = new LiveDraftBuffer();
        inputDone = false;
        LOCK.notifyAll();
        if (cosyvoice != null) {
            cosyvoice.cancel();
        }
        PcmStreamPlayer current = player;
        player = null;
        if (current != null) {
            current.release();
        }
    }

    private static void closeEnginesLocked() {
        if (kokoro != null) {
            try {
                kokoro.release();
            } catch (Throwable ignored) {
            }
            kokoro = null;
        }
        if (cosyvoice != null) {
            cosyvoice.close();
            cosyvoice = null;
        }
        loadedEngine = null;
    }

    private static int currentGeneration() {
        synchronized (LOCK) {
            return generation;
        }
    }

    private static void notifyStart(final int token) {
        final UtteranceProgressListener current = listener;
        final String id = utteranceId;
        if (current == null) {
            return;
        }
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                if (token == currentGeneration()) {
                    current.onStart(id);
                }
            }
        });
    }

    private static void notifyDone(final int token) {
        final UtteranceProgressListener current;
        final String id;
        synchronized (LOCK) {
            if (token != generation || failed) {
                return;
            }
            current = listener;
            id = utteranceId;
        }
        if (current == null) {
            return;
        }
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                if (token == currentGeneration()) {
                    current.onDone(id);
                }
            }
        });
    }

    private static void notifyError(final int token) {
        final UtteranceProgressListener current;
        final String id;
        synchronized (LOCK) {
            if (token != generation) {
                return;
            }
            current = listener;
            id = utteranceId;
            generation++;
        }
        PcmStreamPlayer currentPlayer;
        synchronized (LOCK) {
            currentPlayer = player;
            player = null;
        }
        if (currentPlayer != null) {
            currentPlayer.release();
        }
        if (current == null) {
            return;
        }
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                current.onError(id);
            }
        });
    }
}
