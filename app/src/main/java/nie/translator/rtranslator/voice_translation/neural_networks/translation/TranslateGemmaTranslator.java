package nie.translator.rtranslator.voice_translation.neural_networks.translation;

import androidx.annotation.Nullable;

import java.io.File;

import nie.translator.rtranslator.Global;
import nie.translator.rtranslator.tools.CustomLocale;

/**
 * Prompt for google/translategemma-4b-it. The weights are a mradermacher GGUF.
 * The raw prompt is sent to the shared GGUF runtime. The file's chat template is not applied.
 */
public final class TranslateGemmaTranslator {
    private TranslateGemmaTranslator() {
    }

    public static String translate(Global global, String text, CustomLocale inputLanguage, CustomLocale outputLanguage, @Nullable TranslationContext context, @Nullable GgufTranslator.PartialListener partial) {
        File model = global.getGgufModelFile(Translator.TRANSLATEGEMMA);
        if (!model.isFile()) {
            throw new IllegalStateException("TranslateGemma file is missing");
        }
        // A voice sentence is waited for; a note would add up to ~200 output tokens to each one.
        String level = context == null && global.isGemmaThinkingEnabled() ? global.getGemmaThinkingLevel() : Global.GEMMA_THINKING_OFF;
        String[] prompt = context == null ? new String[]{prompt(text, inputLanguage, outputLanguage, level)} : voicePrompt(text, inputLanguage, outputLanguage, context);
        String output = GgufTranslator.generate(model.getAbsolutePath(), prompt, maxTokens(text, level, context), context != null && context.selfRating, context, partial);
        if (Global.GEMMA_THINKING_OFF.equals(level)) {
            return output;
        }
        return withoutNote(output);
    }

    /** Decodes the fixed start of the voice clause prompts for this pair ahead of the first clause. */
    public static void prepareVoice(Global global, CustomLocale inputLanguage, CustomLocale outputLanguage, TranslationContext context) {
        File model = global.getGgufModelFile(Translator.TRANSLATEGEMMA);
        if (model.isFile()) {
            GgufTranslator.prefill(model.getAbsolutePath(), voicePrompt("", inputLanguage, outputLanguage, context)[0]);
        }
    }

    /**
     * Gemma has no think tokens. It often wraps the note as {@code <note>} instead of
     * {@code <think>note</think>}, and GgufTranslator only strips the second form.
     */
    static String withoutNote(String output) {
        String out = output.trim();
        if (out.startsWith("<")) {
            int close = out.indexOf('>');
            if (close > 0 && close + 1 < out.length()) {
                out = out.substring(close + 1).trim();
            }
        }
        return out;
    }

    /** Answer room plus a short note. Off uses the answer room only. */
    private static int maxTokens(String text, String level, @Nullable TranslationContext context) {
        return GgufTranslator.voiceTokenLimit(text, context, 384) + thinkBudget(level);
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

    private static String prompt(String text, CustomLocale inputLanguage, CustomLocale outputLanguage, String level) {
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
        return "<bos><start_of_turn>user\n"
                + "You are a professional " + source + " to " + target + " translator. "
                + "Your goal is to accurately convey the meaning and nuances of the original " + sourceName + " text "
                + "while adhering to " + targetName + " grammar, vocabulary, and cultural sensitivities.\n"
                + instruction
                + "Please translate the following " + sourceName + " text into " + targetName + ":\n\n"
                + text
                + "<end_of_turn>\n<start_of_turn>model\n";
    }

    /** The text-mode prompt as the first turn; every later clause is a turn of its own, see {@link GgufTranslator#conversation}. */
    private static String[] voicePrompt(String text, CustomLocale inputLanguage, CustomLocale outputLanguage, TranslationContext context) {
        String source = GgufTranslator.languageLabel(inputLanguage);
        String target = GgufTranslator.languageLabel(outputLanguage);
        String sourceName = languageName(source);
        String targetName = languageName(target);
        StringBuilder instructions = new StringBuilder("<bos><start_of_turn>user\n")
                .append("You are a professional ").append(source).append(" to ").append(target).append(" translator. ")
                .append("Your goal is to accurately convey the meaning and nuances of the original ").append(sourceName).append(" text ")
                .append("while adhering to ").append(targetName).append(" grammar, vocabulary, and cultural sensitivities.\n")
                .append("Produce only the ").append(targetName).append(" translation, without any additional explanations or commentary. ");
        if (context.selfRating) {
            instructions.append("Then add the rating line described below. ")
                    .append("The text is an automatic speech transcript and may contain recognition errors. ")
                    .append("If a word is clearly misheard, translate the most likely intended word, but do not add content.\n")
                    .append("After the translation, write one last line with only [Q=3] if the transcript reads naturally and the translation is reliable, ")
                    .append("[Q=2] if some words may be misheard, or [Q=1] if the transcript looks garbled.\n");
        }
        instructions.append("The speech arrives one part at a time; translate each later message the same way.\n")
                .append("Please translate the following ").append(sourceName).append(" text into ").append(targetName).append(":\n\n");
        return GgufTranslator.conversation(instructions.toString(), "<start_of_turn>user\n",
                "<end_of_turn>\n<start_of_turn>model\n", "<end_of_turn>\n", context, text);
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
