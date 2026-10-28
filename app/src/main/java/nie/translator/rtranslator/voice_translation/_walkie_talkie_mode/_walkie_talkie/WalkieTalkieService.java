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

package nie.translator.rtranslator.voice_translation._walkie_talkie_mode._walkie_talkie;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.ArrayList;

import nie.translator.rtranslator.Global;
import nie.translator.rtranslator.bluetooth.Message;
import nie.translator.rtranslator.bluetooth.Peer;
import nie.translator.rtranslator.tools.AsrLanguages;
import nie.translator.rtranslator.tools.CustomLocale;
import nie.translator.rtranslator.tools.ErrorCodes;
import nie.translator.rtranslator.tools.SimultaneousText;
import nie.translator.rtranslator.tools.Tools;
import nie.translator.rtranslator.tools.gui.messages.GuiMessage;
import nie.translator.rtranslator.voice_translation.VoiceTranslationService;
import nie.translator.rtranslator.voice_translation.neural_networks.NeuralNetworkApiResult;
import nie.translator.rtranslator.voice_translation.neural_networks.translation.LanguageDetector;
import nie.translator.rtranslator.voice_translation.neural_networks.translation.Translator;
import nie.translator.rtranslator.voice_translation.neural_networks.voice.Recognizer;
import nie.translator.rtranslator.voice_translation.neural_networks.voice.RecognizerListener;
import nie.translator.rtranslator.voice_translation.neural_networks.voice.RecognizerMultiListener;
import nie.translator.rtranslator.voice_translation.neural_networks.voice.Recorder;


public class WalkieTalkieService extends VoiceTranslationService {
    private static final int TIME_BEFORE_REACTIVATING_TTS_AFTER_VOICE_END = 500;
    //properties
    public static final int SPEECH_BEAM_SIZE = 1;
    public static final int TRANSLATOR_BEAM_SIZE = 1;

    // commands
    public static final int CHANGE_FIRST_LANGUAGE = 22;
    public static final int CHANGE_SECOND_LANGUAGE = 23;
    public static final int GET_FIRST_LANGUAGE = 24;
    public static final int GET_SECOND_LANGUAGE = 25;
    public static final int START_MANUAL_RECOGNITION = 26;
    public static final int STOP_MANUAL_RECOGNITION = 27;
    public static final int START_RECOGNIZING_FIRST_LANGUAGE = 28;
    public static final int STOP_RECOGNIZING_FIRST_LANGUAGE = 29;
    public static final int START_RECOGNIZING_SECOND_LANGUAGE = 30;
    public static final int STOP_RECOGNIZING_SECOND_LANGUAGE = 31;
    public static final int START_RECOGNIZING_AUTO_LANGUAGE = 32;
    public static final int STOP_RECOGNIZING_AUTO_LANGUAGE = 33;
    // callbacks
    public static final int ON_FIRST_LANGUAGE = 22;
    public static final int ON_SECOND_LANGUAGE = 23;
    private RecognizerMultiListener speechRecognizerCallback;
    private RecognizerListener speechRecognizerSingleCallback;

    // objects
    public static boolean isRunning = false;
    private Translator translator;
    private Recognizer speechRecognizer;
    private CustomLocale firstLanguage;
    private CustomLocale secondLanguage;
    /**
     * One side may be detected instead of fixed. Its language field then holds the language
     * that side spoke last, which is also where the fixed side is translated into.
     */
    private boolean firstAuto;
    private boolean secondAuto;
    /** The pending single-language request asked the recognizer to detect the language. */
    private boolean autoRecognition;
    private Translator.TranslateListener firstResultTranslateListener;
    private Translator.TranslateListener secondResultTranslateListener;
    // Holds the microphone until the final TTS chunk of the current translation is queued.
    // An earlier chunk can finish while the decoder is still generating.
    private final Object speechLock = new Object();
    private int speechGeneration = 0;
    private String spokenPrefix = "";
    private long spokenResultId = -1;
    private boolean generationOpen = false;
    private boolean finalSpeechQueued = false;
    private static final int PREPARE_MAX = 2;
    /** The speech model has had its cold start. Capture before this drops the opening words. */
    private boolean hearingReady;


