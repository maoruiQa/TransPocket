package nie.translator.rtranslator.voice_translation.neural_networks.translation;

import java.io.File;

import nie.translator.rtranslator.Global;
import nie.translator.rtranslator.tools.CustomLocale;

/**
 * Prompt for Qwen3.5-0.8B. The weights are the Unsloth Q4_K_M GGUF.
 * An empty think block asks the model to skip its reasoning trace.
 */
public final class Qwen35Translator {
    private Qwen35Translator() {
    }

    public static String translate(Global global, String text, CustomLocale inputLanguage, CustomLocale outputLanguage) {
        File model = new File(global.getFilesDir(), "Translation/Qwen35/Qwen3.5-0.8B-Q4_K_M.gguf");
        if (!model.isFile()) {
            throw new IllegalStateException("Qwen3.5 file is missing");
        }
        return GgufTranslator.generate(model.getAbsolutePath(), prompt(text, inputLanguage, outputLanguage), 512);
    }

    private static String prompt(String text, CustomLocale inputLanguage, CustomLocale outputLanguage) {
        String source = GgufTranslator.languageLabel(inputLanguage);
        String target = GgufTranslator.languageLabel(outputLanguage);
        String sourceName = languageName(source);
        String targetName = languageName(target);
        return "<|im_start|>user\n"
                + "You are a professional " + source + " to " + target + " translator. "
                + "Translate the following " + sourceName + " text into " + targetName + ". "
                + "Output only the translation.\n"
                + text
                + "<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n";
    }

    private static String languageName(String label) {
        int split = label.lastIndexOf(" (");
        if (split > 0) {
            return label.substring(0, split);
        }
        return label;
    }
}
