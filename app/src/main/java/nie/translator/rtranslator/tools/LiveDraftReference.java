package nie.translator.rtranslator.tools;

/**
 * Whether an uploaded CosyVoice reference clip is long enough.
 * {@link Kind} records the old MOSS clip order and stays for the unit test.
 */
public final class LiveDraftReference {
    public static final int SHORT_MS = 3000;
    public static final int RECOMMENDED_MAX_MS = 10000;
    public static final int LIMIT_MS = 15000;

    public enum DurationNote {
        OK,
        SHORT,
        LONG,
        UNREADABLE
    }

    public enum Kind {
        UPLOAD,
        BUILTIN,
        PACKAGED,
        GENERATED,
        CROSS_LINGUAL
    }

    private LiveDraftReference() {
    }

    public static DurationNote duration(long durationMs) {
        if (durationMs <= 0) {
            return DurationNote.UNREADABLE;
        }
        if (durationMs < SHORT_MS) {
            return DurationNote.SHORT;
        }
        if (durationMs > RECOMMENDED_MAX_MS) {
            return DurationNote.LONG;
        }
        return DurationNote.OK;
    }

    /** Samples kept from a reference. Longer clips use only the first 15 seconds. */
    public static int keptSamples(int sampleRate, int sampleCount) {
        if (sampleRate <= 0 || sampleCount <= 0) {
            return 0;
        }
        int limit = sampleRate * LIMIT_MS / 1000;
        return Math.min(sampleCount, limit);
    }

    public static Kind choose(boolean upload, boolean builtin, boolean packaged, boolean generated) {
        if (upload) {
            return Kind.UPLOAD;
        }
        if (builtin) {
            return Kind.BUILTIN;
        }
        if (packaged) {
            return Kind.PACKAGED;
        }
        if (generated) {
            return Kind.GENERATED;
        }
        return Kind.CROSS_LINGUAL;
    }
}
