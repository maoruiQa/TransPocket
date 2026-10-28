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

import nie.translator.rtranslator.tools.CustomLocale;
import nie.translator.rtranslator.voice_translation.neural_networks.translation.GgufTranslator;
import nie.translator.rtranslator.voice_translation.neural_networks.translation.IndexTranslateTranslator;
import nie.translator.rtranslator.voice_translation.neural_networks.translation.Qwen35Translator;
import nie.translator.rtranslator.voice_translation.neural_networks.translation.TranslateGemmaTranslator;
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

    private void run(int mode) {
        Global global = (Global) InstrumentationRegistry.getTargetContext().getApplicationContext();
        File file = global.getGgufModelFile(mode);
        Assume.assumeTrue(file.getName() + " is not on the device", file.isFile());
        for (String[] item : CASES) {
            CustomLocale source = CustomLocale.getInstance(item[0]);
            CustomLocale target = CustomLocale.getInstance(item[1]);
            long start = System.currentTimeMillis();
            String result;
            if (mode == Translator.QWEN35) {
                result = Qwen35Translator.translate(global, item[2], source, target, null);
            } else if (mode == Translator.INDEX_TRANSLATE) {
                result = IndexTranslateTranslator.translate(global, item[2], source, target, null);
            } else {
                result = TranslateGemmaTranslator.translate(global, item[2], source, target, null);
            }
            Log.i(TAG, file.getName() + " " + item[0] + "->" + item[1] + " " + (System.currentTimeMillis() - start) + " ms: " + item[2] + " => " + result);
            assertFalse(result.trim().isEmpty());
        }
    }
}
