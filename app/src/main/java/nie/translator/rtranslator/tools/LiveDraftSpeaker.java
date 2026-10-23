package nie.translator.rtranslator.tools;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import java.io.File;
import java.util.ArrayDeque;

/**
 * Speaks with Fun-CosyVoice 3. Text is committed one sentence at a time
 * and audio plays while the next sentence is synthesized.
 * One worker owns the native session, so a new utterance waits until the
 * current native call returns instead of entering it twice.
 */
public final class LiveDraftSpeaker {
    private static final String TAG = "live_draft";
    private static final Object LOCK = new Object();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ArrayDeque<String> PIECES = new ArrayDeque<>();
    private static CosyVoiceQuant.Session cosyvoice;
    private static String loadedEngine;
    private static PcmStreamPlayer player;
    private static LiveDraftBuffer buffer = new LiveDraftBuffer();
    private static UtteranceProgressListener listener;
    private static String utteranceId = "";
    private static String language = "";
    private static String gender = LiveDraftVoices.GENDER_FEMALE;
    private static String preparedVoice = "";
    private static float speed = 1f;
    private static int generation;
    private static boolean inputDone;
    private static boolean failed;
    private static boolean released;
    private static Context appContext;
    private static Thread worker;

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
        int sampleRate;
        PcmStreamPlayer previous;
        boolean started = false;
        synchronized (LOCK) {
            released = false;
            appContext = app;
            previous = stopLocked();
            listener = progressListener;
            utteranceId = id == null ? "" : id;
            language = code;
            gender = LiveDraftVoices.gender(app);
            speed = rate;
            failed = false;
            inputDone = false;
            buffer = new LiveDraftBuffer();
            try {
                sampleRate = openEngine(app, engine);
            } catch (Throwable error) {
                Log.e(TAG, "Live Draft model failed to load", error);
                closeEnginesLocked();
                sampleRate = 0;
            }
            if (sampleRate > 0) {
                final int token = generation;
                player = new PcmStreamPlayer(app, sampleRate, new PcmStreamPlayer.Listener() {
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
                    ensureWorkerLocked();
                    LOCK.notifyAll();
                    started = true;
                } catch (Throwable error) {
                    Log.e(TAG, "AudioTrack failed", error);
                    player = null;
                }
            }
        }
        releasePlayer(previous);
        return started;
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
        PcmStreamPlayer previous;
        synchronized (LOCK) {
            previous = stopLocked();
        }
        releasePlayer(previous);
    }

    public static void release() {
        Thread current;
        PcmStreamPlayer previous;
        synchronized (LOCK) {
            released = true;
            previous = stopLocked();
            closeEnginesLocked();
            current = worker;
            LOCK.notifyAll();
        }
        releasePlayer(previous);
        if (current != null) {
            try {
                current.join(500);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void ensureWorkerLocked() {
        if (worker != null && worker.isAlive()) {
            return;
        }
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                runWorker();
            }
        }, "live-draft-tts");
        worker.start();
    }

