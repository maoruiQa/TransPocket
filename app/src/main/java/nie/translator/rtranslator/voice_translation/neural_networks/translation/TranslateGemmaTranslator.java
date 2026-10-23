package nie.translator.rtranslator.voice_translation.neural_networks.translation;

import java.io.File;

import nie.translator.rtranslator.Global;
import nie.translator.rtranslator.tools.CustomLocale;

/**
 * Prompt for google/translategemma-4b-it. The weights are the community Q4_K_M GGUF.
 * Generation goes through the native llama.cpp bridge when that library is loaded.
 */
public final class TranslateGemmaTranslator {
    private static boolean libraryLoaded;
    private static boolean libraryFailed;

    private TranslateGemmaTranslator() {
    }

    public static String translate(Global global, String text, CustomLocale inputLanguage, CustomLocale outputLanguage) throws Exception {
        File model = new File(global.getFilesDir(), "Translation/TranslateGemma/translategemma-4b-it.Q4_K_M.gguf");
        if (!model.isFile()) {
            throw new IllegalStateException("TranslateGemma file is missing");
        }
        String source = inputLanguage.getDisplayNameWithoutTTS();
        String target = outputLanguage.getDisplayNameWithoutTTS();
        String prompt = "<start_of_turn>user\n"
                + "You are a professional " + source + " to " + target + " translator. "
                + "Translate the following " + source + " text into " + target + ". "
                + "Output only the translation.\n"
                + text
                + "<end_of_turn>\n<start_of_turn>model\n";
        ensureLibrary();
        String generated = nativeTranslate(model.getAbsolutePath(), prompt, 512);
        if (generated == null) {
            throw new IllegalStateException("TranslateGemma returned no text");
        }
        return generated.trim();
    }

    private static void ensureLibrary() {
        if (libraryLoaded || libraryFailed) {
            if (libraryFailed) {
                throw new IllegalStateException("TranslateGemma native runtime is not available");
            }
            return;
        }
        try {
            System.loadLibrary("gguf_translator");
            libraryLoaded = true;
        } catch (UnsatisfiedLinkError error) {
            libraryFailed = true;
            throw new IllegalStateException("TranslateGemma native runtime is not available", error);
        }
    }

    private static native String nativeTranslate(String modelPath, String prompt, int maxTokens);
}
