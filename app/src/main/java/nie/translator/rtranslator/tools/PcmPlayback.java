package nie.translator.rtranslator.tools;

/**
 * When to start, pause, and resume a streaming playback buffer.
 * Synthesis that runs ahead is held at {@link #HIGH_MS}. Synthesis that falls
 * behind pauses playback instead of inserting silence or skipping samples.
 */
public final class PcmPlayback {
    public static final int PRIME_MS = 300;
    public static final int LOW_MS = 60;
    public static final int HIGH_MS = 6000;
    public static final int RESUME_MS = 4000;
    public static final int FADE_MS = 5;

    private PcmPlayback() {
    }

    public static int samplesFor(int sampleRate, int millis) {
        if (sampleRate <= 0 || millis <= 0) {
            return 0;
        }
        return (int) Math.min(Integer.MAX_VALUE, (long) sampleRate * millis / 1000L);
    }

    /** Start once the buffer can cover a short opening, or immediately when nothing more will arrive. */
    public static boolean shouldStart(int queuedSamples, int sampleRate, boolean finished) {
        if (queuedSamples <= 0) {
            return false;
        }
        if (finished) {
            return true;
        }
        return queuedSamples >= samplesFor(sampleRate, PRIME_MS);
    }

    /** Pause before the buffer runs dry while more audio is still expected. */
    public static boolean shouldPause(int queuedSamples, int sampleRate, boolean finished) {
        if (finished) {
            return false;
        }
        return queuedSamples < samplesFor(sampleRate, LOW_MS);
    }

    /** Soften the first samples after playback resumes from a stall. */
    public static void fadeIn(short[] pcm, int count, int sampleRate) {
        if (pcm == null || count <= 0 || sampleRate <= 0) {
            return;
        }
        int fade = Math.max(1, samplesFor(sampleRate, FADE_MS));
        int limit = Math.min(count, fade);
        for (int i = 0; i < limit; i++) {
            pcm[i] = (short) ((pcm[i] * (i + 1)) / limit);
        }
    }
}
