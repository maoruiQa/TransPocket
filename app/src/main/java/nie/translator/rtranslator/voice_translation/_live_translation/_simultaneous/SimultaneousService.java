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
import nie.translator.rtranslator.tools.SimultaneousText;
import nie.translator.rtranslator.bluetooth.Message;
import nie.translator.rtranslator.tools.CustomLocale;
import nie.translator.rtranslator.tools.Tools;
import nie.translator.rtranslator.tools.gui.messages.GuiMessage;
import nie.translator.rtranslator.voice_translation.VoiceTranslationService;
import nie.translator.rtranslator.voice_translation.neural_networks.translation.Translator;
import nie.translator.rtranslator.voice_translation.neural_networks.voice.Recognizer;
import nie.translator.rtranslator.voice_translation.neural_networks.voice.RecognizerListener;
import nie.translator.rtranslator.voice_translation.neural_networks.voice.Recorder;
import nie.translator.rtranslator.voice_translation.neural_networks.voice.SileroClauseVad;


public class SimultaneousService extends VoiceTranslationService {
    public static final int SPEECH_BEAM_SIZE = 4;
    public static final int TRANSLATOR_BEAM_SIZE = 1;
    public static final int CHANGE_SOURCE_LANGUAGE = 30;
    public static final int CHANGE_TARGET_LANGUAGE = 31;

    public static boolean isRunning = false;

    private Global global;
    private Translator translator;
    private Recognizer speechRecognizer;
    private RecognizerListener speechRecognizerCallback;
    private CustomLocale sourceLanguage;
    private CustomLocale targetLanguage;
    private boolean callbacksAttached = false;
    private final ArrayDeque<PendingClause> pendingClauses = new ArrayDeque<>();
    private final ArrayDeque<Long> clauseSubmittedAt = new ArrayDeque<>();
    private final ArrayDeque<Integer> clauseAudioMs = new ArrayDeque<>();
    private final ArrayDeque<Boolean> clauseUsedVad = new ArrayDeque<>();
    private final Object timingLock = new Object();
    private boolean translatingClause = false;
    private String previousSource;
    private String previousTranslation;
    private long nextMessageId = System.currentTimeMillis();
    private boolean ttsForTarget;

    @Override
    public void onCreate() {
        super.onCreate();
        isRunning = true;
        global = (Global) getApplication();
        // The setting picks the state for this session. The toolbar button overrides it and is not saved.
        SharedPreferences sharedPreferences = getSharedPreferences("default", Context.MODE_PRIVATE);
        String autoSpeak = sharedPreferences.getString(SimultaneousAutoSpeak.KEY, SimultaneousAutoSpeak.AUTO);
        isAudioMute = SimultaneousAutoSpeak.isMuted(autoSpeak, Recorder.headphonesConnected(this));
        sourceLanguage = global.getSimultaneousSourceLanguage(true);
        targetLanguage = global.getSimultaneousTargetLanguage(true);
        translator = global.getTranslator();
        speechRecognizer = global.getSpeechRecognizer();
        mVoiceCallback = new Recorder.Callback() {
            @Override
            public void onVoiceStart() {
                if (speechRecognizer != null) {
                    super.onVoiceStart();
                    SimultaneousService.super.notifyVoiceStart();
                }
            }

            @Override
            public void onVoice(@NonNull float[] data, int size) {
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
                        speechRecognizer.recognizeLatest(data, SPEECH_BEAM_SIZE, sourceLanguage.getCode());
                    }
                }
            }

