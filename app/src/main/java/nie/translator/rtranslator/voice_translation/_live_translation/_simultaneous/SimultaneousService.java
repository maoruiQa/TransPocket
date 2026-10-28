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

package nie.translator.rtranslator.voice_translation._live_translation._simultaneous;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;

import nie.translator.rtranslator.Global;
import nie.translator.rtranslator.tools.AsrLanguages;
import nie.translator.rtranslator.tools.BluetoothRouteWake;
import nie.translator.rtranslator.tools.ClauseConfidence;
import nie.translator.rtranslator.tools.ClauseContextWindow;
import nie.translator.rtranslator.tools.SimultaneousText;
import nie.translator.rtranslator.bluetooth.Message;
import nie.translator.rtranslator.tools.CustomLocale;
import nie.translator.rtranslator.tools.Tools;
import nie.translator.rtranslator.tools.gui.messages.GuiMessage;
import nie.translator.rtranslator.voice_translation.VoiceTranslationService;
import nie.translator.rtranslator.voice_translation.neural_networks.translation.TranslationContext;
import nie.translator.rtranslator.voice_translation.neural_networks.translation.Translator;
import nie.translator.rtranslator.voice_translation.neural_networks.voice.Recognizer;
import nie.translator.rtranslator.voice_translation.neural_networks.voice.RecognizerListener;
import nie.translator.rtranslator.voice_translation.neural_networks.voice.ClauseVad;
import nie.translator.rtranslator.voice_translation.neural_networks.voice.FireRedClauseVad;
import nie.translator.rtranslator.voice_translation.neural_networks.voice.Recorder;
import nie.translator.rtranslator.voice_translation.neural_networks.voice.SileroClauseVad;


public class SimultaneousService extends VoiceTranslationService {
    public static final int SPEECH_BEAM_SIZE = 4;
    public static final int TRANSLATOR_BEAM_SIZE = 1;
    public static final int CHANGE_SOURCE_LANGUAGE = 30;
    public static final int CHANGE_TARGET_LANGUAGE = 31;

    public static boolean isRunning = false;
    /** AudioRecord capacity. The first clause detector run can stall; this keeps those samples. */
    private static final int CAPTURE_BUFFER_MILLIS = 4000;

    private Global global;
    private Translator translator;
    private Recognizer speechRecognizer;
    private RecognizerListener speechRecognizerCallback;
    private CustomLocale sourceLanguage;
    private CustomLocale targetLanguage;
    /** Each clause is translated from its detected language; sourceLanguage is only the first guess. */
    private boolean sourceAuto;
    /** Source language whose translation resources were requested last. */
    private CustomLocale loadedSourceLanguage;
    private boolean callbacksAttached = false;
    private final ArrayDeque<PendingClause> pendingClauses = new ArrayDeque<>();
    private final ArrayDeque<Long> clauseSubmittedAt = new ArrayDeque<>();
    private final ArrayDeque<Integer> clauseAudioMs = new ArrayDeque<>();
    private final ArrayDeque<Boolean> clauseUsedVad = new ArrayDeque<>();
    private final Object timingLock = new Object();
    private boolean translatingClause = false;
    private final ClauseContextWindow contextWindow = new ClauseContextWindow();
    private long nextMessageId = System.currentTimeMillis();
    private boolean ttsForTarget;
    /**
     * The input stays deaf for about two seconds after capture starts and again
     * when this screen comes back, including a return from settings. Speech in
     * that window is not recognized, so the bar stays up until it has passed.
     */
    private static final int PREPARE_MAX = 100;
    private static final int SPEECH_PROGRESS = 34;
    private static final int MIC_PROGRESS = 67;
    private static final int MIC_SETTLE_MILLIS = 2000;
    private static final int SETTLE_TICK_MILLIS = 100;
    private boolean languagesReady;
    private boolean captureReady;
    private boolean capturingSettled;
    private int prepareGeneration;
    private int settleGeneration;
    /** Silent media loop that keeps a connected Bluetooth headset awake for this session. */
    private final BluetoothRouteWake routeWake = new BluetoothRouteWake();

