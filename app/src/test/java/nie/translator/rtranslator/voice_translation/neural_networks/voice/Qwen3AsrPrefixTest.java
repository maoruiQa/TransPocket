package nie.translator.rtranslator.voice_translation.neural_networks.voice;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class Qwen3AsrPrefixTest {
    private static final int[] BYTE_MAP = byteToUnicode();

    @Test
    public void tokenizeMatchesTheLongestVocabularyPiece() {
        Map<String, Integer> vocab = new HashMap<>();
        vocab.put("la", 1);
        vocab.put("language", 10);
        vocab.put(byteLevel(" "), 11);
        vocab.put(byteLevel(" ") + "Russian", 12);

        assertArrayEquals(new int[]{10, 12}, Qwen3AsrEngine.tokenize("language Russian", vocab, 20, BYTE_MAP));
    }

    @Test
    public void tokenizeRejectsUnknownText() {
        Map<String, Integer> vocab = new HashMap<>();
        vocab.put("a", 1);
        assertNull(Qwen3AsrEngine.tokenize("€", vocab, 20, BYTE_MAP));
    }

    @Test
    public void byteLevelKeepsNonAsciiText() {
        Map<String, Integer> vocab = new HashMap<>();
        vocab.put(byteLevel("Рус"), 1);
        vocab.put(byteLevel("ский"), 2);

        assertArrayEquals(new int[]{1, 2}, Qwen3AsrEngine.tokenize("Русский", vocab, 20, BYTE_MAP));
    }

    @Test
    public void prefixAddsOnlyAFewTokens() {
        Map<String, Integer> vocab = new HashMap<>();
        vocab.put("language", 10);
        vocab.put(byteLevel(" ") + "Russian", 11);
        int[] ids = Qwen3AsrEngine.tokenize("language Russian", vocab, 20, BYTE_MAP);
        assertEquals(2, ids.length);
    }

    private static String byteLevel(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        StringBuilder mapped = new StringBuilder(bytes.length);
        for (byte b : bytes) {
            mapped.append((char) BYTE_MAP[b & 0xff]);
        }
        return mapped.toString();
    }

    /** Same mapping as Qwen3AsrEngine.buildByteMap(). */
    private static int[] byteToUnicode() {
        boolean[] seen = new boolean[256];
        for (int b = '!'; b <= '~'; b++) {
            seen[b] = true;
        }
        for (int b = 0xA1; b <= 0xAC; b++) {
            seen[b] = true;
        }
        for (int b = 0xAE; b <= 0xFF; b++) {
            seen[b] = true;
        }
        int[] map = new int[256];
        int extra = 0;
        for (int b = 0; b < 256; b++) {
            if (seen[b]) {
                map[b] = b;
            } else {
                map[b] = 256 + extra;
                extra++;
            }
        }
        return map;
    }
}
