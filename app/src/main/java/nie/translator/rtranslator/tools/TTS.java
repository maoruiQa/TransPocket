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

public class TTS {
    //object
    private TextToSpeech tts;
    private final Context appContext;
    private UtteranceProgressListener progressListener;
    private CustomLocale currentLocale;
    private boolean cosyStream;
    private final Object streamLock = new Object();
    private final StringBuilder systemPending = new StringBuilder();
    private final android.os.Handler mainHandler = new android.os.Handler(Looper.getMainLooper());
    private final ArrayDeque<String> systemPieces = new ArrayDeque<>();
    private int systemGeneration;
    private int systemTicket;
    private int systemPieceIndex;
    private String systemUtteranceId = "";
    private String systemWaitingId = "";
    private boolean systemOpen;
    private boolean systemFinished;
    private boolean systemStarted;
    private boolean systemInFlight;
    private boolean systemForceFlush;
    private boolean systemApplied;
    private int systemQueueMode = TextToSpeech.QUEUE_ADD;
    private Runnable systemWatch;

    //Attributes used for getting the supported languages
    private static Thread getSupportedLanguageThread;
    private static ArrayDeque<SupportedLanguagesListener> supportedLanguagesListeners = new ArrayDeque<>();
    private static final Object lock = new Object();
    private static final ArrayList<CustomLocale> ttsLanguages = new ArrayList<>();


    public TTS(Context context, final InitListener listener) {
        appContext = context.getApplicationContext();
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
                        installBridge();
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
        return tts != null || LiveDraftVoices.isReady(appContext, LiveDraftVoices.ENGINE_COSYVOICE);
    }

    /**
     * Incremental speech is available for CosyVoice and for the system engine.
     * The system engine cannot accept partial text, so sentences are queued instead.
     */
    public boolean canStream(CustomLocale locale) {
        return locale != null && (LiveDraftSpeaker.canSpeak(appContext, locale.getLanguage()) || tts != null);
    }

    private float speechRate() {
        return appContext.getSharedPreferences("default", Context.MODE_PRIVATE).getFloat("ttsSpeechRate", 1f);
    }

    public boolean startStream(CustomLocale locale, String utteranceId) {
        currentLocale = locale;
        if (locale == null) {
            return false;
        }
        cosyStream = false;
        if (LiveDraftSpeaker.canSpeak(appContext, locale.getLanguage())
                && LiveDraftSpeaker.start(appContext, locale.getLanguage(), speechRate(), progressListener, utteranceId)) {
            cosyStream = true;
            return true;
        }
        return beginSystem(locale, utteranceId, TextToSpeech.QUEUE_FLUSH);
    }

    public void appendStream(String text) {
        if (cosyStream) {
            LiveDraftSpeaker.append(text);
            return;
        }
        appendSystem(text, false);
    }

    public void finishStream() {
        if (cosyStream) {
            LiveDraftSpeaker.finish();
            return;
        }
        finishSystem();
    }

    public int speak(CharSequence text, int queueMode, Bundle params, String utteranceId) {
        float rate = speechRate();
        String spoken = text == null ? "" : text.toString();
        if (currentLocale != null && LiveDraftSpeaker.canSpeak(appContext, currentLocale.getLanguage())) {
            cosyStream = true;
            int result = LiveDraftSpeaker.speak(appContext, spoken, currentLocale.getLanguage(), rate, queueMode, progressListener, utteranceId);
            if (result == TextToSpeech.SUCCESS) {
                return result;
            }
            cosyStream = false;
        }
        if (tts == null || currentLocale == null) {
            return TextToSpeech.ERROR;
        }
        if (!beginSystem(currentLocale, utteranceId, queueMode)) {
            return TextToSpeech.ERROR;
        }
        if (!appendSystem(spoken, true)) {
            return TextToSpeech.ERROR;
        }
        return TextToSpeech.SUCCESS;
    }

    private boolean beginSystem(CustomLocale locale, String utteranceId, int queueMode) {
        if (tts == null || locale == null) {
            return false;
        }
        disarmWatch();
        synchronized (streamLock) {
            systemGeneration++;
            systemTicket++;
            systemPending.setLength(0);
            systemPieces.clear();
            systemPieceIndex = 0;
            systemUtteranceId = utteranceId == null ? "" : utteranceId;
            systemWaitingId = "";
            systemOpen = true;
            systemFinished = false;
            systemStarted = false;
            systemInFlight = false;
            systemForceFlush = false;
            systemApplied = false;
            systemQueueMode = queueMode;
        }
        return true;
    }

