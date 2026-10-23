package nie.translator.rtranslator.tools;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import nie.translator.rtranslator.downloader.DownloadGroupInfo;
import nie.translator.rtranslator.downloader.DownloadInfo;

/**
 * Live Draft voices. The system text-to-speech engine is the default.
 * Fun-CosyVoice 3 is the optional quantized GGUF pack. MOSS, Kokoro, and the FP16 ONNX pack are no longer offered.
 * Files already on disk are left in place.
 */
public final class LiveDraftVoices {
    public static final String ENGINE_SYSTEM = "system";
    public static final String ENGINE_KOKORO = "kokoro";
    public static final String ENGINE_COSYVOICE = "cosyvoice3";
    /** Old preference value. Reads as the system engine. */
    private static final String ENGINE_MOSS = "moss";
    public static final String PREF_MODEL = "liveDraftModel";
    public static final String PREF_GENDER = "liveDraftGender";
    public static final String GENDER_FEMALE = "f";
    public static final String GENDER_MALE = "m";

    private static final String COSY_BASE = "https://huggingface.co/cstr/cosyvoice3-0.5b-2512-GGUF/resolve/main/";

    private static final String[] COSY_LANGUAGES = new String[]{
            "zh", "en", "ja", "ko", "de", "es", "fr", "it", "ru", "yue"
    };

    /** Q4 LLM, Q8 flow, F16 HiFT, plus the small cloners used for an uploaded reference. */
    private static final Asset[] COSY_FILES = new Asset[]{
            new Asset(COSY_BASE, "cosyvoice3-llm-q4_k.gguf", 383891200L),
            new Asset(COSY_BASE, "cosyvoice3-flow-q8_0.gguf", 360751936L),
            new Asset(COSY_BASE, "cosyvoice3-hift-f16.gguf", 41601888L),
            new Asset(COSY_BASE, "cosyvoice3-voices.gguf", 665472L),
            new Asset(COSY_BASE, "cosyvoice3-s3tok-q4_k.gguf", 145258240L),
            new Asset(COSY_BASE, "cosyvoice3-campplus-f16.gguf", 14153600L),
    };

    private LiveDraftVoices() {
    }

    public static List<Engine> engines() {
        ArrayList<Engine> engines = new ArrayList<>(2);
        engines.add(engine(ENGINE_SYSTEM));
        engines.add(engine(ENGINE_COSYVOICE));
        return engines;
    }

    public static Engine engine(String id) {
        if (ENGINE_COSYVOICE.equals(id)) {
            return new Engine(ENGINE_COSYVOICE, COSY_LANGUAGES, cosyBytes());
        }
        return new Engine(ENGINE_SYSTEM, new String[0], 0L);
    }

    public static String selected(Context context) {
        String saved = prefs(context).getString(PREF_MODEL, ENGINE_SYSTEM);
        if (ENGINE_COSYVOICE.equals(saved)) {
            return ENGINE_COSYVOICE;
        }
        if (ENGINE_MOSS.equals(saved)) {
            prefs(context).edit().putString(PREF_MODEL, ENGINE_SYSTEM).apply();
        }
        return ENGINE_SYSTEM;
    }

    public static void setSelected(Context context, String engine) {
        String id = ENGINE_COSYVOICE.equals(engine) ? ENGINE_COSYVOICE : ENGINE_SYSTEM;
        prefs(context).edit().putString(PREF_MODEL, id).apply();
    }

    public static String gender(Context context) {
        String saved = prefs(context).getString(PREF_GENDER, GENDER_FEMALE);
        return GENDER_MALE.equals(saved) ? GENDER_MALE : GENDER_FEMALE;
    }

    public static void setGender(Context context, String gender) {
        prefs(context).edit().putString(PREF_GENDER, GENDER_MALE.equals(gender) ? GENDER_MALE : GENDER_FEMALE).apply();
    }

    public static String normalize(String language) {
        if (language == null) {
            return "";
        }
        String code = language.toLowerCase(Locale.US).replace('-', '_');
        int split = code.indexOf('_');
        if (split > 0) {
            code = code.substring(0, split);
        }
        if ("cmn".equals(code)) {
            return "zh";
        }
        return code;
    }

