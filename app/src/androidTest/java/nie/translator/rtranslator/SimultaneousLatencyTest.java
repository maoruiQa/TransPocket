package nie.translator.rtranslator;

import android.util.Log;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import nie.translator.rtranslator.tools.ClauseConfidence;
import nie.translator.rtranslator.tools.ClauseContextWindow;
import nie.translator.rtranslator.tools.CustomLocale;
import nie.translator.rtranslator.voice_translation.neural_networks.translation.GgufTranslator;
import nie.translator.rtranslator.voice_translation.neural_networks.translation.IndexTranslateTranslator;
import nie.translator.rtranslator.voice_translation.neural_networks.translation.Qwen35Translator;
import nie.translator.rtranslator.voice_translation.neural_networks.translation.TranslateGemmaTranslator;
import nie.translator.rtranslator.voice_translation.neural_networks.translation.TranslationContext;
import nie.translator.rtranslator.voice_translation.neural_networks.translation.Translator;

/**
 * Replays a short speech through each downloaded GGUF model the way simultaneous interpretation
 * sends it, and logs how long each clause takes (tag latency_test). A model whose file is not on
 * the device is skipped. The native log (tag gguf) has the prompt and output token counts.
 *
 * v012: the v0.1.2 prompts, where the previous clause was prepended and translated again.
 * voice: the voice clause prompts with grading on and the history of SimultaneousService, after the
 * model was prepared as while the microphone settles. Also logs when the first text was shown.
 * early: each clause cut at its comma, as "translate while speaking" commits it; the time after the
 * clause closes is the tail call only.
 */
@RunWith(AndroidJUnit4.class)
public class SimultaneousLatencyTest {
    private static final String TAG = "latency_test";
    private static final String[] SPEECH = {
            "Good morning everyone, and thank you for coming today.",
            "I want to talk about how our team works with customers in other countries.",
            "Last year we opened two new offices in Asia.",
            "The first one is in Singapore, and the second one is in Tokyo.",
            "Both offices started with only three people.",
            "Now each of them has more than twenty employees.",
            "We learned that local partners are very important.",
            "Without them, we would not understand the market so quickly.",
            "Next year, we plan to open another office in Seoul.",
            "If you have any questions, please ask me after the talk.",
    };
    private static final String[] SPEECH_ZH = {
            "大家好，欢迎来到今天的发布会。",
            "今天我想介绍一下我们的新产品。",
            "这款手机的电池可以用两天，",
            "而且充电只需要三十分钟。",
            "它的价格比去年的型号便宜一百元。",
            "我们在三个城市做了用户测试。",
            "大部分用户觉得屏幕比以前更亮。",
            "如果大家有问题，可以在会后找我。",
    };
    private static final int[] MODES = {Translator.QWEN35, Translator.INDEX_TRANSLATE, Translator.TRANSLATEGEMMA};

    @After
    public void release() {
        GgufTranslator.release();
    }

    @Test
    public void v012() {
        Global global = global();
        CustomLocale source = CustomLocale.getInstance("en");
        CustomLocale target = CustomLocale.getInstance("zh");
        for (int mode : MODES) {
            if (!global.getGgufModelFile(mode).isFile()) {
                continue;
            }
            String path = global.getGgufModelFile(mode).getAbsolutePath();
            List<Long> times = new ArrayList<>();
            String previous = null;
            for (String clause : SPEECH) {
                String modelText = previous == null ? clause : previous + "\n" + clause;
                long start = System.currentTimeMillis();
                String result = GgufTranslator.generate(path, v012Prompt(mode, modelText, source, target), 512);
                long ms = System.currentTimeMillis() - start;
                times.add(ms);
                Log.i(TAG, name(mode) + " v012 " + ms + " ms: " + clause + " => " + result.replace('\n', '|'));
                previous = clause;
            }
            summary(mode, "v012", times);
            GgufTranslator.release();
        }
    }