    @Override
    public void onCreate() {
        super.onCreate();
        isRunning = true;
        // The microphone stays deaf for MIC_SETTLE_MILLIS after it opens. The loop starts
        // now, so that wait is long enough for the headset to leave suspend before the first sentence.
        routeWake.attach(this);
        // The base class starts with the microphone marked on. This mode cannot hear yet.
        isMicActivated = false;
        global = (Global) getApplication();
        // The setting picks the state for this session. The toolbar button overrides it and is not saved.
        SharedPreferences sharedPreferences = getSharedPreferences("default", Context.MODE_PRIVATE);
        String autoSpeak = sharedPreferences.getString(SimultaneousAutoSpeak.KEY, SimultaneousAutoSpeak.AUTO);
        isAudioMute = SimultaneousAutoSpeak.isMuted(autoSpeak, Recorder.headphonesConnected(this));
        sourceLanguage = global.getSimultaneousSourceLanguage(true);
        targetLanguage = global.getSimultaneousTargetLanguage(true);
        sourceAuto = global.isLanguageAuto(Global.AUTO_SIMULTANEOUS_SOURCE);
        translator = global.getTranslator();
        speechRecognizer = global.getSpeechRecognizer();
        mVoiceCallback = new Recorder.Callback() {
            @Override
            public void onVoiceStart() {
                if (speechRecognizer != null && capturingSettled) {
                    super.onVoiceStart();
                    SimultaneousService.super.notifyVoiceStart();
                }
            }

            @Override
            public void onVoice(@NonNull float[] data, int size) {
                if (!capturingSettled) {
                    return;
                }
                if (speechRecognizer != null) {
                    super.onVoice(data, size);
                    int sampleRate = getVoiceRecorderSampleRate();
                    if (sampleRate != 0 && sourceLanguage != null) {
                        int audioMs = (size * 1000) / sampleRate;
                        synchronized (timingLock) {
                            clauseSubmittedAt.addLast(SystemClock.elapsedRealtime());
                            clauseAudioMs.addLast(audioMs);
                            clauseUsedVad.addLast(mVoiceRecorder != null && mVoiceRecorder.usesClauseVad());
                        }
                        String targetCode = targetLanguage != null ? targetLanguage.getCode() : null;
                        String sourceCode = sourceAuto ? AsrLanguages.AUTO : sourceLanguage.getCode();
                        speechRecognizer.recognizeLatest(data, SPEECH_BEAM_SIZE, sourceCode, targetCode);
                    }
                }
            }

            @Override
            public void onVoiceEnd() {
                if (speechRecognizer != null && capturingSettled) {
                    super.onVoiceEnd();
                    SimultaneousService.super.notifyVoiceEnd();
                }
            }

            @Override
            public void onVolumeLevel(float volumeLevel) {
                super.onVolumeLevel(volumeLevel);
                SimultaneousService.super.notifyVolumeLevel(volumeLevel);
            }
        };
        clientHandler = new Handler(new Handler.Callback() {
            @Override
            public boolean handleMessage(android.os.Message message) {
                int command = message.getData().getInt("command", -1);
                if (command != -1 && !SimultaneousService.super.executeCommand(command, message.getData())) {
                    switch (command) {
                        case START_SOUND: {
                            isAudioMute = false;
                            break;
                        }
                        case STOP_SOUND: {
                            isAudioMute = true;
                            if (ttsEngine != null) {
                                ttsEngine.stop();
                            }
                            break;
                        }
                        case CHANGE_SOURCE_LANGUAGE: {
                            CustomLocale language = (CustomLocale) message.getData().getSerializable("language");
                            if (language != null && language.isAuto()) {
                                sourceAuto = true;
                                clearClauseContext();
                            } else if (language != null) {
                                sourceAuto = false;
                                sourceLanguage = language;
                                clearClauseContext();
                                reloadLanguages();
                            }
                            break;
                        }
                        case CHANGE_TARGET_LANGUAGE: {
                            CustomLocale language = (CustomLocale) message.getData().getSerializable("language");
                            if (language != null) {
                                targetLanguage = language;
                                clearClauseContext();
                                reloadLanguages();
                                refreshTtsLanguage();
                            }
                            break;
                        }
                    }
                }
                return false;
            }
        });
        speechRecognizerCallback = new RecognizerListener() {
            @Override
            public void onSpeechRecognizedResult(String text, String languageCode, double confidenceScore, boolean isFinal) {
                final long asrDone = SystemClock.elapsedRealtime();
                final Long submitted;
                final Integer audioMs;
                final Boolean usedVad;
                synchronized (timingLock) {
                    submitted = clauseSubmittedAt.pollFirst();
                    audioMs = clauseAudioMs.pollFirst();
                    usedVad = clauseUsedVad.pollFirst();
                }
                mainHandler.post(() -> handleRecognition(text, languageCode, confidenceScore, isFinal, submitted == null ? asrDone : submitted, asrDone, audioMs == null ? 0 : audioMs, Boolean.TRUE.equals(usedVad)));
            }

            @Override
            public void onError(int[] reasons, long value) {
                if (mVoiceRecorder != null) {
                    mVoiceRecorder.end();
                }
                SimultaneousService.super.notifyError(reasons, value);
            }
        };
    }

