package nie.translator.rtranslator.tools;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AsrLanguageFilterTest {
    private static final int FIRST = AsrLanguages.WHISPER_FIRST_LANGUAGE_ID;
    private static final int EN = FIRST;
    private static final int ZH = FIRST + 1;
    private static final int JA = FIRST + 7;

    @Test
    public void keepsClauseWhenAnAcceptedLanguageLeads() {
        float[] logits = logits(EN, 9f, JA, 2f);
        assertEquals(-1, AsrLanguageFilter.foreignLanguage(logits, FIRST, new int[]{EN}));
    }

    @Test
    public void rejectsConfidentForeignLanguage() {
        float[] logits = logits(JA, 12f, EN, 2f);
        assertEquals(JA, AsrLanguageFilter.foreignLanguage(logits, FIRST, new int[]{EN, ZH}));
    }

    @Test
    public void keepsClauseWhenTheGapIsSmall() {
        float[] logits = logits(JA, 6f, ZH, 4f);
        assertEquals(-1, AsrLanguageFilter.foreignLanguage(logits, FIRST, new int[]{ZH}));
    }

    @Test
    public void keepsClauseWhenTheTopLanguageIsNotConfident() {
        // Ja leads every accepted language by far, but many other languages are close to it.
        float[] logits = new float[20];
        for (int i = 0; i < logits.length; i++) {
            logits[i] = 9f;
        }
        logits[JA - FIRST] = 10f;
        logits[EN - FIRST] = 0f;
        assertEquals(-1, AsrLanguageFilter.foreignLanguage(logits, FIRST, new int[]{EN}));
    }

    @Test
    public void keepsClauseWithoutAcceptedTokens() {
        float[] logits = logits(JA, 12f, EN, 2f);
        assertEquals(-1, AsrLanguageFilter.foreignLanguage(logits, FIRST, new int[0]));
        assertEquals(-1, AsrLanguageFilter.foreignLanguage(logits, FIRST, new int[]{FIRST + 500}));
        assertEquals(-1, AsrLanguageFilter.foreignLanguage(null, FIRST, new int[]{EN}));
    }

    @Test
    public void relatedLanguagesAreNotForeign() {
        assertFalse(AsrLanguageFilter.isForeign("yue", "zh"));
        assertFalse(AsrLanguageFilter.isForeign("id", "ms"));
        assertFalse(AsrLanguageFilter.isForeign("zh", "en", "zh-CN"));
        assertTrue(AsrLanguageFilter.isForeign("ja", "zh", "en"));
    }

    @Test
    public void unknownOrAutoIsNeverForeign() {
        assertFalse(AsrLanguageFilter.isForeign("", "en"));
        assertFalse(AsrLanguageFilter.isForeign("klingon", "en"));
        assertFalse(AsrLanguageFilter.isForeign("ja", AsrLanguages.AUTO));
    }

    @Test
    public void whisperIdsIncludeRelatedLanguages() {
        int[] ids = AsrLanguageFilter.whisperIds(true, "zh-CN");
        assertArrayEquals(new int[]{ZH, AsrLanguages.whisperToken("yue", true)}, ids);
        // Whisper Small maps yue onto the zh token, which must appear once.
        assertArrayEquals(new int[]{ZH}, AsrLanguageFilter.whisperIds(false, "zh"));
        assertEquals(0, AsrLanguageFilter.whisperIds(false, AsrLanguages.AUTO).length);
    }

    private static float[] logits(int topId, float top, int otherId, float other) {
        float[] logits = new float[100];
        logits[topId - FIRST] = top;
        logits[otherId - FIRST] = other;
        return logits;
    }
}
