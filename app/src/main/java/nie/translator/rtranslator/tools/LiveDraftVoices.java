package nie.translator.rtranslator.tools;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import nie.translator.rtranslator.downloader.DownloadGroupInfo;
import nie.translator.rtranslator.downloader.DownloadInfo;

/**
 * Optional per-language Piper voices. A ready voice for a language replaces the system TTS for that language.
 * Archives come from the sherpa-onnx tts-models release and are extracted under files/LiveDraft/&lt;code&gt;/.
 */
public final class LiveDraftVoices {
    public static final String PREF_WANTED = "liveDraftLanguages";
    private static final String BASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/";

    private static final Voice[] VOICES = new Voice[]{
            new Voice("zh", "vits-piper-zh_CN-huayan-medium.tar.bz2", 67255926L),
            new Voice("en", "vits-piper-en_US-lessac-medium.tar.bz2", 67230653L),
            new Voice("de", "vits-piper-de_DE-thorsten-medium.tar.bz2", 67214254L),
            new Voice("fr", "vits-piper-fr_FR-siwis-medium.tar.bz2", 67207459L),
            new Voice("es", "vits-piper-es_ES-davefx-medium.tar.bz2", 67184952L),
            new Voice("ru", "vits-piper-ru_RU-irina-medium.tar.bz2", 67153308L),
            new Voice("it", "vits-piper-it_IT-paola-medium.tar.bz2", 67221173L),
            new Voice("pt", "vits-piper-pt_BR-faber-medium.tar.bz2", 67183065L),
            new Voice("pl", "vits-piper-pl_PL-gosia-medium.tar.bz2", 67211182L),
            new Voice("nl", "vits-piper-nl_NL-ronnie-medium.tar.bz2", 67194164L),
            new Voice("uk", "vits-piper-uk_UA-ukrainian_tts-medium.tar.bz2", 80355083L),
            new Voice("ar", "vits-piper-ar_JO-kareem-medium.tar.bz2", 67177830L),
            new Voice("hi", "vits-piper-hi_IN-pratham-medium.tar.bz2", 67238438L),
            new Voice("tr", "vits-piper-tr_TR-dfki-medium.tar.bz2", 67201221L),
            new Voice("ko", "vits-mimic3-ko_KO-kss_low.tar.bz2", 66838474L),
            new Voice("cs", "vits-piper-cs_CZ-jirka-medium.tar.bz2", 67212775L),
            new Voice("vi", "vits-piper-vi_VN-vais1000-medium.tar.bz2", 67154040L),
            new Voice("sv", "vits-piper-sv_SE-nst-medium.tar.bz2", 67144884L),
            new Voice("da", "vits-piper-da_DK-talesyntese-medium.tar.bz2", 67185257L),
            new Voice("el", "vits-piper-el_GR-rapunzelina-low.tar.bz2", 67105521L),
            new Voice("fi", "vits-piper-fi_FI-harri-medium.tar.bz2", 67205198L),
            new Voice("hu", "vits-piper-hu_HU-anna-medium.tar.bz2", 67167701L),
            new Voice("ro", "vits-piper-ro_RO-mihai-medium.tar.bz2", 67182137L),
            new Voice("sk", "vits-piper-sk_SK-lili-medium.tar.bz2", 67183092L),
            new Voice("sl", "vits-piper-sl_SI-artur-medium.tar.bz2", 67025668L),
            new Voice("ca", "vits-piper-ca_ES-upc_ona-medium.tar.bz2", 67223188L),
            new Voice("fa", "vits-piper-fa_IR-gyro-medium.tar.bz2", 67183308L),
            new Voice("id", "vits-piper-id_ID-news_tts-medium.tar.bz2", 67243848L),
            new Voice("no", "vits-piper-no_NO-talesyntese-medium.tar.bz2", 67194829L),
            new Voice("ne", "vits-piper-ne_NP-google-medium.tar.bz2", 80242801L),
            new Voice("sw", "vits-piper-sw_CD-lanfrica-medium.tar.bz2", 67217230L),
            new Voice("ka", "vits-piper-ka_GE-natia-medium.tar.bz2", 67209457L),
            new Voice("lv", "vits-piper-lv_LV-aivars-medium.tar.bz2", 67196173L),
            new Voice("lb", "vits-piper-lb_LU-marylux-medium.tar.bz2", 67184319L),
            new Voice("ml", "vits-piper-ml_IN-arjun-medium.tar.bz2", 67222458L),
    };

