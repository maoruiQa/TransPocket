package nie.translator.rtranslator.voice_translation.neural_networks.voice;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class Qwen3AsrPromptTest {
    @Test
    public void promptIncludesTheEmptySystemTurn() {
        long[] prompt = Qwen3AsrEngine.buildPrompt(2, 8948, 872, 198, 77091);
        assertEquals(17, prompt.length);
        assertEquals(151644L, prompt[0]);
        assertEquals(8948L, prompt[1]);
        assertEquals(198L, prompt[2]);
        assertEquals(151645L, prompt[3]);
        assertEquals(198L, prompt[4]);
        assertEquals(151644L, prompt[5]);
        assertEquals(872L, prompt[6]);
        assertEquals(198L, prompt[7]);
        assertEquals(151669L, prompt[8]);
        assertEquals(151676L, prompt[9]);
        assertEquals(151676L, prompt[10]);
        assertEquals(151670L, prompt[11]);
        assertEquals(151645L, prompt[12]);
        assertEquals(198L, prompt[13]);
        assertEquals(151644L, prompt[14]);
        assertEquals(77091L, prompt[15]);
        assertEquals(198L, prompt[16]);
    }
}
