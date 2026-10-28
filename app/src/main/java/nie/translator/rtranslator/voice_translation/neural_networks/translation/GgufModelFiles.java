package nie.translator.rtranslator.voice_translation.neural_networks.translation;

/**
 * GGUF files of the llama.cpp translation models, one per quantization level.
 *
 * Each list starts with the level that runs fastest on a phone CPU. Q4_0 and IQ4_NL are repacked
 * by llama.cpp into the dotprod/i8mm layouts at load time and Q4_K is repacked too. IQ4_XS is the
 * smallest 4-bit file but it is not repacked, so prompt reading is slower even though decoding
 * (memory bound) is about the same. Q8_0 is listed last for quality.
 */
public final class GgufModelFiles {
    private static final String QWEN35_URL = "https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/main/";
    private static final String INDEX_URL = "https://huggingface.co/IndexTeam/Index-Translate-2B-GGUF/resolve/main/";
    private static final String GEMMA_URL = "https://huggingface.co/mradermacher/translategemma-4b-it-GGUF/resolve/main/";
    private static final String GEMMA_I1_URL = "https://huggingface.co/mradermacher/translategemma-4b-it-i1-GGUF/resolve/main/";

    private static final String[] QWEN35_QUANTS = {"Q4_0", "IQ4_NL", "IQ4_XS", "Q4_K_M", "Q8_0"};
    private static final long[] QWEN35_BYTES = {507154688L, 506859776L, 492605696L, 532517120L, 811843840L};
    private static final String[] INDEX_QUANTS = {"Q4_K_S", "IQ4_XS", "Q4_K_M", "Q5_K_M", "Q8_0"};
    private static final long[] INDEX_BYTES = {1246308864L, 1234377216L, 1312164352L, 1454787072L, 2076674560L};
    // Q4_0, IQ4_NL and IQ4_XS come from the imatrix repository. Q4_K_M is the static file older versions downloaded.
    private static final String[] GEMMA_QUANTS = {"Q4_0", "IQ4_NL", "IQ4_XS", "Q4_K_M"};
    private static final long[] GEMMA_BYTES = {2370081568L, 2363527968L, 2263257888L, 2489909760L};

    private GgufModelFiles() {
    }

    public static String[] quants(int mode) {
        switch (mode) {
            case Translator.QWEN35:
                return QWEN35_QUANTS.clone();
            case Translator.INDEX_TRANSLATE:
                return INDEX_QUANTS.clone();
            default:
                return GEMMA_QUANTS.clone();
        }
    }

    /** Folder below the app files directory. */
    public static String folder(int mode) {
        switch (mode) {
            case Translator.QWEN35:
                return "Translation/Qwen35";
            case Translator.INDEX_TRANSLATE:
                return "Translation/IndexTranslate";
            default:
                return "Translation/TranslateGemma";
        }
    }

    public static String fileName(int mode, String quant) {
        switch (mode) {
            case Translator.QWEN35:
                return "Qwen3.5-0.8B-" + quant + ".gguf";
            case Translator.INDEX_TRANSLATE:
                return "Index-Translate-2B." + quant + ".gguf";
            default:
                if ("Q4_K_M".equals(quant)) {
                    return "translategemma-4b-it.Q4_K_M.gguf";
                }
                return "translategemma-4b-it.i1-" + quant + ".gguf";
        }
    }

    public static String url(int mode, String quant) {
        switch (mode) {
            case Translator.QWEN35:
                return QWEN35_URL + fileName(mode, quant);
            case Translator.INDEX_TRANSLATE:
                return INDEX_URL + fileName(mode, quant);
            default:
                if ("Q4_K_M".equals(quant)) {
                    return GEMMA_URL + fileName(mode, quant);
                }
                return GEMMA_I1_URL + fileName(mode, quant);
        }
    }

    public static long bytes(int mode, String quant) {
        String[] quants = quants(mode);
        long[] sizes;
        switch (mode) {
            case Translator.QWEN35:
                sizes = QWEN35_BYTES;
                break;
            case Translator.INDEX_TRANSLATE:
                sizes = INDEX_BYTES;
                break;
            default:
                sizes = GEMMA_BYTES;
                break;
        }
        for (int i = 0; i < quants.length; i++) {
            if (quants[i].equals(quant)) {
                return sizes[i];
            }
        }
        return sizes[0];
    }

    public static boolean isQuant(int mode, String quant) {
        for (String choice : quants(mode)) {
            if (choice.equals(quant)) {
                return true;
            }
        }
        return false;
    }
}
