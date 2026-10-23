package nie.translator.rtranslator.tools;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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

/**
 * Speaks with a downloaded Live Draft voice. One Piper session stays loaded for the current language.
 */
public final class LiveDraftSpeaker {
    private static final String TAG = "live_draft";
    private static final Object LOCK = new Object();
    private static final Object ENGINE_LOCK = new Object();
    private static final ArrayDeque<Request> QUEUE = new ArrayDeque<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static OfflineTts engine;
    private static String engineCode;
    private static AudioTrack track;
    private static int generation;
    private static boolean workerRunning;

    private LiveDraftSpeaker() {
    }

    public static boolean canSpeak(Context context, String language) {
        return LiveDraftVoices.isReady(context, language);
    }

    public static int speak(Context context, String text, String language, float speed, int queueMode, UtteranceProgressListener listener, String utteranceId) {
        if (text == null || text.length() == 0 || !canSpeak(context, language)) {
            return TextToSpeech.ERROR;
        }
        // Piper at length scale 1 is slower than system TTS at the same slider position.
        // sherpa 1.13.8 also ignores speed when it is exactly 1 and keeps length_scale.
        float rate = speed * 1.25f;
        if (rate < 0.55f) {
            rate = 0.55f;
        } else if (rate > 2.5f) {
            rate = 2.5f;
        }
        Context app = context.getApplicationContext();
        synchronized (LOCK) {
            if (queueMode == TextToSpeech.QUEUE_FLUSH) {
                QUEUE.clear();
                generation++;
                stopTrackLocked();
            }
            QUEUE.addLast(new Request(app, text, LiveDraftVoices.normalize(language), rate, listener, utteranceId, generation));
            if (!workerRunning) {
                workerRunning = true;
                Thread thread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        drain();
                    }
                }, "live-draft-tts");
                thread.start();
            }
        }
        return TextToSpeech.SUCCESS;
    }

    public static void stop() {
        synchronized (LOCK) {
            QUEUE.clear();
            generation++;
            stopTrackLocked();
        }
    }

    public static void release() {
        stop();
        synchronized (ENGINE_LOCK) {
            releaseEngineLocked();
        }
    }

    private static void drain() {
        while (true) {
            Request request;
            synchronized (LOCK) {
                request = QUEUE.pollFirst();
                if (request == null) {
                    workerRunning = false;
                    return;
                }
            }
            play(request);
        }
    }

    private static void play(Request request) {
        if (request.generation != currentGeneration() || request.text.length() == 0) {
            return;
        }
        float[] samples;
        int sampleRate;
        try {
            OfflineTts tts = engine(request.context, request.code);
            if (tts == null) {
                notifyError(request);
                return;
            }
            samples = tts.generate(request.text, 0, request.speed).getSamples();
            sampleRate = tts.sampleRate();
        } catch (Throwable error) {
            Log.e(TAG, "Live Draft synthesis failed for " + request.code, error);
            notifyError(request);
            return;
        }
        if (request.generation != currentGeneration()) {
            return;
        }
        if (samples == null || samples.length == 0 || sampleRate <= 0) {
            Log.e(TAG, "Live Draft produced no audio for " + request.code);
            notifyError(request);
            return;
        }
        short[] pcm = new short[samples.length];
        for (int i = 0; i < samples.length; i++) {
            float sample = samples[i];
            if (sample > 1f) {
                sample = 1f;
            } else if (sample < -1f) {
                sample = -1f;
            }
            pcm[i] = (short) (sample * 32767f);
        }
        AudioTrack audio;
        boolean streaming;
        try {
            audio = openStatic(sampleRate, pcm);
            streaming = false;
            if (audio == null) {
                audio = openStream(sampleRate);
                streaming = true;
            }
        } catch (Throwable error) {
            Log.e(TAG, "AudioTrack failed", error);
            notifyError(request);
            return;
        }
        synchronized (LOCK) {
            if (request.generation != generation) {
                audio.release();
                return;
            }
            track = audio;
        }
        try {
            if (streaming) {
                // Write before play. An empty stream leaves PLAYSTATE_PLAYING on some phones,
                // and the clip is released before it is heard.
                int priming = Math.min(pcm.length, 4096);
                int wrote = audio.write(pcm, 0, priming);
                if (wrote <= 0) {
                    notifyError(request);
                    return;
                }
                audio.play();
                if (audio.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                    notifyError(request);
                    return;
                }
                notifyStart(request);
                int offset = wrote;
                while (offset < pcm.length && request.generation == currentGeneration()) {
                    wrote = audio.write(pcm, offset, Math.min(pcm.length - offset, 4096));
                    if (wrote <= 0) {
                        break;
                    }
                    offset += wrote;
                }
            } else {
                audio.play();
                notifyStart(request);
            }
            long deadline = SystemClock.elapsedRealtime() + (pcm.length * 1000L / sampleRate) + 200L;
            while (request.generation == currentGeneration() && SystemClock.elapsedRealtime() < deadline) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } catch (Throwable error) {
            Log.e(TAG, "Live Draft playback failed", error);
            notifyError(request);
            return;
        } finally {
            synchronized (LOCK) {
                if (track == audio) {
                    track = null;
                }
            }
            try {
                audio.pause();
                audio.flush();
            } catch (Throwable ignored) {
            }
            audio.release();
        }
        if (request.generation == currentGeneration()) {
            notifyDone(request);
        }
    }

    /** Whole clip is written before play. Returns null when the clip is too long for a static buffer. */
    private static AudioTrack openStatic(int sampleRate, short[] pcm) {
        int bytes = pcm.length * 2;
        if (bytes <= 0 || bytes > 2 * 1024 * 1024) {
            return null;
        }
        AudioTrack audio = null;
        try {
            audio = track(sampleRate, bytes, AudioTrack.MODE_STATIC);
            if (audio.write(pcm, 0, pcm.length) != pcm.length) {
                audio.release();
                return null;
            }
            return audio;
        } catch (Throwable error) {
            Log.w(TAG, "MODE_STATIC unavailable", error);
            if (audio != null) {
                try {
                    audio.release();
                } catch (Throwable ignored) {
                }
            }
            return null;
        }
    }

    private static AudioTrack openStream(int sampleRate) {
        int minBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuffer < 1) {
            minBuffer = sampleRate;
        }
        return track(sampleRate, Math.max(minBuffer, sampleRate), AudioTrack.MODE_STREAM);
    }

    private static AudioTrack track(int sampleRate, int bytes, int mode) {
        return new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setBufferSizeInBytes(bytes)
                .setTransferMode(mode)
                .build();
    }

    private static OfflineTts engine(Context context, String code) {
        synchronized (ENGINE_LOCK) {
            if (engine != null && code.equals(engineCode) && LiveDraftVoices.isReady(context, code)) {
                return engine;
            }
            releaseEngineLocked();
            if (!LiveDraftVoices.isReady(context, code)) {
                return null;
            }
            File dir = LiveDraftVoices.voiceDir(context, code);
            File model = LiveDraftVoices.findFile(dir, ".onnx");
            File tokens = LiveDraftVoices.findNamed(dir, "tokens.txt");
            if (model == null || tokens == null) {
                return null;
            }
            File lexicon = LiveDraftVoices.findNamed(dir, "lexicon.txt");
            File espeak = findEspeak(dir);
            OfflineTtsVitsModelConfig vits = new OfflineTtsVitsModelConfig();
            vits.setModel(model.getAbsolutePath());
            vits.setTokens(tokens.getAbsolutePath());
            vits.setLexicon(lexicon == null ? "" : lexicon.getAbsolutePath());
            vits.setDataDir(espeak == null ? "" : espeak.getAbsolutePath());
            vits.setDictDir("");
            vits.setNoiseScale(0.667f);
            vits.setNoiseScaleW(0.8f);
            vits.setLengthScale(1f);
            OfflineTtsModelConfig modelConfig = new OfflineTtsModelConfig();
            modelConfig.setVits(vits);
            modelConfig.setMatcha(new OfflineTtsMatchaModelConfig());
            modelConfig.setKokoro(new OfflineTtsKokoroModelConfig());
            modelConfig.setZipvoice(new OfflineTtsZipVoiceModelConfig());
            modelConfig.setKitten(new OfflineTtsKittenModelConfig());
            modelConfig.setPocket(new OfflineTtsPocketModelConfig());
            modelConfig.setSupertonic(new OfflineTtsSupertonicModelConfig());
            modelConfig.setNumThreads(2);
            modelConfig.setDebug(false);
            modelConfig.setProvider("cpu");
            OfflineTtsConfig config = new OfflineTtsConfig();
            config.setModel(modelConfig);
            config.setRuleFsts("");
            config.setRuleFars("");
            config.setMaxNumSentences(1);
            config.setSilenceScale(0.2f);
            try {
                engine = new OfflineTts(null, config);
                engineCode = code;
                return engine;
            } catch (Throwable error) {
                Log.e(TAG, "Live Draft model failed to load for " + code, error);
                releaseEngineLocked();
                return null;
            }
        }
    }

    private static File findEspeak(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return null;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return null;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                if ("espeak-ng-data".equals(child.getName())) {
                    return child;
                }
                File nested = findEspeak(child);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    private static int currentGeneration() {
        synchronized (LOCK) {
            return generation;
        }
    }

    private static void stopTrackLocked() {
        AudioTrack current = track;
        track = null;
        if (current != null) {
            try {
                current.pause();
                current.flush();
            } catch (Throwable ignored) {
            }
            current.release();
        }
    }

    private static void releaseEngineLocked() {
        if (engine != null) {
            try {
                engine.release();
            } catch (Throwable ignored) {
            }
            engine = null;
            engineCode = null;
        }
    }

    private static void notifyStart(final Request request) {
        if (request.listener == null) {
            return;
        }
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                request.listener.onStart(request.utteranceId);
            }
        });
    }

    private static void notifyDone(final Request request) {
        if (request.listener == null) {
            return;
        }
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                request.listener.onDone(request.utteranceId);
            }
        });
    }

    private static void notifyError(final Request request) {
        if (request.listener == null) {
            return;
        }
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                request.listener.onError(request.utteranceId);
            }
        });
    }

    private static final class Request {
        private final Context context;
        private final String text;
        private final String code;
        private final float speed;
        private final UtteranceProgressListener listener;
        private final String utteranceId;
        private final int generation;

        private Request(Context context, String text, String code, float speed, UtteranceProgressListener listener, String utteranceId, int generation) {
            this.context = context;
            this.text = text;
            this.code = code;
            this.speed = speed;
            this.listener = listener;
            this.utteranceId = utteranceId == null ? "" : utteranceId;
            this.generation = generation;
        }
    }
}
