package nie.translator.rtranslator.tools;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.SystemClock;

/**
 * Plays PCM as it arrives. A short prime avoids a clipped first word.
 * When playback catches the synthesizer, the track pauses instead of playing silence.
 */
final class PcmStreamPlayer {
    interface Listener {
        void onStarted();
        void onDrained();
    }

    private final Context appContext;
    private final PcmQueue queue;
    private final int sampleRate;
    private final Listener listener;
    private final AudioAttributes attributes;
    private AudioTrack track;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private Thread thread;
    private volatile boolean cancelled;
    private int written;

    PcmStreamPlayer(Context context, int sampleRate, Listener listener) {
        this.appContext = context.getApplicationContext();
        this.sampleRate = sampleRate;
        this.listener = listener;
        this.queue = new PcmQueue(sampleRate);
        this.attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
    }

    /** A sentence has finished synthesizing. Playback may start even when the clip is shorter than the prime. */
    void sentenceReady() {
        queue.sentenceReady();
    }

    void start() {
        track = buildTrack();
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                play();
            }
        }, "live-draft-play");
        thread.start();
    }

    void write(float[] samples) throws InterruptedException {
        if (samples == null || samples.length == 0 || cancelled) {
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
        queue.write(pcm);
    }

    void finish() {
        queue.finish();
    }

    void cancel() {
        cancelled = true;
        queue.cancel();
        AudioTrack current = track;
        if (current != null) {
            try {
                current.pause();
                current.flush();
            } catch (Throwable ignored) {
            }
        }
    }

    void release() {
        cancel();
        Thread current = thread;
        if (current != null) {
            try {
                current.join(500);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        AudioTrack currentTrack = track;
        track = null;
        if (currentTrack != null) {
            try {
                currentTrack.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private AudioTrack buildTrack() {
        int minBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bytes = Math.max(minBuffer, sampleRate * 2 / 5);
        return new AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setBufferSizeInBytes(Math.max(bytes, minBuffer))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
    }

    private void play() {
        requestFocus();
        boolean started = false;
        short[] buffer = new short[Math.max(1, sampleRate / 10)];
        try {
            while (!cancelled) {
                if (!started) {
                    boolean finished = queue.isFinished();
                    int queued = queue.queued();
                    if (!canPlay(queued, finished, queue.isSentenceReady())) {
                        if (finished && queued == 0) {
                            break;
                        }
                        sleep();
                        continue;
                    }
                    AudioTrack opening = track;
                    if (opening == null || opening.getState() != AudioTrack.STATE_INITIALIZED) {
                        opening = replaceTrack();
                    }
                    if (opening == null) {
                        sleep();
                        continue;
                    }
                    opening.play();
                    started = true;
                    if (listener != null) {
                        listener.onStarted();
                    }
                }
                int count = queue.readWaiting(buffer);
                if (count < 0 || cancelled) {
                    break;
                }
                if (count == 0) {
                    break;
                }
                AudioTrack audio = track;
                if (audio == null || !writeAll(audio, buffer, count)) {
                    AudioTrack replaced = replaceTrack();
                    if (replaced != null) {
                        replaced.play();
                        writeAll(replaced, buffer, count);
                    }
                }
            }
            AudioTrack audio = track;
            if (!cancelled && audio != null && written > 0) {
                if (audio.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                    audio.play();
                }
                waitForHead(audio);
            }
        } catch (Throwable ignored) {
        } finally {
            AudioTrack audio = track;
            if (audio != null) {
                try {
                    audio.pause();
                } catch (Throwable ignored) {
                }
            }
            abandonFocus();
            if (!cancelled && listener != null) {
                listener.onDrained();
            }
        }
    }

    /** Opening audio waits for a short prime. A finished sentence plays as soon as it has samples. */
    private boolean canPlay(int queued, boolean finished, boolean sentenceReady) {
        if (PcmPlayback.shouldStart(queued, sampleRate, finished)) {
            return true;
        }
        return sentenceReady && queued > 0;
    }

    private boolean writeAll(AudioTrack audio, short[] buffer, int count) {
        int offset = 0;
        while (offset < count && !cancelled) {
            int wrote = audio.write(buffer, offset, count - offset);
            if (wrote <= 0) {
                return false;
            }
            offset += wrote;
            written += wrote;
        }
        return true;
    }

    private AudioTrack replaceTrack() {
        AudioTrack previous = track;
        track = null;
        if (previous != null) {
            try {
                previous.pause();
                previous.flush();
                previous.release();
            } catch (Throwable ignored) {
            }
        }
        try {
            AudioTrack created = buildTrack();
            track = created;
            return created;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void requestFocus() {
        audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) {
            return;
        }
        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes)
                .build();
        audioManager.requestAudioFocus(focusRequest);
    }

    private void abandonFocus() {
        if (audioManager != null && focusRequest != null) {
            audioManager.abandonAudioFocusRequest(focusRequest);
            focusRequest = null;
        }
    }

    private void waitForHead(AudioTrack audio) {
        long deadline = SystemClock.elapsedRealtime() + Math.max(1000L, written * 1000L / sampleRate + 400L);
        while (!cancelled && SystemClock.elapsedRealtime() < deadline) {
            if (audio.getPlaybackHeadPosition() >= written) {
                return;
            }
            sleep();
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(20);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