    private LiveDraftVoices() {
    }

    public static List<Voice> all() {
        ArrayList<Voice> voices = new ArrayList<>(VOICES.length);
        Collections.addAll(voices, VOICES);
        return voices;
    }

    public static Voice byCode(String language) {
        String code = normalize(language);
        for (Voice voice : VOICES) {
            if (voice.code.equals(code)) {
                return voice;
            }
        }
        return null;
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
        if ("nb".equals(code) || "nn".equals(code)) {
            return "no";
        }
        return code;
    }

    public static boolean isReady(Context context, String language) {
        Voice voice = byCode(language);
        if (voice == null) {
            return false;
        }
        File dir = voiceDir(context, voice.code);
        return findFile(dir, ".onnx") != null && findNamed(dir, "tokens.txt") != null;
    }

    public static boolean isLiveDraftDownload(DownloadGroupInfo group) {
        if (group == null || group.downloadsInfo == null || group.downloadsInfo.length == 0) {
            return false;
        }
        String name = group.downloadsInfo[0].getName();
        return name != null && name.startsWith("vits-") && name.endsWith(".tar.bz2");
    }

    public static boolean isReadyGroup(Context context, DownloadGroupInfo group) {
        if (!isLiveDraftDownload(group)) {
            return false;
        }
        for (Voice voice : VOICES) {
            if (voice.archive.equals(group.downloadsInfo[0].getName())) {
                return isReady(context, voice.code);
            }
        }
        return false;
    }

    public static Set<String> wanted(Context context) {
        SharedPreferences preferences = context.getSharedPreferences("default", Context.MODE_PRIVATE);
        Set<String> saved = preferences.getStringSet(PREF_WANTED, Collections.<String>emptySet());
        return new HashSet<>(saved);
    }

    public static void setWanted(Context context, String language, boolean want) {
        String code = normalize(language);
        if (code.isEmpty()) {
            return;
        }
        Set<String> next = wanted(context);
        if (want) {
            next.add(code);
        } else {
            next.remove(code);
        }
        context.getSharedPreferences("default", Context.MODE_PRIVATE).edit().putStringSet(PREF_WANTED, next).apply();
    }

    public static ArrayList<DownloadGroupInfo> wantedDownloads(Context context) {
        ArrayList<DownloadGroupInfo> groups = new ArrayList<>();
        Set<String> selected = wanted(context);
        for (Voice voice : VOICES) {
            if (selected.contains(voice.code)) {
                groups.add(voice.downloadInfo(context));
            }
        }
        return groups;
    }

    public static File voiceDir(Context context, String language) {
        return new File(context.getFilesDir(), "LiveDraft/" + normalize(language));
    }

    public static File findFile(File dir, String suffix) {
        if (dir == null || !dir.isDirectory()) {
            return null;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return null;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                File nested = findFile(child, suffix);
                if (nested != null) {
                    return nested;
                }
            } else if (child.getName().endsWith(suffix) && child.length() > 0) {
                return child;
            }
        }
        return null;
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

    public static final class Voice {
        public final String code;
        public final String archive;
        public final long bytes;

        private Voice(String code, String archive, long bytes) {
            this.code = code;
            this.archive = archive;
            this.bytes = bytes;
        }

        public int sizeMb() {
            return (int) Math.max(1L, bytes / (1024L * 1024L));
        }

        public String displayName(Locale locale) {
            String name = new Locale(code).getDisplayLanguage(locale);
            if (name == null || name.isEmpty() || name.equalsIgnoreCase(code)) {
                return code;
            }
            return name;
        }

        public DownloadGroupInfo downloadInfo(Context context) {
            String folder = voiceDir(context, code).getAbsolutePath() + "/";
            long kb = Math.max(1L, (bytes + 1023L) / 1024L);
            return new DownloadGroupInfo(new DownloadInfo[]{
                    new DownloadInfo(archive, BASE + archive, folder, kb, false, true)
            });
        }
    }
}
