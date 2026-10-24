/*
 * Copyright 2016 Luca Martino.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copyFile of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nie.translator.rtranslator;

import android.Manifest;
import android.app.ActivityManager;
import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import com.konovalov.vad.silero.Vad;
import com.konovalov.vad.silero.VadSilero;
import com.konovalov.vad.silero.config.FrameSize;
import com.konovalov.vad.silero.config.Mode;
import com.konovalov.vad.silero.config.SampleRate;

import java.io.File;
import java.util.ArrayList;

import nie.translator.rtranslator.access.AccessActivity;
import nie.translator.rtranslator.bluetooth.BluetoothCommunicator;
import nie.translator.rtranslator.bluetooth.Peer;
import nie.translator.rtranslator.downloader.DownloadGroupInfo;
import nie.translator.rtranslator.downloader.DownloadInfo;
import nie.translator.rtranslator.downloader.DownloadManager;
import nie.translator.rtranslator.tools.CustomLocale;
import nie.translator.rtranslator.tools.ErrorCodes;
import nie.translator.rtranslator.tools.ModelStartupGate;
import nie.translator.rtranslator.tools.SessionInitGate;

import nie.translator.rtranslator.tools.TTS;
import nie.translator.rtranslator.voice_translation._conversation_mode._conversation.ConversationService;
import nie.translator.rtranslator.voice_translation._conversation_mode.communication.ConversationBluetoothCommunicator;
import nie.translator.rtranslator.voice_translation._conversation_mode.communication.recent_peer.RecentPeersDataManager;
import nie.translator.rtranslator.voice_translation._text_translation.TranslationFragment;
import nie.translator.rtranslator.voice_translation.neural_networks.NeuralNetworkApi;
import nie.translator.rtranslator.voice_translation.neural_networks.translation.Translator;
import nie.translator.rtranslator.voice_translation.neural_networks.voice.MicSensitivity;
import nie.translator.rtranslator.voice_translation.neural_networks.voice.Recognizer;
import nie.translator.rtranslator.voice_translation.neural_networks.voice.Recorder;


public class Global extends Application implements DefaultLifecycleObserver {
    public enum RTranslatorMode {
        TEXT_TRANSLATION_MODE,
        WALKIE_TALKIE_MODE,
        CONVERSATION_MODE,
        SIMULTANEOUS_MODE
    }

    public enum LanguageNumber {
        FIRST,
        SECOND
    }
    public static final boolean ONLY_TEXT_TRANSLATION_MODE = false;
    public static final boolean USE_EXTERNAL_MEMORY_FOR_RESOURCES = false;

    public static final int REQUEST_CODE_PERMISSIONS_NOTIFICATIONS = 5;
    public static String[] REQUIRED_PERMISSIONS_NOTIFICATIONS;
    public static final int REQUEST_CODE_PERMISSIONS_PAIRING = 2;
    public static String[] REQUIRED_PERMISSIONS_PAIRING;
    public static final int REQUEST_CODE_PERMISSIONS_CONVERSATION = 3;
    public static final int REQUEST_CODE_PERMISSIONS_WALKIETALKIE = 4;
    public static final int REQUEST_CODE_PERMISSIONS_SIMULTANEOUS = 6;
    public static final String[] REQUIRED_PERMISSIONS_VOICE = new String[]{
            Manifest.permission.RECORD_AUDIO,
    };
    private ArrayList<CustomLocale> languages = new ArrayList<>();
    private ArrayList<CustomLocale> translatorLanguages = new ArrayList<>();
    private ArrayList<Translator.MozillaLanguageInfo> mozillaLanguages = new ArrayList<>();
    private ArrayList<CustomLocale> mozillaInstalledLanguages = new ArrayList<>();
    private ArrayList<CustomLocale> ttsLanguages = new ArrayList<>();
    private CustomLocale language;
    private CustomLocale firstLanguage;
    private CustomLocale secondLanguage;
    private CustomLocale firstTextLanguage;
    private CustomLocale secondTextLanguage;
    private CustomLocale simultaneousSourceLanguage;
    private CustomLocale simultaneousTargetLanguage;
    private RecentPeersDataManager recentPeersDataManager;
    private ConversationBluetoothCommunicator bluetoothCommunicator;
    @Nullable
    private Translator translator;
    @Nullable
    private Recognizer speechRecognizer;
    private String name = "";
    private int micSensitivity = -1;
    private boolean micSensitivityExtended;
    private float ttsSpeechRate = -1f;
    private int speechTimeout = -1;
    private int prevVoiceDuration = -1;
    private int beamSize = -1;
    private int amplitudeThreshold = Recorder.DEFAULT_AMPLITUDE_THRESHOLD;
    private boolean isForeground = false;
    @Nullable
    private AccessActivity accessActivity;
    private Handler mainHandler;
    private static Handler mHandler = new Handler();
    private final Object lock = new Object();
    private boolean useTatoeba;
    private boolean simultaneousVadSegmentation;
    private boolean useTranslationDictionaries;
    private boolean useMozillaForVoiceTranslation;
    private boolean whisperReducedRam;
    private int translationMode;
    public static final int ASR_WHISPER = 0;
    public static final int ASR_QWEN3_06 = 1;
    public static final int ASR_QWEN3_17 = 2;
    public static final int ASR_WHISPER_TURBO = 3;
    public static final int ASR_QUANT_INT4 = 0;
    public static final int ASR_QUANT_INT8 = 1;
    public static final int ASR_QUANT_FP16 = 2;
    public static final int ASR_QUANT_FP32 = 3;
    public static final String GEMMA_THINKING_OFF = "off";
    public static final String GEMMA_THINKING_LOW = "low";
    public static final String GEMMA_THINKING_MEDIUM = "medium";
    public static final String GEMMA_THINKING_HIGH = "high";
    private static final int[] WHISPER_QUANTS = {ASR_QUANT_INT4, ASR_QUANT_INT8, ASR_QUANT_FP16, ASR_QUANT_FP32};
    private static final int[] QWEN_QUANTS = {ASR_QUANT_INT4, ASR_QUANT_FP32};
    private static final long WHISPER_VOCAB_BYTES = 1036558L;
    private int asrModel;
    @Nullable
    private String runningAsrSignature;
    private VadSilero vad;
    private final ModelStartupGate modelStartup = new ModelStartupGate();
    private final SessionInitGate translatorInit = new SessionInitGate();
    private final SessionInitGate speechInit = new SessionInitGate();

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
        recentPeersDataManager = new RecentPeersDataManager(this);
        //initializeBluetoothCommunicator();
        getMicSensitivity();
        createNotificationChannel();
        SharedPreferences sharedPreferences = getSharedPreferences("default", Context.MODE_PRIVATE);
        useTatoeba = sharedPreferences.getBoolean("useTatoeba", false);
        // Absent key means a new install: simultaneous interpretation uses the downloadable Silero VAD.
        // LoadingActivity turns this off when the file was never downloaded, so older installs stay on the pause splitter.
        simultaneousVadSegmentation = sharedPreferences.getBoolean("simultaneousVadSegmentation", true);
        useTranslationDictionaries = sharedPreferences.getBoolean("useTranslationDictionaries", false);
        useMozillaForVoiceTranslation = sharedPreferences.getBoolean("useMozillaForVoiceTranslation", false);
        whisperReducedRam = sharedPreferences.getBoolean("whisperReducedRam", getTotalRamSize() <= 7000);
        translationMode = sharedPreferences.getInt("selectedTranslationModel", Translator.MOZILLA);
        asrModel = sharedPreferences.getInt("selectedAsrModel", ASR_WHISPER);

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
            REQUIRED_PERMISSIONS_PAIRING = new String[]{
                    Manifest.permission.BLUETOOTH,
                    Manifest.permission.BLUETOOTH_ADMIN,
                    Manifest.permission.ACCESS_FINE_LOCATION,
            };
        }else{
            REQUIRED_PERMISSIONS_PAIRING = new String[]{
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
            };
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            REQUIRED_PERMISSIONS_NOTIFICATIONS = new String[]{
                    Manifest.permission.POST_NOTIFICATIONS
            };
        }else{
            REQUIRED_PERMISSIONS_NOTIFICATIONS = new String[0];
        }
    }

    public void initializeTranslator(final Translator.GeneralListener initListener) {
        // The Translator object exists before its sessions do. A second caller waits for that attempt.
        SessionInitGate.Join join = translatorInit.join(new SessionInitGate.Listener() {
            @Override
            public void onSuccess() {
                initListener.onSuccess();
            }

            @Override
            public void onFailure(int[] reasons, long value) {
                initListener.onFailure(reasons, value);
            }
        });
        if (!join.start) {
            return;
        }
        final int generation = join.generation;
        translator = new Translator(this, getTranslationMode(), isUseMozillaForVoiceTranslation(), isUseTatoeba(), isUseTranslationDictionaries(), new Translator.GeneralListener() {
            @Override
            public void onSuccess() {
                translatorInit.succeed(generation);
            }

            @Override
            public void onFailure(int[] reasons, long value) {
                translatorInit.fail(generation, reasons, value);
            }
        });
    }

    public void initializeSpeechRecognizer(NeuralNetworkApi.InitListener initListener){
        initializeSpeechRecognizer(initListener, true);
    }

    /**
     * @param async false runs session creation on the caller thread. The apply-settings path uses that so stop, close, and open stay on one background thread.
     */
    public void initializeSpeechRecognizer(final NeuralNetworkApi.InitListener initListener, boolean async) {
        // Same constraint as the translator: non-null does not mean the sessions are ready.
        SessionInitGate.Join join = speechInit.join(new SessionInitGate.Listener() {
            @Override
            public void onSuccess() {
                initListener.onInitializationFinished();
            }

            @Override
            public void onFailure(int[] reasons, long value) {
                initListener.onError(reasons, value);
            }
        });
        if (!join.start) {
            return;
        }
        final int generation = join.generation;
        try {
            vad = Vad.builder()
                    .setContext(this)
                    .setSampleRate(SampleRate.SAMPLE_RATE_16K)
                    .setFrameSize(FrameSize.FRAME_SIZE_512)
                    .setMode(Mode.NORMAL)
                    .setSilenceDurationMs(300)
                    .setSpeechDurationMs(50)
                    .build();
            speechRecognizer = new Recognizer(this, true, new NeuralNetworkApi.InitListener() {
                @Override
                public void onInitializationFinished() {
                    speechInit.succeed(generation);
                }

                @Override
                public void onError(int[] reasons, long value) {
                    speechInit.fail(generation, reasons, value);
                }
            }, async);
        } catch (RuntimeException e) {
            speechInit.fail(generation, new int[]{ErrorCodes.ERROR_LOADING_MODEL}, 0);
        }
    }

    public void initializeBluetoothCommunicator(){
        if(bluetoothCommunicator == null){
            bluetoothCommunicator = new ConversationBluetoothCommunicator(this, getName(), BluetoothCommunicator.STRATEGY_P2P_WITH_RECONNECTION);
        }
    }

    public void destroyBluetoothCommunicator(@Nullable BluetoothCommunicator.DestroyCallback listener){
        if(bluetoothCommunicator != null){
            bluetoothCommunicator.destroy(new BluetoothCommunicator.DestroyCallback() {
                @Override
                public void onDestroyed() {
                    if(listener != null) listener.onDestroyed();
                    bluetoothCommunicator = null;
                }
            });
        }else{
            if(listener != null) listener.onDestroyed();
        }
    }

    @Nullable
    public ConversationBluetoothCommunicator getBluetoothCommunicator() {
        return bluetoothCommunicator;
    }

    public VadSilero getVad() {
        return vad;
    }

    public boolean areModelsLoaded() {
        return modelStartup.isReady();
    }

    public void setModelsLoaded(boolean areModelsLoaded) {
        if (areModelsLoaded) {
            modelStartup.completeSuccess(modelStartup.currentRunId());
        }
    }

    public ModelStartupGate.Join beginOrJoinModelStartup(ModelStartupGate.Observer observer) {
        return modelStartup.beginOrJoin(observer);
    }

    public void removeModelStartupObserver(ModelStartupGate.Observer observer) {
        modelStartup.remove(observer);
    }

    public void completeModelStartup(int runId) {
        modelStartup.completeSuccess(runId);
    }

    public void failModelStartup(int runId, int[] reasons, long value) {
        modelStartup.completeFailure(runId, reasons, value);
    }

    public void clearModelStartupFailure() {
        modelStartup.clearFailure();
    }

    public boolean hasModelStartupFailure() {
        return modelStartup.hasFailure();
    }

    public int[] getModelStartupFailureReasons() {
        return modelStartup.failureReasons();
    }

    public long getModelStartupFailureValue() {
        return modelStartup.failureValue();
    }

    public boolean isTranslatorInitRunning() {
        return translatorInit.isRunning();
    }

    public boolean isTranslatorInitReady() {
        return translatorInit.isReady();
    }

    public boolean isSpeechInitRunning() {
        return speechInit.isRunning();
    }

    public boolean isSpeechInitReady() {
        return speechInit.isReady();
    }

    public void getLanguagesAndCheckTTS(final boolean recycleResult, boolean ignoreTTSError, final GetLocalesListListener responseListener) {
        if (recycleResult && !languages.isEmpty()) {
            responseListener.onSuccess(languages);
        } else {
            TTS.getSupportedLanguages(this, new TTS.SupportedLanguagesListener() {    //we load TTS languages to catch eventual TTS errors
                @Override
                public void onLanguagesListAvailable(ArrayList<CustomLocale> ttsLanguages) {
                    // The language tables are built on the translator thread. This callback only reports TTS readiness.
                    responseListener.onSuccess(ttsLanguages);
                }

                @Override
                public void onError(int reason) {
                    responseListener.onFailure(new int[]{reason}, 0);
                }
            });
        }
    }

    public ArrayList<CustomLocale> getLanguages(RTranslatorMode rtranslatorMode, final boolean recycleResult) {
        ArrayList<CustomLocale> translatorLanguages = getTranslatorLanguages(rtranslatorMode, recycleResult);
        ArrayList<CustomLocale> speechRecognizerLanguages = Recognizer.getSupportedLanguages(Global.this);
        //we return only the languages compatible with the speech recognizer and the translator (without loading TTS languages)
        final ArrayList<CustomLocale> compatibleLanguages = new ArrayList<>();
        for (CustomLocale translatorLanguage : translatorLanguages) {
            if (CustomLocale.containsLanguage(speechRecognizerLanguages, translatorLanguage)) {
                compatibleLanguages.add(translatorLanguage);
            }
        }
        languages = compatibleLanguages;
        return compatibleLanguages;
    }

    public ArrayList<CustomLocale> getTranslatorLanguages(RTranslatorMode rtranslatorMode, final boolean recycleResult) {
        if (recycleResult) {
            if(isUseMozillaForVoiceTranslation() && isVoiceMode(rtranslatorMode)) {
                return getMozillaLanguages(true);
            }else{
                if(!translatorLanguages.isEmpty()) return translatorLanguages;
            }
        }

        // recycleResult is false
        SharedPreferences sharedPreferences = getSharedPreferences("default", Context.MODE_PRIVATE);
        int mode = translator != null ? translator.getMode() : -1;
        if (mode < 0) {
            mode = sharedPreferences.getInt("selectedTranslationModel", Translator.MOZILLA);
        }
        boolean qualityLow = sharedPreferences.getBoolean("languagesNNQualityLow", false);
        ArrayList<CustomLocale> languages = Translator.getSupportedLanguages(Global.this, qualityLow, mode);  //in the case of mozilla, this method will return all the supported languages, not only the installed ones, so for mozilla we will use the getMozillaLanguages method
        ArrayList<CustomLocale> mozillaLanguages = getMozillaLanguages(false);
        if(mode == Translator.MOZILLA) {
            translatorLanguages = mozillaLanguages;
            return mozillaLanguages;
        }else{
            translatorLanguages = languages;
            if(isUseMozillaForVoiceTranslation() && isVoiceMode(rtranslatorMode)) {
                return mozillaLanguages;
            }else{
                return languages;
            }
        }
    }

    /**
     * getMozillaLanguages
     * @param recycleResult decides if recycle the result or not
     * @return the list of installed Mozilla languages
     */
    private ArrayList<CustomLocale> getMozillaLanguages(final boolean recycleResult){
        if (recycleResult && !mozillaInstalledLanguages.isEmpty()) {
            return mozillaInstalledLanguages;
        } else {
            ArrayList<DownloadGroupInfo> downloads = DownloadManager.getSavedDownloadStatus(this);
            ArrayList<MozillaLanguageDownloadInfo> mozillaLanguageDownloadInfos = getMozillaLanguagesDownloadInfo(recycleResult);
            ArrayList<CustomLocale> mozillaInstalledLanguages = new ArrayList<>();
            for (Global.MozillaLanguageDownloadInfo langDownloadInfo : mozillaLanguageDownloadInfos) {
                int index = downloads.indexOf(langDownloadInfo.downloadGroupInfo);
                if (index != -1 && downloads.get(index).isAllDownloadCompleted()) {
                    mozillaInstalledLanguages.add(langDownloadInfo.lang);
                }
            }
            mozillaInstalledLanguages.add(new CustomLocale("en"));  //English is always present, but it isn't present in the getMozillaLanguagesDownloadInfo list, so we add it manually
            this.mozillaInstalledLanguages = mozillaInstalledLanguages;
            return mozillaInstalledLanguages;
        }
    }

    /**
     * getMozillaLanguages
     * @param recycleResult decides if recycle the result or not
     * @return the list of all supported Mozilla languages (installed and not) with also the size of each language model
     */
    public ArrayList<Translator.MozillaLanguageInfo> getMozillaLanguagesInfo(final boolean recycleResult) {
        if (recycleResult && !mozillaLanguages.isEmpty()) {
            return mozillaLanguages;
        } else {
            ArrayList<Translator.MozillaLanguageInfo> languages = Translator.getMozillaLanguagesInfo(this);
            mozillaLanguages = languages;
            return languages;
        }
    }

    public void getTTSLanguages(final boolean recycleResult, final GetLocalesListListener responseListener){
        if(recycleResult && !ttsLanguages.isEmpty()){
            responseListener.onSuccess(ttsLanguages);
        }else{
            TTS.getSupportedLanguages(this, new TTS.SupportedLanguagesListener() {    //we load TTS languages to catch eventual TTS errors
                @Override
                public void onLanguagesListAvailable(ArrayList<CustomLocale> ttsLanguages) {
                    Global.this.ttsLanguages = ttsLanguages;
                    responseListener.onSuccess(ttsLanguages);
                }

                @Override
                public void onError(int reason) {
                    responseListener.onSuccess(new ArrayList<CustomLocale>());
                }
            });
        }
    }


    public ArrayList<MozillaLanguageDownloadInfo> getMozillaLanguagesDownloadInfo(final boolean recycleResult){
        String downloadFolder = getFilesDir().getAbsolutePath()+"/Translation/Mozilla/";
        String baseUrl = "https://github.com/niedev/OnnxModelsEnhancer/releases/download/v1.0.0-beta/";
        ArrayList<MozillaLanguageDownloadInfo> languageDownloadInfos = new ArrayList<>();
        for(Translator.MozillaLanguageInfo langInfo : getMozillaLanguagesInfo(recycleResult)){
            String fileName = "Mozilla_"+langInfo.lang.getLanguage()+".zip";
            languageDownloadInfos.add(new MozillaLanguageDownloadInfo(
                    langInfo.lang,
                    new DownloadGroupInfo(new DownloadInfo[]{new DownloadInfo(fileName, baseUrl+fileName, downloadFolder, langInfo.sizeKb, false, true)})
            ));
        }
        return languageDownloadInfos;
    }

    public static class MozillaLanguageDownloadInfo {
        public CustomLocale lang;
        public DownloadGroupInfo downloadGroupInfo;

        public MozillaLanguageDownloadInfo(CustomLocale lang, DownloadGroupInfo downloadGroupInfo) {
            this.lang = lang;
            this.downloadGroupInfo = downloadGroupInfo;
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if(obj instanceof CustomLocale){
                return lang.equals(obj);
            }
            return super.equals(obj);
        }
    }

    public DownloadGroupInfo getHyMtDownloadInfo(){
        String downloadFolder = getFilesDir().getAbsolutePath();
        String baseUrl = "https://github.com/niedev/OnnxModelsEnhancer/releases/download/v1.0.0-beta/";
        return new DownloadGroupInfo(new DownloadInfo[]{
                new DownloadInfo(
                        "HY-MT.zip",
                        baseUrl + "HY-MT.zip",
                        downloadFolder + "/Translation/",
                        1790000,
                        true,
                        true)
        });
    }

    public DownloadGroupInfo getTranslateGemmaDownloadInfo() {
        String downloadFolder = getFilesDir().getAbsolutePath() + "/Translation/TranslateGemma";
        return new DownloadGroupInfo(new DownloadInfo[]{
                new DownloadInfo(
                        "translategemma-4b-it.Q4_K_M.gguf",
                        "https://huggingface.co/mradermacher/translategemma-4b-it-GGUF/resolve/main/translategemma-4b-it.Q4_K_M.gguf",
                        downloadFolder,
                        2431600,
                        false,
                        false)
        });
    }

    public DownloadGroupInfo getQwen35DownloadInfo() {
        String downloadFolder = getFilesDir().getAbsolutePath() + "/Translation/Qwen35";
        return new DownloadGroupInfo(new DownloadInfo[]{
                new DownloadInfo(
                        "Qwen3.5-0.8B-Q4_K_M.gguf",
                        "https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/main/Qwen3.5-0.8B-Q4_K_M.gguf",
                        downloadFolder,
                        545792,
                        false,
                        false)
        });
    }

    public DownloadGroupInfo getMadladDownloadInfo(){
        String downloadFolder = getFilesDir().getAbsolutePath();
        String baseUrl = "https://github.com/niedev/OnnxModelsEnhancer/releases/download/v1.0.0-beta/";
        return new DownloadGroupInfo(new DownloadInfo[]{
                new DownloadInfo(
                        "Madlad.zip",
                        baseUrl + "Madlad.zip",
                        downloadFolder + "/Translation/",
                        1380000,
                        true,
                        true)

        });
    }

    public DownloadGroupInfo getSileroVadDownloadInfo() {
        String folder = getFilesDir().getAbsolutePath() + "/VAD";
        // Pinned to Silero VAD v6.2.1. 2327524 bytes, shown as kilobytes for the progress bar.
        return new DownloadGroupInfo(new DownloadInfo[]{
                new DownloadInfo(
                        "silero_vad.onnx",
                        "https://github.com/snakers4/silero-vad/raw/v6.2.1/src/silero_vad/data/silero_vad.onnx",
                        folder,
                        2273,
                        false,
                        false)
        });
    }

    public File getSileroVadFile() {
        return new File(getFilesDir(), "VAD/silero_vad.onnx");
    }

    public DownloadGroupInfo getTatoebaDownloadInfo(){
        String downloadFolder = getFilesDir().getAbsolutePath();
        String baseUrl = "https://github.com/niedev/OnnxModelsEnhancer/releases/download/v1.0.0-beta/";
        return new DownloadGroupInfo(new DownloadInfo[]{
                new DownloadInfo(
                        "Tatoeba.zip",
                        baseUrl + "Tatoeba.zip",
                        downloadFolder + "/Translation/",
                        596000,
                        false,
                        true)

        });
    }

    public DownloadGroupInfo getNllbDownloadInfo(){
        String downloadFolder = getFilesDir().getAbsolutePath();
        String baseUrl = "https://github.com/niedev/RTranslator/releases/download/2.0.0/";
        return new DownloadGroupInfo(new DownloadInfo[]{
                new DownloadInfo(
                        "NLLB_cache_initializer.onnx",
                        baseUrl + "NLLB_cache_initializer.onnx",
                        downloadFolder,
                        24000,
                        true,
                        false
                ),
                new DownloadInfo(
                        "NLLB_decoder.onnx",
                        baseUrl + "NLLB_decoder.onnx",
                        downloadFolder,
                        171000,
                        true,
                        false
                ),
                new DownloadInfo(
                        "NLLB_embed_and_lm_head.onnx",
                        baseUrl + "NLLB_embed_and_lm_head.onnx",
                        downloadFolder,
                        500000,
                        true,
                        false
                ),
                new DownloadInfo(
                        "NLLB_encoder.onnx",
                        baseUrl + "NLLB_encoder.onnx",
                        downloadFolder,
                        254000,
                        true,
                        false
                ),
        });
    }

    public DownloadGroupInfo getInitialDownloadInfo(){
        String downloadFolder = getFilesDir().getAbsolutePath();
        String baseUrl = "https://github.com/niedev/RTranslator/releases/download/2.0.0/";
        String baseUrlAlt = "https://github.com/niedev/OnnxModelsEnhancer/releases/download/v1.0.0-beta/";
        return new DownloadGroupInfo(new DownloadInfo[]{
                /*new DownloadInfo(
                        "NLLB_cache_initializer.onnx",
                        baseUrl + "NLLB_cache_initializer.onnx",
                        downloadFolder,
                        24000,
                        true,
                        false
                ),
                new DownloadInfo(
                        "NLLB_decoder.onnx",
                        baseUrl + "NLLB_decoder.onnx",
                        downloadFolder,
                        171000,
                        true,
                        false
                ),
                new DownloadInfo(
                        "NLLB_embed_and_lm_head.onnx",
                        baseUrl + "NLLB_embed_and_lm_head.onnx",
                        downloadFolder,
                        500000,
                        true,
                        false
                ),
                new DownloadInfo(
                        "NLLB_encoder.onnx",
                        baseUrl + "NLLB_encoder.onnx",
                        downloadFolder,
                        254000,
                        true,
                        false
                ),*/
                new DownloadInfo(
                        "Whisper_cache_initializer.onnx",
                        baseUrl + "Whisper_cache_initializer.onnx",
                        downloadFolder,
                        14000,
                        true,
                        false
                ),
                new DownloadInfo(
                        "Whisper_cache_initializer_batch.onnx",
                        baseUrl + "Whisper_cache_initializer_batch.onnx",
                        downloadFolder,
                        14000,
                        true,
                        false
                ),
                new DownloadInfo(
                        "Whisper_decoder.onnx",
                        baseUrl + "Whisper_decoder.onnx",
                        downloadFolder,
                        173000,
                        true,
                        false
                ),
                new DownloadInfo(
                        "Whisper_detokenizer.onnx",
                        baseUrl + "Whisper_detokenizer.onnx",
                        downloadFolder,
                        461,
                        true,
                        false
                ),
                new DownloadInfo(
                        "Whisper_encoder.onnx",
                        baseUrl + "Whisper_encoder.onnx",
                        downloadFolder,
                        88000,
                        true,
                        false
                ),
                new DownloadInfo(
                        "Whisper_initializer.onnx",
                        baseUrl + "Whisper_initializer.onnx",
                        downloadFolder,
                        69,
                        true,
                        false
                ),
                new DownloadInfo(
                        "TranslationDictionaries.zip",
                        baseUrlAlt + "TranslationDictionaries.zip",
                        downloadFolder + "/Translation/",
                        108000,
                        false,
                        true
                ),
        });
    }

    public DownloadGroupInfo getWhisperDownloadInfo() {
        return whisperOnnxDownload(ASR_WHISPER, getWhisperSmallDirectory(), "https://huggingface.co/onnx-community/whisper-small/resolve/main/");
    }

    public static int[] asrQuantChoices(int model) {
        if (model == ASR_QWEN3_06 || model == ASR_QWEN3_17) {
            return QWEN_QUANTS.clone();
        }
        return WHISPER_QUANTS.clone();
    }

    public static String asrQuantLabel(int quant) {
        switch (quant) {
            case ASR_QUANT_INT8:
                return "int8";
            case ASR_QUANT_FP16:
                return "fp16";
            case ASR_QUANT_FP32:
                return "fp32";
            default:
                return "int4";
        }
    }

    public static String whisperEncoderName(int quant) {
        switch (quant) {
            case ASR_QUANT_INT8:
                return "encoder_model_int8.onnx";
            case ASR_QUANT_FP16:
                return "encoder_model_fp16.onnx";
            case ASR_QUANT_FP32:
                return "encoder_model.onnx";
            default:
                return "encoder_model_q4.onnx";
        }
    }

    public static String whisperDecoderName(int quant) {
        switch (quant) {
            case ASR_QUANT_INT8:
                return "decoder_model_merged_int8.onnx";
            case ASR_QUANT_FP16:
                return "decoder_model_merged_fp16.onnx";
            case ASR_QUANT_FP32:
                return "decoder_model_merged.onnx";
            default:
                return "decoder_model_merged_q4.onnx";
        }
    }

    public int getAsrQuant(int model) {
        SharedPreferences prefs = getSharedPreferences("default", Context.MODE_PRIVATE);
        String key = asrQuantKey(model);
        if (!prefs.contains(key)) {
            // Turbo used to download only the int8 pair. Keep that install until a level is chosen.
            if (model == ASR_WHISPER_TURBO && whisperQuantPresent(model, ASR_QUANT_INT8) && !whisperQuantPresent(model, ASR_QUANT_INT4)) {
                return ASR_QUANT_INT8;
            }
            return ASR_QUANT_INT4;
        }
        int quant = prefs.getInt(key, ASR_QUANT_INT4);
        for (int choice : asrQuantChoices(model)) {
            if (choice == quant) {
                return quant;
            }
        }
        return ASR_QUANT_INT4;
    }

    private boolean whisperQuantPresent(int model, int quant) {
        String folder = model == ASR_WHISPER_TURBO ? getWhisperTurboDirectory() : getWhisperSmallDirectory();
        File encoder = new File(folder, whisperEncoderName(quant));
        File decoder = new File(folder, whisperDecoderName(quant));
        File vocab = new File(folder, "vocab.json");
        if (!encoder.isFile() || encoder.length() == 0 || !decoder.isFile() || decoder.length() == 0 || !vocab.isFile() || vocab.length() == 0) {
            return false;
        }
        if (quant == ASR_QUANT_FP32 && model == ASR_WHISPER_TURBO) {
            File data = new File(folder, "encoder_model.onnx_data");
            return data.isFile() && data.length() > 0;
        }
        return true;
    }

    public void setAsrQuant(int model, int quant) {
        getSharedPreferences("default", Context.MODE_PRIVATE).edit().putInt(asrQuantKey(model), quant).apply();
    }

    public void markAsrRunning() {
        runningAsrSignature = asrSignature();
    }

    private String asrSignature() {
        if (asrModel == ASR_WHISPER && !groupFilesPresent(getWhisperDownloadInfo()) && legacyWhisperPresent()) {
            return "whisper-legacy";
        }
        return asrModel + ":" + getAsrQuant(asrModel);
    }

    private static String asrQuantKey(int model) {
        switch (model) {
            case ASR_QWEN3_06:
                return "asrQuantQwen06";
            case ASR_QWEN3_17:
                return "asrQuantQwen17";
            case ASR_WHISPER_TURBO:
                return "asrQuantWhisperTurbo";
            default:
                return "asrQuantWhisper";
        }
    }

    public int getAsrModel() {
        return asrModel;
    }

    public String getAsrModelDirectory() {
        if (asrModel == ASR_QWEN3_17) {
            return getFilesDir().getAbsolutePath() + "/ASR/Qwen3-ASR-1.7B";
        }
        return getFilesDir().getAbsolutePath() + "/ASR/Qwen3-ASR-0.6B";
    }

    public String getWhisperSmallDirectory() {
        return getFilesDir().getAbsolutePath() + "/ASR/Whisper-Small";
    }

    public String getWhisperTurboDirectory() {
        return getFilesDir().getAbsolutePath() + "/ASR/Whisper-Large-V3-Turbo";
    }

    public DownloadGroupInfo getAsrDownloadInfo(int model) {
        if (model == ASR_QWEN3_06) {
            return getQwen3AsrDownloadInfo(false);
        }
        if (model == ASR_QWEN3_17) {
            return getQwen3AsrDownloadInfo(true);
        }
        if (model == ASR_WHISPER_TURBO) {
            return getWhisperTurboDownloadInfo();
        }
        return getWhisperDownloadInfo();
    }

    public DownloadGroupInfo getWhisperTurboDownloadInfo() {
        return whisperOnnxDownload(ASR_WHISPER_TURBO, getWhisperTurboDirectory(), "https://huggingface.co/onnx-community/whisper-large-v3-turbo/resolve/main/");
    }

    private DownloadGroupInfo whisperOnnxDownload(int model, String folder, String base) {
        int quant = getAsrQuant(model);
        boolean turbo = model == ASR_WHISPER_TURBO;
        String encoder = whisperEncoderName(quant);
        String decoder = whisperDecoderName(quant);
        long encoderBytes;
        long decoderBytes;
        long dataBytes = 0;
        if (turbo) {
            switch (quant) {
                case ASR_QUANT_INT8:
                    encoderBytes = 644822094L;
                    decoderBytes = 439936716L;
                    break;
                case ASR_QUANT_FP16:
                    encoderBytes = 1274342603L;
                    decoderBytes = 344227339L;
                    break;
                case ASR_QUANT_FP32:
                    encoderBytes = 439254L;
                    decoderBytes = 688031263L;
                    dataBytes = 2547875840L;
                    break;
                default:
                    encoderBytes = 424942775L;
                    decoderBytes = 334147222L;
                    break;
            }
        } else {
            switch (quant) {
                case ASR_QUANT_INT8:
                    encoderBytes = 92326127L;
                    decoderBytes = 156750845L;
                    break;
                case ASR_QUANT_FP16:
                    encoderBytes = 176607756L;
                    decoderBytes = 308583076L;
                    break;
                case ASR_QUANT_FP32:
                    encoderBytes = 352825870L;
                    decoderBytes = 615324301L;
                    break;
                default:
                    encoderBytes = 66182104L;
                    decoderBytes = 233149327L;
                    break;
            }
        }
        ArrayList<DownloadInfo> files = new ArrayList<>();
        files.add(new DownloadInfo(encoder, base + "onnx/" + encoder, folder, kb(encoderBytes), false, false));
        if (dataBytes > 0) {
            files.add(new DownloadInfo("encoder_model.onnx_data", base + "onnx/encoder_model.onnx_data", folder, kb(dataBytes), false, false));
        }
        files.add(new DownloadInfo(decoder, base + "onnx/" + decoder, folder, kb(decoderBytes), false, false));
        files.add(new DownloadInfo("vocab.json", base + "vocab.json", folder, kb(WHISPER_VOCAB_BYTES), false, false));
        return new DownloadGroupInfo(files.toArray(new DownloadInfo[0]));
    }

    public DownloadGroupInfo getQwen3Asr06DownloadInfo() {
        return getQwen3AsrDownloadInfo(false);
    }

    public DownloadGroupInfo getQwen3Asr17DownloadInfo() {
        return getQwen3AsrDownloadInfo(true);
    }

    private DownloadGroupInfo getQwen3AsrDownloadInfo(boolean large) {
        String folder = getFilesDir().getAbsolutePath() + (large ? "/ASR/Qwen3-ASR-1.7B" : "/ASR/Qwen3-ASR-0.6B");
        String repo = large ? "andrewleech/qwen3-asr-1.7b-onnx" : "andrewleech/qwen3-asr-0.6b-onnx";
        String base = "https://huggingface.co/" + repo + "/resolve/main/";
        // This export publishes int4 and fp32. The int4 encoder file is float32 weights under that name.
        boolean fp32 = getAsrQuant(large ? ASR_QWEN3_17 : ASR_QWEN3_06) == ASR_QUANT_FP32;
        String encoder = fp32 ? "encoder.onnx" : "encoder.int4.onnx";
        String init = fp32 ? "decoder_init.onnx" : "decoder_init.int4.onnx";
        String step = fp32 ? "decoder_step.onnx" : "decoder_step.int4.onnx";
        String weights = fp32 ? "decoder_weights.data" : "decoder_weights.int4.data";
        long encoderBytes = large ? 1270241779L : 745762694L;
        long initBytes = fp32 ? (large ? 286423L : 286310L) : (large ? 356165L : 355390L);
        long stepBytes = fp32 ? (large ? 285925L : 285813L) : (large ? 355662L : 354888L);
        long weightsBytes = fp32 ? (large ? 8126930944L : 3006500864L) : (large ? 2226321408L : 962460672L);
        long embedBytes = large ? 622329856L : 311164928L;
        long configBytes = large ? 1251L : 1250L;
        return new DownloadGroupInfo(new DownloadInfo[]{
                new DownloadInfo(encoder, base + encoder, folder, kb(encoderBytes), false, false),
                new DownloadInfo(init, base + init, folder, kb(initBytes), false, false),
                new DownloadInfo(step, base + step, folder, kb(stepBytes), false, false),
                new DownloadInfo(weights, base + weights, folder, kb(weightsBytes), false, false),
                new DownloadInfo("embed_tokens.bin", base + "embed_tokens.bin", folder, kb(embedBytes), false, false),
                new DownloadInfo("config.json", base + "config.json", folder, kb(configBytes), false, false),
                new DownloadInfo("tokenizer.json", base + "tokenizer.json", folder, kb(11429377L), false, false)
        });
    }

    private static long kb(long bytes) {
        return Math.max(1L, (bytes + 1023L) / 1024L);
    }

    public boolean groupFilesPresent(DownloadGroupInfo group) {
        if (group == null || group.downloadsInfo == null) {
            return false;
        }
        for (DownloadInfo info : group.downloadsInfo) {
            File file = new File(info.getDestinationCompletePath());
            if (!file.isFile() || file.length() == 0) {
                return false;
            }
        }
        return group.downloadsInfo.length > 0;
    }

    public boolean legacyWhisperPresent() {
        String dir = getFilesDir().getPath();
        String[] names = {
                "Whisper_cache_initializer.onnx",
                "Whisper_cache_initializer_batch.onnx",
                "Whisper_decoder.onnx",
                "Whisper_detokenizer.onnx",
                "Whisper_encoder.onnx",
                "Whisper_initializer.onnx"
        };
        for (String name : names) {
            File file = new File(dir, name);
            if (!file.isFile() || file.length() == 0) {
                return false;
            }
        }
        return true;
    }

    /** Selected quant files, or the older split Whisper Small package when int4 has not been downloaded yet. */
    public boolean asrReady(int model) {
        if (groupFilesPresent(getAsrDownloadInfo(model))) {
            return true;
        }
        return model == ASR_WHISPER && getAsrQuant(model) == ASR_QUANT_INT4 && legacyWhisperPresent();
    }

    public int asrDownloadSizeMb(int model) {
        long kb = 0;
        for (DownloadInfo info : getAsrDownloadInfo(model).downloadsInfo) {
            kb += info.getSize();
        }
        return (int) Math.max(1L, (kb + 512L) / 1024L);
    }

    public void setAsrModel(int asrModel, boolean reloadRecognizer, @Nullable Translator.GeneralListener listener) {
        boolean modelChanged = this.asrModel != asrModel;
        this.asrModel = asrModel;
        SharedPreferences.Editor editor = getSharedPreferences("default", Context.MODE_PRIVATE).edit();
        editor.putInt("selectedAsrModel", asrModel);
        editor.apply();
        String nextSignature = asrSignature();
        boolean changed = modelChanged || (runningAsrSignature != null && !nextSignature.equals(runningAsrSignature));
        if (!changed || !reloadRecognizer || speechRecognizer == null) {
            if (listener != null) {
                listener.onSuccess();
            }
            return;
        }
        new Thread(() -> {
            try {
            deleteSpeechRecognizer();
            initializeSpeechRecognizer(new NeuralNetworkApi.InitListener() {
                @Override
                public void onInitializationFinished() {
                    mainHandler.post(() -> {
                        if (listener != null) {
                            listener.onSuccess();
                        }
                    });
                }

                @Override
                public void onError(int[] reasons, long value) {
                    mainHandler.post(() -> {
                        if (listener != null) {
                            listener.onFailure(reasons, value);
                        }
                    });
                }
            }, false);
            } catch (Exception e) {
                e.printStackTrace();
                mainHandler.post(() -> {
                    if (listener != null) {
                        listener.onFailure(new int[]{ErrorCodes.ERROR_LOADING_MODEL}, 0);
                    }
                });
            }
        }, "asrReload").start();
    }

    public DownloadGroupInfo getDictionaryDownloadInfo() {
        DownloadInfo[] initial = getInitialDownloadInfo().downloadsInfo;
        for (DownloadInfo info : initial) {
            if ("TranslationDictionaries.zip".equals(info.getName())) {
                return new DownloadGroupInfo(new DownloadInfo[]{info});
            }
        }
        return getInitialDownloadInfo();
    }


    @Nullable
    public Translator getTranslator() {
        return translator;
    }

    public void deleteTranslator(){
        translator = null;
        translatorInit.reset();
    }

    @Nullable
    public Recognizer getSpeechRecognizer() {
        return speechRecognizer;
    }

    public void deleteSpeechRecognizer(){
        Recognizer previous = speechRecognizer;
        // Invalidate in-flight callbacks before stop, so that attempt cannot complete a later one.
        speechInit.reset();
        if (previous != null) {
            previous.stop();
            previous.destroy();
        }
        speechRecognizer = null;
    }

    public boolean isForeground() {
        return isForeground;
    }

    @Override
    public void onStop(@NonNull LifecycleOwner owner) {
        DefaultLifecycleObserver.super.onStop(owner);
        //App in background
        isForeground = false;
    }

    @Override
    public void onStart(@NonNull LifecycleOwner owner) {
        DefaultLifecycleObserver.super.onStart(owner);
        // App in foreground
        isForeground = true;
    }

    @Nullable
    public AccessActivity getRunningAccessActivity() {
        return accessActivity;
    }

    public void setAccessActivity(@Nullable AccessActivity accessActivity) {
        this.accessActivity = accessActivity;
    }

    public interface GetLocalesListListener {
        void onSuccess(ArrayList<CustomLocale> result);

        void onFailure(int[] reasons, long value);
    }

    public CustomLocale getLanguage(final boolean recycleResult) {
        ArrayList<CustomLocale> languages = getLanguages(RTranslatorMode.CONVERSATION_MODE, true);
        CustomLocale predefinedLanguage = CustomLocale.getDefault();
        CustomLocale language = null;
        if (recycleResult && Global.this.language != null) {
            language = Global.this.language;
        } else {
            SharedPreferences sharedPreferences = Global.this.getSharedPreferences("default", Context.MODE_PRIVATE);
            String code = sharedPreferences.getString("language", predefinedLanguage.getCode());
            if (code != null) {
                language = CustomLocale.getInstance(code);
            }
        }

        int index = CustomLocale.search(languages, language);
        if (index != -1) {
            language = languages.get(index);
        } else {
            int index2 = CustomLocale.search(languages, predefinedLanguage);
            if (index2 != -1) {
                language = predefinedLanguage;
            } else {
                language = new CustomLocale("en");
            }
        }

        Global.this.language = language;
        return language;
    }

    public CustomLocale getFirstLanguage(final boolean recycleResult) {
        final ArrayList<CustomLocale> languages = getLanguages(RTranslatorMode.WALKIE_TALKIE_MODE, true);
        CustomLocale predefinedLanguage = getLanguage(true);
        CustomLocale language = null;
        if (recycleResult && Global.this.firstLanguage != null) {
            language = Global.this.firstLanguage;
        } else {
            SharedPreferences sharedPreferences = Global.this.getSharedPreferences("default", Context.MODE_PRIVATE);
            String code = sharedPreferences.getString("firstLanguage", predefinedLanguage.getCode());
            if (code != null) {
                language = CustomLocale.getInstance(code);
            }
        }

        int index = CustomLocale.search(languages, language);
        if (index != -1) {
            language = languages.get(index);
        } else {
            int index2 = CustomLocale.search(languages, predefinedLanguage);
            if (index2 != -1) {
                language = predefinedLanguage;
            } else if(!languages.isEmpty()) {
                language = languages.get(0);  //we pick the first language of the list
            } else {
                language = new CustomLocale("en");
            }
        }

        Global.this.firstLanguage = language;
        return language;
    }

    public CustomLocale getSecondLanguage(final boolean recycleResult) {
        ArrayList<CustomLocale> languages = getLanguages(RTranslatorMode.WALKIE_TALKIE_MODE, true);
        CustomLocale predefinedLanguage = CustomLocale.getDefault();
        CustomLocale language = null;
        if (recycleResult && Global.this.secondLanguage != null) {
            language = Global.this.secondLanguage;
        } else {
            SharedPreferences sharedPreferences = Global.this.getSharedPreferences("default", Context.MODE_PRIVATE);
            String code = sharedPreferences.getString("secondLanguage", null);
            if (code != null) {
                language = CustomLocale.getInstance(code);
            }
        }

        int index = CustomLocale.search(languages, language);
        if (index != -1) {
            language = languages.get(index);
        } else {
            language = new CustomLocale("en");
        }

        Global.this.secondLanguage = language;
        return language;
    }

    public CustomLocale getFirstTextLanguage(final boolean recycleResult) {
        final ArrayList<CustomLocale> languages = getTranslatorLanguages(RTranslatorMode.TEXT_TRANSLATION_MODE, true);
        CustomLocale predefinedLanguage = getLanguage(true);
        CustomLocale language = null;
        if (recycleResult && Global.this.firstTextLanguage != null) {
            language = Global.this.firstTextLanguage;
        } else {
            SharedPreferences sharedPreferences = Global.this.getSharedPreferences("default", Context.MODE_PRIVATE);
            String code = sharedPreferences.getString("firstTextLanguage", predefinedLanguage.getCode());
            if (code != null) {
                language = CustomLocale.getInstance(code);
            }
        }

        int index = CustomLocale.search(languages, language);
        if (index != -1) {
            language = languages.get(index);
        } else {
            int index2 = CustomLocale.search(languages, predefinedLanguage);
            if (index2 != -1) {
                language = predefinedLanguage;
            } else if(!languages.isEmpty()) {
                language = languages.get(0);  //we pick the first language of the list
            } else {
                language = new CustomLocale("en");
            }
        }

        Global.this.firstTextLanguage = language;
        return language;
    }

    public CustomLocale getSecondTextLanguage(final boolean recycleResult) {
        ArrayList<CustomLocale> languages = getTranslatorLanguages(RTranslatorMode.TEXT_TRANSLATION_MODE, true);
        CustomLocale language = null;
        if (recycleResult && Global.this.secondTextLanguage != null) {
            language = Global.this.secondTextLanguage;
        } else {
            SharedPreferences sharedPreferences = Global.this.getSharedPreferences("default", Context.MODE_PRIVATE);
            String code = sharedPreferences.getString("secondTextLanguage", null);
            if (code != null) {
                language = CustomLocale.getInstance(code);
            }
        }

        int index = CustomLocale.search(languages, language);
        if (index != -1) {
            language = languages.get(index);
        } else {
            language = new CustomLocale("en");
        }

        Global.this.secondTextLanguage = language;
        return language;
    }

    public interface GetLocaleListener {
        void onSuccess(CustomLocale result);

        void onFailure(int[] reasons, long value);
    }

    public interface GetTwoLocaleListener {
        void onSuccess(CustomLocale language1, CustomLocale language2);

        void onFailure(int[] reasons, long value);
    }

    public void setLanguage(CustomLocale language) {
        this.language = language;
        SharedPreferences sharedPreferences = this.getSharedPreferences("default", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString("language", language.getCode());
        editor.apply();
        if(ConversationService.isRunning && translator != null){
            translator.loadTgtLangResourcesForConversation(language, null);
            if(getBluetoothCommunicator() != null){
                getBluetoothCommunicator().sendLanguage(language);
            }
        }
    }

    public void setFirstLanguage(CustomLocale language, @Nullable Translator.GeneralListener listener) {
        this.firstLanguage = language;
        SharedPreferences sharedPreferences = this.getSharedPreferences("default", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString("firstLanguage", language.getCode());
        editor.apply();
        loadLanguagesResources(language, getSecondLanguage(true), RTranslatorMode.WALKIE_TALKIE_MODE, listener);
    }

    public void setSecondLanguage(CustomLocale language, @Nullable Translator.GeneralListener listener) {
        this.secondLanguage = language;
        SharedPreferences sharedPreferences = this.getSharedPreferences("default", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString("secondLanguage", language.getCode());
        editor.apply();
        loadLanguagesResources(getFirstLanguage(true), language, RTranslatorMode.WALKIE_TALKIE_MODE, listener);
    }

    public void setFirstTextLanguage(CustomLocale language, @Nullable Translator.GeneralListener listener) {
        this.firstTextLanguage = language;
        SharedPreferences sharedPreferences = this.getSharedPreferences("default", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString("firstTextLanguage", language.getCode());
        editor.apply();
        loadLanguagesResources(language, getSecondTextLanguage(true), RTranslatorMode.TEXT_TRANSLATION_MODE, listener);
    }

    public void setSecondTextLanguage(CustomLocale language, @Nullable Translator.GeneralListener listener) {
        this.secondTextLanguage = language;
        CustomLocale firstLanguage = getFirstTextLanguage(true);
        SharedPreferences sharedPreferences = this.getSharedPreferences("default", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString("secondTextLanguage", language.getCode());
        editor.apply();
        loadLanguagesResources(getFirstTextLanguage(true), language, RTranslatorMode.TEXT_TRANSLATION_MODE, listener);
    }

    public void switchTextLanguages(@Nullable Translator.GeneralListener listener) {
        CustomLocale firstLanguage = getFirstTextLanguage(true);
        CustomLocale secondLanguage = getSecondTextLanguage(true);
        this.firstTextLanguage = secondLanguage;
        this.secondTextLanguage = firstLanguage;
        SharedPreferences sharedPreferences = this.getSharedPreferences("default", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString("firstTextLanguage", this.firstTextLanguage.getCode());
        editor.putString("secondTextLanguage", this.secondTextLanguage.getCode());
        editor.apply();
        loadLanguagesResources(secondLanguage, firstLanguage, RTranslatorMode.TEXT_TRANSLATION_MODE, listener);
    }


    public int getAmplitudeThreshold() {
        return amplitudeThreshold;
    }


    public int getMicSensitivity() {
        if (micSensitivity == -1) {
            final SharedPreferences sharedPreferences = this.getSharedPreferences("default", Context.MODE_PRIVATE);
            int saved = sharedPreferences.getInt("micSensibility", MicSensitivity.DEFAULT);
            boolean extended = sharedPreferences.getBoolean("micSensitivityExtended", false);
            // Older installs could save 101–200 before the switch existed.
            if (!sharedPreferences.contains("micSensitivityExtended") && saved > MicSensitivity.STANDARD_MAX) {
                extended = true;
                sharedPreferences.edit().putBoolean("micSensitivityExtended", true).apply();
            }
            micSensitivityExtended = extended;
            micSensitivity = MicSensitivity.clamp(saved, extended);
            setAmplitudeThreshold(micSensitivity);
        }
        return micSensitivity;
    }

    public boolean isMicSensitivityExtended() {
        getMicSensitivity();
        return micSensitivityExtended;
    }

    public void setMicSensitivityExtended(boolean extended) {
        getMicSensitivity();
        micSensitivityExtended = extended;
        SharedPreferences.Editor editor = this.getSharedPreferences("default", Context.MODE_PRIVATE).edit();
        editor.putBoolean("micSensitivityExtended", extended);
        editor.apply();
        setMicSensitivity(micSensitivity);
    }

    public void setMicSensitivity(int value) {
        getMicSensitivity();
        micSensitivity = MicSensitivity.clamp(value, micSensitivityExtended);
        setAmplitudeThreshold(micSensitivity);
        final SharedPreferences sharedPreferences = this.getSharedPreferences("default", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putInt("micSensibility", micSensitivity);
        editor.apply();
    }

    public float getTtsSpeechRate() {
        if (ttsSpeechRate < 0f) {
            ttsSpeechRate = getSharedPreferences("default", Context.MODE_PRIVATE).getFloat("ttsSpeechRate", 1f);
        }
        return ttsSpeechRate;
    }

    public void setTtsSpeechRate(float value) {
        if (value < 0.5f) {
            value = 0.5f;
        } else if (value > 2f) {
            value = 2f;
        }
        ttsSpeechRate = value;
        SharedPreferences.Editor editor = getSharedPreferences("default", Context.MODE_PRIVATE).edit();
        editor.putFloat("ttsSpeechRate", value);
        editor.apply();
    }

    public int getSpeechTimeout() {
        if (speechTimeout == -1) {
            final SharedPreferences sharedPreferences = this.getSharedPreferences("default", Context.MODE_PRIVATE);
            speechTimeout = sharedPreferences.getInt("speechTimeout", Recorder.DEFAULT_SPEECH_TIMEOUT_MILLIS);
        }
        return speechTimeout;
    }

    public void setSpeechTimeout(int value) {
        speechTimeout = value;
        final SharedPreferences sharedPreferences = this.getSharedPreferences("default", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putInt("speechTimeout", value);
        editor.apply();
    }

    public int getPrevVoiceDuration() {
        if (prevVoiceDuration == -1) {
            final SharedPreferences sharedPreferences = this.getSharedPreferences("default", Context.MODE_PRIVATE);
            prevVoiceDuration = sharedPreferences.getInt("prevVoiceDuration", Recorder.DEFAULT_PREV_VOICE_DURATION);
        }
        return prevVoiceDuration;
    }

    public void setPrevVoiceDuration(int value) {
        prevVoiceDuration = value;
        final SharedPreferences sharedPreferences = this.getSharedPreferences("default", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putInt("prevVoiceDuration", value);
        editor.apply();
    }

    private void setAmplitudeThreshold(int micSensitivity) {
        amplitudeThreshold = MicSensitivity.amplitudeThreshold(micSensitivity);
    }

    public int getBeamSize() {
        if (beamSize == -1) {
            final SharedPreferences sharedPreferences = this.getSharedPreferences("default", Context.MODE_PRIVATE);
            beamSize = sharedPreferences.getInt("beamSize", TranslationFragment.DEFAULT_BEAM_SIZE);
        }
        return beamSize;
    }

    public void setBeamSize(int value) {
        beamSize = value;
        final SharedPreferences sharedPreferences = this.getSharedPreferences("default", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putInt("beamSize", value);
        editor.apply();
    }

    /** Off unless the user turns it on. The saved default is off. */
    public boolean isGemmaThinkingEnabled() {
        return getSharedPreferences("default", Context.MODE_PRIVATE).getBoolean("gemmaThinking", false);
    }

    public void setGemmaThinkingEnabled(boolean enabled) {
        getSharedPreferences("default", Context.MODE_PRIVATE).edit().putBoolean("gemmaThinking", enabled).apply();
    }

    /** Low, medium, or high. Used only while thinking is on. An unknown value is low. */
    public String getGemmaThinkingLevel() {
        String level = getSharedPreferences("default", Context.MODE_PRIVATE).getString("gemmaThinkingLevel", GEMMA_THINKING_LOW);
        if (GEMMA_THINKING_MEDIUM.equals(level) || GEMMA_THINKING_HIGH.equals(level)) {
            return level;
        }
        return GEMMA_THINKING_LOW;
    }

    public void setGemmaThinkingLevel(String level) {
        if (!GEMMA_THINKING_MEDIUM.equals(level) && !GEMMA_THINKING_HIGH.equals(level)) {
            level = GEMMA_THINKING_LOW;
        }
        getSharedPreferences("default", Context.MODE_PRIVATE).edit().putString("gemmaThinkingLevel", level).apply();
    }

    public boolean isUseTatoeba(){
        return useTatoeba;
    }

    public boolean isSimultaneousVadSegmentation() {
        return simultaneousVadSegmentation;
    }

    public void setSimultaneousVadSegmentation(boolean enabled) {
        simultaneousVadSegmentation = enabled;
        SharedPreferences.Editor editor = getSharedPreferences("default", Context.MODE_PRIVATE).edit();
        editor.putBoolean("simultaneousVadSegmentation", enabled);
        editor.apply();
    }

    public boolean isUseTranslationDictionaries(){
        return useTranslationDictionaries;
    }

    public boolean isUseMozillaForVoiceTranslation() {
        return useMozillaForVoiceTranslation;
    }

    public boolean isWhisperReducedRam() {
        return whisperReducedRam;
    }

    public void setWhisperReducedRam(boolean whisperReducedRam, @Nullable Translator.GeneralListener listener) {
        boolean changed = this.whisperReducedRam != whisperReducedRam;
        if(changed){
            this.whisperReducedRam = whisperReducedRam;
            final SharedPreferences sharedPreferences = this.getSharedPreferences("default", Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putBoolean("whisperReducedRam", whisperReducedRam);
            editor.apply();
            // restart of encoder session with the new option
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        if(speechRecognizer != null) speechRecognizer.initializeEncoderSession();
                        mainHandler.post(() -> {
                            if(listener != null) listener.onSuccess();
                        });
                    } catch (Exception e) {
                        e.printStackTrace();
                        mainHandler.post(() -> {
                            if (listener != null) listener.onFailure(new int[]{ErrorCodes.ERROR}, 0);
                        });
                    }
                }
            }).start();
        }else{
            if(listener != null) listener.onSuccess();
        }
    }

    public int getTranslationMode() {
        return translationMode;
    }

    public void setTranslationStatus(int translationMode, boolean useMozillaForVoiceTranslation, boolean useTatoeba, boolean useTranslationDicts, @NonNull Translator.GeneralListener listener) {
        boolean changed = this.translationMode != translationMode
                || this.useMozillaForVoiceTranslation != useMozillaForVoiceTranslation
                || this.useTatoeba != useTatoeba
                || this.useTranslationDictionaries != useTranslationDicts;
        if(changed) {
            this.translationMode = translationMode;
            this.useMozillaForVoiceTranslation = useMozillaForVoiceTranslation;
            this.useTatoeba = useTatoeba;
            this.useTranslationDictionaries = useTranslationDicts;
            final SharedPreferences sharedPreferences = this.getSharedPreferences("default", Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putInt("selectedTranslationModel", translationMode);
            editor.putBoolean("useMozillaForVoiceTranslation", useMozillaForVoiceTranslation);
            editor.putBoolean("useTatoeba", useTatoeba);
            editor.putBoolean("useTranslationDictionaries", useTranslationDicts);
            editor.apply();
            // update of the translator status (if the translator is already initialized)
            if (translator != null) {
                translator.setTranslationStatus(translationMode, useMozillaForVoiceTranslation, useTatoeba, useTranslationDicts, listener);
            }else{
                listener.onSuccess();
            }
        }else{
            listener.onSuccess();
        }
    }

    /**
     * This method will update the languages selected for all rtranslator modes (text, walkieTalkie, conversation),
     * usually it is called after a model change for some modes, because the selected languages could be not compatible anymore with the new model.
     * In that case, this method will select a compatible language for the selected model in that rtranslator mode.
     * This method will also update the lists of languages available to each rtranslator mode based on the new selected models.
     * But it won't update the resources associated with the new languages.
     */
    public void updateLanguages() {
        getLanguages(RTranslatorMode.TEXT_TRANSLATION_MODE, false);  //this will update the recognizer langs, the translator langs and also the mozilla langs (for all the RTranslator modes)
        getFirstTextLanguage(false);
        getSecondTextLanguage(false);
        getFirstLanguage(false);
        getSecondLanguage(false);
        getLanguage(false);
    }

    /**
     * This method does the same thing as updateLanguages() but it also updates the preferences and resources of the new languages.
     * @param listener
     */
    public void updateLanguagesAndResources(Translator.GeneralListener listener){
        new Thread(() -> {
            getLanguages(RTranslatorMode.TEXT_TRANSLATION_MODE, false);  //this will update the recognizer langs, the translator langs and also the mozilla langs (for all the RTranslator modes)
            final CustomLocale firstText = getFirstTextLanguage(false);
            final CustomLocale secondText = getSecondTextLanguage(false);
            final CustomLocale first = getFirstLanguage(false);
            final CustomLocale second = getSecondLanguage(false);
            final CustomLocale language = getLanguage(false);
            mainHandler.post(() -> setFirstTextLanguage(firstText, new Translator.GeneralListener() {
                @Override
                public void onSuccess() {
                    setSecondTextLanguage(secondText, new Translator.GeneralListener() {
                        @Override
                        public void onSuccess() {
                            setFirstLanguage(first, new Translator.GeneralListener() {
                                @Override
                                public void onSuccess() {
                                    setSecondLanguage(second, new Translator.GeneralListener() {
                                        @Override
                                        public void onSuccess() {
                                            setLanguage(language);
                                            if (listener != null) {
                                                listener.onSuccess();
                                            }
                                        }
                                    });
                                }
                            });
                        }
                    });
                }
            }));
        }, "updateLanguages").start();
    }

    public String getName() {
        if (name.length() == 0) {
            final SharedPreferences sharedPreferences = this.getSharedPreferences("default", Context.MODE_PRIVATE);
            name = sharedPreferences.getString("name", "user");
        }
        return name;
    }

    public void setName(String savedName) {
        name = savedName;
        final SharedPreferences sharedPreferences = this.getSharedPreferences("default", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString("name", savedName);
        editor.apply();
        if(getBluetoothCommunicator() != null) {
            getBluetoothCommunicator().setName(savedName);  //we update the name also for communicator
        }
    }

    public Peer getMyPeer() {
        return new Peer(null, getName(), false);
    }

    public abstract static class MyPeerListener {
        public abstract void onSuccess(Peer myPeer);

        public void onFailure(int[] reasons, long value) {
        }
    }

    public void getMyID(final MyIDListener responseListener) {
        responseListener.onSuccess(Settings.Secure.getString(this.getContentResolver(), Settings.Secure.ANDROID_ID));
    }

    public abstract static class MyIDListener {
        public abstract void onSuccess(String id);

        public void onFailure(int[] reasons, long value) {
        }
    }

    public RecentPeersDataManager getRecentPeersDataManager() {
        return recentPeersDataManager;
    }

    public abstract static class ResponseListener {
        public void onSuccess() {

        }

        public void onFailure(int[] reasons, long value) {
        }
    }

    public boolean isFirstStart() {
        final SharedPreferences sharedPreferences = this.getSharedPreferences("default", Context.MODE_PRIVATE);
        return sharedPreferences.getBoolean("firstStart", true);
    }

    public void setFirstStart(boolean firstStart) {
        final SharedPreferences sharedPreferences = this.getSharedPreferences("default", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putBoolean("firstStart", firstStart);
        editor.apply();
    }


    private void createNotificationChannel(){
        String channelID = "service_background_notification";
        String channelName = getResources().getString(R.string.notification_channel_name);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel notificationChannel = new NotificationChannel(channelID, channelName, NotificationManager.IMPORTANCE_LOW);
            NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            notificationManager.createNotificationChannel(notificationChannel);
        }
    }

    /**
     * Returns the total RAM size of the device in MB
     */
    public int getTotalRamSize(){
        ActivityManager actManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
        actManager.getMemoryInfo(memInfo);
        long totalMemory = memInfo.totalMem / 1000000L;
        android.util.Log.i("memory", "Total memory: " + totalMemory);
        return (int) totalMemory;
    }

    /**
     * Returns the available RAM size of the device in MB
     */
    public int getAvailableRamSize(){
        ActivityManager actManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
        actManager.getMemoryInfo(memInfo);
        long availableMemory = memInfo.availMem;
        android.util.Log.i("memory", "Total memory: " + availableMemory);
        return (int) (availableMemory / 1000000);
    }

    /**
     * Returns (in MB) the absolute maximum native RAM allocatable by forcing the OS
     * to kill all other killable processes.
     */
    public int getMaxAllocatableRAM() {
        ActivityManager activityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (activityManager == null) return 0;

        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memoryInfo);

        // Total physical RAM available to the OS (already excludes GPU-reserved RAM)
        long totalRam = memoryInfo.totalMem;

        // Android doesn't have an API that shows the max amount of RAM available for native allocations before a crash occurs,
        // so the best way (also hinted here https://developer.android.com/games/optimize/memory-allocation#conservative-memory-budgets)
        // is to use a percentage of the total RAM between 50% and 25% as a hard limit of native RAM usage for a single app.
        double maxPercentage = 0.45;

        // The absolute theoretical maximum you can allocate before the OS targets YOU
        long maxAllocatable = (long) (totalRam - (totalRam*maxPercentage)) / 1000000L;

        // Return the result, ensuring we don't return a negative number on heavily constrained devices
        return (int) Math.max(0, maxAllocatable);
    }

    public int getRamThreshold(){
        ActivityManager actManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
        actManager.getMemoryInfo(memInfo);
        long threshold = memInfo.threshold;
        return (int) (threshold / 1000000);
    }

    /**
     * Returns the available internal memory space in MB
     */
    public long getAvailableInternalMemorySize() {
        File internalFilesDir = this.getFilesDir();
        if(internalFilesDir != null) {
            long freeMBInternal = new File(internalFilesDir.getAbsoluteFile().toString()).getFreeSpace() / 1000000L;
            return freeMBInternal;
        }
        return -1;
    }

    /**
     * Returns the available external memory space in MB
     */
    public long getAvailableExternalMemorySize() {
        File externalFilesDir = this.getExternalFilesDir(null);
        if(externalFilesDir != null) {
            long freeMBExternal = new File(externalFilesDir.getAbsoluteFile().toString()).getFreeSpace() / 1000000L;
            return freeMBExternal;
        }
        return -1;
    }



    public boolean isNetworkOnWifi() {
        WifiManager wifi_m = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        if (wifi_m.isWifiEnabled()) { // if wifi is on
            WifiInfo wifi_i = wifi_m.getConnectionInfo();
            if (wifi_i.getNetworkId() == -1) {
                return false; // Not connected to any wifi device
            }
            return true; // Connected to some wifi device
        } else {
            return false; // user turned off wifi
        }
    }

    private void loadLanguagesResources(CustomLocale firstLanguage, CustomLocale secondLanguage, RTranslatorMode mode, Translator.GeneralListener listener){
        if(translator != null) {
            translator.loadLanguageResources(firstLanguage, secondLanguage, mode, listener);
        }else if(listener != null){
            listener.onSuccess();  //if the translator is not initialized yet we do not load the language resources
        }
    }

    private static boolean isVoiceMode(RTranslatorMode rtranslatorMode) {
        return rtranslatorMode == RTranslatorMode.CONVERSATION_MODE
                || rtranslatorMode == RTranslatorMode.WALKIE_TALKIE_MODE
                || rtranslatorMode == RTranslatorMode.SIMULTANEOUS_MODE;
    }

    public CustomLocale getSimultaneousSourceLanguage(final boolean recycleResult) {
        ArrayList<CustomLocale> languages = getLanguages(RTranslatorMode.SIMULTANEOUS_MODE, true);
        CustomLocale target = getSimultaneousTargetLanguage(true);
        CustomLocale fallback = new CustomLocale("en");
        if (target.equalsLanguage(fallback)) {
            for (CustomLocale candidate : languages) {
                if (!candidate.equalsLanguage(target)) {
                    fallback = candidate;
                    break;
                }
            }
        }
        return resolveSavedLanguage(languages, "simultaneousSourceLanguage", fallback, recycleResult, true);
    }

    public CustomLocale getSimultaneousTargetLanguage(final boolean recycleResult) {
        ArrayList<CustomLocale> languages = getLanguages(RTranslatorMode.SIMULTANEOUS_MODE, true);
        return resolveSavedLanguage(languages, "simultaneousTargetLanguage", getLanguage(true), recycleResult, false);
    }

    public void setSimultaneousSourceLanguage(CustomLocale language) {
        simultaneousSourceLanguage = language;
        SharedPreferences.Editor editor = getSharedPreferences("default", Context.MODE_PRIVATE).edit();
        editor.putString("simultaneousSourceLanguage", language.getCode());
        editor.apply();
        if (nie.translator.rtranslator.voice_translation._live_translation._simultaneous.SimultaneousService.isRunning) {
            loadLanguagesResources(language, getSimultaneousTargetLanguage(true), RTranslatorMode.SIMULTANEOUS_MODE, null);
        }
    }

    public void setSimultaneousTargetLanguage(CustomLocale language) {
        simultaneousTargetLanguage = language;
        SharedPreferences.Editor editor = getSharedPreferences("default", Context.MODE_PRIVATE).edit();
        editor.putString("simultaneousTargetLanguage", language.getCode());
        editor.apply();
        if (nie.translator.rtranslator.voice_translation._live_translation._simultaneous.SimultaneousService.isRunning) {
            loadLanguagesResources(getSimultaneousSourceLanguage(true), language, RTranslatorMode.SIMULTANEOUS_MODE, null);
        }
    }

    private CustomLocale resolveSavedLanguage(ArrayList<CustomLocale> languages, String preferenceKey, CustomLocale fallback, boolean recycleResult, boolean source) {
        CustomLocale cached = source ? simultaneousSourceLanguage : simultaneousTargetLanguage;
        CustomLocale language = null;
        if (recycleResult && cached != null) {
            language = cached;
        } else {
            String code = getSharedPreferences("default", Context.MODE_PRIVATE).getString(preferenceKey, fallback.getCode());
            if (code != null) {
                language = CustomLocale.getInstance(code);
            }
        }
        int index = CustomLocale.search(languages, language);
        if (index != -1) {
            language = languages.get(index);
        } else {
            int fallbackIndex = CustomLocale.search(languages, fallback);
            if (fallbackIndex != -1) {
                language = languages.get(fallbackIndex);
            } else if (!languages.isEmpty()) {
                language = languages.get(0);
            } else if (language == null) {
                language = fallback;
            }
        }
        if (source) {
            simultaneousSourceLanguage = language;
        } else {
            simultaneousTargetLanguage = language;
        }
        return language;
    }
}

