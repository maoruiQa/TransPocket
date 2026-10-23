package nie.translator.rtranslator.tools;

import java.util.ArrayList;

/**
 * Text helpers for simultaneous interpretation. Kept free of Android types so
 * host unit tests can cover overlap, context stripping, and TTS chunking.
 */
public final class SimultaneousText {
    /** A sentence with no ending mark is split once it reaches this length. */
    public static final int SPEAK_CHUNK_CHARS = 80;

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
     * Returns the next sentence that can be spoken, or null while the pending
     * text is still too short. One call never returns more than one sentence.
     * A final result still comes out one sentence at a time; the caller loops.
     * A sentence that already has an ending is kept whole. Text with no ending
     * is cut at {@link #SPEAK_CHUNK_CHARS}.
     */
    public static String takeSpeakable(String pending, boolean isFinal) {
        return takeSpeakable(pending, isFinal, SPEAK_CHUNK_CHARS, false);
    }

    /**
     * Same as {@link #takeSpeakable(String, boolean)}. When {@code hardCap} is
     * set, a sentence longer than {@code maxChars} is cut inside that cap so a
     * synthesizer is not given a whole paragraph in one request.
     */
    public static String takeSpeakable(String pending, boolean isFinal, int maxChars, boolean hardCap) {
        if (pending == null || pending.trim().isEmpty()) {
            return null;
        }
        int cap = maxChars <= 0 ? SPEAK_CHUNK_CHARS : maxChars;
        int sentence = firstSentenceEnd(pending);
        if (sentence > 0 && (!hardCap || sentence <= cap)) {
            return pending.substring(0, sentence);
        }
        if (pending.length() >= cap || sentence > cap) {
            int cut = lastClause(pending, Math.min(pending.length(), cap));
            if (cut <= 0) {
                cut = Math.min(pending.length(), cap);
            }
            return pending.substring(0, cut);
        }
        if (isFinal) {
            return pending;
        }
        return null;
    }

    /** False when the piece would make a speech engine wait forever on punctuation. */
    public static boolean isAudible(String text) {
        if (text == null) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (Character.isLetterOrDigit(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /** Every piece that can be spoken now, leaving an unfinished tail behind. */
    public static ArrayList<String> speakablePieces(String pending, boolean isFinal) {
        ArrayList<String> pieces = new ArrayList<>();
        String rest = pending == null ? "" : pending;
        while (!rest.isEmpty()) {
            String piece = takeSpeakable(rest, isFinal);
            if (piece == null || piece.isEmpty()) {
                break;
            }
            pieces.add(piece);
            if (piece.length() >= rest.length()) {
                break;
            }
            rest = rest.substring(piece.length());
        }
        return pieces;
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

    private static int firstSentenceEnd(String pending) {
        for (int i = 0; i < pending.length(); i++) {
            char c = pending.charAt(i);
            if (c == '.' && isDecimalPoint(pending, i)) {
                continue;
            }
            if (isSentenceBoundary(c)) {
                return i + 1;
            }
        }
        return -1;
    }

    /** Last comma, enumeration comma, or space inside the cap, when it is not tiny. */
    private static int lastClause(String text, int cap) {
        int limit = Math.min(text.length(), cap);
        int cut = -1;
        for (int i = 7; i < limit; i++) {
            char c = text.charAt(i);
            if (c == ',' || c == '，' || c == '、' || c == ' ') {
                cut = i + 1;
            }
        }
        return cut;
    }

    private static boolean isSentenceBoundary(char c) {
        return c == '。' || c == '！' || c == '？' || c == '；'
                || c == '!' || c == '?' || c == ';' || c == '.' || c == '\n'
                || c == '．' || c == '｡';
    }

    private static boolean isDecimalPoint(String text, int index) {
        return index > 0 && index + 1 < text.length()
                && Character.isDigit(text.charAt(index - 1))
                && Character.isDigit(text.charAt(index + 1));
    }
}