    @Override
    public void onCreate() {
        super.onCreate();
        isRunning = true;
        isMicActivated = false;
        translator = ((Global) getApplication()).getTranslator();
        speechRecognizer = ((Global) getApplication()).getSpeechRecognizer();
        SharedPreferences sharedPreferences = this.getSharedPreferences("default", Context.MODE_PRIVATE);
        isAudioMute = !sharedPreferences.getBoolean("walkieTalkieAutoTTS", true);
        clientHandler = new Handler(new Handler.Callback() {
            @Override
            public boolean handleMessage(@NonNull android.os.Message message) {
                int command = message.getData().getInt("command", -1);
                if (command != -1) {
                    if (!WalkieTalkieService.super.executeCommand(command, message.getData())) {
                        switch (command) {
                            case START_SOUND: {
                                isAudioMute = false;
                                SharedPreferences sharedPreferences = WalkieTalkieService.this.getSharedPreferences("default", Context.MODE_PRIVATE);
                                SharedPreferences.Editor editor = sharedPreferences.edit();
                                editor.putBoolean("walkieTalkieAutoTTS", true);
                                editor.apply();
                                break;
                            }
                            case STOP_SOUND: {
                                isAudioMute = true;
                                SharedPreferences sharedPreferences = WalkieTalkieService.this.getSharedPreferences("default", Context.MODE_PRIVATE);
                                SharedPreferences.Editor editor = sharedPreferences.edit();
                                editor.putBoolean("walkieTalkieAutoTTS", false);
                                editor.apply();
                                if(ttsEngine != null) {
                                    ttsEngine.stop();
                                }
                                break;
                            }
                            case CHANGE_FIRST_LANGUAGE:
                                CustomLocale newFirstLanguage = (CustomLocale) message.getData().getSerializable("language");
                                if (newFirstLanguage != null && !newFirstLanguage.isAuto() && !firstLanguage.equals(newFirstLanguage)) {
                                    firstLanguage = newFirstLanguage;
                                }
                                readAutoLanguages();
                                break;
                            case CHANGE_SECOND_LANGUAGE:
                                CustomLocale newSecondLanguage = (CustomLocale) message.getData().getSerializable("language");
                                if (newSecondLanguage != null && !newSecondLanguage.isAuto() && !secondLanguage.equals(newSecondLanguage)) {
                                    secondLanguage = newSecondLanguage;
                                }
                                readAutoLanguages();
                                break;
                            case GET_FIRST_LANGUAGE:
                                Bundle bundle = new Bundle();
                                bundle.putInt("callback", ON_FIRST_LANGUAGE);
                                bundle.putSerializable("language", firstLanguage);
                                WalkieTalkieService.super.notifyToClient(bundle);
                                break;
                            case GET_SECOND_LANGUAGE:
                                Bundle bundle2 = new Bundle();
                                bundle2.putInt("callback", ON_SECOND_LANGUAGE);
                                bundle2.putSerializable("language", secondLanguage);
                                WalkieTalkieService.super.notifyToClient(bundle2);
                                break;
                            case RECEIVE_TEXT:
                                String text = message.getData().getString("text", null);
                                if (text != null) {
                                    //we stop speech recognition
                                    stopVoiceRecorder();
                                    notifyMicDeactivated();   // we notify the client
                                    if (firstAuto || secondAuto) {
                                        // A detected side can be any language, so the text is not limited to the two.
                                        translator.detectTextLanguage(text, new LanguageDetector.DetectLanguageListener() {
                                            @Override
                                            public void onSuccess(String languageCode) {
                                                translateDetected(text, languageCode);
                                            }
                                        });
                                        break;
                                    }
                                    translator.detectLanguage(new NeuralNetworkApiResult(text), true, new CustomLocale[]{firstLanguage, secondLanguage}, new Translator.DetectLanguageListener() {
                                        @Override
                                        public void onDetectedText(final NeuralNetworkApiResult result) {
                                            // here the result returns with the same language as the text sent from the Fragment editText
                                            if (result.getLanguage().equalsLanguage(firstLanguage)) {
                                                translate(result.getText(), firstLanguage, secondLanguage, TRANSLATOR_BEAM_SIZE, false, firstResultTranslateListener);
                                            } else {
                                                translate(result.getText(), secondLanguage, firstLanguage, TRANSLATOR_BEAM_SIZE, false, secondResultTranslateListener);
                                            }
                                        }

                                        @Override
                                        public void onFailure(int[] reasons, long value) {
                                            // detectLanguage leaves texts of MIN_WORDS words or more to the speech
                                            // confidence, which typed text does not have; FastText decides those.
                                            translator.detectTextLanguage(text, new LanguageDetector.DetectLanguageListener() {
                                                @Override
                                                public void onSuccess(String languageCode) {
                                                    CustomLocale detected = ((Global) getApplication()).findDetectedLanguage(languageCode, Global.RTranslatorMode.WALKIE_TALKIE_MODE);
                                                    if (detected != null && detected.equalsLanguage(firstLanguage)) {
                                                        translate(text, firstLanguage, secondLanguage, TRANSLATOR_BEAM_SIZE, false, firstResultTranslateListener);
                                                    } else if (detected != null && detected.equalsLanguage(secondLanguage)) {
                                                        translate(text, secondLanguage, firstLanguage, TRANSLATOR_BEAM_SIZE, false, secondResultTranslateListener);
                                                    } else {
                                                        WalkieTalkieService.super.notifyError(reasons, value);
                                                        // The mic was stopped for this text; nothing else turns it back on.
                                                        startVoiceRecorder();
                                                        notifyMicActivated();
                                                    }
                                                }
                                            });
                                        }
                                    });
                                }
                                break;
                            case START_MANUAL_RECOGNITION:
                                isMicAutomatic = false;
                                if(mVoiceRecorder != null) {
                                    mVoiceRecorder.setManualMode(true);
                                }
                                break;
                            case STOP_MANUAL_RECOGNITION:
                                isMicAutomatic = true;
                                if(manualRecognizingFirstLanguage || manualRecognizingSecondLanguage || manualRecognizingAutoLanguage){
                                    if(mVoiceRecorder != null) {
                                        mVoiceRecorder.stop();
                                    }
                                    manualRecognizingFirstLanguage = false;
                                    manualRecognizingSecondLanguage = false;
                                    manualRecognizingAutoLanguage = false;
                                }
                                if(mVoiceRecorder != null) {
                                    mVoiceRecorder.setManualMode(false);
                                }
                                break;
                            case START_RECOGNIZING_FIRST_LANGUAGE:
                                if(!manualRecognizingSecondLanguage && !isMicAutomatic) {
                                    manualRecognizingFirstLanguage = true;
                                    if(mVoiceRecorder != null) {
                                        mVoiceRecorder.startRecording();
                                    }
                                    Log.d("voice", "started manual listening left");
                                }
                                break;
                            case STOP_RECOGNIZING_FIRST_LANGUAGE:
                                if(mVoiceRecorder != null) {
                                    mVoiceRecorder.stopRecording();
                                }
                                Log.d("voice", "stopped manual listening left");
                                break;
                            case START_RECOGNIZING_SECOND_LANGUAGE:
                                if(!manualRecognizingFirstLanguage && !isMicAutomatic) {
                                    manualRecognizingSecondLanguage = true;
                                    if(mVoiceRecorder != null) {
                                        mVoiceRecorder.startRecording();
                                    }
                                    Log.d("voice", "started manual listening right");
                                }
                                break;
                            case STOP_RECOGNIZING_SECOND_LANGUAGE:
                                if(mVoiceRecorder != null) {
                                    mVoiceRecorder.stopRecording();
                                }
                                Log.d("voice", "stopped manual listening right");
                                break;
                            case START_RECOGNIZING_AUTO_LANGUAGE:
                                if(!manualRecognizingAutoLanguage && !isMicAutomatic) {
                                    manualRecognizingAutoLanguage = true;
                                    if(mVoiceRecorder != null) {
                                        mVoiceRecorder.startRecording();
                                    }
                                }
                                break;
                            case STOP_RECOGNIZING_AUTO_LANGUAGE:
                                if(mVoiceRecorder != null) {
                                    mVoiceRecorder.stopRecording();
                                }
                                break;
                        }
                    }
                }
                return false;
            }
        });
        mVoiceCallback = new Recorder.SimpleCallback() {
            @Override
            public void onVoiceStart() {
                super.onVoiceStart();
                if(ttsEngine != null){
                    mainTTSHandler.removeCallbacksAndMessages(null);  //cancel an eventual delayed resume command
                    ttsEngine.pause();
                }
                // we notify the client
                WalkieTalkieService.super.notifyVoiceStart();
            }

            @Override
            public void onVoice(@NonNull float[] data, int size) {
                super.onVoice(data,size);
                if(manualRecognizingFirstLanguage){
                    // we start the speech recognition in only the first language
                    if (firstAuto) {
                        recognizeAutoLanguage(data);
                    } else {
                        speechRecognizer.recognize(data, SPEECH_BEAM_SIZE, firstLanguage.getCode());
                    }

                } else if (manualRecognizingSecondLanguage) {
                    // we start the speech recognition in only the second language
                    if (secondAuto) {
                        recognizeAutoLanguage(data);
                    } else {
                        speechRecognizer.recognize(data, SPEECH_BEAM_SIZE, secondLanguage.getCode());
                    }

                }else if(manualRecognizingAutoLanguage || isMicAutomatic){
                    if (firstAuto || secondAuto) {
                        recognizeAutoLanguage(data);
                    } else {
                        // we start the speech recognition in both languages
                        speechRecognizer.recognize(data, SPEECH_BEAM_SIZE, firstLanguage.getCode(), secondLanguage.getCode());
                    }
                }
            }

            @Override
            public void onVoiceEnd() {
                super.onVoiceEnd();
                // we notify the client
                WalkieTalkieService.super.notifyVoiceEnd();
                // we resume tts after a delay, in case the user has not done speaking
                mainTTSHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if(ttsEngine != null) ttsEngine.resume();
                    }
                }, TIME_BEFORE_REACTIVATING_TTS_AFTER_VOICE_END);
            }

            @Override
            public void onVolumeLevel(float volumeLevel) {
                super.onVolumeLevel(volumeLevel);
                // we notify the client
                WalkieTalkieService.super.notifyVolumeLevel(volumeLevel);
            }
        };
        speechRecognizerCallback = new RecognizerMultiListener() {
            @Override
            public void onSpeechRecognizedResult(String text1, String languageCode1, double confidenceScore1, String text2, String languageCode2, double confidenceScore2) {
                NeuralNetworkApiResult firstResult = new NeuralNetworkApiResult(text1, confidenceScore1, true);
                NeuralNetworkApiResult secondResult = new NeuralNetworkApiResult(text2, confidenceScore2, true);
                manualRecognizingAutoLanguage = false;
                if (isMetaText(text1) && isMetaText(text2)) {
                    // Neither side heard its language, e.g. a clause in a third language that was dropped.
                    Log.i("walkieTalkie", "skipped utterance without a session language");
                    startVoiceRecorder();
                    notifyMicActivated();
                    return;
                }
                compareResults(firstResult, secondResult);
            }

            @Override
            public void onError(int[] reasons, long value) {
                manualRecognizingAutoLanguage = false;
                //we restart the mic here
                startVoiceRecorder();
                notifyMicActivated();
            }
        };
        speechRecognizerSingleCallback = new RecognizerListener() {
            @Override
            public void onSpeechRecognizedResult(String text, String languageCode, double confidenceScore, boolean isFinal) {
                if (autoRecognition) {
                    autoRecognition = false;
                    manualRecognizingFirstLanguage = false;
                    manualRecognizingSecondLanguage = false;
                    manualRecognizingAutoLanguage = false;
                    mainHandler.post(() -> translateDetected(text, languageCode));
                    return;
                }
                if(manualRecognizingFirstLanguage){
                    translate(text, firstLanguage, secondLanguage, TRANSLATOR_BEAM_SIZE, false, firstResultTranslateListener);
                } else if (manualRecognizingSecondLanguage) {
                    translate(text, secondLanguage, firstLanguage, TRANSLATOR_BEAM_SIZE, false, secondResultTranslateListener);
                }
                manualRecognizingFirstLanguage = false;
                manualRecognizingSecondLanguage = false;
            }

            @Override
            public void onError(int[] reasons, long value) {
                autoRecognition = false;
                manualRecognizingFirstLanguage = false;
                manualRecognizingSecondLanguage = false;
                manualRecognizingAutoLanguage = false;
                notifyMicActivated();
            }
        };
        firstResultTranslateListener = new Translator.TranslateListener() {
            @Override
            public void onTranslatedText(String textToTranslate, String translatedText, String[] synonyms, long resultID, boolean isFinal, ResultType resultType, CustomLocale inputLanguage, CustomLocale outputLanguage) {
                deliverTranslation(textToTranslate, translatedText, resultID, true, isFinal, inputLanguage, outputLanguage);
            }

            @Override
            public void onFailure(int[] reasons, long value) {
                WalkieTalkieService.super.notifyError(reasons,value);
                //if the tts is not active we restart the mic here
                if(ttsEngine == null || !ttsEngine.isActive() || isAudioMute){
                    startVoiceRecorder();
                    notifyMicActivated();
                }
            }
        };
        secondResultTranslateListener = new Translator.TranslateListener() {
            @Override
            public void onTranslatedText(String textToTranslate, String translatedText, String[] synonyms, long resultID, boolean isFinal, ResultType resultType, CustomLocale inputLanguage, CustomLocale outputLanguage) {
                deliverTranslation(textToTranslate, translatedText, resultID, false, isFinal, inputLanguage, outputLanguage);
            }

            @Override
            public void onFailure(int[] reasons, long value) {
                WalkieTalkieService.super.notifyError(reasons,value);
                //if the tts is not active we restart the mic here
                if(ttsEngine == null || !ttsEngine.isActive() || isAudioMute){
                    startVoiceRecorder();
                    notifyMicActivated();
                }
            }
        };
        //voice recorder initialization
        initializeVoiceRecorder();
        notifyModelLoading(1, PREPARE_MAX);
        new Thread(() -> {
            try {
                if (speechRecognizer != null) {
                    speechRecognizer.warmup();
                }
            } finally {
                mainHandler.post(() -> {
                    notifyModelLoading(PREPARE_MAX, PREPARE_MAX);
                    hearingReady = true;
                    notifyMicActivated();
                });
            }
        }, "speech-warmup").start();
    }

    @Override
    public void startVoiceRecorder() {
        if (!hearingReady) {
            return;
        }
        super.startVoiceRecorder();
    }

    public void initializeVoiceRecorder(){
        if (Tools.hasPermissions(this, Global.REQUIRED_PERMISSIONS_VOICE)) {
            //voice recorder initialization
            Global global = (Global) getApplication();
            super.mVoiceRecorder = new Recorder(global, false, mVoiceCallback, null, global.getVad());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        int startType = super.onStartCommand(intent, flags, startId);
        final CustomLocale finalFirstLanguage = this.firstLanguage;
        final CustomLocale finalSecondLanguage = this.secondLanguage;

        //getGroup the languages
        firstLanguage = (CustomLocale) intent.getSerializableExtra("firstLanguage");
        secondLanguage = (CustomLocale) intent.getSerializableExtra("secondLanguage");

        readAutoLanguages();

        if(finalFirstLanguage==null || finalSecondLanguage==null ) {  //se è il primo avvio
            //we attach the speech recognition callbacks
            speechRecognizer.addMultiCallback(speechRecognizerCallback);
            speechRecognizer.addCallback(speechRecognizerSingleCallback);
        }
        return startType;
    }

    @Override
    public void onDestroy() {
        Log.i("walkieTalkie", "WalkieTalkie service destroyed");
        //disconnect speechRecognizerCallback
        speechRecognizer.removeMultiCallback(speechRecognizerCallback);
        speechRecognizer.removeCallback(speechRecognizerSingleCallback);
        // Stop recognizer
        speechRecognizer.stop();
        isRunning = false;
        super.onDestroy();
    }

    private void readAutoLanguages() {
        Global global = (Global) getApplication();
        firstAuto = global.isLanguageAuto(Global.AUTO_FIRST_LANGUAGE);
        secondAuto = !firstAuto && global.isLanguageAuto(Global.AUTO_SECOND_LANGUAGE);
    }

    private void recognizeAutoLanguage(float[] data) {
        autoRecognition = true;
        speechRecognizer.recognize(data, SPEECH_BEAM_SIZE, AsrLanguages.AUTO);
    }

    /**
     * Speech in the fixed language goes to the language the detected side spoke last.
     * Any other supported language becomes the detected side and is translated into the fixed one.
     */
    private void translateDetected(String text, String languageCode) {
        if (text == null || text.isEmpty() || isMetaText(text)) {
            startVoiceRecorder();
            notifyMicActivated();
            return;
        }
        final Global global = (Global) getApplication();
        final boolean detectFirst = firstAuto;
        final CustomLocale fixed = detectFirst ? secondLanguage : firstLanguage;
        final CustomLocale detected = global.findDetectedLanguage(languageCode, Global.RTranslatorMode.WALKIE_TALKIE_MODE);
        if (detected == null) {
            Log.i("walkieTalkie", "skipped utterance in unsupported language " + languageCode);
            startVoiceRecorder();
            notifyMicActivated();
            return;
        }
        final Translator.TranslateListener fixedListener = detectFirst ? secondResultTranslateListener : firstResultTranslateListener;
        final Translator.TranslateListener detectedListener = detectFirst ? firstResultTranslateListener : secondResultTranslateListener;
        if (detected.equalsLanguage(fixed)) {
            CustomLocale other = detectFirst ? firstLanguage : secondLanguage;
            if (other.equalsLanguage(fixed)) {
                // The other person has not spoken yet, so there is no language to translate into.
                Log.i("walkieTalkie", "skipped utterance: no detected language yet");
                startVoiceRecorder();
                notifyMicActivated();
                return;
            }
            translate(text, fixed, other, TRANSLATOR_BEAM_SIZE, false, fixedListener);
            return;
        }
        CustomLocale current = detectFirst ? firstLanguage : secondLanguage;
        if (detected.equals(current)) {
            translate(text, detected, fixed, TRANSLATOR_BEAM_SIZE, false, detectedListener);
            return;
        }
        if (detectFirst) {
            firstLanguage = detected;
        } else {
            secondLanguage = detected;
        }
        // Saving it also loads its translation resources, which must be ready before translating.
        Translator.GeneralListener loaded = new Translator.GeneralListener() {
            @Override
            public void onSuccess() {
                translate(text, detected, fixed, TRANSLATOR_BEAM_SIZE, false, detectedListener);
            }

            @Override
            public void onFailure(int[] reasons, long value) {
                WalkieTalkieService.super.notifyError(reasons, value);
                startVoiceRecorder();
                notifyMicActivated();
            }
        };
        if (detectFirst) {
            global.setFirstLanguage(detected, loaded);
        } else {
            global.setSecondLanguage(detected, loaded);
        }
    }

    private void compareResults(NeuralNetworkApiResult firstResult, NeuralNetworkApiResult secondResult) {
        if(isMetaText(firstResult.getText()) && !isMetaText(secondResult.getText())){
            translate(secondResult.getText(), secondLanguage, firstLanguage, TRANSLATOR_BEAM_SIZE, false, secondResultTranslateListener);
            return;
        }
        if(isMetaText(secondResult.getText()) && !isMetaText(firstResult.getText())){
            translate(firstResult.getText(), firstLanguage, secondLanguage, TRANSLATOR_BEAM_SIZE, false, firstResultTranslateListener);
            return;
        }
        translator.detectLanguage(firstResult, secondResult, false, new CustomLocale[]{firstLanguage, secondLanguage}, new Translator.DetectMultiLanguageListener() {
            @Override
            public void onDetectedText(NeuralNetworkApiResult firstResult, NeuralNetworkApiResult secondResult, int message) {
                if (message == ErrorCodes.BOTH_RESULTS_SUCCESS){  // if both results languages were found
                    if (firstResult.getLanguage().equalsLanguage(firstLanguage)) {
                        if (secondResult.getLanguage().equalsLanguage(secondLanguage)) {  // if both have recognized their respective language
                            compareResultsConfidence(firstResult, secondResult);
                        } else {  // if only the first result language is recognized
                            translate(firstResult.getText(), firstLanguage, secondLanguage, TRANSLATOR_BEAM_SIZE, false, firstResultTranslateListener);
                        }
                    } else {
                        if (secondResult.getLanguage().equalsLanguage(secondLanguage)) {  // if only the second result language is recognized

                            translate(secondResult.getText(), secondLanguage, firstLanguage, TRANSLATOR_BEAM_SIZE, false, secondResultTranslateListener);
                        } else {  // if neither of them recognized his language but another (or none)
                            compareResultsConfidence(firstResult, secondResult);
                        }
                    }
                }else if(message == ErrorCodes.SECOND_RESULT_FAIL){  // if only the first result language was found
                    translate(firstResult.getText(), firstLanguage, secondLanguage, TRANSLATOR_BEAM_SIZE, false, firstResultTranslateListener);
                }else if(message == ErrorCodes.FIRST_RESULT_FAIL){   // if only the second result language was found
                    translate(secondResult.getText(), secondLanguage, firstLanguage, TRANSLATOR_BEAM_SIZE, false, secondResultTranslateListener);
                }
                // if both results languages were not found the onFailure method is called
            }

            @Override
            public void onFailure(int[] reasons, long value) {  // if both results languages were not found
                compareResultsConfidence(firstResult, secondResult);
            }
        });
    }

    private void translate(final String textToTranslate, final CustomLocale languageInput, final CustomLocale languageOutput, int beamSize, boolean saveResults, final Translator.TranslateListener responseListener){
        if(!isMetaText(textToTranslate)) {
            beginSpeech();
            translator.translate(textToTranslate, languageInput, languageOutput, beamSize, saveResults, Global.RTranslatorMode.WALKIE_TALKIE_MODE, speechListener(responseListener));
        }else{
            Log.i("walkieTalkie", "skipped translation of: "+textToTranslate+" because it is meta text");
            //we restart the mic here
            startVoiceRecorder();
            notifyMicActivated();
        }
    }

    private void beginSpeech() {
        synchronized (speechLock) {
            speechGeneration++;
            spokenPrefix = "";
            spokenResultId = -1;
            generationOpen = true;
            finalSpeechQueued = false;
        }
        // Drop speech left over from the previous sentence before the new one starts.
        Runnable stopSpeech = new Runnable() {
            @Override
            public void run() {
                if (ttsEngine != null) {
                    ttsEngine.stop();
                }
            }
        };
        if (mainHandler != null && android.os.Looper.myLooper() != mainHandler.getLooper()) {
            mainHandler.postAtFrontOfQueue(stopSpeech);
        } else {
            stopSpeech.run();
        }
    }

    private Translator.TranslateListener speechListener(final Translator.TranslateListener delegate) {
        final int generation;
        synchronized (speechLock) {
            generation = speechGeneration;
        }
        return new Translator.TranslateListener() {
            @Override
            public void onTranslatedText(String textToTranslate, String translatedText, String[] synonyms, long resultID, boolean isFinal, ResultType resultType, CustomLocale inputLanguage, CustomLocale outputLanguage) {
                if (!isCurrentSpeech(generation)) {
                    return;
                }
                delegate.onTranslatedText(textToTranslate, translatedText, synonyms, resultID, isFinal, resultType, inputLanguage, outputLanguage);
            }

            @Override
            public void onFailure(int[] reasons, long value) {
                if (!isCurrentSpeech(generation)) {
                    return;
                }
                synchronized (speechLock) {
                    generationOpen = false;
                    finalSpeechQueued = false;
                }
                if (ttsEngine != null) {
                    ttsEngine.stop();
                }
                delegate.onFailure(reasons, value);
            }
        };
    }

    private boolean isCurrentSpeech(int generation) {
        synchronized (speechLock) {
            return generation == speechGeneration;
        }
    }

    private void deliverTranslation(String textToTranslate, String translatedText, long resultID, boolean isMine, boolean isFinal, CustomLocale inputLanguage, CustomLocale outputLanguage) {
        final int generation;
        synchronized (speechLock) {
            generation = speechGeneration;
        }
        GuiMessage message = new GuiMessage(new Message(textToTranslate, WalkieTalkieService.this, translatedText), inputLanguage, outputLanguage, resultID, isMine, isFinal);
        notifyMessage(message);
        addOrUpdateMessage(message);
        if (isAudioMute) {
            if (isFinal) {
                finishWithoutSpeech();
            }
            return;
        }
        ((Global) getApplication()).getTTSLanguages(true, new Global.GetLocalesListListener() {
            @Override
            public void onSuccess(ArrayList<CustomLocale> ttsLanguages) {
                if (!isCurrentSpeech(generation)) {
                    return;
                }
                boolean canSpeak = ttsEngine != null && ttsEngine.isActive() && CustomLocale.containsLanguage(ttsLanguages, outputLanguage);
                if (!canSpeak) {
                    if (isFinal) {
                        finishWithoutSpeech();
                    }
                    return;
                }
                feedTts(translatedText, resultID, isMine, isFinal, outputLanguage);
            }

            @Override
            public void onFailure(int[] reasons, long value) {
                if (!isCurrentSpeech(generation)) {
                    return;
                }
                if (isFinal) {
                    finishWithoutSpeech();
                }
            }
        });
    }

    private void feedTts(String translation, long resultID, boolean isMine, boolean isFinal, CustomLocale outputLanguage) {
        if (translation == null) {
            translation = "";
        }
        boolean resetSpeech = false;
        boolean speakChunk = false;
        boolean closeIfIdle = false;
        String chunk = null;
        java.util.ArrayList<String> queuedPieces = new java.util.ArrayList<>();
        synchronized (speechLock) {
            if (resultID != spokenResultId) {
                spokenResultId = resultID;
                spokenPrefix = "";
            }
            if (!translation.startsWith(spokenPrefix)) {
                if (!isFinal) {
                    return;
                }
                resetSpeech = true;
                spokenPrefix = "";
            }
            String pending = translation.substring(spokenPrefix.length());
            java.util.ArrayList<String> pieces = SimultaneousText.speakablePieces(pending, isFinal);
            for (String piece : pieces) {
                spokenPrefix = spokenPrefix + piece;
                if (piece.trim().length() > 0) {
                    if (chunk == null) {
                        chunk = piece;
                    }
                }
            }
            speakChunk = chunk != null;
            if (isFinal) {
                finalSpeechQueued = true;
                closeIfIdle = !speakChunk;
            }
            queuedPieces = pieces;
        }
        if (resetSpeech && ttsEngine != null) {
            ttsEngine.stop();
        }
        if (speakChunk) {
            int audible = 0;
            for (String piece : queuedPieces) {
                if (piece.trim().length() > 0) {
                    audible++;
                }
            }
            int spokenAudible = 0;
            for (String piece : queuedPieces) {
                if (piece.trim().isEmpty()) {
                    continue;
                }
                spokenAudible++;
                GuiMessage spoken = new GuiMessage(new Message("", WalkieTalkieService.this, piece), outputLanguage, outputLanguage, resultID, isMine, true);
                speakPiece(spoken, outputLanguage, isFinal && spokenAudible == audible);
            }
        } else if (isFinal) {
            speakPiece(new GuiMessage(new Message("", WalkieTalkieService.this, ""), outputLanguage, outputLanguage, resultID, isMine, true), outputLanguage, true);
        }
        if (closeIfIdle && !isTtsBusy()) {
            finishWithoutSpeech();
        }
    }

    private void finishWithoutSpeech() {
        if (isTtsBusy()) {
            synchronized (speechLock) {
                finalSpeechQueued = true;
            }
            return;
        }
        long resultId;
        synchronized (speechLock) {
            generationOpen = false;
            finalSpeechQueued = false;
            resultId = spokenResultId;
        }
        notifyTTSDone(String.valueOf(resultId));
        startVoiceRecorder();
        notifyMicActivated();
    }

    @Override
    public void notifyTTSDone(String utteranceId) {
        synchronized (speechLock) {
            if (generationOpen && !finalSpeechQueued) {
                return;
            }
            // A later chunk is already queued. Keep the bubble highlighted.
            if (ttsEngine != null && !ttsEngine.isEmpty()) {
                return;
            }
        }
        super.notifyTTSDone(utteranceId);
    }

    @Override
    protected boolean shouldReactivateMicAfterTts() {
        if (!shouldDeactivateMicDuringTTS()) {
            return false;
        }
        synchronized (speechLock) {
            // Keep the mic closed while tokens are still arriving.
            if (generationOpen && !finalSpeechQueued) {
                return false;
            }
        }
        return true;
    }

    @Override
    protected void onMicReactivatedAfterTts() {
        synchronized (speechLock) {
            generationOpen = false;
            finalSpeechQueued = false;
        }
    }

    private void compareResultsConfidence(NeuralNetworkApiResult firstResult, NeuralNetworkApiResult secondResult) {
        if (firstResult.getConfidenceScore() >= secondResult.getConfidenceScore()) {
            translate(firstResult.getText(), firstLanguage, secondLanguage, TRANSLATOR_BEAM_SIZE, false, firstResultTranslateListener);
        } else {
            translate(secondResult.getText(), secondLanguage, firstLanguage, TRANSLATOR_BEAM_SIZE, false, secondResultTranslateListener);
        }
    }

    public static class WalkieTalkieServiceCommunicator extends VoiceTranslationServiceCommunicator {
        private ArrayList<LanguageListener> firstLanguageListeners = new ArrayList<>();
        private ArrayList<LanguageListener> secondLanguageListeners = new ArrayList<>();

        public WalkieTalkieServiceCommunicator(int id) {
            super(id);
            super.serviceHandler = new Handler(new Handler.Callback() {
                @Override
                public boolean handleMessage(@NonNull android.os.Message msg) {
                    msg.getData().setClassLoader(Peer.class.getClassLoader());
                    int callbackMessage = msg.getData().getInt("callback", -1);
                    Bundle data= msg.getData();
                    if(!executeCallback(callbackMessage,data)){
                        switch (callbackMessage){
                            case ON_FIRST_LANGUAGE:{
                                CustomLocale language = (CustomLocale) data.getSerializable("language");
                                while (!firstLanguageListeners.isEmpty()) {
                                    firstLanguageListeners.remove(0).onLanguage(language);
                                }
                                break;
                            }
                            case ON_SECOND_LANGUAGE:{
                                CustomLocale language = (CustomLocale) data.getSerializable("language");
                                while (!secondLanguageListeners.isEmpty()) {
                                    secondLanguageListeners.remove(0).onLanguage(language);
                                }
                                break;
                            }
                        }
                    }
                    return true;
                }
            });
        }

        public void getFirstLanguage(LanguageListener responseListener){
            firstLanguageListeners.add(responseListener);
            if (firstLanguageListeners.size() == 1) {
                Bundle bundle = new Bundle();
                bundle.putInt("command", GET_FIRST_LANGUAGE);
                super.sendToService(bundle);
            }
        }

        public void getSecondLanguage(LanguageListener responseListener){
            secondLanguageListeners.add(responseListener);
            if (secondLanguageListeners.size() == 1) {
                Bundle bundle = new Bundle();
                bundle.putInt("command", GET_SECOND_LANGUAGE);
                super.sendToService(bundle);
            }
        }

        public void changeFirstLanguage(CustomLocale language) {
            Bundle bundle = new Bundle();
            bundle.putInt("command", WalkieTalkieService.CHANGE_FIRST_LANGUAGE);
            bundle.putSerializable("language", language);
            super.sendToService(bundle);
        }

        public void changeSecondLanguage(CustomLocale language) {
            Bundle bundle = new Bundle();
            bundle.putInt("command", WalkieTalkieService.CHANGE_SECOND_LANGUAGE);
            bundle.putSerializable("language", language);
            super.sendToService(bundle);
        }

        public void startManualRecognition() {
            Bundle bundle = new Bundle();
            bundle.putInt("command", START_MANUAL_RECOGNITION);
            super.sendToService(bundle);
        }

        public void stopManualRecognition() {
            Bundle bundle = new Bundle();
            bundle.putInt("command", STOP_MANUAL_RECOGNITION);
            super.sendToService(bundle);
        }

        public void startRecognizingFirstLanguage() {
            Bundle bundle = new Bundle();
            bundle.putInt("command", START_RECOGNIZING_FIRST_LANGUAGE);
            super.sendToService(bundle);
        }

        public void stopRecognizingFirstLanguage() {
            Bundle bundle = new Bundle();
            bundle.putInt("command", STOP_RECOGNIZING_FIRST_LANGUAGE);
            super.sendToService(bundle);
        }

        public void startRecognizingSecondLanguage() {
            Bundle bundle = new Bundle();
            bundle.putInt("command", START_RECOGNIZING_SECOND_LANGUAGE);
            super.sendToService(bundle);
        }

        public void stopRecognizingSecondLanguage() {
            Bundle bundle = new Bundle();
            bundle.putInt("command", STOP_RECOGNIZING_SECOND_LANGUAGE);
            super.sendToService(bundle);
        }

        public void startRecognizingAutoLanguage() {
            Bundle bundle = new Bundle();
            bundle.putInt("command", START_RECOGNIZING_AUTO_LANGUAGE);
            super.sendToService(bundle);
        }

        public void stopRecognizingAutoLanguage() {
            Bundle bundle = new Bundle();
            bundle.putInt("command", STOP_RECOGNIZING_AUTO_LANGUAGE);
            super.sendToService(bundle);
        }
    }

    public interface LanguageListener{
        void onLanguage(CustomLocale language);
    }
}