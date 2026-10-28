package nie.translator.rtranslator.tools;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Single source for ASR language codes, aliases and the Whisper / Qwen tables.
 * Pure Java so the mapping can be covered by local unit tests. Whisper Small has
 * 99 language tokens; large-v3 inserts {@code <|yue|>} before {@code <|translate|>}.
 */
public final class AsrLanguages {
    /** Token id of {@code <|en|>}: the first language after {@code <|startoftranscript|>}. */
    public static final int WHISPER_FIRST_LANGUAGE_ID = 50259;
    public static final int WHISPER_SMALL_LANGUAGE_COUNT = 99;
    /**
     * Request code for "detect the spoken language". It is the ISO 639-2 code for an
     * undetermined language, so no ASR table maps it to a token.
     */
    public static final String AUTO = "und";

    private static final String[] WHISPER_CODES = {
            "en", "zh", "de", "es", "ru", "ko", "fr", "ja", "pt", "tr", "pl", "ca", "nl", "ar", "sv", "it", "id", "hi", "fi", "vi",
            "he", "uk", "el", "ms", "cs", "ro", "da", "hu", "ta", "no", "th", "ur", "hr", "bg", "lt", "la", "mi", "ml", "cy", "sk",
            "te", "fa", "lv", "bn", "sr", "az", "sl", "kn", "et", "mk", "br", "eu", "is", "hy", "ne", "mn", "bs", "kk", "sq", "sw",
            "gl", "mr", "pa", "si", "km", "sn", "yo", "so", "af", "oc", "ka", "be", "tg", "sd", "gu", "am", "yi", "lo", "uz", "fo",
            "ht", "ps", "tk", "nn", "mt", "sa", "lb", "my", "bo", "tl", "mg", "as", "tt", "haw", "ln", "ha", "ba", "jw", "su", "yue"
    };

    private static final String[] ENGLISH_NAMES = {
            "English", "Chinese", "German", "Spanish", "Russian", "Korean", "French", "Japanese", "Portuguese", "Turkish",
            "Polish", "Catalan", "Dutch", "Arabic", "Swedish", "Italian", "Indonesian", "Hindi", "Finnish", "Vietnamese",
            "Hebrew", "Ukrainian", "Greek", "Malay", "Czech", "Romanian", "Danish", "Hungarian", "Tamil", "Norwegian",
            "Thai", "Urdu", "Croatian", "Bulgarian", "Lithuanian", "Latin", "Maori", "Malayalam", "Welsh", "Slovak",
            "Telugu", "Persian", "Latvian", "Bengali", "Serbian", "Azerbaijani", "Slovenian", "Kannada", "Estonian", "Macedonian",
            "Breton", "Basque", "Icelandic", "Armenian", "Nepali", "Mongolian", "Bosnian", "Kazakh", "Albanian", "Swahili",
            "Galician", "Marathi", "Punjabi", "Sinhala", "Khmer", "Shona", "Yoruba", "Somali", "Afrikaans", "Occitan",
            "Georgian", "Belarusian", "Tajik", "Sindhi", "Gujarati", "Amharic", "Yiddish", "Lao", "Uzbek", "Faroese",
            "Haitian Creole", "Pashto", "Turkmen", "Nynorsk", "Maltese", "Sanskrit", "Luxembourgish", "Burmese", "Tibetan", "Tagalog",
            "Malagasy", "Assamese", "Tatar", "Hawaiian", "Lingala", "Hausa", "Bashkir", "Javanese", "Sundanese", "Cantonese"
    };

    /** Two-letter and ISO-639-2 codes that the app or the models may produce. */
    private static final Map<String, String> ALIASES = new HashMap<>();
    private static final Map<String, Integer> WHISPER_INDEX = new HashMap<>();
    private static final Map<String, String> NAME_BY_CODE = new HashMap<>();
    private static final Map<String, String> CODE_BY_NAME = new HashMap<>();
    private static final Map<String, String> QWEN_NAME_OVERRIDES = new HashMap<>();
    private static final String[][] RELATED_GROUPS = {
            {"zh", "yue"}, {"no", "nn"}, {"hr", "sr", "bs"}, {"ms", "id"}, {"hi", "ur"}
    };

    static {
        addAlias("iw", "he");
        addAlias("in", "id");
        addAlias("ji", "yi");
        addAlias("jv", "jw");
        addAlias("fil", "tl");
        addAlias("nb", "no");
        addAlias("mo", "ro");
        addAlias("cmn", "zh");
        addAlias("zho", "zh");
        addAlias("chi", "zh");
        addAlias("ger", "de");
        addAlias("fre", "fr");
        addAlias("dut", "nl");
        addAlias("may", "ms");
        addAlias("per", "fa");
        addAlias("rum", "ro");
        addAlias("cze", "cs");
        addAlias("gre", "el");
        addAlias("ice", "is");
        addAlias("baq", "eu");
        addAlias("arm", "hy");
        addAlias("geo", "ka");
        addAlias("bur", "my");
        addAlias("tib", "bo");
        addAlias("wel", "cy");
        addAlias("mao", "mi");
        addAlias("slo", "sk");
        addAlias("alb", "sq");
        addAlias("mac", "mk");
        addAlias("nno", "nn");
        addAlias("jav", "jw");
        addAlias("tgl", "tl");

        for (int i = 0; i < WHISPER_CODES.length; i++) {
            WHISPER_INDEX.put(WHISPER_CODES[i], i);
            NAME_BY_CODE.put(WHISPER_CODES[i], ENGLISH_NAMES[i]);
            CODE_BY_NAME.put(ENGLISH_NAMES[i].toLowerCase(Locale.US), WHISPER_CODES[i]);
        }
        // Names used by the Qwen3-ASR export, which differ from the Whisper spelling.
        QWEN_NAME_OVERRIDES.put("tl", "Filipino");
        QWEN_NAME_OVERRIDES.put("yue", "Cantonese");
        QWEN_NAME_OVERRIDES.put("zh", "Chinese");
        QWEN_NAME_OVERRIDES.put("no", "Norwegian");
        QWEN_NAME_OVERRIDES.put("jw", "Javanese");
        CODE_BY_NAME.put("filipino", "tl");
        CODE_BY_NAME.put("mandarin", "zh");
        CODE_BY_NAME.put("farsi", "fa");
    }

