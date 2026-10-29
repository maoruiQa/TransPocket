package nie.translator.rtranslator.voice_translation.neural_networks.translation;

import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;

import nie.translator.rtranslator.tools.ClauseConfidence;
import nie.translator.rtranslator.tools.CustomLocale;

/**
 * One llama.cpp context for the GGUF translation models. The prompt is already formatted.
 * The chat template inside the file is not applied.
 */
public final class GgufTranslator {
    private static final Object LOCK = new Object();
    /**
     * Guards {@link #running} and the native cancel flag, which is true only while the running
     * call is the cancelled one. Separate from LOCK, which a running call holds until it returns.
     */
    private static final Object CANCEL_LOCK = new Object();
    @Nullable
    private static TranslationContext running;
    private static boolean libraryLoaded;
    private static boolean libraryFailed;
    private static String loadedPath;

    private GgufTranslator() {
    }

    /** The call was stopped by {@link #cancel}. It is not a model error. */
    public static final class Cancelled extends IllegalStateException {
        Cancelled() {
            super("GGUF translation cancelled");
        }
    }

    /** Gets the answer so far, on the translating thread, after each token that adds text. */
    public interface PartialListener {
        void onPartial(String answer);
    }

    public static String generate(String modelPath, String prompt, int maxTokens) {
        return generate(modelPath, new String[]{prompt}, maxTokens, false, null, null);
    }

    /** The answer exactly as the model wrote it, for a test that builds the next prompt from it. */
    public static String generateRaw(String modelPath, String prompt, int maxTokens) {
        TranslationContext context = TranslationContext.withoutHistory(ClauseConfidence.UNKNOWN, false);
        generate(modelPath, new String[]{prompt}, maxTokens, false, context, null);
        return context.answer;
    }

    /**
     * The native side keeps the decoded prompt and answer between calls, so a prompt that repeats
     * the previous one and its answer only decodes what was added.
     *
     * @param segments the prompt in parts, see {@link #conversation}. Each part is tokenized on its own.
     * @param rating the prompt asks for a closing {@code [Q=n]} line
     * @param context the clause this call translates; {@link #cancel} with the same object stops it.
     *                Its {@code answer} is set to the raw answer.
     */
    public static String generate(String modelPath, String[] segments, int maxTokens, boolean rating, @Nullable TranslationContext context, @Nullable PartialListener partial) {
        ensureLibrary();
        byte[][] utf8 = utf8(segments);
        synchronized (LOCK) {
            loadLocked(modelPath);
            synchronized (CANCEL_LOCK) {
                if (context != null && context.cancelled) {
                    throw new Cancelled();
                }
                running = context;
                nativeSetCancel(false);
            }
            byte[] generated;
            try {
                generated = nativeGenerate(utf8, maxTokens, rating, partial == null ? null : new NativePartial(partial));
            } finally {
                synchronized (CANCEL_LOCK) {
                    running = null;
                    nativeSetCancel(false);
                }
            }
            if (context != null && context.cancelled) {
                throw new Cancelled();
            }
            if (generated == null) {
                throw new IllegalStateException("GGUF model returned no text");
            }
            String raw = new String(generated, StandardCharsets.UTF_8);
            if (context != null) {
                context.answer = raw;
            }
            String cleaned = clean(raw);
            if (cleaned.isEmpty()) {
                throw new IllegalStateException("GGUF model returned no text");
            }
            return cleaned;
        }
    }

    /**
     * Loads the model and decodes {@code instructions}, the first part of the prompts that follow,
     * so the first of them waits for neither. Nothing is generated.
     */
    public static void prefill(String modelPath, String instructions) {
        ensureLibrary();
        byte[][] utf8 = utf8(new String[]{instructions, ""});
        synchronized (LOCK) {
            loadLocked(modelPath);
            nativeGenerate(utf8, 0, false, null);
        }
    }

