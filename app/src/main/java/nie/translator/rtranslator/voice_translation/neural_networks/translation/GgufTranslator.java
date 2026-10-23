package nie.translator.rtranslator.voice_translation.neural_networks.translation;

import java.util.Locale;

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
            String generated = nativeGenerate(prompt, maxTokens);
            if (generated == null) {
                throw new IllegalStateException("GGUF model returned no text");
            }
            String cleaned = clean(generated);
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

    private static native String nativeGenerate(String prompt, int maxTokens);

    private static native void nativeRelease();
}
