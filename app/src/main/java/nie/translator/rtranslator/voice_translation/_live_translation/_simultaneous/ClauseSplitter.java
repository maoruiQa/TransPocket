package nie.translator.rtranslator.voice_translation._live_translation._simultaneous;

import java.util.ArrayList;

/**
 * Splits a finished recognition into clauses that can be translated while the
 * microphone keeps listening. Sentence punctuation always breaks. A comma breaks
 * only after the clause is already long enough to speak on its own.
 */
final class ClauseSplitter {
    private static final int CJK_COMMA_CHARS = 18;
    private static final int LATIN_COMMA_CHARS = 80;

    private ClauseSplitter() {
    }

    static ArrayList<String> split(String text) {
        ArrayList<String> clauses = new ArrayList<>();
        if (text == null) {
            return clauses;
        }
        StringBuilder current = new StringBuilder();
        int length = text.length();
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            current.append(c);
            boolean sentenceEnd = c == '。' || c == '！' || c == '？' || c == '；' || c == '!' || c == '?' || c == ';' || c == '\n';
            if (c == '.' && !isDecimalPoint(text, i)) {
                sentenceEnd = true;
            }
            boolean longComma = (c == '，' || c == '、' || c == ',') && current.length() >= (isMostlyCjk(current) ? CJK_COMMA_CHARS : LATIN_COMMA_CHARS);
            if (sentenceEnd || longComma) {
                push(clauses, current);
            }
        }
        push(clauses, current);
        return clauses;
    }

    private static void push(ArrayList<String> clauses, StringBuilder current) {
        String clause = current.toString().trim();
        current.setLength(0);
        if (clause.length() >= 2) {
            clauses.add(clause);
        }
    }

    private static boolean isDecimalPoint(String text, int index) {
        return index > 0 && index + 1 < text.length()
                && Character.isDigit(text.charAt(index - 1))
                && Character.isDigit(text.charAt(index + 1));
    }

    private static boolean isMostlyCjk(CharSequence text) {
        int cjk = 0;
        int letters = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                letters++;
                Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
                if (block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                        || block == Character.UnicodeBlock.HIRAGANA
                        || block == Character.UnicodeBlock.KATAKANA
                        || block == Character.UnicodeBlock.HANGUL_SYLLABLES) {
                    cjk++;
                }
            }
        }
        return cjk > 0 && cjk * 2 >= letters;
    }
}
