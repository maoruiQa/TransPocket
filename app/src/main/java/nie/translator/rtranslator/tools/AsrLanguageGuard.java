package nie.translator.rtranslator.tools;

import java.util.HashMap;
import java.util.Map;

/**
 * Checks a transcript against the language the user selected. The check is a
 * script and exclusive-letter test, not a language model: it catches a Cyrillic
 * transcript labelled English, or Ukrainian letters in a Russian transcript,
 * and stays silent when the evidence is weak (short text, Latin to Latin).
 */
public final class AsrLanguageGuard {
    public enum Verdict { MATCH, WEAK, CONFLICT }

    /** Below this many letters the result is WEAK; a one-word clause has no evidence. */
    private static final int MIN_LETTERS = 12;
    private static final int SIBLING_LETTERS = 2;

    private static final String RU_ONLY = "ыэё";
    private static final String RU_HARD_SIGN = "ъ";
    private static final String UK_ONLY = "іїєґ";

    private static final Map<String, Family> FAMILIES = new HashMap<>();

    static {
        for (String code : new String[]{"en", "de", "fr", "es", "pt", "it", "nl", "sv", "da", "no", "fi", "pl",
                "cs", "sk", "hr", "sl", "ro", "tr", "id", "ms", "vi", "ca", "gl", "eu", "cy", "is", "mt", "lb",
                "af", "sw", "la", "ht", "mg", "sq", "az", "uz", "tk", "ha", "yo", "so", "oc", "br", "fo", "jw",
                "su", "tl", "mi", "haw"}) {
            FAMILIES.put(code, Family.LATIN);
        }
        for (String code : new String[]{"ru", "uk", "bg", "mk", "sr", "be", "kk", "ky", "tg", "mn", "tt", "ba"}) {
            FAMILIES.put(code, Family.CYRILLIC);
        }
        FAMILIES.put("zh", Family.HAN);
        FAMILIES.put("yue", Family.HAN);
        FAMILIES.put("ja", Family.JAPANESE);
        FAMILIES.put("ko", Family.HANGUL);
        for (String code : new String[]{"ar", "fa", "ur", "ps", "sd"}) {
            FAMILIES.put(code, Family.ARABIC);
        }
        for (String code : new String[]{"he", "yi"}) {
            FAMILIES.put(code, Family.HEBREW);
        }
        FAMILIES.put("el", Family.GREEK);
        for (String code : new String[]{"hi", "mr", "ne"}) {
            FAMILIES.put(code, Family.DEVANAGARI);
        }
        FAMILIES.put("th", Family.THAI);
        FAMILIES.put("bn", Family.BENGALI);
        FAMILIES.put("ta", Family.TAMIL);
        FAMILIES.put("te", Family.TELUGU);
        FAMILIES.put("kn", Family.KANNADA);
        FAMILIES.put("ml", Family.MALAYALAM);
        FAMILIES.put("gu", Family.GUJARATI);
        FAMILIES.put("pa", Family.GURMUKHI);
        FAMILIES.put("si", Family.SINHALA);
        FAMILIES.put("km", Family.KHMER);
        FAMILIES.put("my", Family.MYANMAR);
        FAMILIES.put("bo", Family.TIBETAN);
        FAMILIES.put("hy", Family.ARMENIAN);
        FAMILIES.put("ka", Family.GEORGIAN);
        FAMILIES.put("am", Family.ETHIOPIC);
        FAMILIES.put("lo", Family.LAO);
    }

    private AsrLanguageGuard() {
    }

    public static Verdict check(String text, String languageCode) {
        return analyze(text, languageCode).verdict;
    }

    public static String reason(String text, String languageCode) {
        return analyze(text, languageCode).reason;
    }

    public static Result analyze(String text, String languageCode) {
        String code = AsrLanguages.canonicalCode(languageCode);
        if (text == null || text.trim().isEmpty()) {
            return new Result(Verdict.WEAK, "empty", 0, 0);
        }
        if (code.isEmpty()) {
            return new Result(Verdict.WEAK, "unknown-language", 0, 0);
        }
        Family family = FAMILIES.get(code);
        if (family == null) {
            return new Result(Verdict.WEAK, "unknown-language", 0, 0);
        }

        int[] counts = countScripts(text);
        int total = 0;
        for (int count : counts) {
            total += count;
        }
        if (total == 0) {
            return new Result(Verdict.WEAK, "no-letters", 0, 0);
        }
        int expected = countFamily(family, counts);
        double ratio = (double) expected / total;

        if (family == Family.CYRILLIC && total >= MIN_LETTERS) {
            int ruOnly = countLetters(text, RU_ONLY);
            int ruHard = countLetters(text, RU_HARD_SIGN);
            int ukOnly = countLetters(text, UK_ONLY);
            if ("ru".equals(code) && ukOnly >= SIBLING_LETTERS && ruOnly == 0 && ruHard == 0) {
                return new Result(Verdict.CONFLICT, "uk-letters", total, expected);
            }
            if ("uk".equals(code) && ruOnly + ruHard >= SIBLING_LETTERS && ukOnly == 0) {
                return new Result(Verdict.CONFLICT, "ru-letters", total, expected);
            }
            if ("bg".equals(code) && (ruOnly >= SIBLING_LETTERS || ukOnly >= SIBLING_LETTERS)) {
                return new Result(Verdict.CONFLICT, "sibling-letters", total, expected);
            }
            if (("mk".equals(code) || "sr".equals(code))
                    && (ruOnly >= SIBLING_LETTERS || ukOnly >= SIBLING_LETTERS || ruHard >= SIBLING_LETTERS)) {
                return new Result(Verdict.CONFLICT, "sibling-letters", total, expected);
            }
        }
        // Serbian is written in both scripts; Latin text is not a contradiction.
        if ("sr".equals(code) && ratio < 0.40 && countFamily(Family.LATIN, counts) >= MIN_LETTERS) {
            return new Result(Verdict.WEAK, "latin-script", total, expected);
        }
        if (total < MIN_LETTERS) {
            return new Result(Verdict.WEAK, "short", total, expected);
        }
        if (ratio >= 0.85) {
            return new Result(Verdict.MATCH, "script", total, expected);
        }
        if (ratio < 0.40) {
            return new Result(Verdict.CONFLICT, "script", total, expected);
        }
        return new Result(Verdict.WEAK, "mixed-script", total, expected);
    }