    private static void loadLocked(String modelPath) {
        if (!modelPath.equals(loadedPath)) {
            try {
                nativeLoad(modelPath);
                loadedPath = modelPath;
            } catch (RuntimeException error) {
                loadedPath = null;
                throw error;
            }
        }
    }

    private static byte[][] utf8(String[] segments) {
        byte[][] utf8 = new byte[segments.length][];
        for (int i = 0; i < segments.length; i++) {
            utf8[i] = segments[i].getBytes(StandardCharsets.UTF_8);
        }
        return utf8;
    }

    /**
     * Stops the call that translates {@code context}, or the call before it starts. A call for
     * any other context is left alone, so a late cancel cannot stop the next clause.
     */
    public static void cancel(TranslationContext context) {
        synchronized (CANCEL_LOCK) {
            context.cancelled = true;
            if (running == context && libraryLoaded) {
                nativeSetCancel(true);
            }
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
     * A voice clause prompt as a conversation: the fixed instructions, each earlier clause with
     * the answer the model gave it, then the new clause. The next clause's prompt is this one plus
     * the answer plus the new clause, so the native side decodes only that. Earlier prompts that
     * listed the earlier clauses inside the instructions changed near the start with every clause
     * and were decoded whole each time (~200 tokens); the small models also copied the list into the answer.
     *
     * Each part is tokenized on its own, so a part ends where a later prompt may differ. A
     * recurrent model keeps checkpoints at the end of the first part, the start of the last one
     * and the end of the prompt.
     *
     * @param firstOpen   everything before the first clause's text, including the instructions
     * @param nextOpen    what starts every later clause
     * @param answerOpen  what follows a clause's text and starts the answer
     * @param answerClose what ends an answer
     */
    static String[] conversation(String firstOpen, String nextOpen, String answerOpen, String answerClose, @Nullable TranslationContext context, String text) {
        ArrayList<String> segments = new ArrayList<>();
        segments.add(firstOpen);
        boolean first = true;
        if (context != null) {
            for (TranslationContext.Line line : context.lines) {
                if (line.answer == null) {
                    continue;
                }
                segments.add((first ? "" : nextOpen) + line.text.trim() + answerOpen);
                segments.add(line.answer + answerClose);
                first = false;
            }
        }
        segments.add((first ? "" : nextOpen) + text.trim() + answerOpen);
        return segments.toArray(new String[0]);
    }

    /**
     * What may be shown of an answer that is still being written: cleaned as the final answer,
     * without a rating tag or the start of one. Empty while the answer is a note in angle brackets.
     */
    public static String partialText(String answer) {
        String out = clean(answer);
        if (out.startsWith("<")) {
            return "";
        }
        int open = out.lastIndexOf('[');
        if (open >= 0 && out.indexOf(']', open) < 0) {
            out = out.substring(0, open);
        }
        return ClauseConfidence.withoutModelRating(out);
    }

    /**
     * Output budget for a voice clause, which is one short sentence. A model that keeps writing
     * after its answer stops here instead of at the text-mode limit.
     */
    static int voiceTokenLimit(String text, @Nullable TranslationContext context, int limit) {
        if (context == null) {
            return limit;
        }
        return Math.min(limit, 64 + 3 * text.codePointCount(0, text.length()));
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

    /** Called by the native side, on the translating thread, with the UTF-8 answer so far. */
    private static final class NativePartial {
        private final PartialListener listener;

        NativePartial(PartialListener listener) {
            this.listener = listener;
        }

        @SuppressWarnings("unused")
        void onPartial(byte[] answer) {
            listener.onPartial(new String(answer, StandardCharsets.UTF_8));
        }
    }

    private static native void nativeLoad(String modelPath);

    /** @param rating the prompt asks for a closing {@code [Q=n]} line */
    private static native byte[] nativeGenerate(byte[][] segments, int maxTokens, boolean rating, @Nullable NativePartial listener);

    private static native void nativeSetCancel(boolean cancel);

    private static native void nativeRelease();
}
