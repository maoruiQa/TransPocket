package nie.translator.rtranslator.voice_translation.neural_networks.translation;

import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import nie.translator.rtranslator.tools.ClauseConfidence;
import nie.translator.rtranslator.tools.CustomLocale;

/**
 * One llama.cpp context for the GGUF translation models. The prompt is already formatted.
 * The chat template inside the file is not applied.
 */
public final class GgufTranslator {
    private static final Object LOCK = new Object();
    private static boolean libraryLoaded;
    private static boolean libraryFailed;
    private static String loadedPath;

    private GgufTranslator() {
    }

    public static String generate(String modelPath, String prompt, int maxTokens) {
        ensureLibrary();
        synchronized (LOCK) {
            if (!modelPath.equals(loadedPath)) {
                try {
                    nativeLoad(modelPath);
                    loadedPath = modelPath;
                } catch (RuntimeException error) {
                    loadedPath = null;
                    throw error;
                }
            }
            byte[] generated = nativeGenerate(prompt, maxTokens);
            if (generated == null) {
                throw new IllegalStateException("GGUF model returned no text");
            }
            String cleaned = clean(new String(generated, StandardCharsets.UTF_8));
            if (cleaned.isEmpty()) {
                throw new IllegalStateException("GGUF model returned no text");
            }
            return cleaned;
        }
    }

    public static void release() {
        synchronized (LOCK) {
            if (!libraryLoaded) {
                loadedPath = null;
                return;
            }
            nativeRelease();
            loadedPath = null;
        }
    }

    /** English language name plus the ISO code. The device language is not used. */
    public static String languageLabel(CustomLocale locale) {
        String code = locale.getLanguage();
        if (code == null) {
            code = "";
        }
        if (isTraditionalChinese(locale)) {
            return "Traditional Chinese (zh-Hant)";
        }
        int dash = code.indexOf('-');
        if (dash > 0) {
            code = code.substring(0, dash);
        }
        String name = locale.getLocale().getDisplayLanguage(Locale.ENGLISH);
        if (name == null || name.isEmpty()) {
            name = code;
        }
        return name + " (" + code + ")";
    }

    /** zh-HANT in the language lists becomes language zh with country HANT. */
    static boolean isTraditionalChinese(CustomLocale locale) {
        String country = locale.getCountry();
        return "zh".equalsIgnoreCase(locale.getLanguage())
                && ("HANT".equalsIgnoreCase(country) || "TW".equalsIgnoreCase(country) || "HK".equalsIgnoreCase(country) || "MO".equalsIgnoreCase(country));
    }

    /**
     * Prompt lines for a voice clause: the earlier clauses, and with self-rating the
     * transcript warning and the {@code [Q=n]} request. Empty when there is nothing to add,
     * so the text-mode prompts stay unchanged.
     */
    static String voiceNotes(@Nullable TranslationContext context) {
        if (context == null) {
            return "";
        }
        StringBuilder notes = new StringBuilder();
        if (!context.lines.isEmpty()) {
            notes.append("Earlier sentences of the same speech, for reference only. Do not translate them:\n");
            for (TranslationContext.Line line : context.lines) {
                notes.append("- ").append(line.text);
                if (line.doubtful) {
                    notes.append(" (may contain speech recognition errors)");
                }
                notes.append('\n');
            }
        }
        if (context.selfRating) {
            notes.append("The text is an automatic speech transcript and may contain recognition errors. ")
                    .append("If a word is clearly misheard, translate the most likely intended word, but do not add content.\n");
            if (context.asrLevel != ClauseConfidence.UNKNOWN) {
                notes.append("Speech recognition confidence: ").append(ClauseConfidence.name(context.asrLevel)).append(".\n");
            }
            notes.append("After the translation, write one last line with only [Q=3] if the transcript reads naturally and the translation is reliable, ")
                    .append("[Q=2] if some words may be misheard, or [Q=1] if the transcript looks garbled.\n");
        }
        return notes.toString();
    }

    private static String clean(String text) {
        String out = text;
        int think = out.lastIndexOf("</think>");
        if (think >= 0) {
            out = out.substring(think + "</think>".length());
        }
        out = out.replace("<end_of_turn>", "");
        out = out.replace("<|im_end|>", "");
        out = out.replace("<start_of_turn>model", "");
        return out.trim();
    }

    private static void ensureLibrary() {
        if (libraryLoaded) {
            return;
        }
        if (libraryFailed) {
            throw new IllegalStateException("GGUF native runtime is not available");
        }
        synchronized (LOCK) {
            if (libraryLoaded || libraryFailed) {
                if (libraryFailed) {
                    throw new IllegalStateException("GGUF native runtime is not available");
                }
                return;
            }
            try {
                System.loadLibrary("gguf_translator");
                libraryLoaded = true;
            } catch (UnsatisfiedLinkError error) {
                libraryFailed = true;
                throw new IllegalStateException("GGUF native runtime is not available", error);
            }
        }
    }

    private static native void nativeLoad(String modelPath);

    private static native byte[] nativeGenerate(String prompt, int maxTokens);

    private static native void nativeRelease();
}