    private static int[] countScripts(String text) {
        int[] counts = new int[Character.UnicodeScript.values().length];
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            if (Character.isLetter(codePoint)) {
                counts[Character.UnicodeScript.of(codePoint).ordinal()]++;
            }
        }
        return counts;
    }

    private static int countLetters(String text, String letters) {
        int count = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            if (letters.indexOf(Character.toLowerCase(codePoint)) >= 0) {
                count++;
            }
        }
        return count;
    }

    private static int countFamily(Family family, int[] counts) {
        switch (family) {
            case LATIN:
                return counts[Character.UnicodeScript.LATIN.ordinal()];
            case CYRILLIC:
                return counts[Character.UnicodeScript.CYRILLIC.ordinal()];
            case HAN:
                return counts[Character.UnicodeScript.HAN.ordinal()];
            case JAPANESE:
                return counts[Character.UnicodeScript.HAN.ordinal()]
                        + counts[Character.UnicodeScript.HIRAGANA.ordinal()]
                        + counts[Character.UnicodeScript.KATAKANA.ordinal()];
            case HANGUL:
                return counts[Character.UnicodeScript.HANGUL.ordinal()];
            case ARABIC:
                return counts[Character.UnicodeScript.ARABIC.ordinal()];
            case HEBREW:
                return counts[Character.UnicodeScript.HEBREW.ordinal()];
            case GREEK:
                return counts[Character.UnicodeScript.GREEK.ordinal()];
            case DEVANAGARI:
                return counts[Character.UnicodeScript.DEVANAGARI.ordinal()];
            case THAI:
                return counts[Character.UnicodeScript.THAI.ordinal()];
            case BENGALI:
                return counts[Character.UnicodeScript.BENGALI.ordinal()];
            case TAMIL:
                return counts[Character.UnicodeScript.TAMIL.ordinal()];
            case TELUGU:
                return counts[Character.UnicodeScript.TELUGU.ordinal()];
            case KANNADA:
                return counts[Character.UnicodeScript.KANNADA.ordinal()];
            case MALAYALAM:
                return counts[Character.UnicodeScript.MALAYALAM.ordinal()];
            case GUJARATI:
                return counts[Character.UnicodeScript.GUJARATI.ordinal()];
            case GURMUKHI:
                return counts[Character.UnicodeScript.GURMUKHI.ordinal()];
            case SINHALA:
                return counts[Character.UnicodeScript.SINHALA.ordinal()];
            case KHMER:
                return counts[Character.UnicodeScript.KHMER.ordinal()];
            case MYANMAR:
                return counts[Character.UnicodeScript.MYANMAR.ordinal()];
            case TIBETAN:
                return counts[Character.UnicodeScript.TIBETAN.ordinal()];
            case ARMENIAN:
                return counts[Character.UnicodeScript.ARMENIAN.ordinal()];
            case GEORGIAN:
                return counts[Character.UnicodeScript.GEORGIAN.ordinal()];
            case ETHIOPIC:
                return counts[Character.UnicodeScript.ETHIOPIC.ordinal()];
            case LAO:
                return counts[Character.UnicodeScript.LAO.ordinal()];
            default:
                return 0;
        }
    }

    private enum Family {
        LATIN, CYRILLIC, HAN, JAPANESE, HANGUL, ARABIC, HEBREW, GREEK, DEVANAGARI, THAI,
        BENGALI, TAMIL, TELUGU, KANNADA, MALAYALAM, GUJARATI, GURMUKHI, SINHALA, KHMER,
        MYANMAR, TIBETAN, ARMENIAN, GEORGIAN, ETHIOPIC, LAO
    }

    public static final class Result {
        public final Verdict verdict;
        public final String reason;
        public final int letters;
        public final int expectedLetters;

        Result(Verdict verdict, String reason, int letters, int expectedLetters) {
            this.verdict = verdict;
            this.reason = reason;
            this.letters = letters;
            this.expectedLetters = expectedLetters;
        }
    }
}
