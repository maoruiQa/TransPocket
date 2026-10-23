package nie.translator.rtranslator.tools;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import nie.translator.rtranslator.downloader.DownloadGroupInfo;
import nie.translator.rtranslator.downloader.DownloadInfo;

/**
 * On-device Live Draft engines. Kokoro is the default. Fun-CosyVoice 3 is optional.
 * Piper voices are no longer offered. Files already on disk are left in place.
 */
public final class LiveDraftVoices {
    public static final String ENGINE_KOKORO = "kokoro";
    public static final String ENGINE_COSYVOICE = "cosyvoice3";
    public static final String PREF_MODEL = "liveDraftModel";

    private static final String KOKORO_BASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/";
    private static final String KOKORO_ARCHIVE = "kokoro-int8-multi-lang-v1_1.tar.bz2";
    private static final long KOKORO_BYTES = 147031220L;
    private static final String COSY_BASE = "https://huggingface.co/ayousanz/cosy-voice3-onnx/resolve/main/";

    private static final String[] KOKORO_LANGUAGES = new String[]{"en", "zh"};
    private static final String[] COSY_LANGUAGES = new String[]{"zh", "en", "ja", "ko", "de", "es", "fr", "it", "ru", "yue"};

    /** v1.1-zh speaker ids. English defaults to af_maple. Chinese defaults to zf_001. */
    private static final Speaker[] KOKORO_VOICES = new Speaker[]{
            new Speaker(0, "en", "af_maple"),
            new Speaker(1, "en", "af_sol"),
            new Speaker(2, "en", "bf_vale"),
            new Speaker(3, "zh", "zf_001"),
            new Speaker(12, "zh", "zf_018"),
            new Speaker(57, "zh", "zf_099"),
            new Speaker(58, "zh", "zm_009"),
            new Speaker(102, "zh", "zm_100"),
    };

    /**
     * Prompt transcripts must match the reference clips. Nova's text is the one published
     * with en_female_nova_greeting.wav. Onyx uses the greeting clip shipped beside it.
     */
    private static final Speaker[] COSY_VOICES = new Speaker[]{
            new Speaker(0, "", "Nova", "en_female_nova_greeting.wav",
                    "Hello, my name is Sarah. I'm excited to help you with your project today. Let me know if you have any questions."),
            new Speaker(1, "", "Onyx", "en_male_onyx_greeting.wav",
                    "Hello, my name is Onyx."),
    };

    private static final Asset[] COSY_FILES = new Asset[]{
            new Asset("text_embedding_fp32.onnx", "text_embedding_fp32.onnx", 544538870L),
            new Asset("llm_backbone_initial_fp16.onnx", "llm_backbone_initial_fp16.onnx", 716774004L),
            new Asset("llm_backbone_decode_fp16.onnx", "llm_backbone_decode_fp16.onnx", 717252905L),
            new Asset("llm_decoder_fp16.onnx", "llm_decoder_fp16.onnx", 12116511L),
            new Asset("llm_speech_embedding_fp16.onnx", "llm_speech_embedding_fp16.onnx", 12116180L),
            new Asset("flow_token_embedding_fp16.onnx", "flow_token_embedding_fp16.onnx", 1050554L),
            new Asset("flow_speaker_projection_fp16.onnx", "flow_speaker_projection_fp16.onnx", 31710L),
            new Asset("flow_pre_lookahead_fp16.onnx", "flow_pre_lookahead_fp16.onnx", 1160571L),
            new Asset("flow.decoder.estimator.fp16.onnx", "flow.decoder.estimator.fp16.onnx", 664306890L),
            new Asset("hift_f0_predictor_fp32.onnx", "hift_f0_predictor_fp32.onnx", 13264386L),
            new Asset("hift_source_generator_fp32.onnx", "hift_source_generator_fp32.onnx", 259217882L),
            new Asset("hift_decoder_fp32.onnx", "hift_decoder_fp32.onnx", 70163545L),
            new Asset("vocab.json", "vocab.json", 3535130L),
            new Asset("merges.txt", "merges.txt", 1537044L),
            new Asset("speech_tokenizer_v3.onnx", "speech_tokenizer_v3.onnx", 969451503L),
            new Asset("campplus.onnx", "campplus.onnx", 28303423L),
            new Asset("prompts/en_female_nova_greeting.wav", "en_female_nova_greeting.wav", 312044L),
            new Asset("prompts/en_male_onyx_greeting.wav", "en_male_onyx_greeting.wav", 328244L),
    };

    private LiveDraftVoices() {
    }

    public static List<Engine> engines() {
        ArrayList<Engine> engines = new ArrayList<>(2);
        engines.add(engine(ENGINE_KOKORO));
        engines.add(engine(ENGINE_COSYVOICE));
        return engines;
    }

