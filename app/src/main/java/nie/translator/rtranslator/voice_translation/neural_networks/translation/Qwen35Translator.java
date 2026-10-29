package nie.translator.rtranslator.voice_translation.neural_networks.translation;

import androidx.annotation.Nullable;

import java.io.File;

import nie.translator.rtranslator.Global;
import nie.translator.rtranslator.tools.CustomLocale;

/**
 * Prompt for Qwen3.5-0.8B. The weights are an Unsloth GGUF.
 * An empty think block asks the model to skip its reasoning trace.
 */
public final class Qwen35Translator {
    private Qwen35Translator() {
    }

    public static String translate(Global global, String text, CustomLocale inputLanguage, CustomLocale outputLanguage, @Nullable TranslationContext context, @Nullable GgufTranslator.PartialListener partial) {
        File model = global.getGgufModelFile(Translator.QWEN35);
        if (!model.isFile()) {
            throw new IllegalStateException("Qwen3.5 file is missing");
        }
        String[] prompt = context == null ? new String[]{prompt(text, inputLanguage, outputLanguage)} : voicePrompt(text, inputLanguage, outputLanguage, context);
        return GgufTranslator.generate(model.getAbsolutePath(), prompt, GgufTranslator.voiceTokenLimit(text, context, 512), false, context, partial);
    }

    /** Decodes the fixed start of the voice clause prompts for this pair ahead of the first clause. */
    public static void prepareVoice(Global global, CustomLocale inputLanguage, CustomLocale outputLanguage, TranslationContext context) {
        File model = global.getGgufModelFile(Translator.QWEN35);
        if (model.isFile()) {
            GgufTranslator.prefill(model.getAbsolutePath(), voicePrompt("", inputLanguage, outputLanguage, context)[0]);
        }
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

    /**
     * The instructions go in a system turn and each clause is a user turn. The model does not rate
     * itself: asked for a [Q=n] line it rated nearly every clause 1 and often echoed the request.
     */
    private static String[] voicePrompt(String text, CustomLocale inputLanguage, CustomLocale outputLanguage, TranslationContext context) {
        String source = GgufTranslator.languageLabel(inputLanguage);
        String target = GgufTranslator.languageLabel(outputLanguage);
        String instructions = "<|im_start|>system\n"
                + "You are a professional " + source + " to " + target + " translator. "
                + "The user sends a speech one part at a time. Translate each part from " + languageName(source)
                + " into " + languageName(target) + ". Output only the translation.<|im_end|>\n<|im_start|>user\n";
        return GgufTranslator.conversation(instructions, "<|im_start|>user\n",
                "<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n", "<|im_end|>\n", context, text);
    }

    private static String languageName(String label) {
        int split = label.lastIndexOf(" (");
        if (split > 0) {
            return label.substring(0, split);
        }
        return label;
    }
}
