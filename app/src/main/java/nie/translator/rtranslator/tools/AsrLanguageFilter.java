package nie.translator.rtranslator.tools;

import java.util.ArrayList;

/**
 * Decides whether a clause was spoken in a language the session does not use.
 * Forcing such a clause through the configured language token makes Whisper
 * translate or invent text, which is then translated and kept as context. The
 * test is deliberately one-sided: a clause is rejected only when the acoustic
 * language ID is confident and every accepted language is far behind.
 */
public final class AsrLanguageFilter {
    /** One second at 16 kHz. Language ID on shorter clips is not reliable. */
    public static final int MIN_SAMPLES = 16000;
    /** Same gap a soft prompt needs before it leaves the configured language. */
    static final float REJECT_MARGIN = AsrLanguageHint.HINT_BONUS + AsrLanguageHint.SWITCH_MARGIN;
    static final double MIN_FOREIGN_PROBABILITY = 0.5;

    private AsrLanguageFilter() {
    }

    /**
     * @param languageLogits scores for token ids {@code firstLanguageId + index}
     * @param acceptedIds absolute token ids of the session languages, related languages included
     * @return absolute token id of the foreign language, or -1 when the clause is kept
     */
    public static int foreignLanguage(float[] languageLogits, int firstLanguageId, int[] acceptedIds) {
        int count = languageLogits == null ? 0 : languageLogits.length;
        if (count == 0 || acceptedIds == null || acceptedIds.length == 0) {
            return -1;
        }
        int top = 0;
        for (int i = 1; i < count; i++) {
            if (languageLogits[i] > languageLogits[top]) {
                top = i;
            }
        }
        float bestAccepted = -Float.MAX_VALUE;
        boolean anyAccepted = false;
        for (int id : acceptedIds) {
            int index = id - firstLanguageId;
            if (index < 0 || index >= count) {
                continue;
            }
            if (index == top) {
                return -1;
            }
            anyAccepted = true;
            bestAccepted = Math.max(bestAccepted, languageLogits[index]);
        }
        if (!anyAccepted) {
            // The session language has no token in this model; there is nothing to compare.
            return -1;
        }
        if (languageLogits[top] - bestAccepted < REJECT_MARGIN) {
            return -1;
        }
        if (probability(languageLogits, top) < MIN_FOREIGN_PROBABILITY) {
            return -1;
        }
        return firstLanguageId + top;
    }

    /** True when {@code detected} is a known language outside {@code accepted} and its related codes. */
    public static boolean isForeign(String detected, String... accepted) {
        if (!AsrLanguages.isKnownCode(detected) || accepted == null) {
            return false;
        }
        boolean anyAccepted = false;
        for (String language : accepted) {
            if (language == null || AsrLanguages.isAuto(language)) {
                return false;
            }
            for (String related : AsrLanguages.relatedCodes(language)) {
                anyAccepted = true;
                if (AsrLanguages.sameLanguage(related, detected)) {
                    return false;
                }
            }
        }
        return anyAccepted;
    }

    /** Whisper token ids for the given codes and their related codes. Empty when none exist in the model. */
    public static int[] whisperIds(boolean largeV3, String... languages) {
        ArrayList<Integer> ids = new ArrayList<>();
        if (languages != null) {
            for (String language : languages) {
                if (language == null) {
                    continue;
                }
                for (String related : AsrLanguages.relatedCodes(language)) {
                    int id = AsrLanguages.whisperToken(related, largeV3);
                    if (id >= 0 && !ids.contains(id)) {
                        ids.add(id);
                    }
                }
            }
        }
        int[] result = new int[ids.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = ids.get(i);
        }
        return result;
    }

    private static double probability(float[] logits, int index) {
        double max = logits[index];
        double sum = 0;
        for (float logit : logits) {
            sum += Math.exp(logit - max);
        }
        return 1.0 / sum;
    }
}
