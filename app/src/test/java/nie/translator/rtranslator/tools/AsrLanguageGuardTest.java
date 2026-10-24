package nie.translator.rtranslator.tools;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class AsrLanguageGuardTest {
    private static final String RUSSIAN = "Это очень длинное предложение на русском языке";
    private static final String UKRAINIAN = "Це дуже важлива ініціатива, дякую за підтримку";
    private static final String ENGLISH = "This is a long enough sentence about language detection";
    private static final String CHINESE = "这是一个用于测试语言判断的句子";

    @Test
    public void russianTextMatchesRussian() {
        assertEquals(AsrLanguageGuard.Verdict.MATCH, AsrLanguageGuard.check(RUSSIAN, "ru"));
    }

    @Test
    public void englishTextConflictsWithRussian() {
        AsrLanguageGuard.Result result = AsrLanguageGuard.analyze(ENGLISH, "ru");
        assertEquals(AsrLanguageGuard.Verdict.CONFLICT, result.verdict);
        assertEquals("script", result.reason);
    }

    @Test
    public void ukrainianLettersConflictWithRussian() {
        AsrLanguageGuard.Result result = AsrLanguageGuard.analyze(UKRAINIAN, "ru");
        assertEquals(AsrLanguageGuard.Verdict.CONFLICT, result.verdict);
        assertEquals("uk-letters", result.reason);
    }

    @Test
    public void russianLettersConflictWithUkrainian() {
        String text = "Это быстро, спасибо за поддержку";
        AsrLanguageGuard.Result result = AsrLanguageGuard.analyze(text, "uk");
        assertEquals(AsrLanguageGuard.Verdict.CONFLICT, result.verdict);
        assertEquals("ru-letters", result.reason);
    }

    @Test
    public void latinLanguagesAreNotSeparatedByScript() {
        assertEquals(AsrLanguageGuard.Verdict.MATCH, AsrLanguageGuard.check(ENGLISH, "en"));
        assertEquals(AsrLanguageGuard.Verdict.MATCH,
                AsrLanguageGuard.check("Esta es una frase larga escrita en espanol", "en"));
    }

    @Test
    public void shortTextIsWeak() {
        assertEquals(AsrLanguageGuard.Verdict.WEAK, AsrLanguageGuard.check("Да", "ru"));
        assertEquals("short", AsrLanguageGuard.reason("Да", "ru"));
    }

    @Test
    public void mixedTextIsNotAConflict() {
        assertNotEquals(AsrLanguageGuard.Verdict.CONFLICT,
                AsrLanguageGuard.check("Это очень длинное предложение про smartphone и email", "ru"));
    }

    @Test
    public void hanTextMatchesChineseAndConflictsWithEnglish() {
        assertEquals(AsrLanguageGuard.Verdict.MATCH, AsrLanguageGuard.check(CHINESE, "zh"));
        assertEquals(AsrLanguageGuard.Verdict.CONFLICT, AsrLanguageGuard.check(CHINESE, "en"));
    }

    @Test
    public void japaneseAndKoreanUseTheirOwnScripts() {
        assertEquals(AsrLanguageGuard.Verdict.MATCH,
                AsrLanguageGuard.check("これはテストのための文章です", "ja"));
        assertEquals(AsrLanguageGuard.Verdict.MATCH,
                AsrLanguageGuard.check("이것은 테스트를 위한 문장입니다", "ko"));
    }

    @Test
    public void emptyOrUnknownInputIsWeak() {
        assertEquals(AsrLanguageGuard.Verdict.WEAK, AsrLanguageGuard.check("", "ru"));
        assertEquals(AsrLanguageGuard.Verdict.WEAK, AsrLanguageGuard.check(RUSSIAN, "xx"));
    }
}
