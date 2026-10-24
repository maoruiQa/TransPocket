package nie.translator.rtranslator.tools;

/**
 * Picks a Whisper language token. The configured language receives a limited
 * bonus. A locked session keeps it. An unlocked session may switch, but only to
 * the language the user is translating into, and only when that language still
 * leads after the bonus.
 */
public final class AsrLanguageHint {
    public static final float HINT_BONUS = 2f;
    public static final float SWITCH_MARGIN = 1.5f;

    private AsrLanguageHint() {
    }

    /** Language subtag only, so {@code zh-CN} and {@code zh_CN} both become {@code zh}. */
    public static String baseCode(String language) {
        return AsrLanguages.baseCode(language);
    }

    /**
     * @param languageLogits scores for token ids {@code firstLanguageId + index}. Empty when the decoder returned none.
     * @param hintedId absolute token id, or a negative value when the configured language is not in this model
     * @return absolute token id, or -1 when there are no logits and the hint is unusable
     */
    public static int choose(float[] languageLogits, int firstLanguageId, int hintedId) {
        return chooseAny(languageLogits, firstLanguageId, hintedId, HINT_BONUS, SWITCH_MARGIN);
    }

    /**
     * @param allowSwitch false keeps the configured language. True still requires the
     *                    alternative to be {@code allowedSwitchId}.
     * @param allowedSwitchId absolute token id the session may switch to, usually the target language
     */
    public static int choose(float[] languageLogits, int firstLanguageId, int hintedId,
                             boolean allowSwitch, int allowedSwitchId) {
        return choose(languageLogits, firstLanguageId, hintedId, HINT_BONUS, SWITCH_MARGIN,
                allowSwitch, allowedSwitchId);
    }

    static int choose(float[] languageLogits, int firstLanguageId, int hintedId, float hintBonus, float switchMargin) {
        return chooseAny(languageLogits, firstLanguageId, hintedId, hintBonus, switchMargin);
    }

    static int choose(float[] languageLogits, int firstLanguageId, int hintedId, float hintBonus, float switchMargin,
                      boolean allowSwitch, int allowedSwitchId) {
        int count = languageLogits == null ? 0 : languageLogits.length;
        if (count == 0) {
            return hintedId >= firstLanguageId ? hintedId : -1;
        }
        int hintIndex = hintedId - firstLanguageId;
        if (hintIndex < 0 || hintIndex >= count) {
            return firstLanguageId + argMax(languageLogits);
        }
        if (!allowSwitch) {
            return hintedId;
        }
        int allowedIndex = allowedSwitchId - firstLanguageId;
        if (allowedIndex < 0 || allowedIndex >= count || allowedIndex == hintIndex) {
            return hintedId;
        }
        float hintedScore = languageLogits[hintIndex] + hintBonus;
        if (languageLogits[allowedIndex] >= hintedScore + switchMargin) {
            return allowedSwitchId;
        }
        return hintedId;
    }

    private static int chooseAny(float[] languageLogits, int firstLanguageId, int hintedId,
                                 float hintBonus, float switchMargin) {
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