    private AsrLanguages() {
    }

    private static void addAlias(String from, String to) {
        ALIASES.put(from, to);
    }

    /** Lowercase language subtag only, so {@code zh-CN} and {@code zh_CN} both become {@code zh}. */
    public static String baseCode(String language) {
        if (language == null) {
            return "";
        }
        String code = language.trim().toLowerCase(Locale.US);
        int dash = code.indexOf('-');
        if (dash > 0) {
            code = code.substring(0, dash);
        }
        int underscore = code.indexOf('_');
        if (underscore > 0) {
            code = code.substring(0, underscore);
        }
        return code;
    }

    /** Base code plus the aliases the app and the ASR models use for the same language. */
    public static String canonicalCode(String language) {
        String code = baseCode(language);
        if (code.isEmpty()) {
            return "";
        }
        String alias = ALIASES.get(code);
        return alias == null ? code : alias;
    }

    /** Absolute Whisper language token, or -1 when this model has no token for the code. */
    public static int whisperToken(String language, boolean largeV3) {
        String code = canonicalCode(language);
        if (code.isEmpty()) {
            return -1;
        }
        Integer index = WHISPER_INDEX.get(code);
        if (index == null) {
            return -1;
        }
        if (!largeV3 && index >= WHISPER_SMALL_LANGUAGE_COUNT) {
            if (!"yue".equals(code)) {
                return -1;
            }
            // Whisper Small has no <|yue|>; 50358 there is <|translate|>. Chinese is the
            // closest token the model actually has, and the guard still checks the text.
            index = WHISPER_INDEX.get("zh");
            if (index == null) {
                return -1;
            }
        }
        return WHISPER_FIRST_LANGUAGE_ID + index;
    }

    public static boolean isWhisperLanguage(String language, boolean largeV3) {
        return whisperToken(language, largeV3) >= 0;
    }

    /** Code for an absolute Whisper language token, or "" when the id is not a language. */
    public static String whisperCode(int token, boolean largeV3) {
        int index = token - WHISPER_FIRST_LANGUAGE_ID;
        int count = largeV3 ? WHISPER_CODES.length : WHISPER_SMALL_LANGUAGE_COUNT;
        if (index < 0 || index >= count) {
            return "";
        }
        return WHISPER_CODES[index];
    }

    /** Language codes in Whisper token order. Used by the language picker. */
    public static String[] whisperCodes(boolean largeV3) {
        if (largeV3) {
            return WHISPER_CODES.clone();
        }
        String[] small = new String[WHISPER_SMALL_LANGUAGE_COUNT];
        System.arraycopy(WHISPER_CODES, 0, small, 0, small.length);
        return small;
    }

    /** English name the Qwen3-ASR tokenizer expects after {@code language }. */
    public static String qwenName(String language) {
        String code = canonicalCode(language);
        if (code.isEmpty()) {
            return null;
        }
        String override = QWEN_NAME_OVERRIDES.get(code);
        if (override != null) {
            return override;
        }
        return NAME_BY_CODE.get(code);
    }

    /** Language code for a name emitted by Qwen3-ASR. "" when the name is unknown. */
    public static String qwenCode(String name) {
        if (name == null) {
            return "";
        }
        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        String code = CODE_BY_NAME.get(trimmed.toLowerCase(Locale.US));
        if (code != null) {
            return code;
        }
        String canonical = canonicalCode(trimmed);
        if (NAME_BY_CODE.containsKey(canonical)) {
            return canonical;
        }
        return "";
    }

    public static boolean isAuto(String language) {
        return AUTO.equals(baseCode(language));
    }

    /** True for a code in the Whisper table, the superset of what both ASR models emit. */
    public static boolean isKnownCode(String language) {
        return NAME_BY_CODE.containsKey(canonicalCode(language));
    }

    /**
     * The code itself plus the languages the ASR models routinely mistake it for.
     * A clause labelled with one of these is not evidence of a foreign speaker.
     */
    public static String[] relatedCodes(String language) {
        String code = canonicalCode(language);
        if (code.isEmpty()) {
            return new String[0];
        }
        for (String[] group : RELATED_GROUPS) {
            for (String member : group) {
                if (member.equals(code)) {
                    return group.clone();
                }
            }
        }
        return new String[]{code};
    }

    public static boolean sameLanguage(String left, String right) {
        String a = canonicalCode(left);
        String b = canonicalCode(right);
        return !a.isEmpty() && a.equals(b);
    }

    /** Readable name for diagnostics, or the original code when it is unknown. */
    public static String displayName(String language) {
        String code = canonicalCode(language);
        String name = NAME_BY_CODE.get(code);
        return name == null ? baseCode(language) : name;
    }
}