    private static void runWorker() {
        while (true) {
            int token;
            Context context;
            synchronized (LOCK) {
                while (player == null && !released) {
                    try {
                        LOCK.wait();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                if (released && player == null) {
                    return;
                }
                token = generation;
                context = appContext;
            }
            try {
                synthesize(context, token);
            } catch (Throwable error) {
                Log.e(TAG, "Live Draft synthesis failed", error);
                synchronized (LOCK) {
                    if (token == generation) {
                        failed = true;
                    }
                }
                notifyError(token);
            }
            synchronized (LOCK) {
                while (!released && player != null && generation == token) {
                    try {
                        LOCK.wait();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                if (released && player == null) {
                    return;
                }
            }
        }
    }

    private static void synthesize(Context context, int token) throws Exception {
        while (true) {
            String piece;
            synchronized (LOCK) {
                while (PIECES.isEmpty() && !inputDone && token == generation && !released) {
                    LOCK.wait(200);
                }
                if (token != generation || released) {
                    return;
                }
                piece = PIECES.pollFirst();
                if (piece == null) {
                    break;
                }
            }
            speakPiece(context, piece, token);
            if (token != currentGeneration()) {
                return;
            }
            PcmStreamPlayer current = playerFor(token);
            if (current != null) {
                current.sentenceReady();
            }
        }
        PcmStreamPlayer current;
        synchronized (LOCK) {
            current = token == generation ? player : null;
        }
        if (current != null) {
            current.finish();
        }
    }

    private static void speakPiece(Context context, String text, final int token) throws Exception {
        if (!SimultaneousText.isAudible(text)) {
            return;
        }
        int produced = synthesizeOnce(context, text, token);
        if (produced > 0 || token != currentGeneration()) {
            return;
        }
        Log.w(TAG, "CosyVoice produced no audio, reopening the session");
        reopenEngine(context, token);
        if (token != currentGeneration()) {
            return;
        }
        synthesizeOnce(context, text, token);
    }

    private static int synthesizeOnce(Context context, String text, final int token) throws Exception {
        final int[] produced = new int[1];
        PcmSink sink = new PcmSink() {
            @Override
            public boolean onSamples(float[] samples) {
                if (samples != null) {
                    produced[0] += samples.length;
                }
                return writeSamples(speedUp(samples), token);
            }
        };
        CosyVoiceQuant.Session engine;
        String code;
        String voiceGender;
        synchronized (LOCK) {
            if (token != generation) {
                return produced[0];
            }
            engine = cosyvoice;
            code = language;
            voiceGender = gender;
        }
        if (engine == null) {
            throw new IllegalStateException("CosyVoice is not loaded");
        }
        prepareVoice(context, engine, code, voiceGender);
        engine.synthesize(text, sink);
        return produced[0];
    }

    /** Reloading the voice between sentences resets the decoder and stalls the next sentence. */
    private static void prepareVoice(Context context, CosyVoiceQuant.Session engine, String code, String voiceGender) {
        File upload = LiveDraftVoices.uploadFile(context, voiceGender);
        long uploadLength = upload.isFile() ? upload.length() : 0L;
        String key = code + "\n" + voiceGender + "\n" + uploadLength;
        synchronized (LOCK) {
            if (key.equals(preparedVoice)) {
                return;
            }
        }
        engine.prepare(code, voiceGender);
        if (uploadLength > 0) {
            engine.useUpload(upload);
        }
        synchronized (LOCK) {
            preparedVoice = key;
        }
    }

    private static void reopenEngine(Context context, int token) throws Exception {
        synchronized (LOCK) {
            if (token != generation) {
                return;
            }
            closeEnginesLocked();
            openEngine(context, LiveDraftVoices.ENGINE_COSYVOICE);
        }
    }

    private static PcmStreamPlayer playerFor(int token) {
        synchronized (LOCK) {
            return token == generation ? player : null;
        }
    }

    private static float[] speedUp(float[] samples) {
        float rate;
        synchronized (LOCK) {
            rate = speed;
        }
        if (samples == null || samples.length == 0 || rate == 1f) {
            return samples;
        }
        int out = Math.max(1, Math.round(samples.length / rate));
        float[] scaled = new float[out];
        for (int i = 0; i < out; i++) {
            float position = i * rate;
            int left = Math.min((int) position, samples.length - 1);
            int right = Math.min(left + 1, samples.length - 1);
            float mix = position - left;
            scaled[i] = samples[left] * (1f - mix) + samples[right] * mix;
        }
        return scaled;
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
        if (!LiveDraftVoices.ENGINE_COSYVOICE.equals(engine)) {
            throw new IllegalStateException("CosyVoice is not selected");
        }
        if (!CosyVoiceQuant.available()) {
            throw new IllegalStateException("CosyVoice runtime is not available");
        }
        if (cosyvoice == null || !LiveDraftVoices.ENGINE_COSYVOICE.equals(loadedEngine)) {
            if (cosyvoice != null) {
                cosyvoice.close();
            }
            cosyvoice = new CosyVoiceQuant.Session(LiveDraftVoices.modelDir(context, LiveDraftVoices.ENGINE_COSYVOICE));
            loadedEngine = LiveDraftVoices.ENGINE_COSYVOICE;
        }
        return CosyVoiceQuant.SAMPLE_RATE;
    }

    private static void pullLocked(boolean finished) {
        String piece;
        while ((piece = buffer.pull(finished)) != null) {
            if (SimultaneousText.isAudible(piece)) {
                PIECES.addLast(piece);
            }
        }
    }

    /** Drops the current utterance. The caller releases the returned player outside {@link #LOCK}. */
    private static PcmStreamPlayer stopLocked() {
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
        return current;
    }

    private static void releasePlayer(PcmStreamPlayer current) {
        if (current != null) {
            current.release();
        }
    }

    private static void closeEnginesLocked() {
        if (cosyvoice != null) {
            cosyvoice.close();
            cosyvoice = null;
        }
        loadedEngine = null;
        preparedVoice = "";
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