    @Override
    public int onStartCommand(android.content.Intent intent, int flags, int startId) {
        int startType = super.onStartCommand(intent, flags, startId);
        if (intent != null) {
            CustomLocale source = (CustomLocale) intent.getSerializableExtra("sourceLanguage");
            CustomLocale target = (CustomLocale) intent.getSerializableExtra("targetLanguage");
            if (source != null) {
                sourceLanguage = source;
            }
            sourceAuto = global.isLanguageAuto(Global.AUTO_SIMULTANEOUS_SOURCE);
            if (target != null) {
                targetLanguage = target;
            }
        }
        if (!callbacksAttached && speechRecognizer != null) {
            callbacksAttached = true;
            speechRecognizer.addCallback(speechRecognizerCallback);
            reloadLanguages();
            refreshTtsLanguage();
        }
        return startType;
    }

    @Override
    protected void onTtsPlaybackStarted(boolean first) {
        if (mVoiceRecorder != null) {
            mVoiceRecorder.setTtsPlaying(true);
        }
    }

    @Override
    protected void onTtsPlaybackFinished(boolean last) {
        if (last && mVoiceRecorder != null) {
            mVoiceRecorder.setTtsPlaying(false);
        }
    }

    private void reloadLanguages() {
        if (translator == null || sourceLanguage == null || targetLanguage == null) {
            languagesReady = true;
            beginCapture();
            return;
        }
        final boolean opening = !captureReady;
        if (opening) {
            languagesReady = false;
            notifyModelLoading(0, PREPARE_MAX);
        }
        final int generation = ++prepareGeneration;
        loadedSourceLanguage = sourceLanguage;
        translator.loadLanguageResources(sourceLanguage, targetLanguage, Global.RTranslatorMode.SIMULTANEOUS_MODE, new Translator.GeneralListener() {
            @Override
            public void onSuccess() {
                if (generation != prepareGeneration) {
                    return;
                }
                if (!opening) {
                    languagesReady = true;
                    return;
                }
                notifyModelLoading(SPEECH_PROGRESS, PREPARE_MAX);
                new Thread(() -> {
                    try {
                        if (speechRecognizer != null) {
                            speechRecognizer.warmup();
                        }
                    } finally {
                        mainHandler.post(() -> {
                            if (generation != prepareGeneration) {
                                return;
                            }
                            languagesReady = true;
                            beginCapture();
                        });
                    }
                }, "speech-warmup").start();
            }

            @Override
            public void onFailure(int[] reasons, long value) {
                notifyError(reasons, value);
            }
        });
    }