    private boolean appendSystem(String text, boolean finished) {
        String doneId = null;
        synchronized (streamLock) {
            if (!systemOpen) {
                return false;
            }
            if (text != null && text.length() > 0) {
                systemPending.append(text);
            }
            if (finished) {
                systemFinished = true;
            }
            collectSystemPieces();
            if (systemPieces.isEmpty() && !systemInFlight && systemFinished && systemPending.length() == 0) {
                doneId = systemUtteranceId;
                systemOpen = false;
            }
        }
        if (doneId != null) {
            postDone(doneId);
            return true;
        }
        pumpSystem();
        return true;
    }

    private void collectSystemPieces() {
        while (true) {
            String piece = SimultaneousText.takeSpeakable(systemPending.toString(), systemFinished);
            if (piece == null) {
                break;
            }
            systemPending.delete(0, piece.length());
            if (!SimultaneousText.isAudible(piece)) {
                continue;
            }
            systemPieces.addLast(piece);
        }
    }

    /**
     * The system engine stalls when several utterances are queued before the first
     * one starts. One sentence is submitted at a time, on the main thread, and the
     * next is submitted only after that sentence finishes.
     */
    private void pumpSystem() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    pumpSystem();
                }
            });
            return;
        }
        String piece;
        String pieceId;
        int queue;
        int ticket;
        CustomLocale locale;
        int max = tts == null ? 0 : tts.getMaxSpeechInputLength();
        synchronized (streamLock) {
            if (!systemOpen || systemInFlight || systemPieces.isEmpty()) {
                return;
            }
            piece = systemPieces.removeFirst();
            if (max > 1 && piece.length() > max) {
                systemPieces.addFirst(piece.substring(max));
                piece = piece.substring(0, max);
            }
            systemInFlight = true;
            ticket = systemTicket;
            pieceId = systemGeneration + ":" + systemPieceIndex;
            systemPieceIndex++;
            systemWaitingId = pieceId;
            queue = systemPieceIndex == 1 || systemForceFlush ? systemQueueMode : TextToSpeech.QUEUE_ADD;
            if (systemForceFlush) {
                queue = TextToSpeech.QUEUE_FLUSH;
                systemForceFlush = false;
            }
            locale = currentLocale;
        }
        if (tts == null) {
            failSystem(ticket);
            return;
        }
        boolean apply;
        synchronized (streamLock) {
            apply = !systemApplied;
            systemApplied = true;
        }
        float rate = speechRate();
        if (rate < 0.5f) {
            rate = 0.5f;
        } else if (rate > 2f) {
            rate = 2f;
        }
        if (apply) {
            tts.setSpeechRate(rate);
            if (locale != null) {
                tts.setLanguage(new Locale(locale.getLanguage()));
            }
        }
        android.os.Bundle params = new android.os.Bundle();
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, pieceId);
        int result = tts.speak(piece, queue, params, pieceId);
        if (result == TextToSpeech.ERROR) {
            failSystem(ticket);
            return;
        }
        armWatch(ticket, pieceId, piece.length(), rate);
    }

    private void armWatch(final int ticket, final String pieceId, int chars, float rate) {
        disarmWatch();
        float safeRate = rate <= 0f ? 1f : rate;
        final long budget = Math.max(8000L, (long) (chars * 700L / safeRate) + 4000L);
        systemWatch = new Runnable() {
            @Override
            public void run() {
                postPieceFinished(ticket, pieceId, true);
            }
        };
        mainHandler.postDelayed(systemWatch, budget);
    }

    private void disarmWatch() {
        if (systemWatch != null) {
            mainHandler.removeCallbacks(systemWatch);
            systemWatch = null;
        }
    }

    private void postPieceFinished(final int ticket, final String pieceId, final boolean forceFlush) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                onPieceFinished(ticket, pieceId, forceFlush);
            }
        });
    }

    private void onPieceFinished(int ticket, String pieceId, boolean forceFlush) {
        String doneId = null;
        synchronized (streamLock) {
            if (!systemOpen || ticket != systemTicket || pieceId == null || !pieceId.equals(systemWaitingId)) {
                return;
            }
            systemWaitingId = "";
            systemInFlight = false;
            if (forceFlush) {
                systemForceFlush = true;
            }
            if (systemPieces.isEmpty() && systemFinished && systemPending.length() == 0) {
                doneId = systemUtteranceId;
                systemOpen = false;
            }
        }
        disarmWatch();
        if (doneId != null) {
            postDone(doneId);
            return;
        }
        pumpSystem();
    }

    private void failSystem(int ticket) {
        String id;
        synchronized (streamLock) {
            if (ticket != systemTicket || !systemOpen) {
                return;
            }
            id = systemUtteranceId;
            systemOpen = false;
            systemInFlight = false;
            systemWaitingId = "";
            systemPieces.clear();
        }
        disarmWatch();
        postError(id);
    }

    private void finishSystem() {
        appendSystem("", true);
    }

    private void installBridge() {
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
                UtteranceProgressListener forward = null;
                String id = utteranceId;
                synchronized (streamLock) {
                    if (isSystemPiece(utteranceId)) {
                        if (!systemStarted) {
                            systemStarted = true;
                            forward = progressListener;
                            id = systemUtteranceId;
                        }
                    } else if (!isSystemId(utteranceId)) {
                        forward = progressListener;
                    }
                }
                if (forward != null) {
                    forward.onStart(id);
                }
            }

            @Override
            public void onDone(String utteranceId) {
                if (isWaitingPiece(utteranceId)) {
                    int ticket;
                    synchronized (streamLock) {
                        ticket = systemTicket;
                    }
                    postPieceFinished(ticket, utteranceId, false);
                    return;
                }
                if (!isSystemId(utteranceId) && progressListener != null) {
                    progressListener.onDone(utteranceId);
                }
            }

            @Override
            public void onError(String utteranceId) {
                deliverError(utteranceId);
            }

            @Override
            public void onStop(String utteranceId, boolean interrupted) {
                if (isWaitingPiece(utteranceId)) {
                    if (interrupted) {
                        UtteranceProgressListener forward;
                        String id;
                        synchronized (streamLock) {
                            systemOpen = false;
                            systemInFlight = false;
                            systemWaitingId = "";
                            systemPieces.clear();
                            forward = progressListener;
                            id = systemUtteranceId;
                        }
                        disarmWatch();
                        if (forward != null) {
                            forward.onStop(id, true);
                        }
                        return;
                    }
                    int ticket;
                    synchronized (streamLock) {
                        ticket = systemTicket;
                    }
                    postPieceFinished(ticket, utteranceId, false);
                    return;
                }
                if (!isSystemId(utteranceId) && progressListener != null) {
                    progressListener.onStop(utteranceId, interrupted);
                }
            }
        });
    }

    private boolean isWaitingPiece(String utteranceId) {
        synchronized (streamLock) {
            return isSystemPiece(utteranceId);
        }
    }

    private boolean isSystemPiece(String utteranceId) {
        return systemOpen && utteranceId != null && utteranceId.equals(systemWaitingId);
    }

    /** Ids queued by {@link #appendSystem}. Stale ones must not be forwarded. */
    private static boolean isSystemId(String utteranceId) {
        if (utteranceId == null) {
            return false;
        }
        int colon = utteranceId.indexOf(':');
        if (colon <= 0) {
            return false;
        }
        for (int i = 0; i < colon; i++) {
            if (!Character.isDigit(utteranceId.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private void deliverError(String utteranceId) {
        UtteranceProgressListener forward = null;
        String id = utteranceId;
        boolean ours = false;
        synchronized (streamLock) {
            if (isSystemPiece(utteranceId)) {
                systemOpen = false;
                systemInFlight = false;
                systemWaitingId = "";
                systemPieces.clear();
                forward = progressListener;
                id = systemUtteranceId;
                ours = true;
            } else if (!isSystemId(utteranceId)) {
                forward = progressListener;
            }
        }
        if (ours) {
            disarmWatch();
        }
        if (forward != null) {
            forward.onError(id);
        }
    }

    private void postDone(final String id) {
        final UtteranceProgressListener forward = progressListener;
        if (forward == null) {
            return;
        }
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                forward.onDone(id);
            }
        });
    }

    private void postError(final String id) {
        final UtteranceProgressListener forward = progressListener;
        if (forward == null) {
            return;
        }
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                forward.onError(id);
            }
        });
    }

    @Nullable
    public Voice getVoice() {
        if (tts != null) {
            return tts.getVoice();
        }
        return null;
    }

    @Nullable
    public Set<Voice> getVoices() {
        if (tts != null) {
            return tts.getVoices();
        }
        return null;
    }

    public int setOnUtteranceProgressListener(UtteranceProgressListener listener) {
        progressListener = listener;
        return TextToSpeech.SUCCESS;
    }

    public int setLanguage(CustomLocale loc, Context context) {
        currentLocale = loc;
        if (tts != null && loc != null) {
            return tts.setLanguage(new Locale(loc.getLocale().getLanguage()));
        }
        return tts != null ? TextToSpeech.ERROR : TextToSpeech.SUCCESS;
    }

    public int stop() {
        cosyStream = false;
        LiveDraftSpeaker.stop();
        disarmWatch();
        synchronized (streamLock) {
            systemOpen = false;
            systemInFlight = false;
            systemWaitingId = "";
            systemPending.setLength(0);
            systemPieces.clear();
            systemTicket++;
        }
        if (tts != null) {
            return tts.stop();
        }
        return TextToSpeech.SUCCESS;
    }

    public void shutdown() {
        LiveDraftSpeaker.release();
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
