package nie.translator.rtranslator.tools;

import android.util.JsonReader;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Byte-level BPE used by the Qwen2 tokenizer shipped with Fun-CosyVoice 3.
 * Special tokens are not added.
 */
public final class Qwen2Tokenizer {
    private static final Pattern PATTERN = Pattern.compile("'s|'t|'re|'ve|'m|'ll|'d| ?\\p{L}+| ?\\p{N}+| ?[^\\s\\p{L}\\p{N}]+|\\s+(?!\\S)|\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    private final Map<String, Integer> vocab;
    private final Map<Pair, Integer> ranks;
    private final char[] byteToUnicode = new char[256];

    public Qwen2Tokenizer(Map<String, Integer> vocab, List<Pair> merges) {
        this.vocab = vocab;
        this.ranks = new HashMap<>();
        for (int i = 0; i < merges.size(); i++) {
            ranks.put(merges.get(i), i);
        }
        buildByteMap();
    }

    public static Qwen2Tokenizer load(File vocabFile, File mergeFile) throws IOException {
        Map<String, Integer> vocab = new HashMap<>();
        try (Reader reader = new InputStreamReader(new FileInputStream(vocabFile), StandardCharsets.UTF_8);
             JsonReader json = new JsonReader(reader)) {
            json.beginObject();
            while (json.hasNext()) {
                String key = json.nextName();
                vocab.put(key, json.nextInt());
            }
            json.endObject();
        }
        ArrayList<Pair> merges = new ArrayList<>();
        try (Reader reader = new InputStreamReader(new FileInputStream(mergeFile), StandardCharsets.UTF_8)) {
            StringBuilder line = new StringBuilder();
            boolean first = true;
            while (true) {
                int value = reader.read();
                if (value == -1 || value == '\n') {
                    if (line.length() > 0) {
                        String text = line.toString().trim();
                        if (!(first && text.startsWith("#")) && !text.isEmpty()) {
                            int space = text.indexOf(' ');
                            if (space > 0 && space < text.length() - 1) {
                                merges.add(new Pair(text.substring(0, space), text.substring(space + 1)));
                            }
                        }
                        first = false;
                    }
                    line.setLength(0);
                    if (value == -1) {
                        break;
                    }
                } else if (value != '\r') {
                    line.append((char) value);
                }
            }
        }
        return new Qwen2Tokenizer(vocab, merges);
    }

    public int[] encode(String text) {
        if (text == null || text.isEmpty()) {
            return new int[0];
        }
        ArrayList<Integer> ids = new ArrayList<>();
        Matcher matcher = PATTERN.matcher(text);
        while (matcher.find()) {
            String token = matcher.group();
            StringBuilder unicode = new StringBuilder(token.length() * 2);
            byte[] bytes = token.getBytes(StandardCharsets.UTF_8);
            for (byte value : bytes) {
                unicode.append(byteToUnicode[value & 0xff]);
            }
            for (String piece : bpe(unicode.toString())) {
                Integer id = vocab.get(piece);
                if (id != null) {
                    ids.add(id);
                }
            }
        }
        int[] encoded = new int[ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            encoded[i] = ids.get(i);
        }
        return encoded;
    }

    private List<String> bpe(String token) {
        ArrayList<String> word = new ArrayList<>();
        for (int i = 0; i < token.length(); ) {
            int point = token.codePointAt(i);
            word.add(new String(Character.toChars(point)));
            i += Character.charCount(point);
        }
        if (word.size() < 2) {
            return word;
        }
        while (word.size() >= 2) {
            int bestRank = Integer.MAX_VALUE;
            int bestIndex = -1;
            for (int i = 0; i < word.size() - 1; i++) {
                Integer rank = ranks.get(new Pair(word.get(i), word.get(i + 1)));
                if (rank != null && rank < bestRank) {
                    bestRank = rank;
                    bestIndex = i;
                }
            }
            if (bestIndex < 0) {
                break;
            }
            String merged = word.get(bestIndex) + word.get(bestIndex + 1);
            word.set(bestIndex, merged);
            word.remove(bestIndex + 1);
        }
        return word;
    }

    private void buildByteMap() {
        ArrayList<Integer> visible = new ArrayList<>();
        for (int value = '!'; value <= '~'; value++) {
            visible.add(value);
        }
        for (int value = 0xA1; value <= 0xAC; value++) {
            visible.add(value);
        }
        for (int value = 0xAE; value <= 0xFF; value++) {
            visible.add(value);
        }
        boolean[] used = new boolean[256];
        for (int value : visible) {
            used[value] = true;
            byteToUnicode[value] = (char) value;
        }
        int extra = 0;
        for (int value = 0; value < 256; value++) {
            if (!used[value]) {
                byteToUnicode[value] = (char) (256 + extra);
                extra++;
            }
        }
    }

    public static final class Pair {
        public final String left;
        public final String right;

        public Pair(String left, String right) {
            this.left = left;
            this.right = right;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Pair)) {
                return false;
            }
            Pair pair = (Pair) other;
            return left.equals(pair.left) && right.equals(pair.right);
        }

        @Override
        public int hashCode() {
            return 31 * left.hashCode() + right.hashCode();
        }
    }
}