    /**
     * Opens the microphone only after translation resources and the clause detector are ready.
     * Audio from before that point cannot be recognized, so the screen stays on the waiting message.
     */
    private void beginCapture() {
        if (!languagesReady || captureReady) {
            return;
        }
        if (!Tools.hasPermissions(this, Global.REQUIRED_PERMISSIONS_VOICE)) {
            return;
        }
        initializeVoiceRecorder();
        if (mVoiceRecorder == null) {
            return;
        }
        captureReady = true;
        if (isMicMute) {
            notifyMicActivated();
            return;
        }
        ensureRecording();
        beginMicSettle();
    }

    /**
     * Opens the input, then holds recognition until {@link #MIC_SETTLE_MILLIS} of
     * capture has passed. A screen that comes back here starts this again.
     */
    private void beginMicSettle() {
        if (!captureReady || isMicMute || mVoiceRecorder == null) {
            return;
        }
        final int generation = ++settleGeneration;
        routeWake.sync();
        capturingSettled = false;
        isMicActivated = false;
        ensureRecording();
        mVoiceRecorder.discardPendingSpeech();
        publishSettle(generation, SystemClock.elapsedRealtime());
    }

    private void publishSettle(int generation, long started) {
        mainHandler.post(() -> {
            if (generation != settleGeneration) {
                return;
            }
            long elapsed = SystemClock.elapsedRealtime() - started;
            if (elapsed >= MIC_SETTLE_MILLIS) {
                capturingSettled = true;
                if (mVoiceRecorder != null) {
                    mVoiceRecorder.discardPendingSpeech();
                }
                notifyModelLoading(PREPARE_MAX, PREPARE_MAX);
                notifyMicActivated();
                return;
            }
            int progress = MIC_PROGRESS + (int) ((PREPARE_MAX - MIC_PROGRESS) * elapsed / MIC_SETTLE_MILLIS);
            if (progress >= PREPARE_MAX) {
                progress = PREPARE_MAX - 1;
            }
            notifyModelLoading(progress, PREPARE_MAX);
            mainHandler.postDelayed(() -> publishSettle(generation, started), SETTLE_TICK_MILLIS);
        });
    }

    private void ensureRecording() {
        if (isMicMute) {
            return;
        }
        if (mVoiceRecorder == null) {
            initializeVoiceRecorder();
        }
        if (mVoiceRecorder != null && !mVoiceRecorder.isSessionActive()) {
            super.startVoiceRecorder();
        }
    }

    @Override
    public void startVoiceRecorder() {
        if (!languagesReady) {
            return;
        }
        if (!captureReady) {
            beginCapture();
            return;
        }
        if (isMicMute || (mVoiceRecorder != null && mVoiceRecorder.isSessionActive())) {
            return;
        }
        ensureRecording();
        beginMicSettle();
    }

    @Override
    protected boolean executeCommand(int command, Bundle data) {
        boolean handled = super.executeCommand(command, data);
        if (command == STOP_MIC) {
            settleGeneration++;
        } else if (command == INITIALIZE_COMMUNICATION && captureReady && !isMicMute) {
            beginMicSettle();
        }
        return handled;
    }

    private void clearClauseContext() {
        contextWindow.clear();
    }

    private void refreshTtsLanguage() {
        if (global == null || targetLanguage == null) {
            ttsForTarget = false;
            return;
        }
        global.getTTSLanguages(true, new Global.GetLocalesListListener() {
            @Override
            public void onSuccess(ArrayList<CustomLocale> ttsLanguages) {
                ttsForTarget = CustomLocale.containsLanguage(ttsLanguages, targetLanguage);
            }

            @Override
            public void onFailure(int[] reasons, long value) {
                ttsForTarget = false;
            }
        });
    }

