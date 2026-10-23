package nie.translator.rtranslator.tools;

import java.util.ArrayDeque;

/**
 * PCM waiting to be played. Writers block once the queue is {@link PcmPlayback#HIGH_MS}
 * ahead, and stay blocked until it falls to {@link PcmPlayback#RESUME_MS}.
 * The queue never pads with silence.
 */
public final class PcmQueue {
    private final int highSamples;
    private final int resumeSamples;
    private final Object lock = new Object();
    private final ArrayDeque<short[]> chunks = new ArrayDeque<>();
    private int headOffset;
    private int queued;
    private boolean finished;
    private boolean cancelled;
    private boolean holding;
    /** A finished sentence may start playback before the opening prime fills. */
    private boolean sentenceReady;

    public PcmQueue(int sampleRate) {
        highSamples = Math.max(1, PcmPlayback.samplesFor(sampleRate, PcmPlayback.HIGH_MS));
        resumeSamples = Math.max(1, PcmPlayback.samplesFor(sampleRate, PcmPlayback.RESUME_MS));
    }

    public void write(short[] pcm) throws InterruptedException {
        if (pcm == null || pcm.length == 0) {
            return;
        }
        int offset = 0;
        synchronized (lock) {
            while (offset < pcm.length) {
                if (cancelled) {
                    return;
                }
                if (queued >= highSamples || (holding && queued > resumeSamples)) {
                    holding = true;
                    lock.wait(50);
                    continue;
                }
                holding = false;
                int room = highSamples - queued;
                int count = Math.min(room, pcm.length - offset);
                short[] slice = new short[count];
                System.arraycopy(pcm, offset, slice, 0, count);
                chunks.addLast(slice);
                queued += count;
                offset += count;
                lock.notifyAll();
            }
        }
    }

    /** How many samples fit right now. Does not block. */
    public int offer(short[] pcm) {
        if (pcm == null || pcm.length == 0) {
            return 0;
        }
        synchronized (lock) {
            if (cancelled || queued >= highSamples || (holding && queued > resumeSamples)) {
                holding = queued >= highSamples || holding;
                return 0;
            }
            int room = highSamples - queued;
            int count = Math.min(room, pcm.length);
            short[] slice = new short[count];
            System.arraycopy(pcm, 0, slice, 0, count);
            chunks.addLast(slice);
            queued += count;
            lock.notifyAll();
            return count;
        }
    }

    /**
     * Copies queued samples into {@code dest}. Returns the count, or -1 when cancelled.
     * Zero means the queue is empty.
     */
    public int read(short[] dest) {
        if (dest == null || dest.length == 0) {
            return 0;
        }
        synchronized (lock) {
            if (cancelled) {
                return -1;
            }
            int filled = 0;
            while (filled < dest.length && queued > 0) {
                short[] head = chunks.peekFirst();
                int count = Math.min(head.length - headOffset, dest.length - filled);
                System.arraycopy(head, headOffset, dest, filled, count);
                headOffset += count;
                filled += count;
                queued -= count;
                if (headOffset >= head.length) {
                    chunks.removeFirst();
                    headOffset = 0;
                }
            }
            if (filled > 0) {
                lock.notifyAll();
            }
            return filled;
        }
    }

    /** One sentence has been synthesized. Short audio can start without waiting for the rest. */
    public void sentenceReady() {
        synchronized (lock) {
            sentenceReady = true;
            lock.notifyAll();
        }
    }

    public boolean isSentenceReady() {
        synchronized (lock) {
            return sentenceReady;
        }
    }

    public void finish() {
        synchronized (lock) {
            finished = true;
            lock.notifyAll();
        }
    }

    /**
     * Blocks until samples arrive, the utterance is finished, or playback is cancelled.
     * Returns 0 when finished and empty, or -1 when cancelled.
     */
    public int readWaiting(short[] dest) throws InterruptedException {
        if (dest == null || dest.length == 0) {
            return 0;
        }
        synchronized (lock) {
            while (!cancelled && queued == 0 && !finished) {
                lock.wait(50);
            }
            if (cancelled) {
                return -1;
            }
            if (queued == 0) {
                return 0;
            }
        }
        return read(dest);
    }

    public void cancel() {
        synchronized (lock) {
            cancelled = true;
            finished = true;
            chunks.clear();
            headOffset = 0;
            queued = 0;
            holding = false;
            lock.notifyAll();
        }
    }

    public int queued() {
        synchronized (lock) {
            return queued;
        }
    }

    public boolean isFinished() {
        synchronized (lock) {
            return finished;
        }
    }

    public boolean isCancelled() {
        synchronized (lock) {
            return cancelled;
        }
    }
}
