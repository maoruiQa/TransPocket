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

package nie.translator.rtranslator.tools;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import androidx.annotation.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;

/**
 * The system text-to-speech engine. This class only forwards requests to the engine:
 * ordering, "one sentence at a time", interruption recovery and the completion
 * bookkeeping live in {@code TTSEngine}, so there is a single place that decides
 * what is spoken next.
 */
public class TTS {
    /**
     * Pause the engine needs after a {@code stop()}, a language change, or the
     * {@code onDone} of the previous sentence. On several engines the previous audio
     * output is still being torn down when the callback arrives, and a request
     * submitted in that moment loses its first words.
     */
    public static final int ENGINE_SETTLE_MS = 250;

    //object
    private TextToSpeech tts;
    private final Context appContext;
    private final AudioManager audioManager;
    private final AudioAttributes speechAttributes;
    private AudioFocusRequest focusRequest;
    private boolean focusHeld;
    private UtteranceProgressListener progressListener;
    private CustomLocale currentLocale;
    /** Last values really applied to the engine. Re-applying them before every sentence clips the opening words on some engines. */
    private String appliedLanguage = "";
    private float appliedRate = -1f;

    //Attributes used for getting the supported languages
    private static Thread getSupportedLanguageThread;
    private static ArrayDeque<SupportedLanguagesListener> supportedLanguagesListeners = new ArrayDeque<>();
    private static final Object lock = new Object();
    private static final ArrayList<CustomLocale> ttsLanguages = new ArrayList<>();