    private void handleRecognition(String text, String languageCode, double asrScore, boolean isFinal, long submittedAt, long asrDone, int audioMs, boolean vadClause) {
        if (text != null && !text.isEmpty() && isFinal) {
            android.util.Log.i("simultaneous", "recognized vad=" + vadClause + " audioMs=" + audioMs + " text=" + text);
        }
        if (text == null || text.isEmpty() || isMetaText(text) || !isFinal || sourceLanguage == null || targetLanguage == null) {
            return;
        }
        CustomLocale clauseLanguage = sourceLanguage;
        if (sourceAuto) {
            clauseLanguage = global.findDetectedLanguage(languageCode, Global.RTranslatorMode.SIMULTANEOUS_MODE);
            // Speech already in the target language has nothing to translate and would only pollute the context.
            if (clauseLanguage == null || clauseLanguage.equalsLanguage(targetLanguage)) {
                android.util.Log.i("simultaneous", "skipped clause detected=" + languageCode);
                return;
            }
        }
        long asrMs = Math.max(0, asrDone - submittedAt);
        // Graded on the whole result: the speaking rate needs the text that matches the audio length.
        int asrLevel = ClauseConfidence.asrLevel(text, asrScore, audioMs);
        android.util.Log.i("simultaneous", "asr score=" + asrScore + " audioMs=" + audioMs + " level=" + ClauseConfidence.name(asrLevel));
        ArrayList<String> clauses = vadClause ? vadClauses(text) : ClauseSplitter.split(text);
        if (clauses.isEmpty()) {
            return;
        }
        for (String clause : clauses) {
            PendingClause pending = new PendingClause();
            pending.id = nextMessageId++;
            pending.text = clause;
            pending.language = clauseLanguage;
            pending.audioMs = audioMs;
            pending.asrMs = asrMs;
            pending.submittedAt = submittedAt;
            pending.asrLevel = asrLevel;
            publishClause(pending, "", false);
            pendingClauses.add(pending);
        }
        drainClauses();
    }

    private void drainClauses() {
        if (translatingClause || sourceLanguage == null || targetLanguage == null) {
            return;
        }
        final PendingClause clause = pendingClauses.poll();
        if (clause == null) {
            return;
        }
        translatingClause = true;
        translate(clause);
    }

    private void clauseFinished() {
        translatingClause = false;
        drainClauses();
    }

    private void translate(PendingClause clause) {
        if (translator == null) {
            clauseFinished();
            return;
        }
        if (!clause.language.equals(loadedSourceLanguage)) {
            // The previous clause was in another language; its context would mislead this one.
            clearClauseContext();
            loadedSourceLanguage = clause.language;
            translator.loadLanguageResources(clause.language, targetLanguage, Global.RTranslatorMode.SIMULTANEOUS_MODE, new Translator.GeneralListener() {
                @Override
                public void onSuccess() {
                    performTranslation(clause);
                }

                @Override
                public void onFailure(int[] reasons, long value) {
                    loadedSourceLanguage = null;
                    SimultaneousService.super.notifyError(reasons, value);
                    clauseFinished();
                }
            });
            return;
        }
        performTranslation(clause);
    }

    private void performTranslation(PendingClause clause) {
        final boolean assess = global.isTranslationAssessment();
        final TranslationContext context = clauseContext(clause, assess);
        final long translateStarted = SystemClock.elapsedRealtime();
        translator.translate(clause.text, context, clause.language, targetLanguage, TRANSLATOR_BEAM_SIZE, false, Global.RTranslatorMode.SIMULTANEOUS_MODE, new Translator.TranslateListener() {
            @Override
            public void onTranslationRating(long resultID, int rating) {
                clause.modelRating = rating;
            }

            @Override
            public void onTranslatedText(String textToTranslate, String translatedText, @Nullable String[] synonyms, long resultID, boolean isFinal, ResultType resultType, CustomLocale inputLanguage, CustomLocale outputLanguage) {
                String shown = translatedText == null ? "" : translatedText;
                if (!clause.sawTranslation && !shown.isEmpty()) {
                    clause.sawTranslation = true;
                    clause.subtitleMs = SystemClock.elapsedRealtime() - clause.submittedAt;
                }
                if (isFinal && assess) {
                    int signals = ClauseConfidence.lowest(clause.asrLevel, ClauseConfidence.translationLevel(clause.text, shown, context.lastTranslation, clause.language.equalsLanguage(targetLanguage)));
                    clause.confidence = ClauseConfidence.withModelRating(signals, clause.modelRating);
                }
                publishClause(clause, shown, isFinal);
                feedTts(clause, shown, isFinal);
                if (!isFinal) {
                    return;
                }
                rememberClause(clause, shown, assess);
                android.util.Log.i("performance", "simultaneous audioMs=" + clause.audioMs + " asrMs=" + clause.asrMs + " translateMs=" + (SystemClock.elapsedRealtime() - translateStarted) + " subtitleMs=" + clause.subtitleMs);
                clauseFinished();
            }

            @Override
            public void onFailure(int[] reasons, long value) {
                SimultaneousService.super.notifyError(reasons, value);
                clauseFinished();
            }
        });
    }

