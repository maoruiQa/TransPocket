package nie.translator.rtranslator.tools;

import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Grades one voice clause as HIGH, MEDIUM or LOW. Most errors come from speech
 * recognition on poor audio, so the recognition signals decide the grade and the
 * translation signals only lower it. Kept free of Android types for host unit tests.
 */
public final class ClauseConfidence {
    public static final int UNKNOWN = 0;
    public static final int LOW = 1;
    public static final int MEDIUM = 2;
    public static final int HIGH = 3;

    /** Mean token log-prob of the recognizer. Starting points, tuned from the "simultaneous" log. */
    static final double ASR_HIGH_LOG_PROB = -0.4;
    static final double ASR_LOW_LOG_PROB = -0.9;
    /** One CJK character is one unit; one word of a spaced language is about 1.7 units of speaking time. */
    private static final double WORD_UNITS = 1.7;
    /** Normal speech is about 5 units per second. Far above that, the text was not in the audio. */
    private static final double MAX_UNITS_PER_SECOND = 12;
    private static final int MIN_RATE_AUDIO_MS = 300;
    private static final int LONG_AUDIO_MS = 3000;
    private static final double SHORT_FRAGMENT_UNITS = 2;
    /** A repeated tail at least this long is a decoder loop, not emphasis. */
    private static final int MIN_LOOP_UNITS = 6;
    private static final double MAX_LENGTH_RATIO = 4;
    private static final double MIN_RATIO_SOURCE_UNITS = 4;
    private static final int MIN_CONTEXT_ECHO_CHARS = 12;
    /** Phrases Whisper-style recognizers write for silence or noise. */
    private static final String[] HALLUCINATIONS = {
            "thank you for watching", "thanks for watching", "please subscribe", "subtitles by", "amara.org",
            "谢谢观看", "感谢观看", "谢谢收看", "字幕由", "字幕提供", "字幕志愿者", "请不吝点赞", "订阅转发",
            "ご視聴ありがとうございました", "시청해주셔서 감사합니다"
    };
    private static final Pattern MODEL_RATING = Pattern.compile("\\[\\s*Q\\s*=\\s*([123])\\s*]", Pattern.CASE_INSENSITIVE);

    private ClauseConfidence() {
    }

    /** NaN, infinity and scores &ge; 0 mean the engine gave no score. */
    public static int fromAsrScore(double avgLogProb) {
        if (Double.isNaN(avgLogProb) || Double.isInfinite(avgLogProb) || avgLogProb >= 0) {
            return UNKNOWN;
        }
        if (avgLogProb >= ASR_HIGH_LOG_PROB) {
            return HIGH;
        }
        if (avgLogProb < ASR_LOW_LOG_PROB) {
            return LOW;
        }
        return MEDIUM;
    }

    /**
     * Grades a whole recognition result. Clauses split from it share this grade.
     * @param audioMs 0 when the length of the audio is not known
     */
    public static int asrLevel(String recognizedText, double avgLogProb, int audioMs) {
        if (recognizedText == null || recognizedText.trim().isEmpty()) {
            return LOW;
        }
        String lower = recognizedText.toLowerCase(Locale.ROOT);
        for (String phrase : HALLUCINATIONS) {
            if (lower.contains(phrase)) {
                return LOW;
            }
        }
        if (isLoop(recognizedText)) {
            return LOW;
        }
        int level = fromAsrScore(avgLogProb);
        if (level == UNKNOWN) {
            level = HIGH;
        }
        double units = speechUnits(recognizedText);
        if (audioMs >= MIN_RATE_AUDIO_MS && units * 1000.0 / audioMs > MAX_UNITS_PER_SECOND) {
            return LOW;
        }
        if (audioMs >= LONG_AUDIO_MS && units <= SHORT_FRAGMENT_UNITS) {
            level = Math.min(level, MEDIUM);
        }
        return level;
    }

    /** A one-word fragment may translate fine but says too little to be trusted as context. */
    public static int fragmentLevel(String clause) {
        return speechUnits(clause) <= SHORT_FRAGMENT_UNITS ? MEDIUM : HIGH;
    }

