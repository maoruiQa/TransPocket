package nie.translator.rtranslator.voice_translation.neural_networks.translation;

import java.io.File;

import nie.translator.rtranslator.Global;
import nie.translator.rtranslator.tools.CustomLocale;

/**
 * Prompt for google/translategemma-4b-it. The weights are the community Q4_K_M GGUF.
 * The raw prompt is sent to the shared GGUF runtime. The file's chat template is not applied.
 */
public final class TranslateGemmaTranslator {
    private TranslateGemmaTranslator() {
    }

    public static String translate(Global global, String text, CustomLocale inputLanguage, CustomLocale outputLanguage) {
        File model = new File(global.getFilesDir(), "Translation/TranslateGemma/translategemma-4b-it.Q4_K_M.gguf");
        if (!model.isFile()) {
            throw new IllegalStateException("TranslateGemma file is missing");
        }
        return GgufTranslator.generate(model.getAbsolutePath(), prompt(text, inputLanguage, outputLanguage), 512);
    }

    private static String prompt(String text, CustomLocale inputLanguage, CustomLocale outputLanguage) {
        String source = GgufTranslator.languageLabel(inputLanguage);
        String target = GgufTranslator.languageLabel(outputLanguage);
        String sourceName = languageName(source);
        String targetName = languageName(target);
        return "<bos><start_of_turn>user\n"
                + "You are a professional " + source + " to " + target + " translator. "
                + "Your goal is to accurately convey the meaning and nuances of the original " + sourceName + " text "
                + "while adhering to " + targetName + " grammar, vocabulary, and cultural sensitivities.\n"
                + "Produce only the " + targetName + " translation, without any additional explanations or commentary. "
                + "Please translate the following " + sourceName + " text into " + targetName + ":\n\n"
                + text
                + "<end_of_turn>\n<start_of_turn>model\n";
    }

    private static String languageName(String label) {
        int split = label.lastIndexOf(" (");
        if (split > 0) {
            return label.substring(0, split);
        }
        return label;
    }
}