    public TTS(Context context, final InitListener listener) {
        appContext = context.getApplicationContext();
        audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
        // The default attributes of the engine are USAGE_ASSISTANCE_ACCESSIBILITY, and some
        // ROMs fade the beginning of that usage in (it sounds like the first words are missing).
        speechAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(speechAttributes)
                .setOnAudioFocusChangeListener(new AudioManager.OnAudioFocusChangeListener() {
                    @Override
                    public void onAudioFocusChange(int focusChange) {
                        if (focusChange == AudioManager.AUDIOFOCUS_LOSS || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                            // Another app took the focus. The engine stops on its own and reports it
                            // through the progress listener, so the queue is still in charge.
                            focusHeld = false;
                        }
                    }
                })
                .build();
        tts = new TextToSpeech(context, new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if (status == TextToSpeech.SUCCESS) {
                    /*List<EngineInfo> engineInfos=TTS.this.getEngines();   //method 1
                    boolean found=false;
                    for (int i=0;i<engineInfos.size() && !found;i++){
                        if(engineInfos.get(i).name.equals("com.google.android.tts")){
                            found=true;
                        }
                    }*/

                    /*boolean found = false;*/    //method 2
                    if (tts != null) {
                        /*ArrayList<TextToSpeech.EngineInfo> engines = new ArrayList<>(tts.getEngines());
                        for (int i = 0; i < engines.size() && !found; i++) {
                            switch (engines.get(i).name) {
                                case "com.google.android.tts":
                                    found = true; // Check Google TTS here.
                                    break;
                                case "com.samsung.SMT": // Check Samsung TTS here.
                                    found = true;
                                    break;
                                case "com.huawei.hiai": // Check Huawei TTS here.
                                    found = true;
                                    break;
                            }
                        } // Look forward to supporting more TTS engine.
                        if (!found) {
                            tts = null;
                            listener.onError(ErrorCodes.MISSING_GOOGLE_TTS);
                        } else {
                            listener.onInit();
                        }
                        return;*/
                        tts.setAudioAttributes(speechAttributes);
                        listener.onInit();
                        return; // Set TTS to the default TTS directly.
                    }
                }
                tts = null;
                listener.onError(ErrorCodes.GOOGLE_TTS_ERROR);
            }
        },
        null);// use default TTS when this is null
    }

    public boolean isActive() {
        return tts != null;
    }

    /**
     * Holds the audio focus for one run of sentences. Without it the engine output can be
     * ducked at the start of a sentence while the microphone is capturing.
     */
    public void requestSpeechFocus() {
        if (focusHeld || audioManager == null) {
            return;
        }
        focusHeld = audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    public void abandonSpeechFocus() {
        if (!focusHeld || audioManager == null) {
            return;
        }
        audioManager.abandonAudioFocusRequest(focusRequest);
        focusHeld = false;
    }

    /** Speech always uses the system engine. */
    public boolean isLiveDraft(CustomLocale locale) {
        return false;
    }

    /**
     * The system engine cannot accept partial text, so sentences are queued instead.
     */
    public boolean canStream(CustomLocale locale) {
        return locale != null && tts != null;
    }

    private float speechRate() {
        return appContext.getSharedPreferences("default", Context.MODE_PRIVATE).getFloat("ttsSpeechRate", 1f);
    }

    /** Longest text the system engine accepts in one request. 0 when there is no engine. */
    public int getMaxSpeechInputLength() {
        return tts != null ? tts.getMaxSpeechInputLength() : 0;
    }

    private static float clampRate(float rate) {
        if (rate < 0.5f) {
            return 0.5f;
        }
        if (rate > 2f) {
            return 2f;
        }
        return rate;
    }

    /** The system engine has no streaming session. The caller speaks the queued sentence. */
    public boolean startStream(CustomLocale locale, String utteranceId) {
        currentLocale = locale;
        return false;
    }

    public void appendStream(String text) {
    }

    public void finishStream() {
    }

    public int speak(CharSequence text, int queueMode, Bundle params, String utteranceId) {
        String spoken = text == null ? "" : text.toString();
        if (spoken.trim().isEmpty()) {
            // Most engines answer ERROR to blank input, which used to clear the whole queue.
            return TextToSpeech.SUCCESS;
        }
        float rate = clampRate(speechRate());
        if (tts == null) {
            return TextToSpeech.ERROR;
        }
        if (rate != appliedRate) {
            tts.setSpeechRate(rate);
            appliedRate = rate;
        }
        return tts.speak(spoken, queueMode, params, utteranceId);
    }

    @Nullable
    public Voice getVoice() {
        if (isActive()) {
            return tts != null ? tts.getVoice() : null;
        }
        return null;
    }

    @Nullable
    public Set<Voice> getVoices() {
        if (isActive()) {
            return tts != null ? tts.getVoices() : null;
        }
        return null;
    }

    public int setOnUtteranceProgressListener(UtteranceProgressListener listener) {
        progressListener = listener;
        if (tts != null) {
            return tts.setOnUtteranceProgressListener(listener);
        }
        return TextToSpeech.SUCCESS;
    }

    /**
     * Applies the language only when the engine is not already using it, and reports
     * whether a change was made. The caller has to wait {@link #ENGINE_SETTLE_MS} before
     * speaking: a language change right before a request clips the opening words.
     */
    public boolean prepareLanguage(CustomLocale loc, Context context) {
        currentLocale = loc;
        if (tts == null || loc == null) {
            return false;
        }
        String language = loc.getLocale().getLanguage();
        if (language.equals(appliedLanguage)) {
            return false;
        }
        int result = tts.setLanguage(new Locale(language));
        if (result != TextToSpeech.ERROR) {
            appliedLanguage = language;
        }
        return true;
    }

    public int setLanguage(CustomLocale loc, Context context) {
        prepareLanguage(loc, context);
        if (tts == null) {
            return TextToSpeech.SUCCESS;
        }
        return loc != null ? TextToSpeech.LANG_AVAILABLE : TextToSpeech.ERROR;
    }

    /**
     * Applies the configured rate only when it differs from the one the engine already
     * has. Like a language change, it has to be applied before the settle delay of the
     * next request, never in the same moment as the request itself.
     */
    public boolean prepareSpeechRate() {
        if (tts == null) {
            return false;
        }
        float rate = clampRate(speechRate());
        if (rate == appliedRate) {
            return false;
        }
        tts.setSpeechRate(rate);
        appliedRate = rate;
        return true;
    }

    public int stop() {
        if (tts != null) {
            return tts.stop();
        }
        return TextToSpeech.SUCCESS;
    }

    public void shutdown() {
        abandonSpeechFocus();
        if (tts != null) {
            tts.shutdown();
        }
    }

    public static void getSupportedLanguages(Context context, SupportedLanguagesListener responseListener){
        synchronized (lock) {
            if (responseListener != null) {
                supportedLanguagesListeners.addLast(responseListener);
            }
            if (getSupportedLanguageThread == null) {
                getSupportedLanguageThread = new Thread(new GetSupportedLanguageRunnable(context, new SupportedLanguagesListener() {
                    @Override
                    public void onLanguagesListAvailable(ArrayList<CustomLocale> languages) {
                        notifyGetSupportedLanguagesSuccess(languages);
                    }
                    @Override
                    public void onError(int reason) {
                        notifyGetSupportedLanguagesFailure(reason);
                    }
                }), "getSupportedLanguagePerformer");
                getSupportedLanguageThread.start();
            }
        }
    }

    private static void notifyGetSupportedLanguagesSuccess(ArrayList<CustomLocale> languages) {
        synchronized (lock) {
            while (supportedLanguagesListeners.peekFirst() != null) {
                supportedLanguagesListeners.pollFirst().onLanguagesListAvailable(languages);
            }
            getSupportedLanguageThread = null;
        }
    }

    private static void notifyGetSupportedLanguagesFailure(final int reasons) {
        synchronized (lock) {
            while (supportedLanguagesListeners.peekFirst() != null) {
                supportedLanguagesListeners.pollFirst().onError(reasons);
            }
            getSupportedLanguageThread = null;
        }
    }

    private static class GetSupportedLanguageRunnable implements Runnable {
        private SupportedLanguagesListener responseListener;
        private Context context;
        private static TTS tempTts;
        private static android.os.Handler mainHandler;   // handler that can be used to post to the main thread



        private GetSupportedLanguageRunnable(Context context, final SupportedLanguagesListener responseListener) {
            this.responseListener = responseListener;
            this.context = context;
            mainHandler = new android.os.Handler(Looper.getMainLooper());
        }

        @Override
        public void run() {
            tempTts = new TTS((context), new TTS.InitListener() {    // tts initialization (to be improved, automatic package installation)
                @Override
                public void onInit() {
                    Set<Voice> set = tempTts.getVoices();
                    SharedPreferences sharedPreferences = context.getSharedPreferences("default", Context.MODE_PRIVATE);
                    boolean qualityLow = sharedPreferences.getBoolean("languagesQualityLow", false);
                    int quality;
                    if (qualityLow) {
                        quality = Voice.QUALITY_VERY_LOW;
                    } else {
                        quality = Voice.QUALITY_NORMAL;
                    }
                    boolean foundLanguage = false; // if there is available languages
                    if (set != null) {
                        // we filter the languages that have a tts that reflects the quality characteristics we want
                        for (Voice aSet : set) {
                            if (aSet.getQuality() >= quality && (qualityLow || !aSet.getFeatures().contains("legacySetLanguageVoice"))) {
                                CustomLocale language;
                                if(aSet.getLocale() != null){
                                    language = new CustomLocale(aSet.getLocale()); // Use .getLocale() for google
                                    foundLanguage = true;
                                }else{
                                    language = CustomLocale.getInstance(aSet.getName()); // Use .getName() for samsung/huawei (maybe others also)
                                    foundLanguage = true;
                                }

                                ttsLanguages.add(language);
                            }
                        }
                    }
                    if (foundLanguage) {    // start TTS if the above lines find at least 1 supported language
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                responseListener.onLanguagesListAvailable(ttsLanguages);
                            }
                        });
                    } else {
                        onError(ErrorCodes.GOOGLE_TTS_ERROR);
                    }
                    tempTts.stop();
                    tempTts.shutdown();
                }

                @Override
                public void onError(final int reason) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            responseListener.onError(reason);
                        }
                    });
                }
            });
        }
    }

    public interface InitListener {
        void onInit();

        void onError(int reason);
    }

    public interface SupportedLanguagesListener {
        void onLanguagesListAvailable(ArrayList<CustomLocale> languages);
        void onError(int reason);
    }
}