    /** The earlier clauses as each engine takes them; none when context is switched off. */
    private TranslationContext clauseContext(PendingClause clause, boolean assess) {
        if (global.isSimultaneousIgnoreContext()) {
            return TranslationContext.withoutHistory(clause.asrLevel, assess);
        }
        long now = SystemClock.elapsedRealtime();
        StringBuilder joined = new StringBuilder();
        for (ClauseContextWindow.Entry entry : contextWindow.trusted(now)) {
            if (joined.length() > 0) {
                joined.append('\n');
            }
            joined.append(entry.source);
        }
        ArrayList<TranslationContext.Line> lines = new ArrayList<>();
        for (ClauseContextWindow.Entry entry : contextWindow.lines(now)) {
            lines.add(new TranslationContext.Line(entry.source, entry.isDoubtful()));
        }
        ClauseContextWindow.Entry last = contextWindow.lastTrusted(now);
        android.util.Log.i("simultaneous", "context trusted=" + (joined.length() == 0 ? "-" : joined.toString().replace('\n', '|')) + " lines=" + lines.size());
        return new TranslationContext(last == null ? null : last.source, last == null ? null : last.translation, joined.length() == 0 ? null : joined.toString(), lines, clause.asrLevel, assess);
    }

    /**
     * A misheard clause stays out of the context of the next ones. A short fragment can be
     * shown without a mark yet still be too thin to trust as context.
     */
    private void rememberClause(PendingClause clause, String translation, boolean assess) {
        long now = SystemClock.elapsedRealtime();
        if (!assess) {
            // Without grading only the previous clause is context, as before.
            contextWindow.clear();
            contextWindow.add(clause.text, translation, ClauseConfidence.UNKNOWN, now);
            return;
        }
        int contextLevel = ClauseConfidence.lowest(clause.confidence, ClauseConfidence.fragmentLevel(clause.text));
        contextWindow.add(clause.text, translation, contextLevel, now);
        android.util.Log.i("simultaneous", "graded asr=" + ClauseConfidence.name(clause.asrLevel) + " model=" + ClauseConfidence.name(clause.modelRating)
                + " shown=" + ClauseConfidence.name(clause.confidence) + " context=" + ClauseConfidence.name(contextLevel) + " text=" + clause.text);
    }

    private void publishClause(PendingClause clause, String translation, boolean isFinal) {
        GuiMessage message = new GuiMessage(new Message(clause.text, SimultaneousService.this, translation), clause.language, targetLanguage, clause.id, false, isFinal);
        message.setConfidence(isFinal ? clause.confidence : ClauseConfidence.UNKNOWN);
        notifyMessage(message);
        addOrUpdateMessage(message);
    }

    private void feedTts(PendingClause clause, String translation, boolean isFinal) {
        if (isAudioMute || !ttsForTarget || translation == null || targetLanguage == null) {
            return;
        }
        if (!translation.startsWith(clause.spoken)) {
            return;
        }
        String pending = translation.substring(clause.spoken.length());
        java.util.ArrayList<String> pieces = SimultaneousText.speakablePieces(pending, isFinal);
        int audible = 0;
        for (String piece : pieces) {
            if (piece.trim().length() > 0) {
                audible++;
            }
        }
        if (audible == 0) {
            if (isFinal) {
                speakPiece(new GuiMessage(new Message("", SimultaneousService.this, ""), targetLanguage, targetLanguage, clause.id, false, true), targetLanguage, true);
            }
            return;
        }
        int spokenAudible = 0;
        for (String piece : pieces) {
            clause.spoken = clause.spoken + piece;
            if (piece.trim().isEmpty()) {
                continue;
            }
            spokenAudible++;
            GuiMessage spoken = new GuiMessage(new Message("", SimultaneousService.this, piece), targetLanguage, targetLanguage, clause.id, false, true);
            speakPiece(spoken, targetLanguage, isFinal && spokenAudible == audible);
        }
    }