    @Test
    public void voice() {
        Global global = global();
        for (int mode : MODES) {
            if (!global.getGgufModelFile(mode).isFile()) {
                continue;
            }
            prepare(global, mode, "en", "zh");
            ClauseContextWindow window = new ClauseContextWindow();
            List<Long> times = new ArrayList<>();
            List<Long> firsts = new ArrayList<>();
            long now = 0;
            for (String clause : SPEECH) {
                now += 3000;
                long[] ms = translateClause(global, mode, window, "en", "zh", clause, now, "voice");
                times.add(ms[0]);
                firsts.add(ms[1]);
            }
            summary(mode, "voice", times);
            summary(mode, "voice-first-text", firsts);
            GgufTranslator.release();
        }
    }

    /** Chinese to English, so the answers in the prompt are English and the clauses Chinese. */
    @Test
    public void voiceChinese() {
        Global global = global();
        for (int mode : MODES) {
            if (!global.getGgufModelFile(mode).isFile()) {
                continue;
            }
            prepare(global, mode, "zh", "en");
            ClauseContextWindow window = new ClauseContextWindow();
            List<Long> times = new ArrayList<>();
            long now = 0;
            for (String clause : SPEECH_ZH) {
                now += 3000;
                times.add(translateClause(global, mode, window, "zh", "en", clause, now, "voice-zh")[0]);
            }
            summary(mode, "voice-zh", times);
            GgufTranslator.release();
        }
    }

    @Test
    public void early() {
        Global global = global();
        for (int mode : MODES) {
            if (!global.getGgufModelFile(mode).isFile()) {
                continue;
            }
            prepare(global, mode, "en", "zh");
            ClauseContextWindow window = new ClauseContextWindow();
            List<Long> tails = new ArrayList<>();
            List<Long> firsts = new ArrayList<>();
            long now = 0;
            for (String clause : SPEECH) {
                now += 3000;
                int comma = clause.indexOf(", ");
                long[] ms;
                if (comma < 0) {
                    ms = translateClause(global, mode, window, "en", "zh", clause, now, "early-whole");
                } else {
                    translateClause(global, mode, window, "en", "zh", clause.substring(0, comma + 1), now, "early-piece");
                    ms = translateClause(global, mode, window, "en", "zh", clause.substring(comma + 2), now + 500, "early-tail");
                }
                tails.add(ms[0]);
                firsts.add(ms[1]);
            }
            summary(mode, "early", tails);
            summary(mode, "early-first-text", firsts);
            GgufTranslator.release();
        }
    }

    /** What SimultaneousService does while the microphone settles. */
    private static void prepare(Global global, int mode, String source, String target) {
        long start = System.currentTimeMillis();
        TranslationContext context = TranslationContext.withoutHistory(ClauseConfidence.UNKNOWN, true);
        CustomLocale input = CustomLocale.getInstance(source);
        CustomLocale output = CustomLocale.getInstance(target);
        if (mode == Translator.QWEN35) {
            Qwen35Translator.prepareVoice(global, input, output, context);
        } else if (mode == Translator.INDEX_TRANSLATE) {
            IndexTranslateTranslator.prepareVoice(global, input, output, context);
        } else {
            TranslateGemmaTranslator.prepareVoice(global, input, output, context);
        }
        Log.i(TAG, name(mode) + " prepared in " + (System.currentTimeMillis() - start) + " ms");
    }

