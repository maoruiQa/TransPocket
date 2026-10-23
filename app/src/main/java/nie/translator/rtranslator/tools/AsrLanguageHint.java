package nie.translator.rtranslator.tools;

import java.util.Locale;

/**
 * Picks a Whisper language token when simultaneous interpretation must not
 * lock the decoder to one language. The configured language receives a limited
 * bonus. Another language is used only when it still leads after that bonus.
 */
public final class AsrLanguageHint {
    public static final float HINT_BONUS = 2f;
    public static final float SWITCH_MARGIN = 1.5f;

    private AsrLanguageHint() {
    }

    /** Language subtag only, so {@code zh-CN} and {@code zh_CN} both become {@code zh}. */
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

    /**
     * @param languageLogits scores for token ids {@code firstLanguageId + index}. Empty when the decoder returned none.
     * @param hintedId absolute token id, or a negative value when the configured language is not in this model
     * @return absolute token id, or -1 when there are no logits and the hint is unusable
     */
    public static int choose(float[] languageLogits, int firstLanguageId, int hintedId) {
        return choose(languageLogits, firstLanguageId, hintedId, HINT_BONUS, SWITCH_MARGIN);
    }

    static int choose(float[] languageLogits, int firstLanguageId, int hintedId, float hintBonus, float switchMargin) {
        int count = languageLogits == null ? 0 : languageLogits.length;
        if (count == 0) {
            if (hintedId >= firstLanguageId) {
                return hintedId;
            }
            return -1;
        }
        int hintIndex = hintedId - firstLanguageId;
        if (hintIndex < 0 || hintIndex >= count) {
            return firstLanguageId + argMax(languageLogits);
        }
        int other = -1;
        float otherScore = -Float.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            if (i == hintIndex) {
                continue;
            }
            if (other < 0 || languageLogits[i] > otherScore) {
                other = i;
                otherScore = languageLogits[i];
            }
        }
        float hintedScore = languageLogits[hintIndex] + hintBonus;
        if (other >= 0 && otherScore >= hintedScore + switchMargin) {
            return firstLanguageId + other;
        }
        return hintedId;
    }

    private static int argMax(float[] scores) {
        int best = 0;
        for (int i = 1; i < scores.length; i++) {
            if (scores[i] > scores[best]) {
                best = i;
            }
        }
        return best;
    }
}
