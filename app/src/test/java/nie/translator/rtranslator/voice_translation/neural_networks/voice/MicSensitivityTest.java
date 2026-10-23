package nie.translator.rtranslator.voice_translation.neural_networks.voice;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class MicSensitivityTest {
    @Test
    public void historicalSliderKeepsItsGates() {
        assertEquals(15000, MicSensitivity.amplitudeThreshold(0));
        assertEquals(8250, MicSensitivity.amplitudeThreshold(25));
        assertEquals(1500, MicSensitivity.amplitudeThreshold(50));
        assertEquals(950, MicSensitivity.amplitudeThreshold(75));
        assertEquals(400, MicSensitivity.amplitudeThreshold(MicSensitivity.DEFAULT));
    }

    @Test
    public void extendedRangeHalvesTheGateEveryHundredPoints() {
        assertEquals(397, MicSensitivity.amplitudeThreshold(101));
        assertEquals(283, MicSensitivity.amplitudeThreshold(150));
        assertEquals(200, MicSensitivity.amplitudeThreshold(200));
        assertEquals(100, MicSensitivity.amplitudeThreshold(300));
        assertEquals(50, MicSensitivity.amplitudeThreshold(MicSensitivity.EXTENDED_MAX));
    }

    @Test
    public void valuesOutsideTheSliderUseTheEnds() {
        assertEquals(MicSensitivity.amplitudeThreshold(0), MicSensitivity.amplitudeThreshold(-10));
        assertEquals(MicSensitivity.amplitudeThreshold(MicSensitivity.EXTENDED_MAX), MicSensitivity.amplitudeThreshold(900));
        assertEquals(0, MicSensitivity.clamp(-10, false));
        assertEquals(MicSensitivity.STANDARD_MAX, MicSensitivity.clamp(150, false));
        assertEquals(150, MicSensitivity.clamp(150, true));
        assertEquals(MicSensitivity.EXTENDED_MAX, MicSensitivity.clamp(900, true));
    }
}
