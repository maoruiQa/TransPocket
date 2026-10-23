package nie.translator.rtranslator.voice_translation.neural_networks.voice;

/**
 * Where a VAD clause starts in the ring buffer. Silero confirms speech only
 * after a few loud frames, so the samples before that confirmation still
 * belong to the clause. Samples already handed to recognition stay out.
 */
public final class ClauseAudioStart {
    private ClauseAudioStart() {
    }

    /**
     * @param end exclusive index of the frame that confirmed speech
     * @param padSamples how far back the clause may reach, including that frame
     * @param head oldest sample the ring still holds
     * @param emittedUntil exclusive end of the previous clause, or -1 when none
     *                     has been emitted in this recording
     */
    public static int index(int bufferLength, int end, int padSamples, int head, int emittedUntil) {
        if (bufferLength <= 0) {
            throw new IllegalArgumentException("bufferLength");
        }
        if (padSamples < 0) {
            padSamples = 0;
        }
        end = floorMod(end, bufferLength);
        head = floorMod(head, bufferLength);
        int available = distance(bufferLength, head, end);
        if (padSamples > available) {
            padSamples = available;
        }
        int start = rewind(bufferLength, end, padSamples);
        if (emittedUntil < 0) {
            return start;
        }
        int boundary = floorMod(emittedUntil, bufferLength);
        int toBoundary = distance(bufferLength, head, boundary);
        if (toBoundary >= available) {
            return start;
        }
        int unsent = distance(bufferLength, boundary, end);
        int padded = distance(bufferLength, start, end);
        if (unsent < padded) {
            return boundary;
        }
        return start;
    }

    static int rewind(int bufferLength, int end, int samples) {
        end = floorMod(end, bufferLength);
        if (samples <= 0) {
            return end;
        }
        if (end >= samples) {
            return end - samples;
        }
        return bufferLength - (samples - end);
    }

    private static int distance(int bufferLength, int begin, int end) {
        if (begin <= end) {
            return end - begin;
        }
        return (bufferLength - begin) + end;
    }

    private static int floorMod(int value, int mod) {
        int remainder = value % mod;
        return remainder < 0 ? remainder + mod : remainder;
    }
}
