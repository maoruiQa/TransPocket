/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copyFile of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nie.translator.rtranslator.voice_translation.neural_networks.voice;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import java.io.File;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.extensions.OrtxPackage;
import nie.translator.rtranslator.Global;
import nie.translator.rtranslator.R;
import nie.translator.rtranslator.tools.AsrLanguageFilter;
import nie.translator.rtranslator.tools.AsrLanguageGuard;
import nie.translator.rtranslator.tools.AsrLanguageHint;
import nie.translator.rtranslator.tools.AsrLanguages;
import nie.translator.rtranslator.tools.AsrRepetition;
import nie.translator.rtranslator.tools.CustomLocale;
import nie.translator.rtranslator.tools.ErrorCodes;
import nie.translator.rtranslator.tools.nn.TensorUtils;
import nie.translator.rtranslator.tools.nn.Utils;
import nie.translator.rtranslator.voice_translation.neural_networks.NeuralNetworkApi;


public class Recognizer extends NeuralNetworkApi {
    private static final int MAX_TOKENS_PER_SECOND = 30;
    private static final int MAX_TOKENS = 445;   //if we generate more than this quantity of tokens for a transcription we have an error
    public static final String UNDEFINED_TEXT = "[(und)]";
    private ArrayList<RecognizerListener> callbacks = new ArrayList<>();
    private ArrayList<RecognizerMultiListener> multiCallbacks = new ArrayList<>();
    private boolean recognizing = false;
    private boolean speechWarmed = false;
    private ArrayDeque<DataContainer> dataToRecognize = new ArrayDeque<>();
    private final Object lock = new Object();
    private final Object warmupLock = new Object();

    // Same order as AsrLanguages.whisperCodes(true). getLanguageID() resolves the
    // code through AsrLanguages so Whisper Small never receives a large-v3-only id.
    private static final String[] LANGUAGES = {
            "en",
            "zh",
            "de",
            "es",
            "ru",
            "ko",
            "fr",
            "ja",
            "pt",
            "tr",
            "pl",
            "ca",
            "nl",
            "ar",
            "sv",
            "it",
            "id",
            "hi",
            "fi",
            "vi",
            "he",
            "uk",
            "el",
            "ms",
            "cs",
            "ro",
            "da",
            "hu",
            "ta",
            "no",
            "th",
            "ur",
            "hr",
            "bg",
            "lt",
            "la",
            "mi",
            "ml",
            "cy",
            "sk",
            "te",
            "fa",
            "lv",
            "bn",
            "sr",
            "az",
            "sl",
            "kn",
            "et",
            "mk",
            "br",
            "eu",
            "is",
            "hy",
            "ne",
            "mn",
            "bs",
            "kk",
            "sq",
            "sw",
            "gl",
            "mr",
            "pa",
            "si",
            "km",
            "sn",
            "yo",
            "so",
            "af",
            "oc",
            "ka",
            "be",
            "tg",
            "sd",
            "gu",
            "am",
            "yi",
            "lo",
            "uz",
            "fo",
            "ht",
            "ps",
            "tk",
            "nn",
            "mt",
            "sa",
            "lb",
            "my",
            "bo",
            "tl",
            "mg",
            "as",
            "tt",
            "haw",
            "ln",
            "ha",
            "ba",
            "jw",
            "su",
            "yue"
    };

    private final int START_TOKEN_ID = 50258;
    private final int TRANSCRIBE_TOKEN_ID = 50359;
    private final int NO_TIMESTAMPS_TOKEN_ID = 50363;

    private OrtSession session;
    private OrtSession initSession;
    private OrtSession encoderSession;
    private OrtSession cacheInitSession;
    private OrtSession cacheInitBatchSession;
    private OrtSession decoderSession;
    private OrtSession detokenizerSession;
    private OrtEnvironment onnxEnv;
    private Qwen3AsrEngine qwenEngine;
    private WhisperTurboEngine turboEngine;


    public Recognizer(Global global, final boolean returnResultOnlyAtTheEnd, final NeuralNetworkApi.InitListener initListener) {
        this(global, returnResultOnlyAtTheEnd, initListener, true);
    }

