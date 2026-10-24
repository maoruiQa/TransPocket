package nie.translator.rtranslator.tools;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class AsrLanguageHintTest {
    private static final int FIRST = 50259;

    @Test
    public void keepsHintWhenItLeads() {
        float[] logits = scores(FIRST, 50260, 5f, 1f);
        assertEquals(50260, AsrLanguageHint.choose(logits, FIRST, 50260));
    }

    @Test
    public void keepsHintWhenTheOtherLanguageIsOnlySlightlyHigher() {
        float[] logits = scores(FIRST, 50260, 10f, 12f);
        assertEquals(50260, AsrLanguageHint.choose(logits, FIRST, 50260));
    }

    @Test
    public void switchesWhenAnotherLanguageIsClearlyHigher() {
        float[] logits = scores(FIRST, 50260, 1f, 10f);
        assertEquals(FIRST, AsrLanguageHint.choose(logits, FIRST, 50260));
    }

    @Test
    public void emptyLogitsKeepAUsableHint() {
        assertEquals(50260, AsrLanguageHint.choose(new float[0], FIRST, 50260));
    }

    @Test
    public void emptyLogitsWithoutAHintReturnUnknown() {
        assertEquals(-1, AsrLanguageHint.choose(null, FIRST, -1));
    }

    @Test
    public void hintOutsideTheModelUsesTheBestLogit() {
        float[] logits = new float[]{4f, 1f, 0f};
        assertEquals(FIRST, AsrLanguageHint.choose(logits, FIRST, 50358));
    }

    @Test
    public void baseCodeDropsRegion() {
        assertEquals("zh", AsrLanguageHint.baseCode("zh-CN"));
        assertEquals("zh", AsrLanguageHint.baseCode("zh_CN"));
        assertEquals("", AsrLanguageHint.baseCode(null));
    }

    @Test
    public void lockedSessionKeepsTheHint() {
        float[] logits = new float[]{20f, 0f, 0f};
        assertEquals(50261, AsrLanguageHint.choose(logits, FIRST, 50261, false, FIRST));
    }

    @Test
    public void unlockedSessionOnlySwitchesToTheTarget() {
        float[] logits = new float[]{20f, 0f, 0f};
        // English leads, but the session is set to German: keep German.
        assertEquals(50261, AsrLanguageHint.choose(logits, FIRST, 50261, true, 50263));
        // The target is English: the same logits switch.
        assertEquals(FIRST, AsrLanguageHint.choose(logits, FIRST, 50261, true, FIRST));
    }

    @Test
    public void targetStillNeedsTheSwitchMargin() {
        float[] logits = new float[]{3f, 0f, 0f};
        assertEquals(50261, AsrLanguageHint.choose(logits, FIRST, 50261, true, FIRST));
        logits[0] = 4f;
        assertEquals(FIRST, AsrLanguageHint.choose(logits, FIRST, 50261, true, FIRST));
    }

    private static float[] scores(int first, int hinted, float hintedScore, float otherScore) {
        float[] logits = new float[3];
        logits[hinted - first] = hintedScore;
        logits[0] = otherScore;
        return logits;
    }
}
