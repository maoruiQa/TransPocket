package nie.translator.rtranslator.voice_translation.neural_networks.voice;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SileroVadWindowTest {
    @Test
    public void firstFrameStartsWithZeroContext() {
        SileroVadWindow window = new SileroVadWindow();
        float[] frame = ramp(1f);
        float[] input = window.assemble(frame);
        assertEquals(SileroVadWindow.MODEL_SAMPLES, input.length);
        for (int i = 0; i < SileroVadWindow.CONTEXT_SAMPLES; i++) {
            assertEquals(0f, input[i], 0f);
        }
        assertEquals(frame[0], input[SileroVadWindow.CONTEXT_SAMPLES], 0f);
        assertEquals(frame[frame.length - 1], input[input.length - 1], 0f);
    }

    @Test
    public void nextFrameKeepsThePreviousTail() {
        SileroVadWindow window = new SileroVadWindow();
        float[] first = ramp(1f);
        window.advance(first);
        float[] second = ramp(1000f);
        float[] input = window.assemble(second);
        for (int i = 0; i < SileroVadWindow.CONTEXT_SAMPLES; i++) {
            assertEquals(first[first.length - SileroVadWindow.CONTEXT_SAMPLES + i], input[i], 0f);
        }
        assertEquals(second[0], input[SileroVadWindow.CONTEXT_SAMPLES], 0f);
    }

    @Test
    public void resetClearsContext() {
        SileroVadWindow window = new SileroVadWindow();
        float[] first = ramp(1f);
        window.advance(first);
        window.reset();
        float[] input = window.assemble(ramp(1000f));
        for (int i = 0; i < SileroVadWindow.CONTEXT_SAMPLES; i++) {
            assertEquals(0f, input[i], 0f);
        }
    }

    private static float[] ramp(float start) {
        float[] frame = new float[ClauseVadSegmenter.FRAME_SAMPLES];
        for (int i = 0; i < frame.length; i++) {
            frame[i] = start + i;
        }
        return frame;
    }
}
