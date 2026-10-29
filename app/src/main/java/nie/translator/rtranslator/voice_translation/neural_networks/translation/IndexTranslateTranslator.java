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

    public static String translate(Global global, String text, CustomLocale inputLanguage, CustomLocale outputLanguage, @Nullable TranslationContext context) {
        File model = global.getGgufModelFile(Translator.INDEX_TRANSLATE);
        if (!model.isFile()) {
            throw new IllegalStateException("Index-Translate file is missing");
        }
        return GgufTranslator.generate(model.getAbsolutePath(), prompt(text, inputLanguage, outputLanguage, context), GgufTranslator.voiceTokenLimit(text, context, 512));
    }

    private static String prompt(String text, CustomLocale inputLanguage, CustomLocale outputLanguage, @Nullable TranslationContext context) {
        String source = chineseName(inputLanguage);
        String target = chineseName(outputLanguage);
        String notes = GgufTranslator.voiceNotes(context, false);
        String body;
        if (notes.isEmpty()) {
            body = "请将以下" + source + "文本翻译为" + target + "，直接输出翻译结果，不要进行任何解释。\n\n" + text;
        } else {
            boolean rated = context.selfRating;
            body = "请将以下" + source + "文本翻译成" + target + "，并且严格遵循所有约束要求。\n"
                    + "【源文】\n" + text + "\n"
                    + "【约束要求】\n"
                    + "1. 【注意】" + notes.trim() + "\n"
                    + (rated ? "只输出译文和最后一行评分，不要有任何额外说明。" : "只输出译文，不要有任何额外说明。");
        }
        return "<|im_start|>user\n" + body + "<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n";
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