    /**
     * As SimultaneousService.performTranslation with grading on: history with the raw answers,
     * then the clause joins the window. Returns the time to the final answer and to the first shown text.
     */
    private long[] translateClause(Global global, int mode, ClauseContextWindow window, String sourceCode, String targetCode, String clause, long now, String label) {
        CustomLocale source = CustomLocale.getInstance(sourceCode);
        CustomLocale target = CustomLocale.getInstance(targetCode);
        ArrayList<TranslationContext.Line> lines = new ArrayList<>();
        for (ClauseContextWindow.Entry entry : window.history(now)) {
            lines.add(new TranslationContext.Line(entry.source, entry.isDoubtful(), entry.answer));
        }
        ClauseContextWindow.Entry last = window.lastTrusted(now);
        TranslationContext context = new TranslationContext(last == null ? null : last.source, last == null ? null : last.translation, null, lines, ClauseConfidence.HIGH, true);
        final long start = System.currentTimeMillis();
        final long[] firstText = {0};
        GgufTranslator.PartialListener partial = answer -> {
            if (firstText[0] == 0 && !GgufTranslator.partialText(answer).isEmpty()) {
                firstText[0] = System.currentTimeMillis() - start;
            }
        };
        String result;
        if (mode == Translator.QWEN35) {
            result = Qwen35Translator.translate(global, clause, source, target, context, partial);
        } else if (mode == Translator.INDEX_TRANSLATE) {
            result = IndexTranslateTranslator.translate(global, clause, source, target, context, partial);
        } else {
            result = TranslateGemmaTranslator.translate(global, clause, source, target, context, partial);
        }
        long ms = System.currentTimeMillis() - start;
        int rating = ClauseConfidence.modelRating(result);
        String shown = ClauseConfidence.withoutModelRating(result);
        window.add(clause, shown, context.answer, rating == ClauseConfidence.UNKNOWN ? ClauseConfidence.HIGH : rating, now);
        Log.i(TAG, name(mode) + " " + label + " " + ms + " ms (text after " + firstText[0] + " ms): " + clause + " => " + result.replace('\n', '|'));
        return new long[]{ms, firstText[0] == 0 ? ms : firstText[0]};
    }

    /** The prompts of v0.1.2, which sent the previous clause and the new one as one text. */
    private static String v012Prompt(int mode, String text, CustomLocale inputLanguage, CustomLocale outputLanguage) {
        String source = GgufTranslator.languageLabel(inputLanguage);
        String target = GgufTranslator.languageLabel(outputLanguage);
        String sourceName = source.substring(0, source.lastIndexOf(" ("));
        String targetName = target.substring(0, target.lastIndexOf(" ("));
        if (mode == Translator.TRANSLATEGEMMA) {
            return "<bos><start_of_turn>user\n"
                    + "You are a professional " + source + " to " + target + " translator. "
                    + "Your goal is to accurately convey the meaning and nuances of the original " + sourceName + " text "
                    + "while adhering to " + targetName + " grammar, vocabulary, and cultural sensitivities.\n"
                    + "Produce only the " + targetName + " translation, without any additional explanations or commentary. "
                    + "Please translate the following " + sourceName + " text into " + targetName + ":\n\n"
                    + text
                    + "<end_of_turn>\n<start_of_turn>model\n";
        }
        if (mode == Translator.INDEX_TRANSLATE) {
            // Index-Translate was added in 0.1.4; this is its plain text-mode prompt.
            return "<|im_start|>user\n请将以下英语文本翻译为简体中文，直接输出翻译结果，不要进行任何解释。\n\n" + text
                    + "<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n";
        }
        return "<|im_start|>user\n"
                + "You are a professional " + source + " to " + target + " translator. "
                + "Translate the following " + sourceName + " text into " + targetName + ". "
                + "Output only the translation.\n"
                + text
                + "<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n";
    }

    private static void summary(int mode, String scenario, List<Long> times) {
        long rest = 0;
        for (int i = 1; i < times.size(); i++) {
            rest += times.get(i);
        }
        long mean = times.size() > 1 ? rest / (times.size() - 1) : 0;
        Log.i(TAG, String.format(Locale.ROOT, "SUMMARY %s %s first=%d ms mean(after first)=%d ms clauses=%d", name(mode), scenario, times.get(0), mean, times.size()));
    }

    private static String name(int mode) {
        if (mode == Translator.QWEN35) {
            return "qwen35";
        }
        if (mode == Translator.INDEX_TRANSLATE) {
            return "index";
        }
        return "gemma";
    }

    private static Global global() {
        return (Global) InstrumentationRegistry.getTargetContext().getApplicationContext();
    }
}
