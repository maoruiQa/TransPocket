package nie.translator.rtranslator.tools;

import java.util.List;

/**
 * Cuts a decoder tail that repeats one token or a short phrase.
 * The kept length stops where that repeated block starts.
 */
public final class AsrRepetition {
    private static final int MAX_BLOCK = 8;
    private static final int MIN_COPIES = 3;

    private AsrRepetition() {
    }

    public static int keepCount(int[] tokens) {
        if (tokens == null || tokens.length == 0) {
            return 0;
        }
        return keepCount(tokens.length, tokens, null);
    }

    public static int keepCount(List<Integer> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            return 0;
        }
        return keepCount(tokens.size(), null, tokens);
    }

    private static int keepCount(int n, int[] array, List<Integer> list) {
        if (n < MIN_COPIES) {
            return n;
        }
        int earliest = n;
        int maxBlock = Math.min(MAX_BLOCK, n / MIN_COPIES);
        for (int block = 1; block <= maxBlock; block++) {
            int start = n - block;
            int copies = 1;
            while (start - block >= 0 && same(start - block, start, block, array, list)) {
                start -= block;
                copies++;
            }
            if (copies >= MIN_COPIES && start < earliest) {
                earliest = start;
            }
        }
        return earliest;
    }

    private static boolean same(int left, int right, int block, int[] array, List<Integer> list) {
        for (int i = 0; i < block; i++) {
            if (tokenAt(left + i, array, list) != tokenAt(right + i, array, list)) {
                return false;
            }
        }
        return true;
    }

    private static int tokenAt(int index, int[] array, List<Integer> list) {
        if (array != null) {
            return array[index];
        }
        Integer value = list.get(index);
        return value == null ? 0 : value;
    }
}
