package nie.translator.rtranslator;

import static org.junit.Assert.assertFalse;

import android.util.Log;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import nie.translator.rtranslator.tools.ClauseConfidence;
import nie.translator.rtranslator.tools.CustomLocale;
import nie.translator.rtranslator.voice_translation.neural_networks.translation.GgufTranslator;
import nie.translator.rtranslator.voice_translation.neural_networks.translation.IndexTranslateTranslator;
import nie.translator.rtranslator.voice_translation.neural_networks.translation.Qwen35Translator;
import nie.translator.rtranslator.voice_translation.neural_networks.translation.TranslateGemmaTranslator;
import nie.translator.rtranslator.voice_translation.neural_networks.translation.TranslationContext;
import nie.translator.rtranslator.voice_translation.neural_networks.translation.Translator;

/**
 * Runs each downloaded GGUF model on a few sentences. A model whose file is not on the device is
 * skipped. The quantization is the one saved in the app's settings. Output goes to logcat (tag gguf_test).
 */
@RunWith(AndroidJUnit4.class)
public class GgufTranslationTest {
    private static final String TAG = "gguf_test";
    private static final String[][] CASES = {
            {"en", "zh", "The train to the airport leaves in ten minutes."},
            {"zh", "en", "请问附近有没有便宜一点的酒店？"},
            {"en", "zh-HANT", "Thank you for your help today."},
            {"en", "ja", "I would like a table for two, please. 😊"},
    };

    @After
    public void release() {
        GgufTranslator.release();
    }

    @Test
    public void qwen35() {
        run(Translator.QWEN35);
    }

    @Test
    public void indexTranslate() {
        run(Translator.INDEX_TRANSLATE);
    }

    @Test
    public void translateGemma() {
        run(Translator.TRANSLATEGEMMA);
    }

    /**
     * Clauses in a row as SimultaneousService sends them: the two earlier clauses slide along, one
     * is marked doubtful, and the model rates itself. The native log (tag gguf) shows how much of
     * each prompt came from the saved prefix and whether the answer stopped at the rating.
     */
    @Test
    public void voiceClauses() {
        String[] clauses = {
                "We landed in Rome yesterday evening.",
                "The hotel is near the station.",
                "Tomorrow we want to visit the Colosseum early in the morning.",
                "After that we will look for a small restaurant for lunch.",
                "In the afternoon we could walk to the Trevi Fountain, if it is not too hot.",
                "My sister says the museum closes at six, so we should hurry.",
                "Do you know if we need to book tickets in advance?",
        };
        Global global = (Global) InstrumentationRegistry.getTargetContext().getApplicationContext();
        for (int mode : new int[]{Translator.QWEN35, Translator.INDEX_TRANSLATE, Translator.TRANSLATEGEMMA}) {
            if (!global.getGgufModelFile(mode).isFile()) {
                continue;
            }
            for (int i = 1; i < clauses.length; i++) {
                List<TranslationContext.Line> lines = new ArrayList<>();
                for (int j = Math.max(0, i - 2); j < i; j++) {
                    lines.add(new TranslationContext.Line(clauses[j], j == 3));
                }
                TranslationContext context = new TranslationContext(null, null, null, lines, ClauseConfidence.HIGH, true);
                translate(global, mode, new String[]{"en", "zh", clauses[i]}, context);
            }
            GgufTranslator.release();
        }
    }

    private void run(int mode) {
        Global global = (Global) InstrumentationRegistry.getTargetContext().getApplicationContext();
        File file = global.getGgufModelFile(mode);
        Assume.assumeTrue(file.getName() + " is not on the device", file.isFile());
        for (String[] item : CASES) {
            translate(global, mode, item, null);
        }
    }

    private void translate(Global global, int mode, String[] item, TranslationContext context) {
        CustomLocale source = CustomLocale.getInstance(item[0]);
        CustomLocale target = CustomLocale.getInstance(item[1]);
        long start = System.currentTimeMillis();
        String result;
        if (mode == Translator.QWEN35) {
            result = Qwen35Translator.translate(global, item[2], source, target, context, null);
        } else if (mode == Translator.INDEX_TRANSLATE) {
            result = IndexTranslateTranslator.translate(global, item[2], source, target, context, null);
        } else {
            result = TranslateGemmaTranslator.translate(global, item[2], source, target, context, null);
        }
        Log.i(TAG, global.getGgufModelFile(mode).getName() + (context != null ? " voice " : " ") + item[0] + "->" + item[1] + " "
                + (System.currentTimeMillis() - start) + " ms: " + item[2] + " => " + result);
        assertFalse(result.trim().isEmpty());
    }
}