            @Override
            public void onVoiceEnd() {
                if (speechRecognizer != null) {
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
                            if (language != null) {
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
                mainHandler.post(() -> handleRecognition(text, isFinal, submitted == null ? asrDone : submitted, asrDone, audioMs == null ? 0 : audioMs, Boolean.TRUE.equals(usedVad)));
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
            initializeVoiceRecorder();
            return;
        }
        translator.loadLanguageResources(sourceLanguage, targetLanguage, Global.RTranslatorMode.SIMULTANEOUS_MODE, new Translator.GeneralListener() {
            @Override
            public void onSuccess() {
                initializeVoiceRecorder();
            }

            @Override
            public void onFailure(int[] reasons, long value) {
                notifyError(reasons, value);
            }
        });
    }

    private void clearClauseContext() {
        previousSource = null;
        previousTranslation = null;
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

    private void handleRecognition(String text, boolean isFinal, long submittedAt, long asrDone, int audioMs, boolean vadClause) {
        if (text == null || text.isEmpty() || isMetaText(text) || !isFinal || sourceLanguage == null || targetLanguage == null) {
            return;
        }
        long asrMs = Math.max(0, asrDone - submittedAt);
        ArrayList<String> clauses = vadClause ? vadClauses(text) : ClauseSplitter.split(text);
        if (clauses.isEmpty()) {
            return;
        }
        for (String clause : clauses) {
            PendingClause pending = new PendingClause();
            pending.id = nextMessageId++;
            pending.text = clause;
            pending.audioMs = audioMs;
            pending.asrMs = asrMs;
            pending.submittedAt = submittedAt;
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
        final String contextSource = previousSource;
        final String contextTranslation = previousTranslation;
        final long translateStarted = SystemClock.elapsedRealtime();
        translator.translate(clause.text, contextSource, contextTranslation, sourceLanguage, targetLanguage, TRANSLATOR_BEAM_SIZE, false, Global.RTranslatorMode.SIMULTANEOUS_MODE, new Translator.TranslateListener() {
            @Override
            public void onTranslatedText(String textToTranslate, String translatedText, @Nullable String[] synonyms, long resultID, boolean isFinal, ResultType resultType, CustomLocale inputLanguage, CustomLocale outputLanguage) {
                String shown = translatedText == null ? "" : translatedText;
                if (!clause.sawTranslation && !shown.isEmpty()) {
                    clause.sawTranslation = true;
                    clause.subtitleMs = SystemClock.elapsedRealtime() - clause.submittedAt;
                }
                publishClause(clause, shown, isFinal);
                feedTts(clause, shown, isFinal);
                if (!isFinal) {
                    return;
                }
                previousSource = clause.text;
                previousTranslation = shown;
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

    private void publishClause(PendingClause clause, String translation, boolean isFinal) {
        GuiMessage message = new GuiMessage(new Message(clause.text, SimultaneousService.this, translation), sourceLanguage, targetLanguage, clause.id, false, isFinal);
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
        String chunk = SimultaneousText.takeSpeakable(pending, isFinal);
        if (chunk == null || chunk.trim().isEmpty()) {
            return;
        }
        clause.spoken = clause.spoken + chunk;
        GuiMessage spoken = new GuiMessage(new Message("", SimultaneousService.this, chunk), targetLanguage, targetLanguage, clause.id, false, true);
        speak(spoken, targetLanguage);
    }

    private static final class PendingClause {
        private long id;
        private String text;
        private String spoken = "";
        private int audioMs;
        private long asrMs;
        private long submittedAt;
        private long subtitleMs;
        private boolean sawTranslation;
    }

    @Override
    public void initializeVoiceRecorder() {
        if (mVoiceRecorder != null) {
            return;
        }
        if (Tools.hasPermissions(this, Global.REQUIRED_PERMISSIONS_VOICE)) {
            mVoiceRecorder = new Recorder(global, true, mVoiceCallback, null, global.getVad());
            mVoiceRecorder.setClauseSegmentation(true);
            if (global.isSimultaneousVadSegmentation()) {
                SileroClauseVad clauseVad = SileroClauseVad.open(global.getSileroVadFile());
                if (clauseVad == null) {
                    android.util.Log.i("simultaneous", "VAD segmentation unavailable, using pause and punctuation");
                } else {
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