    public static boolean supports(String engine, String language) {
        if (!ENGINE_COSYVOICE.equals(engine)) {
            return false;
        }
        String code = normalize(language);
        for (String candidate : COSY_LANGUAGES) {
            if (candidate.equals(code)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isReady(Context context, String engine) {
        if (!ENGINE_COSYVOICE.equals(engine)) {
            return false;
        }
        File dir = modelDir(context, engine);
        for (Asset asset : COSY_FILES) {
            if (findNamed(dir, asset.name) == null) {
                return false;
            }
        }
        return true;
    }

    /** CosyVoice when it is selected, on disk, and knows the language. Otherwise the caller uses system TTS. */
    public static String engineFor(Context context, String language) {
        if (ENGINE_COSYVOICE.equals(selected(context)) && isReady(context, ENGINE_COSYVOICE) && supports(ENGINE_COSYVOICE, language)) {
            return ENGINE_COSYVOICE;
        }
        return null;
    }

    public static boolean canSpeak(Context context, String language) {
        return engineFor(context, language) != null;
    }

    public static List<String> readyLanguages(Context context) {
        ArrayList<String> languages = new ArrayList<>();
        if (isReady(context, ENGINE_COSYVOICE)) {
            Collections.addAll(languages, COSY_LANGUAGES);
        }
        return languages;
    }

    public static boolean isLiveDraftDownload(DownloadGroupInfo group) {
        if (group == null || group.downloadsInfo == null || group.downloadsInfo.length == 0) {
            return false;
        }
        String name = group.downloadsInfo[0].getName();
        return "moss_tts_prefill.onnx".equals(name) || "cosyvoice3-llm-q4_k.gguf".equals(name);
    }

    public static boolean isReadyGroup(Context context, DownloadGroupInfo group) {
        if (!isLiveDraftDownload(group)) {
            return false;
        }
        String name = group.downloadsInfo[0].getName();
        if ("cosyvoice3-llm-q4_k.gguf".equals(name)) {
            return isReady(context, ENGINE_COSYVOICE);
        }
        // MOSS is no longer offered. A leftover group must not block first launch.
        return "moss_tts_prefill.onnx".equals(name);
    }

    /** CosyVoice is downloaded on first launch only when it is the selected voice. */
    public static ArrayList<DownloadGroupInfo> wantedDownloads(Context context) {
        ArrayList<DownloadGroupInfo> groups = new ArrayList<>();
        if (context.getSharedPreferences("default", Context.MODE_PRIVATE).getBoolean("firstStart", true)
                && ENGINE_COSYVOICE.equals(selected(context))) {
            groups.add(engine(ENGINE_COSYVOICE).downloadInfo(context));
        }
        return groups;
    }

    public static File modelDir(Context context, String engine) {
        String folder = ENGINE_COSYVOICE.equals(engine) ? ENGINE_COSYVOICE : ENGINE_SYSTEM;
        return new File(context.getFilesDir(), "LiveDraft/" + folder);
    }

    public static File uploadFile(Context context, String gender) {
        return new File(referenceDir(context), "upload-" + (GENDER_MALE.equals(gender) ? GENDER_MALE : GENDER_FEMALE));
    }

    public static void copyUpload(Context context, String gender, InputStream input) throws IOException {
        File dest = uploadFile(context, gender);
        File parent = dest.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Could not create " + parent);
        }
        FileOutputStream output = new FileOutputStream(dest);
        try {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
        } finally {
            output.close();
        }
        File codes = new File(dest.getAbsolutePath() + ".codes");
        if (codes.isFile() && !codes.delete()) {
            codes.deleteOnExit();
        }
    }

    public static void clearUpload(Context context, String gender) {
        File dest = uploadFile(context, gender);
        if (dest.isFile()) {
            dest.delete();
        }
        File codes = new File(dest.getAbsolutePath() + ".codes");
        if (codes.isFile()) {
            codes.delete();
        }
    }

    public static File findNamed(File dir, String name) {
        if (dir == null || !dir.isDirectory()) {
            return null;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return null;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                File nested = findNamed(child, name);
                if (nested != null) {
                    return nested;
                }
            } else if (child.getName().equals(name) && child.length() > 0) {
                return child;
            }
        }
        return null;
    }

    private static File referenceDir(Context context) {
        return new File(context.getFilesDir(), "LiveDraft/refs");
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("default", Context.MODE_PRIVATE);
    }

    private static long cosyBytes() {
        return bytes(COSY_FILES);
    }

    private static long bytes(Asset[] files) {
        long total = 0L;
        for (Asset asset : files) {
            total += asset.bytes;
        }
        return total;
    }

    /** Kept so the unused FP16 CosyVoice class still compiles. The app does not load that graph. */
    public static final class Speaker {
        public final int id;
        public final String promptWav;

        public Speaker(int id, String promptWav) {
            this.id = id;
            this.promptWav = promptWav;
        }
    }

    public static final class Engine {
        public final String id;
        public final String[] languages;
        public final long bytes;

        private Engine(String id, String[] languages, long bytes) {
            this.id = id;
            this.languages = languages;
            this.bytes = bytes;
        }

        public int sizeMb() {
            return (int) Math.max(1L, bytes / (1024L * 1024L));
        }

        public DownloadGroupInfo downloadInfo(Context context) {
            String folder = modelDir(context, ENGINE_COSYVOICE).getAbsolutePath() + "/";
            Asset[] files = COSY_FILES;
            DownloadInfo[] infos = new DownloadInfo[files.length];
            for (int i = 0; i < files.length; i++) {
                Asset asset = files[i];
                long kb = Math.max(1L, (asset.bytes + 1023L) / 1024L);
                infos[i] = new DownloadInfo(asset.name, asset.base + asset.name, folder, kb, false, false);
            }
            return new DownloadGroupInfo(infos);
        }
    }

    private static final class Asset {
        private final String base;
        private final String name;
        private final long bytes;

        private Asset(String base, String name, long bytes) {
            this.base = base;
            this.name = name;
            this.bytes = bytes;
        }
    }
}