    /**
     * @param previousTranslation the context translation that should have been stripped, or null
     */
    public static int translationLevel(String source, String translation, String previousTranslation, boolean sameLanguage) {
        String src = source == null ? "" : source.trim();
        String out = translation == null ? "" : translation.trim();
        if (src.isEmpty()) {
            return UNKNOWN;
        }
        if (out.isEmpty()) {
            return MEDIUM;
        }
        if (isLoop(out)) {
            return LOW;
        }
        int level = HIGH;
        if (!sameLanguage && src.length() > 3 && src.equalsIgnoreCase(out)) {
            level = MEDIUM;
        }
        double srcUnits = speechUnits(src);
        if (srcUnits >= MIN_RATIO_SOURCE_UNITS) {
            double ratio = speechUnits(out) / srcUnits;
            if (ratio > MAX_LENGTH_RATIO || ratio < 1 / MAX_LENGTH_RATIO) {
                level = MEDIUM;
            }
        }
        if (echoesContext(previousTranslation, out)) {
            level = MEDIUM;
        }
        return level;
    }

    /** LOW, MEDIUM or HIGH from a {@code [Q=n]} tag, else UNKNOWN. */
    public static int modelRating(String output) {
        if (output == null) {
            return UNKNOWN;
        }
        Matcher matcher = MODEL_RATING.matcher(output);
        int rating = UNKNOWN;
        while (matcher.find()) {
            rating = matcher.group(1).charAt(0) - '0';
        }
        return rating;
    }

    /** The output without any {@code [Q=n]} tag, so neither the screen nor TTS gets it. */
    public static String withoutModelRating(String output) {
        if (output == null) {
            return "";
        }
        return MODEL_RATING.matcher(output).replaceAll("").trim();
    }

    /**
     * The model can only lower the grade. A small model is often wrong, so on clean
     * signals it can lower the grade to MEDIUM at most.
     */
    public static int withModelRating(int signals, int model) {
        if (model == UNKNOWN) {
            return signals;
        }
        if (signals == UNKNOWN) {
            return model;
        }
        int floor = signals == HIGH ? MEDIUM : LOW;
        return Math.min(signals, Math.max(model, floor));
    }

    /** The lowest known grade, or UNKNOWN when none is known. */
    public static int lowest(int... levels) {
        int result = UNKNOWN;
        for (int level : levels) {
            if (level != UNKNOWN && (result == UNKNOWN || level < result)) {
                result = level;
            }
        }
        return result;
    }

    public static String name(int level) {
        switch (level) {
            case HIGH:
                return "high";
            case MEDIUM:
                return "medium";
            case LOW:
                return "low";
            default:
                return "unknown";
        }
    }

    static double speechUnits(String text) {
        if (text == null) {
            return 0;
        }
        double units = 0;
        boolean inWord = false;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            if (isCjk(codePoint)) {
                units += 1;
                inWord = false;
            } else if (Character.isLetterOrDigit(codePoint)) {
                if (!inWord) {
                    units += WORD_UNITS;
                    inWord = true;
                }
            } else {
                inWord = false;
            }
        }
        return units;
    }

    private static boolean isCjk(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA || script == Character.UnicodeScript.HANGUL
                || script == Character.UnicodeScript.THAI;
    }

    /** Words, or single characters for CJK, as ids that {@link AsrRepetition} can compare. */
    private static boolean isLoop(String text) {
        ArrayList<Integer> tokens = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            if (isCjk(codePoint)) {
                addWord(tokens, word);
                tokens.add(codePoint);
            } else if (Character.isLetterOrDigit(codePoint)) {
                word.appendCodePoint(Character.toLowerCase(codePoint));
            } else {
                addWord(tokens, word);
            }
        }
        addWord(tokens, word);
        return tokens.size() - AsrRepetition.keepCount(tokens) >= MIN_LOOP_UNITS;
    }

    private static void addWord(ArrayList<Integer> tokens, StringBuilder word) {
        if (word.length() > 0) {
            // Offset keeps word hashes apart from CJK code points.
            tokens.add(word.toString().hashCode() | 0x40000000);
            word.setLength(0);
        }
    }

    private static boolean echoesContext(String previousTranslation, String output) {
        if (previousTranslation == null) {
            return false;
        }
        String previous = previousTranslation.trim();
        if (previous.length() < MIN_CONTEXT_ECHO_CHARS) {
            return false;
        }
        int needed = Math.max(MIN_CONTEXT_ECHO_CHARS, (int) (previous.length() * 0.6));
        int shared = 0;
        int max = Math.min(previous.length(), output.length());
        while (shared < max && Character.toLowerCase(previous.charAt(shared)) == Character.toLowerCase(output.charAt(shared))) {
            shared++;
        }
        return shared >= needed;
    }
}
