package nie.translator.rtranslator.tools;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AsrLanguagesTest {
    @Test
    public void whisperLargeLanguageIdsFollowTheLargeVocabulary() {
        assertEquals(50259, AsrLanguages.whisperToken("en", true));
        assertEquals(50263, AsrLanguages.whisperToken("ru", true));
        assertEquals(50358, AsrLanguages.whisperToken("yue", true));
    }

    @Test
    public void whisperSmallHasNoCantoneseToken() {
        assertEquals(50260, AsrLanguages.whisperToken("yue", false));
        assertEquals(50263, AsrLanguages.whisperToken("ru", false));
        assertEquals("", AsrLanguages.whisperCode(50358, false));
        assertEquals("yue", AsrLanguages.whisperCode(50358, true));
    }

    @Test
    public void unknownLanguageHasNoToken() {
        assertEquals(-1, AsrLanguages.whisperToken("xx", true));
        assertEquals(-1, AsrLanguages.whisperToken(null, false));
        assertFalse(AsrLanguages.isWhisperLanguage("xx", true));
    }

    @Test
    public void aliasesResolveToTheWhisperCode() {
        assertEquals(AsrLanguages.whisperToken("tl", true), AsrLanguages.whisperToken("fil", true));
        assertEquals(AsrLanguages.whisperToken("he", true), AsrLanguages.whisperToken("iw", true));
        assertEquals("zh", AsrLanguages.canonicalCode("zh_CN"));
        assertEquals("no", AsrLanguages.canonicalCode("nb"));
        assertEquals("jw", AsrLanguages.canonicalCode("jv"));
    }

    @Test
    public void qwenNamesCoverThePickerLanguages() {
        assertEquals("Russian", AsrLanguages.qwenName("ru"));
        assertEquals("Ukrainian", AsrLanguages.qwenName("uk"));
        assertEquals("Bulgarian", AsrLanguages.qwenName("bg"));
        assertEquals("Filipino", AsrLanguages.qwenName("tl"));
        assertEquals("Cantonese", AsrLanguages.qwenName("yue"));
        assertEquals(null, AsrLanguages.qwenName("xx"));
    }

    @Test
    public void qwenNamesMapBackToCodes() {
        assertEquals("ru", AsrLanguages.qwenCode("Russian"));
        assertEquals("uk", AsrLanguages.qwenCode("ukrainian"));
        assertEquals("tl", AsrLanguages.qwenCode("Filipino"));
        assertEquals("zh", AsrLanguages.qwenCode("Mandarin"));
        assertEquals("", AsrLanguages.qwenCode("Klingon"));
    }

    @Test
    public void sameLanguageUsesAliasesAndIgnoresRegion() {
        assertTrue(AsrLanguages.sameLanguage("tl", "fil"));
        assertTrue(AsrLanguages.sameLanguage("zh-CN", "zh"));
        assertTrue(AsrLanguages.sameLanguage("he", "iw"));
        assertTrue(AsrLanguages.sameLanguage("no", "nb"));
        assertFalse(AsrLanguages.sameLanguage("ru", "uk"));
        assertFalse(AsrLanguages.sameLanguage(null, "ru"));
    }
}
