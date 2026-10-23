package nie.translator.rtranslator.tools;

/**
 * Text helpers for simultaneous interpretation. Kept free of Android types so
 * host unit tests can cover overlap, context stripping, and TTS chunking.
 */
public final class SimultaneousText {
    public static final int SPEAK_CHUNK_CHARS = 16;

    private SimultaneousText() {
    }

    /**
     * Drops a prefix of {@code current} that repeats the end of {@code previous}.
     * A match is at least four characters, or the last two words.
     */
    public static String stripOverlap(String previous, String current) {
        if (current == null) {
            return "";
        }
        String next = current.trim();
        if (previous == null || next.isEmpty()) {
            return next;
        }
        String prior = previous.trim();
        if (prior.isEmpty()) {
            return next;
        }
        int cut = charOverlap(prior, next);
        int wordCut = wordOverlap(prior, next);
        if (wordCut > cut) {
            cut = wordCut;
        }
        if (cut <= 0 || cut > next.length()) {
            return next;
        }
        return next.substring(cut).trim();
    }

    /**
     * Drops a leading copy of the previous translation. If it does not match,
     * the whole output is kept.
     */
    public static String stripContextTranslation(String previousTranslation, String output) {
        if (output == null) {
            return "";
        }
        String translated = output.trim();
        if (previousTranslation == null || translated.isEmpty()) {
            return translated;
        }
        String previous = previousTranslation.trim();
        if (previous.isEmpty()) {
            return translated;
        }
        if (translated.regionMatches(true, 0, previous, 0, previous.length())) {
            return translated.substring(previous.length()).trim();
        }
        return translated;
    }

    /**
     * Returns the next piece that can be spoken, or null while the pending
     * text is still too short. A final result speaks whatever remains.
     */
    public static String takeSpeakable(String pending, boolean isFinal) {
        if (pending == null) {
            return null;
        }
        if (pending.trim().isEmpty()) {
            return null;
        }
        if (isFinal) {
            return pending;
        }
        int cut = 0;
        for (int i = 0; i < pending.length(); i++) {
            char c = pending.charAt(i);
            if (c == '.' && isDecimalPoint(pending, i)) {
                continue;
            }
            if (isBoundary(c)) {
                cut = i + 1;
            }
        }
        if (cut > 0) {
            return pending.substring(0, cut);
        }
        if (pending.length() >= SPEAK_CHUNK_CHARS) {
            int space = pending.lastIndexOf(' ', SPEAK_CHUNK_CHARS);
            if (space >= 4) {
                return pending.substring(0, space + 1);
            }
            return pending.substring(0, SPEAK_CHUNK_CHARS);
        }
        return null;
    }

    private static int charOverlap(String previous, String current) {
        int max = Math.min(previous.length(), current.length());
        for (int len = max; len >= 4; len--) {
            if (current.regionMatches(true, 0, previous, previous.length() - len, len)) {
                return len;
            }
        }
        return 0;
    }

    private static int wordOverlap(String previous, String current) {
        int previousStart = startOfLastWords(previous, 2);
        int currentEnd = endOfFirstWords(current, 2);
        if (previousStart < 0 || currentEnd < 0) {
            return 0;
        }
        String tail = previous.substring(previousStart);
        String head = current.substring(0, currentEnd);
        if (tail.equalsIgnoreCase(head)) {
            return currentEnd;
        }
        return 0;
    }

    private static int startOfLastWords(String text, int words) {
        int index = text.length() - 1;
        int found = 0;
        while (index >= 0 && found < words) {
            while (index >= 0 && Character.isWhitespace(text.charAt(index))) {
                index--;
            }
            if (index < 0) {
                break;
            }
            while (index >= 0 && !Character.isWhitespace(text.charAt(index))) {
                index--;
            }
            found++;
        }
        if (found < words) {
            return -1;
        }
        return index + 1;
    }

    private static int endOfFirstWords(String text, int words) {
        int index = 0;
        int found = 0;
        int end = -1;
        while (index < text.length() && found < words) {
            while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
                index++;
            }
            if (index >= text.length()) {
                break;
            }
            while (index < text.length() && !Character.isWhitespace(text.charAt(index))) {
                index++;
            }
            found++;
            end = index;
        }
        if (found < words) {
            return -1;
        }
        return end;
    }

    private static boolean isBoundary(char c) {
        return c == '。' || c == '！' || c == '？' || c == '；' || c == '，' || c == '、'
                || c == '!' || c == '?' || c == ';' || c == ',' || c == '.' || c == '\n';
    }

    private static boolean isDecimalPoint(String text, int index) {
        return index > 0 && index + 1 < text.length()
                && Character.isDigit(text.charAt(index - 1))
                && Character.isDigit(text.charAt(index + 1));
    }
}
