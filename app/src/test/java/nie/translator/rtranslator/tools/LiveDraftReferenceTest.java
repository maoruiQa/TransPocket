package nie.translator.rtranslator.tools;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class LiveDraftReferenceTest {
    @Test
    public void durationNotesMatchTheSpokenRange() {
        assertEquals(LiveDraftReference.DurationNote.UNREADABLE, LiveDraftReference.duration(0));
        assertEquals(LiveDraftReference.DurationNote.SHORT, LiveDraftReference.duration(2999));
        assertEquals(LiveDraftReference.DurationNote.OK, LiveDraftReference.duration(3000));
        assertEquals(LiveDraftReference.DurationNote.OK, LiveDraftReference.duration(10000));
        assertEquals(LiveDraftReference.DurationNote.LONG, LiveDraftReference.duration(10001));
    }

    @Test
    public void longReferenceKeepsFifteenSeconds() {
        assertEquals(48000 * 15, LiveDraftReference.keptSamples(48000, 48000 * 30));
        assertEquals(1000, LiveDraftReference.keptSamples(16000, 1000));
    }

    @Test
    public void referencePrefersUploadThenBuiltinThenPackaged() {
        assertEquals(LiveDraftReference.Kind.UPLOAD, LiveDraftReference.choose(true, true, true, true));
        assertEquals(LiveDraftReference.Kind.BUILTIN, LiveDraftReference.choose(false, true, true, true));
        assertEquals(LiveDraftReference.Kind.PACKAGED, LiveDraftReference.choose(false, false, true, true));
        assertEquals(LiveDraftReference.Kind.GENERATED, LiveDraftReference.choose(false, false, false, true));
        assertEquals(LiveDraftReference.Kind.CROSS_LINGUAL, LiveDraftReference.choose(false, false, false, false));
    }

    @Test
    public void cosyVoiceNameFollowsLanguage() {
        assertEquals("fleurs-zh", CosyVoiceQuant.voiceName("zh", "f"));
        assertEquals("zero_shot", CosyVoiceQuant.voiceName("zh", "m"));
        assertEquals("fleurs-ja", CosyVoiceQuant.voiceName("ja", "f"));
        assertEquals("zero_shot", CosyVoiceQuant.voiceName("ja", "m"));
        assertEquals("fleurs-en", CosyVoiceQuant.voiceName("it", "f"));
        assertEquals("zero_shot", CosyVoiceQuant.voiceName("it", "m"));
    }
}
