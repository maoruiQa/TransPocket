package nie.translator.rtranslator.voice_translation.neural_networks.translation;

import androidx.annotation.Nullable;

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

    public static String translate(Global global, String text, CustomLocale inputLanguage, CustomLocale outputLanguage, @Nullable TranslationContext context) {
        File model = new File(global.getFilesDir(), "Translation/TranslateGemma/translategemma-4b-it.Q4_K_M.gguf");
        if (!model.isFile()) {
            throw new IllegalStateException("TranslateGemma file is missing");
        }
        String level = global.isGemmaThinkingEnabled() ? global.getGemmaThinkingLevel() : Global.GEMMA_THINKING_OFF;
        return GgufTranslator.generate(model.getAbsolutePath(), prompt(text, inputLanguage, outputLanguage, level, context), maxTokens(level));
    }

    /** Answer room plus a short note. Off uses the answer room only. */
    private static int maxTokens(String level) {
        return 384 + thinkBudget(level);
    }

    private static int thinkBudget(String level) {
        if (Global.GEMMA_THINKING_HIGH.equals(level)) {
            return 192;
        }
        if (Global.GEMMA_THINKING_MEDIUM.equals(level)) {
            return 96;
        }
        if (Global.GEMMA_THINKING_LOW.equals(level)) {
            return 48;
        }
        return 0;
    }

    private static String prompt(String text, CustomLocale inputLanguage, CustomLocale outputLanguage, String level, @Nullable TranslationContext context) {
        String source = GgufTranslator.languageLabel(inputLanguage);
        String target = GgufTranslator.languageLabel(outputLanguage);
        String sourceName = languageName(source);
        String targetName = languageName(target);
        String instruction;
        if (Global.GEMMA_THINKING_OFF.equals(level)) {
            instruction = "Produce only the " + targetName + " translation, without any additional explanations or commentary. ";
        } else {
            instruction = "First write a reasoning note inside <think> and </think>, using at most "
                    + thinkWords(level) + " words. Then output only the " + targetName
                    + " translation, with no extra commentary. ";
        }
        if (context != null && context.selfRating) {
            instruction += "Then add the rating line described below. ";
        }
        return "<bos><start_of_turn>user\n"
                + "You are a professional " + source + " to " + target + " translator. "
                + "Your goal is to accurately convey the meaning and nuances of the original " + sourceName + " text "
                + "while adhering to " + targetName + " grammar, vocabulary, and cultural sensitivities.\n"
                + instruction
                + GgufTranslator.voiceNotes(context)
                + "Please translate the following " + sourceName + " text into " + targetName + ":\n\n"
                + text
                + "<end_of_turn>\n<start_of_turn>model\n";
    }

    private static int thinkWords(String level) {
        if (Global.GEMMA_THINKING_HIGH.equals(level)) {
            return 80;
        }
        if (Global.GEMMA_THINKING_MEDIUM.equals(level)) {
            return 40;
        }
        return 20;
    }

    private static String languageName(String label) {
        int split = label.lastIndexOf(" (");
        if (split > 0) {
            return label.substring(0, split);
        }
        return label;
    }
}
