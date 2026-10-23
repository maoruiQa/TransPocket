package nie.translator.rtranslator.tools;

import android.media.AudioAttributes;
import android.media.AudioFormat;
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

    private final PcmQueue queue;
    private final int sampleRate;
    private final Listener listener;
    private AudioTrack track;
    private Thread thread;
    private volatile boolean cancelled;
    private int written;

    PcmStreamPlayer(int sampleRate, Listener listener) {
        this.sampleRate = sampleRate;
        this.listener = listener;
        this.queue = new PcmQueue(sampleRate);
    }

    void start() {
        int minBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bytes = Math.max(minBuffer, sampleRate * 2 / 5);
        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setBufferSizeInBytes(Math.max(bytes, minBuffer))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
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

    private void play() {
        AudioTrack audio = track;
        if (audio == null) {
            return;
        }
        boolean playing = false;
        boolean started = false;
        boolean resume = false;
        short[] buffer = new short[Math.max(1, sampleRate / 10)];
        try {
            while (!cancelled) {
                boolean finished = queue.isFinished();
                int queued = queue.queued();
                if (!playing) {
                    if (!PcmPlayback.shouldStart(queued, sampleRate, finished)) {
                        if (finished && queued == 0) {
                            break;
                        }
                        sleep();
                        continue;
                    }
                    audio.play();
                    playing = true;
                    if (!started) {
                        started = true;
                        if (listener != null) {
                            listener.onStarted();
                        }
                    }
                } else if (PcmPlayback.shouldPause(queued, sampleRate, finished)) {
                    audio.pause();
                    playing = false;
                    resume = true;
                    sleep();
                    continue;
                }
                int count = queue.read(buffer);
                if (count < 0 || cancelled) {
                    break;
                }
                if (count == 0) {
                    if (queue.isFinished()) {
                        break;
                    }
                    sleep();
                    continue;
                }
                if (resume) {
                    PcmPlayback.fadeIn(buffer, count, sampleRate);
                    resume = false;
                }
                int offset = 0;
                while (offset < count && !cancelled) {
                    int wrote = audio.write(buffer, offset, count - offset);
                    if (wrote <= 0) {
                        break;
                    }
                    offset += wrote;
                    written += wrote;
                }
            }
            if (!cancelled && audio.getPlayState() == AudioTrack.PLAYSTATE_PAUSED && written > 0) {
                audio.play();
            }
            if (!cancelled) {
                waitForHead(audio);
            }
        } catch (Throwable ignored) {
        } finally {
            try {
                audio.pause();
            } catch (Throwable ignored) {
            }
            if (!cancelled && listener != null) {
                listener.onDrained();
            }
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
