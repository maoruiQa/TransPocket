package nie.translator.rtranslator.voice_translation.neural_networks.translation;

import androidx.annotation.Nullable;

import java.io.File;
import java.util.Locale;

import nie.translator.rtranslator.Global;
import nie.translator.rtranslator.tools.CustomLocale;

/**
 * Prompt for IndexTeam/Index-Translate-2B, a Qwen3.5 fine-tune. The model was trained on the
 * Chinese instructions of its model card, with Chinese language names, and with thinking off.
 * Earlier clauses and the rating request go in the instTrans constraint block.
 */
public final class IndexTranslateTranslator {
    private IndexTranslateTranslator() {
    }

    public static String translate(Global global, String text, CustomLocale inputLanguage, CustomLocale outputLanguage, @Nullable TranslationContext context, @Nullable GgufTranslator.PartialListener partial) {
        File model = global.getGgufModelFile(Translator.INDEX_TRANSLATE);
        if (!model.isFile()) {
            throw new IllegalStateException("Index-Translate file is missing");
        }
        String[] prompt = context == null ? new String[]{instruction(inputLanguage, outputLanguage) + text + ANSWER_OPEN} : voicePrompt(text, inputLanguage, outputLanguage, context);
        return GgufTranslator.generate(model.getAbsolutePath(), prompt, GgufTranslator.voiceTokenLimit(text, context, 512), false, context, partial);
    }

    /** Decodes the fixed start of the voice clause prompts for this pair ahead of the first clause. */
    public static void prepareVoice(Global global, CustomLocale inputLanguage, CustomLocale outputLanguage, TranslationContext context) {
        File model = global.getGgufModelFile(Translator.INDEX_TRANSLATE);
        if (model.isFile()) {
            GgufTranslator.prefill(model.getAbsolutePath(), voicePrompt("", inputLanguage, outputLanguage, context)[0]);
        }
    }

    private static final String ANSWER_OPEN = "<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n";

    /** The model card's plain instruction, the one the model follows best. */
    private static String instruction(CustomLocale inputLanguage, CustomLocale outputLanguage) {
        return "<|im_start|>user\n请将以下" + chineseName(inputLanguage) + "文本翻译为" + chineseName(outputLanguage) + "，直接输出翻译结果，不要进行任何解释。\n\n";
    }

    /**
     * Every clause is a turn with the plain instruction. The constraint block of the model card
     * (earlier clauses, a rating request) sat after the text, was decoded again for each clause,
     * and short clauses got the whole block copied into the answer.
     */
    private static String[] voicePrompt(String text, CustomLocale inputLanguage, CustomLocale outputLanguage, TranslationContext context) {
        String open = instruction(inputLanguage, outputLanguage);
        return GgufTranslator.conversation(open, open, ANSWER_OPEN, "<|im_end|>\n", context, text);
    }

    private static String chineseName(CustomLocale locale) {
        if (GgufTranslator.isTraditionalChinese(locale)) {
            return "繁体中文";
        }
        if ("zh".equalsIgnoreCase(locale.getLanguage())) {
            return "简体中文";
        }
        String name = locale.getLocale().getDisplayLanguage(Locale.SIMPLIFIED_CHINESE);
        if (name == null || name.isEmpty()) {
            return GgufTranslator.languageLabel(locale);
        }
        return name;
    }
}