    /**
     * @param async false creates the sessions on the caller thread. Used when that thread already owns stop and close.
     */
    public Recognizer(Global global, final boolean returnResultOnlyAtTheEnd, final NeuralNetworkApi.InitListener initListener, boolean async) {
        this.global = global;
        Log.i("recognizer", "ASR load: model=" + global.getAsrModel() + " quant=" + global.getAsrQuant(global.getAsrModel()));
        if (global.getAsrModel() == Global.ASR_QWEN3_06 || global.getAsrModel() == Global.ASR_QWEN3_17) {
            onnxEnv = OrtEnvironment.getEnvironment();
            loadAsync(async, new Runnable() {
                @Override
                public void run() {
                    try {
                        boolean fp32 = global.getAsrQuant(global.getAsrModel()) == Global.ASR_QUANT_FP32;
                        qwenEngine = new Qwen3AsrEngine(new File(global.getAsrModelDirectory()), fp32);
                        global.markAsrRunning();
                        initListener.onInitializationFinished();
                    } catch (Exception e) {
                        e.printStackTrace();
                        initListener.onError(new int[]{ErrorCodes.ERROR_LOADING_MODEL}, 0);
                    }
                }
            });
            return;
        }
        if (global.getAsrModel() == Global.ASR_WHISPER_TURBO || (global.getAsrModel() == Global.ASR_WHISPER && global.groupFilesPresent(global.getWhisperDownloadInfo()))) {
            onnxEnv = OrtEnvironment.getEnvironment();
            loadAsync(async, new Runnable() {
                @Override
                public void run() {
                    try {
                        boolean small = global.getAsrModel() == Global.ASR_WHISPER;
                        int quant = global.getAsrQuant(global.getAsrModel());
                        File dir = new File(small ? global.getWhisperSmallDirectory() : global.getWhisperTurboDirectory());
                        turboEngine = new WhisperTurboEngine(dir, Global.whisperEncoderName(quant), Global.whisperDecoderName(quant), small ? 80 : 128, small ? 12 : 20);
                        global.markAsrRunning();
                        initListener.onInitializationFinished();
                    } catch (Exception e) {
                        e.printStackTrace();
                        initListener.onError(new int[]{ErrorCodes.ERROR_LOADING_MODEL}, 0);
                    }
                }
            });
            return;
        }
        //onnxEnv = OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_VERBOSE);
        onnxEnv = OrtEnvironment.getEnvironment();

        String modelInitPath = global.getFilesDir().getPath() + "/Whisper_initializer.onnx";
        String decoderPath = global.getFilesDir().getPath() + "/Whisper_decoder.onnx";
        String cacheInitPath = global.getFilesDir().getPath() + "/Whisper_cache_initializer.onnx";
        String cacheInitBatchPath = global.getFilesDir().getPath() + "/Whisper_cache_initializer_batch.onnx";
        String detokenizerPath = global.getFilesDir().getPath() + "/Whisper_detokenizer.onnx";

        loadAsync(async, new Runnable() {
            @Override
            public void run() {
                try {
                    OrtSession.SessionOptions initSessionOptions = new OrtSession.SessionOptions();
                    initSessionOptions.registerCustomOpLibrary(OrtxPackage.getLibraryPath());
                    initSessionOptions.setCPUArenaAllocator(false);
                    initSessionOptions.setMemoryPatternOptimization(false);
                    initSessionOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT);
                    initSession = onnxEnv.createSession(modelInitPath, initSessionOptions);

                    initializeEncoderSession();

                    OrtSession.SessionOptions cacheSessionOptions = new OrtSession.SessionOptions();
                    cacheSessionOptions.registerCustomOpLibrary(OrtxPackage.getLibraryPath());
                    cacheSessionOptions.setCPUArenaAllocator(false);
                    cacheSessionOptions.setMemoryPatternOptimization(false);
                    cacheSessionOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT);
                    cacheInitSession = onnxEnv.createSession(cacheInitPath, cacheSessionOptions);
                    cacheInitBatchSession = onnxEnv.createSession(cacheInitBatchPath, cacheSessionOptions);

                    OrtSession.SessionOptions decoderSessionOptions = new OrtSession.SessionOptions();
                    decoderSessionOptions.registerCustomOpLibrary(OrtxPackage.getLibraryPath());
                    decoderSessionOptions.setCPUArenaAllocator(false);
                    decoderSessionOptions.setMemoryPatternOptimization(false);
                    decoderSessionOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT);
                    decoderSession = onnxEnv.createSession(decoderPath, decoderSessionOptions);

                    OrtSession.SessionOptions detokenizerSessionOptions = new OrtSession.SessionOptions();
                    detokenizerSessionOptions.registerCustomOpLibrary(OrtxPackage.getLibraryPath());
                    detokenizerSessionOptions.setCPUArenaAllocator(false);
                    detokenizerSessionOptions.setMemoryPatternOptimization(false);
                    //detokenizerSessionOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT);
                    detokenizerSession = onnxEnv.createSession(detokenizerPath, detokenizerSessionOptions);

                    global.markAsrRunning();
                    initListener.onInitializationFinished();
                } catch (Exception e) {
                    e.printStackTrace();
                    initListener.onError(new int[]{ErrorCodes.ERROR_LOADING_MODEL},0);
                }
            }
        });
    }

    private void loadAsync(boolean async, Runnable load) {
        if (async) {
            new Thread(load).start();
        } else {
            load.run();
        }
    }

    public void initializeEncoderSession() throws OrtException {
        if (qwenEngine != null || turboEngine != null || global.getAsrModel() != Global.ASR_WHISPER) {
            return;
        }
        // release of encoder session in case of a restart
        if(encoderSession != null){
            encoderSession.close();
        }
        // encoder session initialization
        String encoderPath = global.getFilesDir().getPath() + "/Whisper_encoder.onnx";
        OrtSession.SessionOptions encoderSessionOptions = new OrtSession.SessionOptions();
        encoderSessionOptions.registerCustomOpLibrary(OrtxPackage.getLibraryPath());
        if(global.isWhisperReducedRam()){
            encoderSessionOptions.setCPUArenaAllocator(false);
            encoderSessionOptions.setMemoryPatternOptimization(false);
        }else {
            encoderSessionOptions.setCPUArenaAllocator(true);
            encoderSessionOptions.setMemoryPatternOptimization(true);
        }
        encoderSessionOptions.setSymbolicDimensionValue("batch_size", 1);
        encoderSessionOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT);
        encoderSession = onnxEnv.createSession(encoderPath, encoderSessionOptions);
    }

    /**
     * Recognizes the speech audio. This method should be called every time a chunk of byte buffer
     * is returned from onVoice.
     *
     * @param data The audio data.
     * @param languageCode {@link AsrLanguages#AUTO} decodes with the detected language and
     *                     reports it in the result.
     */
    public void recognize(final float[] data, int beamSize, final String languageCode) {
        enqueue(new DataContainer(data, beamSize, languageCode, AsrLanguages.isAuto(languageCode)));
    }

    /**
     * Recognizes one simultaneous-interpretation clause. Every clause stays in
     * the queue, in order. The source language is a hint, not a forced token.
     */
    public void recognizeLatest(final float[] data, int beamSize, final String languageCode) {
        recognizeLatest(data, beamSize, languageCode, null);
    }

    /**
     * @param targetLanguageCode the only language an unlocked session may switch to
     */
    public void recognizeLatest(final float[] data, int beamSize, final String languageCode, final String targetLanguageCode) {
        enqueue(new DataContainer(data, beamSize, languageCode, targetLanguageCode, true));
    }

    /**
     * Runs one short silent pass before capture starts. The first ONNX execution
     * is slow enough to stall the audio thread and drop the opening speech.
     */
    public void warmup() {
        synchronized (warmupLock) {
            if (speechWarmed) {
                return;
            }
            synchronized (lock) {
                while (recognizing) {
                    try {
                        lock.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                recognizing = true;
            }
            try {
                float[] silence = new float[16000];
                if (qwenEngine != null) {
                    qwenEngine.warmup();
                } else if (turboEngine != null) {
                    turboEngine.warmup();
                } else if (initSession != null && encoderSession != null) {
                    warmupLegacy(silence);
                }
            } catch (Exception e) {
                Log.w("recognizer", "speech warmup failed", e);
            } finally {
                boolean drain = false;
                synchronized (lock) {
                    speechWarmed = true;
                    if (dataToRecognize.isEmpty()) {
                        recognizing = false;
                        lock.notifyAll();
                    } else {
                        drain = true;
                    }
                }
                if (drain) {
                    drainQueue();
                }
            }
        }
    }

    private void warmupLegacy(float[] samples) throws OrtException {
        OnnxTensor audioTensor = OnnxTensor.createTensor(onnxEnv, FloatBuffer.wrap(samples), TensorUtils.tensorShape(1L, (long) samples.length));
        OrtSession.Result outputsInit = null;
        OrtSession.Result encoded = null;
        try {
            Map<String, OnnxTensor> initInputs = new LinkedHashMap<>();
            initInputs.put("audio_pcm", audioTensor);
            outputsInit = initSession.run(initInputs);
            OnnxTensor outputInit = (OnnxTensor) outputsInit.get(0);
            Map<String, OnnxTensor> inputs = new LinkedHashMap<>();
            inputs.put("input_features", outputInit);
            encoded = encoderSession.run(inputs);
        } finally {
            if (encoded != null) {
                encoded.close();
            }
            if (outputsInit != null) {
                outputsInit.close();
            }
            audioTensor.close();
        }
    }

    public void recognize(final float[] data, int beamSize, final String languageCode1, final String languageCode2) {
        enqueue(new DataContainer(data, beamSize, languageCode1, languageCode2));
    }

    private void enqueue(final DataContainer container) {
        if (container == null || container.data == null) {
            return;
        }
        new Thread("recognizer") {
            @Override
            public void run() {
                boolean start = false;
                synchronized (lock) {
                    dataToRecognize.addLast(container);
                    if (!recognizing) {
                        recognizing = true;
                        start = true;
                    }
                }
                if (start) {
                    drainQueue();
                }
            }
        }.start();
    }

    /** Runs outside {@link #lock}. The flag {@code recognizing} keeps a single worker. */
    private void drainQueue() {
        while (true) {
            DataContainer data;
            synchronized (lock) {
                data = dataToRecognize.pollFirst();
                if (data == null) {
                    recognizing = false;
                    lock.notifyAll();
                    return;
                }
            }
            try {
                transcribe(data);
            } catch (Throwable t) {
                Log.e("recognizer", "recognition failed", t);
            }
        }
    }

    private void transcribe(DataContainer data) {
        if (data == null) {
            return;
        }
        if (qwenEngine != null) {
            if (data != null) {
                recognizeWithQwen(data);
            }
        } else if (turboEngine != null) {
            recognizeWithTurbo(data);
        } else if (initSession != null && encoderSession != null && cacheInitSession != null && decoderSession != null && detokenizerSession != null) {
                //we convert data in an audioTensor and start the transcription
                try {
                    FloatBuffer floatAudioDataBuffer = FloatBuffer.wrap(data.data);
                    OnnxTensor audioTensor = OnnxTensor.createTensor(onnxEnv, floatAudioDataBuffer, TensorUtils.tensorShape(1L, (long) data.data.length));

                    // if we generate more than this number of tokens it means that we have an infinite loop due to the fact that the sound cannot be transcribed with the language selected
                    int maxTokens = (data.data.length / Recorder.SAMPLE_RATE_CANDIDATES[0]) * MAX_TOKENS_PER_SECOND;
                    if(maxTokens > MAX_TOKENS){
                        maxTokens = MAX_TOKENS;
                    }
                    boolean execution1HitMaxLength = false;
                    boolean execution2HitMaxLength = false;

                    //execution of pre ops
                    long time = System.currentTimeMillis();
                    long startTimeInMs = SystemClock.elapsedRealtime();
                    Map initInputs = (Map) (new LinkedHashMap());
                    initInputs.put("audio_pcm", audioTensor);
                    OrtSession.Result outputsInit = initSession.run(initInputs);
                    OnnxTensor outputInit = (OnnxTensor) outputsInit.get(0);
                    android.util.Log.i("performance", "pre ops done in: " + (System.currentTimeMillis()-time) + "ms");
                    audioTensor.close();

                    //execution of the encoder
                    Map inputs = (Map) (new LinkedHashMap());
                    inputs.put("input_features", outputInit);
                    OrtSession.Result outputs = encoderSession.run(inputs);
                    OnnxTensor outputEncoder = (OnnxTensor) outputs.get(0);
                    android.util.Log.i("performance", "Encoder done in: " + (SystemClock.elapsedRealtime() - startTimeInMs) + "ms");

                    //execution of decoder
                    final int eos = 50257;
                    ArrayList<Integer> completeOutput = new ArrayList<Integer>();
                    ArrayList<Integer> completeOutput2 = new ArrayList<Integer>();
                    double outputProbability1 = 0;
                    double outputProbability2 = 0;
                    boolean finished1 = false;
                    boolean finished2 = false;
                    long initialTime;

                    OnnxTensor inputIDsTensor = null;
                    OnnxTensor decoderOutput = null;
                    Map<String, OnnxTensor> decoderInput = new HashMap<String, OnnxTensor>();
                    float[][][] value = null;
                    float[] outputValues = null;
                    float[] outputValues2 = null;
                    int batchSize = 1;

                    if(data.languageCode2 != null){
                        batchSize = 2;
                    }

                    //We prepare the cache initializer input and execute it
                    Map<String, OnnxTensor> initInput = new HashMap<String, OnnxTensor>();
                    OrtSession.Result initResult = null;
                    if(batchSize == 1) {
                        initInput.put("encoder_hidden_states", outputEncoder);
                        time = System.currentTimeMillis();
                        initResult = cacheInitSession.run(initInput);
                        android.util.Log.i("performance", "Cache initialization done in: " + (System.currentTimeMillis() - time) + "ms");
                    }else{
                        long timeInner = System.currentTimeMillis();
                        float[][] outputEncoderValue = ((float[][][]) outputEncoder.getValue())[0];
                        android.util.Log.i("performance", "Encoder batch extract done in: " + (System.currentTimeMillis()-timeInner) + "ms");
                        timeInner = System.currentTimeMillis();
                        float[][][] outputEncoderFlatBatched = TensorUtils.batchTensor(outputEncoderValue, 2);
                        android.util.Log.i("performance", "Encoder batch batching done in: " + (System.currentTimeMillis()-timeInner) + "ms");
                        timeInner = System.currentTimeMillis();
                        OnnxTensor outputEncoderBatched = TensorUtils.createFloatTensor(onnxEnv, outputEncoderFlatBatched, new long[]{2, outputEncoderValue.length, outputEncoderValue[0].length}, new long[]{0});
                        android.util.Log.i("performance", "Encoder batch creation done in: " + (System.currentTimeMillis()-timeInner) + "ms");
                        initInput.put("encoder_hidden_states", outputEncoderBatched);
                        time = System.currentTimeMillis();
                        initResult = cacheInitBatchSession.run(initInput);
                        outputEncoderBatched.close();
                        android.util.Log.i("performance", "Cache initialization done in: " + (System.currentTimeMillis() - time) + "ms");
                    }

                    //We start the iterative execution of the decoder
                    OrtSession.Result result = null;
                    OrtSession.Result oldResult = null;
                    int max = -1;
                    int max2 = eos;
                    boolean isFirstIteration = true;  //It is used to avoid closing initialResult
                    int j = 1;
                    //first we run the decoder with the first 3 fixed values (START_TOKEN_ID, languageID, TRANSCRIBE_TOKEN_ID, NO_TIMESTAMPS_TOKEN_ID)
                    final int firstLanguageToken = START_TOKEN_ID + 1;
                    final int lastLanguageToken = TRANSCRIBE_TOKEN_ID - 2;
                    final boolean autoLanguage = batchSize == 1 && AsrLanguages.isAuto(data.languageCode);
                    final boolean softLanguage = batchSize == 1 && (data.softLanguage || autoLanguage);
                    final int[] acceptedIds = acceptedWhisperIds(data, false);
                    String rejectedLanguage = null;
                    // Auto has no token to force; the first step picks it from the language scores.
                    int languageID = autoLanguage ? firstLanguageToken : getLanguageID(data.languageCode);
                    if (languageID < firstLanguageToken || languageID > lastLanguageToken) {
                        // A negative id would reach the embedding as the last vocabulary row.
                        throw new IllegalArgumentException("Whisper Small has no language token for " + data.languageCode);
                    }
                    int languageID2 = -1;
                    if(batchSize == 2){
                        languageID2 = getLanguageID(data.languageCode2);
                        if (languageID2 < firstLanguageToken || languageID2 > lastLanguageToken) {
                            throw new IllegalArgumentException("Whisper Small has no language token for " + data.languageCode2);
                        }
                    }
                    int hintedLanguage = autoLanguage ? -1 : languageID;
                    if (hintedLanguage < firstLanguageToken || hintedLanguage > lastLanguageToken) {
                        hintedLanguage = -1;
                    }
                    int chosenLanguage = hintedLanguage >= 0 ? hintedLanguage : firstLanguageToken;
                    int[] decoderInitialInputIDs = {START_TOKEN_ID, languageID, TRANSCRIBE_TOKEN_ID, NO_TIMESTAMPS_TOKEN_ID};
                    int[] decoderInitialInputIDs2 = {START_TOKEN_ID, languageID2, TRANSCRIBE_TOKEN_ID, NO_TIMESTAMPS_TOKEN_ID};
                    
                    while (!(max == eos && max2 == eos)) {
                        initialTime = System.currentTimeMillis();
                        time = System.currentTimeMillis();
                        if (j <= 4) {
                            int token = decoderInitialInputIDs[j-1];
                            if (softLanguage && j == 2) {
                                token = chosenLanguage;
                            }
                            if(batchSize == 1) {
                                inputIDsTensor = TensorUtils.convertIntArrayToTensor(onnxEnv, new int[]{token});
                            }else{
                                inputIDsTensor = TensorUtils.convertIntArrayToTensor(onnxEnv, new int[]{decoderInitialInputIDs[j-1], decoderInitialInputIDs2[j-1]}, new long[]{2,1});
                            }
                        }
                        //We prepare the decoder input
                        decoderInput = new HashMap<String, OnnxTensor>();
                        decoderInput.put("input_ids", inputIDsTensor);
                        OnnxTensor decoderPastTensor = null;
                        if (isFirstIteration) {
                            long[] shape = {batchSize, 12, 0, 64};
                            decoderPastTensor = TensorUtils.createFloatTensorWithSingleValue(onnxEnv, 0, shape);
                            for (int i = 0; i < 12; i++) {
                                decoderInput.put("past_key_values." + i + ".decoder.key", decoderPastTensor);
                                decoderInput.put("past_key_values." + i + ".decoder.value", decoderPastTensor);
                                decoderInput.put("past_key_values." + i + ".encoder.key", (OnnxTensor) initResult.get("present." + i + ".encoder.key").get());
                                decoderInput.put("past_key_values." + i + ".encoder.value", (OnnxTensor) initResult.get("present." + i + ".encoder.value").get());
                            }
                            isFirstIteration = false;
                        } else {
                            for (int i = 0; i < 12; i++) {
                                decoderInput.put("past_key_values." + i + ".decoder.key", (OnnxTensor) result.get("present." + i + ".decoder.key").get());
                                decoderInput.put("past_key_values." + i + ".decoder.value", (OnnxTensor) result.get("present." + i + ".decoder.value").get());
                                decoderInput.put("past_key_values." + i + ".encoder.key", (OnnxTensor) initResult.get("present." + i + ".encoder.key").get());
                                decoderInput.put("past_key_values." + i + ".encoder.value", (OnnxTensor) initResult.get("present." + i + ".encoder.value").get());
                            }
                        }
                        oldResult = result;
                        //execution of decoder (with cache)
                        result = decoderSession.run(decoderInput);

                        if (oldResult != null) {
                            oldResult.close(); //serve a rilasciare la memoria occupata dal risultato (altrimenti di accumula e aumenta molto)
                        }
                        inputIDsTensor.close();
                        if(decoderPastTensor != null) decoderPastTensor.close();
                        //we extract the logits and the highest value
                        decoderOutput = (OnnxTensor) result.get("logits").get();
                        value = (float[][][]) decoderOutput.getValue();
                        outputValues = value[0][0];
                        if (j == 1 && acceptedIds != null && outputValues.length > lastLanguageToken) {
                            // Both batch rows start from the same token, so row 0 holds the language scores.
                            float[] languageLogits = new float[lastLanguageToken - firstLanguageToken + 1];
                            System.arraycopy(outputValues, firstLanguageToken, languageLogits, 0, languageLogits.length);
                            int foreign = AsrLanguageFilter.foreignLanguage(languageLogits, firstLanguageToken, acceptedIds);
                            if (foreign >= 0) {
                                rejectedLanguage = AsrLanguages.whisperCode(foreign, false);
                                break;
                            }
                        }
                        if(!finished1) {
                            max = Utils.getIndexOfLargest(outputValues);
                            completeOutput.add(max);
                            if (softLanguage && j == 1 && outputValues.length > lastLanguageToken) {
                                float[] languageLogits = new float[lastLanguageToken - firstLanguageToken + 1];
                                System.arraycopy(outputValues, firstLanguageToken, languageLogits, 0, languageLogits.length);
                                int picked;
                                if (global.isAsrLanguageLock() && !autoLanguage) {
                                    picked = hintedLanguage;
                                } else {
                                    int allowed = AsrLanguages.whisperToken(data.languageCodeSwitch, false);
                                    picked = AsrLanguageHint.choose(languageLogits, firstLanguageToken, hintedLanguage,
                                            allowed >= firstLanguageToken && allowed <= lastLanguageToken, allowed);
                                }
                                if (picked >= firstLanguageToken && picked <= lastLanguageToken) {
                                    if (hintedLanguage >= 0 && picked != hintedLanguage) {
                                        Log.i("asrLanguage", "model=whisper-small-legacy requested="
                                                + AsrLanguages.whisperCode(hintedLanguage, false)
                                                + " chosen=" + AsrLanguages.whisperCode(picked, false));
                                    }
                                    chosenLanguage = picked;
                                }
                            }
                            if (batchSize == 1 && j > 4 && AsrRepetition.keepCount(completeOutput) < completeOutput.size()) {
                                max = eos;
                            }
                        }
                        if(batchSize == 2){
                            outputValues2 = value[1][0];
                            if(!finished2) {
                                max2 = Utils.getIndexOfLargest(outputValues2);
                                completeOutput2.add(max2);
                            }
                        }
                        //We prepare the inputs for the next iteration
                        if(batchSize == 1) {
                            inputIDsTensor = TensorUtils.convertIntArrayToTensor(onnxEnv, new int[]{max});
                        }else{
                            inputIDsTensor = TensorUtils.convertIntArrayToTensor(onnxEnv, new int[]{max, max2}, new long[]{2,1});
                        }

                        if(batchSize == 2){
                            //we calculate and update the probabilities of the two output sentences
                            double softmax = Utils.softmax(outputValues[max], outputValues);
                            if(!finished1) {
                                outputProbability1 = outputProbability1 + Math.log(softmax);
                            }
                            if(!finished2) {
                                outputProbability2 = outputProbability2 + Math.log(Utils.softmax(outputValues2[max2], outputValues2));
                            }
                        }

                        if(j >= maxTokens) {
                            if (!finished1) {
                                execution1HitMaxLength = true;
                                max = eos;
                            }
                            if (!finished2) {
                                execution2HitMaxLength = true;
                                max2 = eos;
                            }
                        }

                        if(max == eos){
                            finished1 = true;
                        }
                        if(max2 == eos){
                            finished2 = true;
                        }

                        j++;
                    }

                    if (rejectedLanguage != null) {
                        result.close();
                        outputs.close();
                        outputInit.close();
                        initResult.close();
                        notifyRejected(data, rejectedLanguage);
                        return;
                    }

                    if(batchSize == 2) {
                        //we normalize the scores based on the number of tokens of the respective sequence
                        outputProbability1 = outputProbability1 / completeOutput.size();
                        outputProbability2 = outputProbability2 / completeOutput2.size();
                    }

                    //execution of the detokenizer
                    Map detokenizerInputs = (Map) (new LinkedHashMap());
                    if(batchSize == 1) {
                        String finalText = "";
                        int[] sequences = keptTokens(completeOutput, eos);
                        if(sequences.length > 0) {
                            detokenizerInputs.put("sequences", TensorUtils.createInt32Tensor(onnxEnv, sequences, new long[]{1, 1, sequences.length}));
                            OrtSession.Result detokenizerOutputs = this.detokenizerSession.run(detokenizerInputs);
                            Object finalTextResult = detokenizerOutputs.get(0).getValue();
                            finalText = ((String[][]) finalTextResult)[0][0];
                            detokenizerOutputs.close();
                        }
                        android.util.Log.i("result", "result: " + correctText(finalText));
                        android.util.Log.i("score", "score: " + outputProbability1);

                        outputs.close();
                        if (autoLanguage) {
                            notifyResult(correctText(finalText), AsrLanguages.whisperCode(chosenLanguage, false), outputProbability1, true);
                        } else {
                            notifyResult(guardSingle(correctText(finalText), data.languageCode), data.languageCode, outputProbability1, true);
                        }

                    }else{
                        String firstText = UNDEFINED_TEXT;
                        if(!execution1HitMaxLength) {
                            int[] sequence1 = completeOutput.stream().mapToInt(i -> i).toArray();
                            detokenizerInputs.put("sequences", OnnxTensor.createTensor(onnxEnv, IntBuffer.wrap(sequence1), TensorUtils.tensorShape(1, 1, sequence1.length)));
                            OrtSession.Result detokenizerOutputs = this.detokenizerSession.run(detokenizerInputs);
                            Object firstTextResult = detokenizerOutputs.get(0).getValue();
                            firstText = ((String[][]) firstTextResult)[0][0];
                            detokenizerOutputs.close();
                        }

                        String secondText = UNDEFINED_TEXT;
                        if(!execution2HitMaxLength) {
                            int[] sequence2 = completeOutput2.stream().mapToInt(i -> i).toArray();
                            detokenizerInputs = (Map) (new LinkedHashMap());
                            detokenizerInputs.put("sequences", OnnxTensor.createTensor(onnxEnv, IntBuffer.wrap(sequence2), TensorUtils.tensorShape(1, 1, sequence2.length)));
                            OrtSession.Result detokenizerOutputs2 = this.detokenizerSession.run(detokenizerInputs);
                            Object secondTextResult = detokenizerOutputs2.get(0).getValue();
                            secondText = ((String[][]) secondTextResult)[0][0];
                            detokenizerOutputs2.close();
                        }

                        android.util.Log.i("result", "result 1: " + correctText(firstText));
                        android.util.Log.i("result", "result 2: " + correctText(secondText));
                        android.util.Log.i("result", "score 1: " + outputProbability1);
                        android.util.Log.i("result", "score 2: " + outputProbability2);

                        String[] guarded = guardPair(correctText(firstText), data.languageCode, correctText(secondText), data.languageCode2);
                        notifyMultiResult(guarded[0], data.languageCode, outputProbability1, guarded[1], data.languageCode2, outputProbability2);
                    }
                    //closing all results
                    outputs.close();
                    outputInit.close();
                    initResult.close();

                    android.util.Log.i("performance", "SPEECH RECOGNITION DONE IN: " + (SystemClock.elapsedRealtime() - startTimeInMs) + "ms");

            } catch (Exception e) {
                e.printStackTrace();
                notifyError(new int[]{ErrorCodes.ERROR_EXECUTING_MODEL}, 0);
            }
        } else {
            // Nothing was loaded: the old code dropped the utterance without a symptom.
            Log.e("recognizer", "no speech engine ready: model=" + global.getAsrModel()
                    + " quant=" + global.getAsrQuant(global.getAsrModel()));
            notifyError(new int[]{ErrorCodes.ERROR_LOADING_MODEL}, 0);
        }
    }

    private String correctText(String text){
        String correctedText = text;

        //sometimes, even if timestamps are deactivated, Whisper insert those anyway (es. <|0.00|>), so we remove eventual timestamps
        String regex = "<\\|[^>]*\\|> ";    //with this regex we remove all substrings of the form "<|something|> "
        correctedText = correctedText.replaceAll(regex, "");

        //we remove eventual white space from both ends of the text
        correctedText = correctedText.trim();

        if(correctedText.length() >= 2) {
            //if the correctedText start with a lower case letter we make it upper case
            char firstChar = correctedText.charAt(0);
            if (Character.isLowerCase(firstChar)) {
                StringBuilder sb = new StringBuilder(correctedText);
                sb.setCharAt(0, Character.toUpperCase(firstChar));
                correctedText = sb.toString();
            }
            //if the correctedText contains a "..." we remove it
            correctedText = correctedText.replace("...", "");
        }
        return correctedText;
    }

    // this method returns only the languages of Whisper-small that have a minimum quality (wer <= 37%)
    // LANGUAGES instead contains all languages supported by Whisper and it is needed for generating the language ID
    public static ArrayList<CustomLocale> getSupportedLanguages(Context context) {
        ArrayList<CustomLocale> languages = new ArrayList<>();
        SharedPreferences sharedPreferences = context.getSharedPreferences("default", Context.MODE_PRIVATE);
        boolean qualityLow = sharedPreferences.getBoolean("languagesNNQualityLow", false);
        if(!qualityLow) {
            DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
            try {
                DocumentBuilder documentBuilder = documentBuilderFactory.newDocumentBuilder();
                Document document = documentBuilder.parse(context.getResources().openRawResource(R.raw.whisper_supported_languages));
                NodeList list = document.getElementsByTagName("code");
                for (int i = 0; i < list.getLength(); i++) {
                    languages.add(CustomLocale.getInstance(list.item(i).getTextContent()));
                }
            } catch (IOException | SAXException | ParserConfigurationException e) {
                e.printStackTrace();
            }
        }else{
            for (String language : LANGUAGES) {
                languages.add(CustomLocale.getInstance(language));
            }
        }
        return languages;
    }

    public void stop(){
        synchronized (lock) {
            dataToRecognize.clear();
        }
    }

    public void destroy() {
        if (qwenEngine != null) {
            qwenEngine.close();
            qwenEngine = null;
        }
        if (turboEngine != null) {
            turboEngine.close();
            turboEngine = null;
        }
        // Whisper Small sessions are not inside those engines. Leaving them open
        // keeps their weights resident, so the next model fails to allocate.
        closeSession(initSession);
        closeSession(encoderSession);
        closeSession(cacheInitSession);
        closeSession(cacheInitBatchSession);
        closeSession(decoderSession);
        closeSession(detokenizerSession);
        initSession = null;
        encoderSession = null;
        cacheInitSession = null;
        cacheInitBatchSession = null;
        decoderSession = null;
        detokenizerSession = null;
    }

    private static void closeSession(OrtSession session) {
        if (session != null) {
            try {
                session.close();
            } catch (OrtException ignored) {
            }
        }
    }

    private void recognizeWithTurbo(DataContainer data) {
        try {
            long started = SystemClock.elapsedRealtime();
            boolean autoLanguage = data.languageCode2 == null && AsrLanguages.isAuto(data.languageCode);
            boolean softLanguage = (data.softLanguage || autoLanguage) && data.languageCode2 == null;
            boolean largeV3 = global.getAsrModel() == Global.ASR_WHISPER_TURBO;
            int allowedSwitchId = -1;
            if (softLanguage && !autoLanguage) {
                if (global.isAsrLanguageLock() && AsrLanguages.isWhisperLanguage(data.languageCode, largeV3)) {
                    // A locked clause is decoded with the configured token, so it cannot drift.
                    softLanguage = false;
                } else if (!global.isAsrLanguageLock()) {
                    allowedSwitchId = AsrLanguages.whisperToken(data.languageCodeSwitch, largeV3);
                }
            }
            if (!softLanguage && data.languageCode2 == null && !AsrLanguages.isWhisperLanguage(data.languageCode, largeV3)) {
                // Keep the parser alive for a code this model does not know.
                Log.w("asrLanguage", "no token for " + data.languageCode + " in this Whisper model; auto-detect");
                softLanguage = true;
            }
            WhisperTurboEngine.Transcripts transcripts = turboEngine.transcribe(data.data, data.languageCode, data.languageCode2,
                    softLanguage, allowedSwitchId, acceptedWhisperIds(data, largeV3));
            if (transcripts.rejected) {
                notifyRejected(data, transcripts.firstLanguage);
                return;
            }
            Log.i("asrLanguage", "model=" + (largeV3 ? "whisper-turbo" : "whisper-small")
                    + " soft=" + softLanguage + " requested=" + data.languageCode
                    + " rawLen=" + (transcripts.firstText == null ? 0 : transcripts.firstText.length())
                    + " latencyMs=" + (SystemClock.elapsedRealtime() - started));
            if (autoLanguage) {
                notifyResult(correctText(transcripts.firstText), transcripts.firstLanguage, transcripts.firstScore, true);
            } else if (data.languageCode2 == null) {
                String text = guardSingle(correctText(transcripts.firstText), data.languageCode);
                notifyResult(text, data.languageCode, transcripts.firstScore, true);
            } else {
                String first = correctText(transcripts.firstText);
                String second = correctText(transcripts.secondText);
                String[] guarded = guardPair(first, data.languageCode, second, data.languageCode2);
                notifyMultiResult(guarded[0], data.languageCode, transcripts.firstScore, guarded[1], data.languageCode2, transcripts.secondScore);
            }
        } catch (Exception e) {
            e.printStackTrace();
            notifyError(new int[]{ErrorCodes.ERROR_EXECUTING_MODEL}, 0);
        }
    }

    private void recognizeWithQwen(DataContainer data) {
        try {
            long started = SystemClock.elapsedRealtime();
            String model = global.getAsrModel() == Global.ASR_QWEN3_17 ? "qwen1.7b" : "qwen0.6b";
            boolean filter = isFilteringLanguages(data);
            if (data.languageCode2 == null) {
                String requested = data.languageCode;
                boolean autoLanguage = requested == null || requested.isEmpty() || AsrLanguages.isAuto(requested);
                if (autoLanguage || filter) {
                    // A free decode reports the spoken language; a forced one cannot.
                    Qwen3AsrEngine.Transcript free = qwenEngine.transcribe(data.data);
                    String detected = free.languageCode == null ? "" : free.languageCode;
                    if (autoLanguage) {
                        Log.i("asrLanguage", "model=" + model + " auto detected=" + detected
                                + " latencyMs=" + (SystemClock.elapsedRealtime() - started));
                        notifyResult(correctText(free.text), detected, 1, true);
                        return;
                    }
                    if (AsrLanguageFilter.isForeign(detected, requested)) {
                        notifyRejected(data, detected);
                        return;
                    }
                    if (sameLanguage(requested, detected)) {
                        Log.i("asrLanguage", "model=" + model + " soft=false requested=" + requested
                                + " detected=" + detected + " latencyMs=" + (SystemClock.elapsedRealtime() - started));
                        notifyResult(guardSingle(correctText(free.text), requested), requested, 1, true);
                        return;
                    }
                    // Unknown or a related language: the forced decode below keeps the configured one.
                }
                Qwen3AsrEngine.Transcript transcript = qwenEngine.transcribe(data.data, requested);
                String text = correctText(transcript.text);
                String language = requested;
                Log.i("asrLanguage", "model=" + model + " soft=false requested=" + language
                        + " forced=" + transcript.forced + " latencyMs=" + (SystemClock.elapsedRealtime() - started));
                notifyResult(guardSingle(text, language), language, 1, true);
                return;
            }
            Qwen3AsrEngine.Transcript transcript = qwenEngine.transcribe(data.data);
            if (filter && AsrLanguageFilter.isForeign(transcript.languageCode, data.languageCode, data.languageCode2)) {
                notifyRejected(data, transcript.languageCode);
                return;
            }
            String text = correctText(transcript.text);
            boolean second = sameLanguage(data.languageCode2, transcript.languageCode)
                    && !sameLanguage(data.languageCode, transcript.languageCode);
            String language = second ? data.languageCode2 : data.languageCode;
            if (AsrLanguageGuard.analyze(text, language).verdict == AsrLanguageGuard.Verdict.CONFLICT) {
                // One bounded retry with the other side forced into the prompt.
                String other = second ? data.languageCode : data.languageCode2;
                Qwen3AsrEngine.Transcript forced = qwenEngine.transcribe(data.data, other);
                String forcedText = correctText(forced.text);
                // MATCH, not just "no conflict": a short forced result has no evidence.
                if (forced.forced && AsrLanguageGuard.analyze(forcedText, other).verdict == AsrLanguageGuard.Verdict.MATCH) {
                    Log.i("asrLanguage", "model=qwen retry=" + AsrLanguages.displayName(other));
                    text = forcedText;
                    second = !second;
                }
            }
            Log.i("asrLanguage", "model=" + model + " soft=false requested="
                    + (second ? data.languageCode2 : data.languageCode)
                    + " detected=" + transcript.languageCode
                    + " latencyMs=" + (SystemClock.elapsedRealtime() - started));
            String[] guarded = second
                    ? guardPair(UNDEFINED_TEXT, data.languageCode, text, data.languageCode2)
                    : guardPair(text, data.languageCode, UNDEFINED_TEXT, data.languageCode2);
            if (second) {
                notifyMultiResult(guarded[0], data.languageCode, 0, guarded[1], data.languageCode2, 1);
            } else {
                notifyMultiResult(guarded[0], data.languageCode, 1, guarded[1], data.languageCode2, 0);
            }
        } catch (Exception e) {
            e.printStackTrace();
            notifyError(new int[]{ErrorCodes.ERROR_EXECUTING_MODEL}, 0);
        }
    }

    private static boolean sameLanguage(String requested, String detected) {
        return AsrLanguages.sameLanguage(requested, detected);
    }

    /** The session drops clauses in other languages. Auto accepts every language. */
    private boolean isFilteringLanguages(DataContainer data) {
        return global.isAsrIgnoreOtherLanguages()
                && !AsrLanguages.isAuto(data.languageCode)
                && !AsrLanguages.isAuto(data.languageCode2)
                && data.data.length >= AsrLanguageFilter.MIN_SAMPLES;
    }

    /**
     * Whisper tokens of the session languages, or null when nothing is filtered. A session
     * language without a token in this model turns the filter off, or its speech would be dropped.
     */
    private int[] acceptedWhisperIds(DataContainer data, boolean largeV3) {
        if (!isFilteringLanguages(data)) {
            return null;
        }
        String[] languages = data.languageCode2 == null
                ? new String[]{data.languageCode}
                : new String[]{data.languageCode, data.languageCode2};
        for (String language : languages) {
            if (AsrLanguageFilter.whisperIds(largeV3, language).length == 0) {
                return null;
            }
        }
        return AsrLanguageFilter.whisperIds(largeV3, languages);
    }

    /** Callers already skip {@link #UNDEFINED_TEXT}, so a dropped clause reaches no translation or context. */
    private void notifyRejected(DataContainer data, String detected) {
        Log.i("asrLanguage", "dropped clause detected=" + AsrLanguages.displayName(detected)
                + " requested=" + AsrLanguages.displayName(data.languageCode)
                + (data.languageCode2 == null ? "" : "," + AsrLanguages.displayName(data.languageCode2)));
        if (data.languageCode2 == null) {
            notifyResult(UNDEFINED_TEXT, data.languageCode, 0, true);
        } else {
            notifyMultiResult(UNDEFINED_TEXT, data.languageCode, 0, UNDEFINED_TEXT, data.languageCode2, 0);
        }
    }

    /**
     * The guard is advisory. A transcript is never dropped because a script
     * heuristic disagrees: suppressing text here made Whisper look broken when
     * the user switched languages or mixed a name into a sentence. The chosen
     * language token still comes from the lock; this only records the evidence.
     */
    private String guardSingle(String text, String languageCode) {
        if (!hasText(text)) {
            return text;
        }
        AsrLanguageGuard.Result result = logGuard(text, languageCode);
        if (result.verdict == AsrLanguageGuard.Verdict.CONFLICT) {
            Log.i("asrLanguage", "conflict=single reason=" + result.reason
                    + " requested=" + AsrLanguages.displayName(languageCode) + " kept=true");
        }
        return text;
    }

    /** Both sides stay available; the existing meta/confidence path still picks one. */
    private String[] guardPair(String firstText, String firstLanguage, String secondText, String secondLanguage) {
        AsrLanguageGuard.Result first = hasText(firstText) ? logGuard(firstText, firstLanguage) : null;
        AsrLanguageGuard.Result second = hasText(secondText) ? logGuard(secondText, secondLanguage) : null;
        if (first != null && first.verdict == AsrLanguageGuard.Verdict.CONFLICT) {
            Log.i("asrLanguage", "conflict=first reason=" + first.reason + " kept=true");
        }
        if (second != null && second.verdict == AsrLanguageGuard.Verdict.CONFLICT) {
            Log.i("asrLanguage", "conflict=second reason=" + second.reason + " kept=true");
        }
        return new String[]{firstText, secondText};
    }

    private static boolean hasText(String text) {
        return text != null && !text.isEmpty() && !UNDEFINED_TEXT.equals(text);
    }

    private AsrLanguageGuard.Result logGuard(String text, String languageCode) {
        AsrLanguageGuard.Result result;
        try {
            result = AsrLanguageGuard.analyze(text, languageCode);
        } catch (RuntimeException e) {
            // A diagnostic must never turn a valid transcript into a recognition error.
            Log.w("asrLanguage", "guard failed", e);
            return AsrLanguageGuard.weak("guard-error");
        }
        Log.i("asrLanguage", "guard=" + result.verdict + " reason=" + result.reason
                + " requested=" + AsrLanguages.displayName(languageCode)
                + " letters=" + result.letters + " len=" + text.length());
        return result;
    }

    public int getLanguageID(String language){
        // The old split graph is Whisper Small, so large-v3-only tokens are not valid here.
        int id = AsrLanguages.whisperToken(language, false);
        if (id < 0) {
            Log.e("recognizer", "Error converting language code " + language + " to a Whisper Small token");
        }
        return id;
    }

    /** Drops a trailing end token and a repeated tail. An empty result is left for the caller to skip. */
    private static int[] keptTokens(ArrayList<Integer> tokens, int eos) {
        int n = tokens.size();
        if (n > 0 && tokens.get(n - 1) == eos) {
            n--;
        }
        if (n <= 0) {
            return new int[0];
        }
        int[] body = new int[n];
        for (int i = 0; i < n; i++) {
            body[i] = tokens.get(i);
        }
        int keep = AsrRepetition.keepCount(body);
        if (keep <= 0 || !hasTextToken(body, keep, eos)) {
            return new int[0];
        }
        if (keep == n) {
            return body;
        }
        int[] trimmed = new int[keep];
        System.arraycopy(body, 0, trimmed, 0, keep);
        return trimmed;
    }

    private static boolean hasTextToken(int[] tokens, int count, int eos) {
        for (int i = 0; i < count; i++) {
            if (tokens[i] >= 0 && tokens[i] < eos) {
                return true;
            }
        }
        return false;
    }

    public void addCallback(final RecognizerListener callback) {
        callbacks.add(callback);
    }

    public void removeCallback(RecognizerListener callback) {
        callbacks.remove(callback);
    }

    public void addMultiCallback(final RecognizerMultiListener callback) {
        multiCallbacks.add(callback);
    }

    public void removeMultiCallback(RecognizerMultiListener callback) {
        multiCallbacks.remove(callback);
    }

    private void notifyResult(String text, String languageCode, double confidenceScore, boolean isFinal) {
        for (int i = 0; i < callbacks.size(); i++) {
            callbacks.get(i).onSpeechRecognizedResult(text, languageCode, confidenceScore, isFinal);
        }
    }

    private void notifyMultiResult(String text1, String languageCode1, double confidenceScore1, String text2, String languageCode2, double confidenceScore2) {
        for (int i = 0; i < multiCallbacks.size(); i++) {
            multiCallbacks.get(i).onSpeechRecognizedResult(text1, languageCode1, confidenceScore1, text2, languageCode2, confidenceScore2);
        }
    }

    private void notifyError(int[] reasons, long value) {
        for (int i = 0; i < callbacks.size(); i++) {
            callbacks.get(i).onError(reasons, value);
        }
        for (int i = 0; i < multiCallbacks.size(); i++) {
            multiCallbacks.get(i).onError(reasons, value);
        }
    }


    private static class DataContainer{
        private float[] data;
        private String languageCode;
        private String languageCode2;
        private String languageCodeSwitch;
        private int beamSize;
        private boolean softLanguage;

        private DataContainer(float[] data, int beamSize, String languageCode, boolean softLanguage){
            this.data = data;
            this.beamSize = beamSize;
            this.languageCode = languageCode;
            this.softLanguage = softLanguage;
        }

        private DataContainer(float[] data, int beamSize, String languageCode, String languageCode2){
            this.data = data;
            this.beamSize = beamSize;
            this.languageCode = languageCode;
            this.languageCode2 = languageCode2;
        }

        private DataContainer(float[] data, int beamSize, String languageCode, String targetLanguageCode, boolean softLanguage){
            this.data = data;
            this.beamSize = beamSize;
            this.languageCode = languageCode;
            this.languageCodeSwitch = targetLanguageCode;
            this.softLanguage = softLanguage;
        }
    }
}
