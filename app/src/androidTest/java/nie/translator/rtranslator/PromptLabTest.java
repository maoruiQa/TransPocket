package nie.translator.rtranslator;

import android.util.Log;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import nie.translator.rtranslator.voice_translation.neural_networks.translation.GgufTranslator;

/**
 * Tries prompt layouts for voice clauses without rebuilding the app. Reads files/promptlab.json
 * (push it with adb and run-as cp) and logs every answer under tag prompt_lab. Skipped when the
 * file is missing.
 *
 * Each run: "mode" (Translator mode), "first" (the whole first prompt, {text} marks the clause),
 * "next" (what follows an answer and its "close" for every later clause), "clauses".
 */
@RunWith(AndroidJUnit4.class)
public class PromptLabTest {
    private static final String TAG = "prompt_lab";

    @Test
    public void run() throws Exception {
        Global global = (Global) InstrumentationRegistry.getTargetContext().getApplicationContext();
        File file = new File(global.getFilesDir(), "promptlab.json");
        Assume.assumeTrue("no promptlab.json", file.isFile());
        JSONObject root = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        JSONArray runs = root.getJSONArray("runs");
        for (int r = 0; r < runs.length(); r++) {
            JSONObject run = runs.getJSONObject(r);
            int mode = run.getInt("mode");
            File model = global.getGgufModelFile(mode);
            if (!model.isFile()) {
                Log.i(TAG, run.getString("name") + " skipped, no " + model.getName());
                continue;
            }
            String first = run.getString("first");
            String next = run.getString("next");
            String close = run.getString("close");
            int maxTokens = run.optInt("maxTokens", 128);
            JSONArray clauses = run.has("clauses") ? run.getJSONArray("clauses") : root.getJSONArray("clauses");
            String prompt = null;
            long total = 0;
            for (int i = 0; i < clauses.length(); i++) {
                String clause = clauses.getString(i);
                prompt = prompt == null ? first.replace("{text}", clause) : prompt + next.replace("{text}", clause);
                long start = System.currentTimeMillis();
                String raw = GgufTranslator.generateRaw(model.getAbsolutePath(), prompt, maxTokens);
                long ms = System.currentTimeMillis() - start;
                if (i > 0) {
                    total += ms;
                }
                Log.i(TAG, run.getString("name") + " #" + i + " " + ms + " ms: " + clause + " => " + raw.replace('\n', '|'));
                prompt = prompt + raw + close;
            }
            Log.i(TAG, "SUMMARY " + run.getString("name") + " mean(after first)=" + (clauses.length() > 1 ? total / (clauses.length() - 1) : 0) + " ms");
            GgufTranslator.release();
        }
    }
}