    public static Engine engine(String id) {
        if (ENGINE_COSYVOICE.equals(id)) {
            return new Engine(ENGINE_COSYVOICE, COSY_LANGUAGES, COSY_VOICES, cosyBytes());
        }
        return new Engine(ENGINE_KOKORO, KOKORO_LANGUAGES, KOKORO_VOICES, KOKORO_BYTES);
    }

    public static String selected(Context context) {
        String saved = prefs(context).getString(PREF_MODEL, ENGINE_KOKORO);
        if (ENGINE_COSYVOICE.equals(saved)) {
            return ENGINE_COSYVOICE;
        }
        return ENGINE_KOKORO;
    }

    public static void setSelected(Context context, String engine) {
        String id = ENGINE_COSYVOICE.equals(engine) ? ENGINE_COSYVOICE : ENGINE_KOKORO;
        prefs(context).edit().putString(PREF_MODEL, id).apply();
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
        String code = normalize(language);
        String[] languages = ENGINE_COSYVOICE.equals(engine) ? COSY_LANGUAGES : KOKORO_LANGUAGES;
        for (String candidate : languages) {
            if (candidate.equals(code)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isReady(Context context, String engine) {
        if (ENGINE_COSYVOICE.equals(engine)) {
            File dir = modelDir(context, ENGINE_COSYVOICE);
            for (Asset asset : COSY_FILES) {
                if (findNamed(dir, asset.localName) == null) {
                    return false;
                }
            }
            return true;
        }
        File dir = modelDir(context, ENGINE_KOKORO);
        boolean model = findNamed(dir, "model.int8.onnx") != null || findNamed(dir, "model.onnx") != null;
        return model && findNamed(dir, "voices.bin") != null && findNamed(dir, "tokens.txt") != null;
    }

    /** Selected engine when it is on disk and knows the language. CosyVoice falls back to Kokoro for Chinese and English. */
    public static String engineFor(Context context, String language) {
        String selected = selected(context);
        if (isReady(context, selected) && supports(selected, language)) {
            return selected;
        }
        if (ENGINE_COSYVOICE.equals(selected) && isReady(context, ENGINE_KOKORO) && supports(ENGINE_KOKORO, language)) {
            return ENGINE_KOKORO;
        }
        return null;
    }

    public static boolean canSpeak(Context context, String language) {
        return engineFor(context, language) != null;
    }

    public static int voiceId(Context context, String engine, String language) {
        String code = normalize(language);
        int saved = prefs(context).getInt(voiceKey(engine, code), -1);
        if (saved >= 0 && voiceSupports(engine, saved, code)) {
            return saved;
        }
        Speaker[] voices = ENGINE_COSYVOICE.equals(engine) ? COSY_VOICES : KOKORO_VOICES;
        for (Speaker speaker : voices) {
            if (speaker.language.isEmpty() || speaker.language.equals(code)) {
                return speaker.id;
            }
        }
        return voices.length == 0 ? 0 : voices[0].id;
    }

    public static void setVoice(Context context, String engine, int voiceId) {
        Speaker speaker = speaker(engine, voiceId);
        if (speaker == null) {
            return;
        }
        SharedPreferences.Editor editor = prefs(context).edit();
        if (speaker.language.isEmpty()) {
            String[] languages = ENGINE_COSYVOICE.equals(engine) ? COSY_LANGUAGES : KOKORO_LANGUAGES;
            for (String language : languages) {
                editor.putInt(voiceKey(engine, language), voiceId);
            }
        } else {
            editor.putInt(voiceKey(engine, speaker.language), voiceId);
        }
        editor.apply();
    }

    public static Speaker speaker(String engine, int voiceId) {
        Speaker[] voices = ENGINE_COSYVOICE.equals(engine) ? COSY_VOICES : KOKORO_VOICES;
        for (Speaker speaker : voices) {
            if (speaker.id == voiceId) {
                return speaker;
            }
        }
        return null;
    }

    public static List<Speaker> voices(String engine) {
        Speaker[] voices = ENGINE_COSYVOICE.equals(engine) ? COSY_VOICES : KOKORO_VOICES;
        ArrayList<Speaker> copy = new ArrayList<>(voices.length);
        Collections.addAll(copy, voices);
        return copy;
    }

    public static List<String> readyLanguages(Context context) {
        ArrayList<String> languages = new ArrayList<>();
        if (isReady(context, ENGINE_KOKORO)) {
            Collections.addAll(languages, KOKORO_LANGUAGES);
        }
        if (isReady(context, ENGINE_COSYVOICE)) {
            for (String language : COSY_LANGUAGES) {
                if (!languages.contains(language)) {
                    languages.add(language);
                }
            }
        }
        return languages;
    }

    public static boolean isLiveDraftDownload(DownloadGroupInfo group) {
        if (group == null || group.downloadsInfo == null || group.downloadsInfo.length == 0) {
            return false;
        }
        String name = group.downloadsInfo[0].getName();
        return KOKORO_ARCHIVE.equals(name) || "text_embedding_fp32.onnx".equals(name);
    }

    public static boolean isReadyGroup(Context context, DownloadGroupInfo group) {
        if (!isLiveDraftDownload(group)) {
            return false;
        }
        String name = group.downloadsInfo[0].getName();
        if (KOKORO_ARCHIVE.equals(name)) {
            return isReady(context, ENGINE_KOKORO);
        }
        return isReady(context, ENGINE_COSYVOICE);
    }

    /** Kokoro is part of the first-launch download. Later launches do not start it on their own. */
    public static ArrayList<DownloadGroupInfo> wantedDownloads(Context context) {
        ArrayList<DownloadGroupInfo> groups = new ArrayList<>();
        if (context.getSharedPreferences("default", Context.MODE_PRIVATE).getBoolean("firstStart", true)) {
            groups.add(engine(selected(context)).downloadInfo(context));
        }
        return groups;
    }

    public static File modelDir(Context context, String engine) {
        String folder = ENGINE_COSYVOICE.equals(engine) ? ENGINE_COSYVOICE : ENGINE_KOKORO;
        return new File(context.getFilesDir(), "LiveDraft/" + folder);
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

    public static File findDirectory(File dir, String name) {
        if (dir == null || !dir.isDirectory()) {
            return null;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return null;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                if (child.getName().equals(name)) {
                    return child;
                }
                File nested = findDirectory(child, name);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    private static boolean voiceSupports(String engine, int voiceId, String language) {
        Speaker speaker = speaker(engine, voiceId);
        return speaker != null && (speaker.language.isEmpty() || speaker.language.equals(language));
    }

    private static String voiceKey(String engine, String language) {
        return "liveDraftVoice." + engine + "." + language;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("default", Context.MODE_PRIVATE);
    }

    private static long cosyBytes() {
        long total = 0L;
        for (Asset asset : COSY_FILES) {
            total += asset.bytes;
        }
        return total;
    }

    public static final class Engine {
        public final String id;
        public final String[] languages;
        public final Speaker[] voices;
        public final long bytes;

        private Engine(String id, String[] languages, Speaker[] voices, long bytes) {
            this.id = id;
            this.languages = languages;
            this.voices = voices;
            this.bytes = bytes;
        }

        public int sizeMb() {
            return (int) Math.max(1L, bytes / (1024L * 1024L));
        }

        public DownloadGroupInfo downloadInfo(Context context) {
            String folder = modelDir(context, id).getAbsolutePath() + "/";
            if (ENGINE_KOKORO.equals(id)) {
                long kb = Math.max(1L, (KOKORO_BYTES + 1023L) / 1024L);
                return new DownloadGroupInfo(new DownloadInfo[]{
                        new DownloadInfo(KOKORO_ARCHIVE, KOKORO_BASE + KOKORO_ARCHIVE, folder, kb, false, true)
                });
            }
            DownloadInfo[] infos = new DownloadInfo[COSY_FILES.length];
            for (int i = 0; i < COSY_FILES.length; i++) {
                Asset asset = COSY_FILES[i];
                long kb = Math.max(1L, (asset.bytes + 1023L) / 1024L);
                infos[i] = new DownloadInfo(asset.localName, COSY_BASE + asset.remotePath, folder, kb, false, false);
            }
            return new DownloadGroupInfo(infos);
        }
    }

    public static final class Speaker {
        public final int id;
        public final String language;
        public final String name;
        public final String promptWav;
        public final String promptText;

        private Speaker(int id, String language, String name) {
            this(id, language, name, "", "");
        }

        private Speaker(int id, String language, String name, String promptWav, String promptText) {
            this.id = id;
            this.language = language;
            this.name = name;
            this.promptWav = promptWav;
            this.promptText = promptText;
        }

        public String label(Locale locale) {
            if (language == null || language.isEmpty()) {
                return name;
            }
            String spoken = new Locale(language).getDisplayLanguage(locale == null ? Locale.getDefault() : locale);
            if (spoken == null || spoken.isEmpty()) {
                return name;
            }
            return name + " · " + spoken;
        }
    }

    private static final class Asset {
        private final String remotePath;
        private final String localName;
        private final long bytes;

        private Asset(String remotePath, String localName, long bytes) {
            this.remotePath = remotePath;
            this.localName = localName;
            this.bytes = bytes;
        }
    }
}
