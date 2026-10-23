package nie.translator.rtranslator.voice_translation._live_translation._simultaneous;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SimultaneousAutoSpeakTest {
    @Test
    public void onSpeaksWithOrWithoutHeadphones() {
        assertFalse(SimultaneousAutoSpeak.isMuted(SimultaneousAutoSpeak.ON, false));
        assertFalse(SimultaneousAutoSpeak.isMuted(SimultaneousAutoSpeak.ON, true));
    }

    @Test
    public void offStaysSilentWithOrWithoutHeadphones() {
        assertTrue(SimultaneousAutoSpeak.isMuted(SimultaneousAutoSpeak.OFF, false));
        assertTrue(SimultaneousAutoSpeak.isMuted(SimultaneousAutoSpeak.OFF, true));
    }

    @Test
    public void autoFollowsThePlaybackDevice() {
        assertTrue(SimultaneousAutoSpeak.isMuted(SimultaneousAutoSpeak.AUTO, false));
        assertFalse(SimultaneousAutoSpeak.isMuted(SimultaneousAutoSpeak.AUTO, true));
    }

    @Test
    public void unknownModeFollowsThePlaybackDevice() {
        assertEquals(SimultaneousAutoSpeak.AUTO, SimultaneousAutoSpeak.normalize(null));
        assertEquals(SimultaneousAutoSpeak.AUTO, SimultaneousAutoSpeak.normalize(""));
        assertTrue(SimultaneousAutoSpeak.isMuted("remembered", false));
        assertFalse(SimultaneousAutoSpeak.isMuted(null, true));
    }
}
