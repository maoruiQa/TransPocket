package nie.translator.rtranslator.voice_translation._live_translation._simultaneous;

/**
 * The clause that is still being spoken, as the recognizer re-reads its growing audio.
 *
 * Text is "stable" once two consecutive partial transcripts agree on it (local agreement).
 * A stable piece that ends at punctuation, or a long run of stable words, can be translated
 * before the speaker pauses. The final transcript then only needs its tail translated.
 * No Android types, so the rules can be checked with plain strings.
 */
public final class StreamingClause {
    /** Smallest piece worth a translation call: words for spaced scripts, characters otherwise. */
    static final int MIN_SEGMENT_WORDS = 3;
    static final int MIN_SEGMENT_CHARS = 4;
    /** Without punctuation, a spaced script is cut at a word boundary after this many stable words. */
    static final int FORCED_CUT_WORDS = 8;

    private String previous = "";
    private String committed = "";
    /** Message that shows the uncommitted text, or -1. */
    public long draftId = -1;
    /** Message of an early piece whose translation was stopped, or -1. The whole clause takes it over. */
    public long reuseId = -1;

    /**
     * Takes the next partial transcript.
     *
     * @return a newly stable piece to translate now, or null
     */
    public String offer(String partial, boolean commitEarly) {
        String text = partial == null ? "" : partial.trim();
        String stable = text.substring(0, commonPrefix(previous, text));
        previous = text;
        if (!commitEarly || !stable.startsWith(committed)) {
            return null;
        }
        String fresh = stable.substring(committed.length());
        int cut = cutIndex(fresh, stable.length() == text.length());
        if (cut <= 0) {
            return null;
        }
        String segment = fresh.substring(0, cut).trim();
        if (segment.isEmpty()) {
            return null;
        }
        committed = committed + fresh.substring(0, cut);
        return segment;
    }

    /** The part of a partial transcript that has not been handed to translation. */
    public String uncommitted(String partial) {
        String text = partial == null ? "" : partial.trim();
        return text.substring(commonPrefix(committed, text)).trim();
    }

    public boolean hasCommitted() {
        return !committed.isEmpty();
    }

    /**
     * The early piece was not translated after all. {@link #finish} then returns the whole final
     * text, and {@code pieceId} is kept as the message that text is shown in. Nothing more is
     * committed for this clause.
     */
    public void rollback(long pieceId) {
        committed = "";
        reuseId = pieceId;
    }

    /**
     * The final transcript minus what was already translated. If recognition rewrote a
     * committed word, the tail starts where the texts first differ.
     * Clears the text state; {@link #draftId} is left to the caller.
     */
    public String finish(String finalText) {
        String text = finalText == null ? "" : finalText.trim();
        String tail = text.substring(commonPrefix(committed, text)).trim();
        previous = "";
        committed = "";
        return tail;
    }

    public void reset() {
        previous = "";
        committed = "";
        draftId = -1;
        reuseId = -1;
    }

    static int commonPrefix(String a, String b) {
        int n = Math.min(a.length(), b.length());
        int i = 0;
        while (i < n && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        return i;
    }

    /**
     * End of the longest piece of {@code fresh} that can be committed, or -1. The last stable
     * word may be cut short, so a forced cut stops at whitespace unless the whole partial agreed.
     */
    static int cutIndex(String fresh, boolean wholePartialStable) {
        boolean spaced = !hasUnspacedScript(fresh);
        for (int i = fresh.length() - 1; i >= 0; i--) {
            if (!isBoundary(fresh, i)) {
                continue;
            }
            if (isLongEnough(fresh.substring(0, i + 1), spaced)) {
                return i + 1;
            }
            break;
        }
        if (!spaced) {
            return -1;
        }
        int end = fresh.length();
        if (!wholePartialStable) {
            end = fresh.lastIndexOf(' ');
            if (end <= 0) {
                return -1;
            }
        }
        String candidate = fresh.substring(0, end);
        if (words(candidate) >= FORCED_CUT_WORDS) {
            return end;
        }
        return -1;
    }

    private static boolean isBoundary(String text, int index) {
        char c = text.charAt(index);
        switch (c) {
            case '，':
            case '。':
            case '！':
            case '？':
            case '；':
            case '：':
            case '、':
                return true;
            case ',':
            case '.':
            case '!':
            case '?':
            case ';':
            case ':':
                // "3.5" and "1,000" are not boundaries; the next character must be a space.
                return index + 1 < text.length() && Character.isWhitespace(text.charAt(index + 1));
            default:
                return false;
        }
    }

    private static boolean isLongEnough(String segment, boolean spaced) {
        String trimmed = segment.trim();
        if (spaced) {
            return words(trimmed) >= MIN_SEGMENT_WORDS;
        }
        return trimmed.codePointCount(0, trimmed.length()) >= MIN_SEGMENT_CHARS;
    }

    private static int words(String text) {
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return 0;
        }
        return trimmed.split("\\s+").length;
    }

    /** Chinese, Japanese, Thai and similar scripts write words without spaces. */
    static boolean hasUnspacedScript(String text) {
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            Character.UnicodeScript script = Character.UnicodeScript.of(cp);
            if (script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA || script == Character.UnicodeScript.THAI
                    || script == Character.UnicodeScript.LAO || script == Character.UnicodeScript.KHMER
                    || script == Character.UnicodeScript.MYANMAR || script == Character.UnicodeScript.TIBETAN) {
                return true;
            }
            i += Character.charCount(cp);
        }
        return false;
    }
}