    private static final class PendingClause {
        private long id;
        private String text;
        private CustomLocale language;
        private String spoken = "";
        private int audioMs;
        private long asrMs;
        private long submittedAt;
        private long subtitleMs;
        private boolean sawTranslation;
        private int asrLevel = ClauseConfidence.UNKNOWN;
        private int modelRating = ClauseConfidence.UNKNOWN;
        /** Final grade shown on the message. */
        private int confidence = ClauseConfidence.UNKNOWN;
    }

    @Override
    public void initializeVoiceRecorder() {
        if (mVoiceRecorder != null) {
            return;
        }
        if (Tools.hasPermissions(this, Global.REQUIRED_PERMISSIONS_VOICE)) {
            mVoiceRecorder = new Recorder(global, true, mVoiceCallback, null, global.getVad(), CAPTURE_BUFFER_MILLIS);
            mVoiceRecorder.setClauseSegmentation(true);
            if (global.isSimultaneousVadSegmentation()) {
                ClauseVad clauseVad = global.getVadModel() == Global.VAD_FIRERED
                        ? FireRedClauseVad.open(global.getFireRedVadFile(), global.getFireRedVadCmvnFile())
                        : SileroClauseVad.open(global.getSileroVadFile());
                if (clauseVad == null) {
                    android.util.Log.i("simultaneous", "VAD segmentation unavailable, using pause and punctuation");
                } else {
                    clauseVad.warmup();
                    mVoiceRecorder.setClauseVad(clauseVad);
                }
            }
        }
    }

    /** One recognition is already one clause. Punctuation is not applied again. */
    private static ArrayList<String> vadClauses(String text) {
        ArrayList<String> clauses = new ArrayList<>();
        String clause = text.trim();
        if (clause.length() >= 2) {
            clauses.add(clause);
        }
        return clauses;
    }

    @Override
    protected boolean shouldDeactivateMicDuringTTS() {
        // Playback must not turn the microphone off. Clauses keep being recorded while earlier ones are spoken.
        return false;
    }

    @Override
    protected boolean isBluetoothHeadsetConnected() {
        return mVoiceRecorder != null && mVoiceRecorder.isOnHeadsetSco();
    }

    @Override
    public void onDestroy() {
        settleGeneration++;
        routeWake.detach();
        if (speechRecognizer != null && speechRecognizerCallback != null) {
            speechRecognizer.removeCallback(speechRecognizerCallback);
            speechRecognizer.stop();
        }
        speechRecognizer = null;
        if (translator != null) {
            translator.unloadSimultaneousLanguageResources(null);
        }
        isRunning = false;
        super.onDestroy();
    }

    public static class SimultaneousServiceCommunicator extends VoiceTranslationServiceCommunicator {
        public SimultaneousServiceCommunicator(int id) {
            super(id);
            super.serviceHandler = new Handler(new Handler.Callback() {
                @Override
                public boolean handleMessage(@NonNull android.os.Message msg) {
                    int callbackMessage = msg.getData().getInt("callback", -1);
                    executeCallback(callbackMessage, msg.getData());
                    return true;
                }
            });
        }

        public void changeSourceLanguage(CustomLocale language) {
            Bundle bundle = new Bundle();
            bundle.putInt("command", CHANGE_SOURCE_LANGUAGE);
            bundle.putSerializable("language", language);
            super.sendToService(bundle);
        }

        public void changeTargetLanguage(CustomLocale language) {
            Bundle bundle = new Bundle();
            bundle.putInt("command", CHANGE_TARGET_LANGUAGE);
            bundle.putSerializable("language", language);
            super.sendToService(bundle);
        }
    }
}
