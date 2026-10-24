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

import static nie.translator.rtranslator.tools.DownloaderTools.checkMozillaModelsPresence;

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.splashscreen.SplashScreen;

import java.io.File;
import java.util.ArrayList;

import nie.translator.rtranslator.downloader.DownloadGroupInfo;

import nie.translator.rtranslator.access.AccessActivity;
import nie.translator.rtranslator.downloader.DownloadInfo;
import nie.translator.rtranslator.downloader.DownloadManager;
import nie.translator.rtranslator.settings.SettingsActivity;
import nie.translator.rtranslator.tools.CustomLocale;
import nie.translator.rtranslator.tools.DownloaderTools;
import nie.translator.rtranslator.tools.ErrorCodes;
import nie.translator.rtranslator.tools.ModelStartupGate;
import nie.translator.rtranslator.tools.ImageActivity;
import nie.translator.rtranslator.tools.ThemeManager;
import nie.translator.rtranslator.voice_translation.VoiceTranslationActivity;
import nie.translator.rtranslator.voice_translation.neural_networks.NeuralNetworkApi;
import nie.translator.rtranslator.voice_translation.neural_networks.translation.Translator;


public class LoadingActivity extends GeneralActivity {
    private final boolean START_IMAGE = false;
    private Handler mainHandler;
    private boolean isVisible = false;
    private Global global;
    private boolean startingActivity = false;
    private boolean contentReady = false;
    private boolean startupStarted = false;
    private boolean navigationStarted = false;
    private boolean errorDialogShowing = false;
    private boolean failurePresentQueued = false;
    private final ModelStartupGate.Observer startupObserver = new ModelStartupGate.Observer() {
        @Override
        public void onReady() {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    openNextAfterModelsReady();
                }
            });
        }

        @Override
        public void onFailed(int[] reasons, long value) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    presentStartupFailure();
                }
            });
        }
    };
    private TextView loadingStage;
    private ProgressBar loadingProgress;

    public LoadingActivity() {
        // Required empty public constructor
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Intent intent = getIntent();
        String previousActivity = intent.getStringExtra("activity");
        boolean fromWidget = intent.getBooleanExtra("from_widget", false);
        SplashScreen splashScreen = null;
        if(previousActivity == null && !fromWidget) {  //if this activity is called by another activity or from the widget (instead of on launch), we don't use the splash screen
            // Handle the splash screen transition (it must remain before the super.onCreate() call).
            splashScreen = SplashScreen.installSplashScreen(this);
        }
        super.onCreate(savedInstanceState);
        // Re-apply after the splash screen installs its post-splash theme, so a palette
        // selected on a previous run is used by the loading screen too.
        ThemeManager.applyTheme(this);
        // Re-assert the launcher icon alias, in case the process was restored without a cold start.
        ThemeManager.applyLauncherIcon(this);
        setContentView(R.layout.activity_loading);
        mainHandler = new Handler(Looper.getMainLooper());
        loadingStage = findViewById(R.id.loadingStage);
        loadingProgress = findViewById(R.id.loadingProgress);
        showStage(0);

        // Drop the splash once this layout has been drawn. Model loading continues with the progress bar.
        if(splashScreen != null) {
            splashScreen.setKeepOnScreenCondition(new SplashScreen.KeepOnScreenCondition() {
                @Override
                public boolean shouldKeepOnScreen() {
                    return !contentReady;
                }
            });
        }
        findViewById(android.R.id.content).post(new Runnable() {
            @Override
            public void run() {
                contentReady = true;
            }
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Update the original intent with the new one.
        // This is used when this activity is launched when it is already running.
        // If we don't set the newIntent, the new one passed in the launch is ignored.
        setIntent(intent);
    }

    public void onResume() {
        super.onResume();
        isVisible = true;
        global = (Global) getApplication();
        if (!startupStarted) {
            startupStarted = true;
            beginStartup();
            return;
        }
        // This instance was stopped while a load was already running. Ask again; the result may have arrived in the background.
        syncStartupUi();
    }

    @Override
    protected void onDestroy() {
        if (global != null) {
            global.removeModelStartupObserver(startupObserver);
        }
        super.onDestroy();
    }

    private void beginStartup() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                if (isFirstStart()) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (!isFinishing()) {
                                // Access downloads files and then opens a new loading screen. A previous failure must not block that screen.
                                if (global != null) {
                                    global.clearModelStartupFailure();
                                }
                                showStage(0);
                                startAccessActivity();
                            }
                        }
                    });
                    return;
                }
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        joinStartup(false);
                    }
                });
            }
        }, "appStartup").start();
    }

    private void syncStartupUi() {
        if (isFinishing() || global == null) {
            return;
        }
        if (global.areModelsLoaded()) {
            openNextAfterModelsReady();
        } else if (global.hasModelStartupFailure()) {
            presentStartupFailure();
        }
    }

    /**
     * Joins the one startup load kept on Global. A screen created after the user leaves must not
     * treat a non-null Translator or Recognizer as ready: those objects exist before the sessions do.
     */
    private void joinStartup(boolean ignoreTTSError) {
        if (isFinishing() || global == null) {
            return;
        }
        ModelStartupGate.Join join = global.beginOrJoinModelStartup(startupObserver);
        if (global.areModelsLoaded()) {
            openNextAfterModelsReady();
            return;
        }
        if (global.hasModelStartupFailure()) {
            presentStartupFailure();
            return;
        }
        if (join.start) {
            showStage(0);
            initializeApp(ignoreTTSError, join.runId);
            return;
        }
        showJoinedStage();
    }

    private void showJoinedStage() {
        if (global.isSpeechInitRunning() || global.isSpeechInitReady()) {
            showStage(2);
        } else if (global.isTranslatorInitRunning() || global.isTranslatorInitReady()) {
            showStage(1);
        } else {
            showStage(0);
        }
    }

    private void openNextAfterModelsReady() {
        if (!isVisible || isFinishing() || isDestroyed() || navigationStarted || global == null || !global.areModelsLoaded()) {
            return;
        }
        navigationStarted = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean resourcesMatch = checkIfResourcesPreferencesRespectDownloads();
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed() || !isVisible) {
                            navigationStarted = false;
                            return;
                        }
                        showStage(3);
                        if (resourcesMatch) {
                            startVoiceTranslationActivity();
                        } else {
                            startSettingsActivityWithModelManager();
                        }
                    }
                });
            }
        }, "appStartup").start();
    }

    private void retryStartup(boolean ignoreTTSError) {
        errorDialogShowing = false;
        failurePresentQueued = false;
        navigationStarted = false;
        if (global != null) {
            global.clearModelStartupFailure();
        }
        joinStartup(ignoreTTSError);
    }

    private void presentStartupFailure() {
        if (!isVisible || isFinishing() || errorDialogShowing || failurePresentQueued || global == null) {
            return;
        }
        if (global.getModelStartupFailureReasons() == null) {
            return;
        }
        failurePresentQueued = true;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                failurePresentQueued = false;
                if (!isVisible || isFinishing() || errorDialogShowing || global == null) {
                    return;
                }
                int[] reasons = global.getModelStartupFailureReasons();
                if (reasons == null) {
                    return;
                }
                errorDialogShowing = true;
                onFailure(reasons, global.getModelStartupFailureValue());
            }
        });
    }

    private void showStage(int stage) {
        if (loadingStage == null || loadingProgress == null) {
            return;
        }
        int text;
        switch (stage) {
            case 0:
                text = R.string.loading_stage_check;
                break;
            case 1:
                text = R.string.loading_stage_translator;
                break;
            case 2:
                text = R.string.loading_stage_speech;
                break;
            default:
                text = R.string.loading_stage_open;
                break;
        }
        loadingStage.setText(text);
        loadingProgress.setProgress(Math.min(stage + 1, 4));
    }

    @Override
    protected void onPause() {
        super.onPause();
        isVisible = false;
    }

    private boolean isFirstStart(){
        if(!global.isFirstStart()){  //if first start == false, we check if it's the first start after an update from the version 2.0
            // we check if there are some nllb files, if so we delete those and set foundNllb = true (this confirms that it's the first start after an update from the version 2.0)
            boolean foundNllb = false;
            DownloadGroupInfo nllbDownloadInfo = global.getNllbDownloadInfo();
            for(DownloadInfo download : nllbDownloadInfo.downloadsInfo){
                File file = new File(download.getDestinationCompletePath());
                if(file.exists()){
                    if(file.delete()) {
                        foundNllb = true;
                    }
                }
            }
            // if foundNllb == true we add the initial download status to the saved download status in the preferences and then we set first start to true and return true,
            // so the access activity will be started with the fragment started that will depend on the missing data.
            if(foundNllb){
                DownloadGroupInfo initialDownloadInfo = global.getInitialDownloadInfo();
                int firsIncompleteDownload = -1;
                if(!DownloadManager.getSavedDownloadStatus(this).contains(initialDownloadInfo)) {
                    for (int i = 0; i < initialDownloadInfo.downloadsInfo.length; i++) {
                        DownloadInfo download = initialDownloadInfo.downloadsInfo[i];
                        File file = new File(download.getDestinationCompletePath());
                        if (file.exists()) {
                            download.setDownloadCompleted(true);
                            if(download.shouldUnzip()) download.setUnzipped(true);
                            if(download.shouldTestIntegrity()) download.setIntegrityTested(true);
                        }else if(firsIncompleteDownload == -1){
                            firsIncompleteDownload = i;
                        }
                    }
                }
                initialDownloadInfo.setRunningDownloadIndex(firsIncompleteDownload);
                DownloaderTools.addDownloadGroupInfoPreference(this, initialDownloadInfo);
                global.setFirstStart(true);
                return true;
            }else{
                return false;
            }
        }else{
            return true;
        }
    }

    private void initializeApp(final boolean ignoreTTSError, final int runId) {
        Log.i("app", "App initialization");
        showStage(0);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    adaptResourcesPreferencesToDownloads();
                } catch (RuntimeException e) {
                    e.printStackTrace();
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            global.failModelStartup(runId, new int[]{ErrorCodes.ERROR_LOADING_MODEL}, 0);
                        }
                    });
                    return;
                }
                // Keep going after this screen is destroyed. The result is stored on Global for the screen the user comes back to.
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        continueInitializeApp(ignoreTTSError, runId);
                    }
                });
            }
        }, "appStartup").start();
    }

    private void continueInitializeApp(final boolean ignoreTTSError, final int runId) {
        showStage(1);
        global.getLanguagesAndCheckTTS(false, ignoreTTSError, new Global.GetLocalesListListener() {
            @Override
            public void onSuccess(ArrayList<CustomLocale> result) {
                global.initializeTranslator(new Translator.GeneralListener() {
                    @Override
                    public void onSuccess() {
                        showStage(2);
                        NeuralNetworkApi.InitListener speechRecognizerInitListener = new NeuralNetworkApi.InitListener() {
                            @Override
                            public void onInitializationFinished() {
                                global.completeModelStartup(runId);
                            }

                            @Override
                            public void onError(int[] reasons, long value) {
                                try {
                                    global.deleteSpeechRecognizer();  //we do this to ensure the restart of the loading of models when the app is restarted
                                } finally {
                                    global.failModelStartup(runId, reasons, value);
                                }
                            }
                        };
                        if(Global.ONLY_TEXT_TRANSLATION_MODE) {
                            speechRecognizerInitListener.onInitializationFinished();
                        }else{
                            global.initializeSpeechRecognizer(speechRecognizerInitListener);
                        }
                    }

                    @Override
                    public void onFailure(int[] reasons, long value) {
                        try {
                            global.deleteTranslator();   //we do this to ensure the restart of the loading of models when the app is restarted
                        } finally {
                            global.failModelStartup(runId, reasons, value);
                        }
                    }
                });
            }

            @Override
            public void onFailure(int[] reasons, long value) {
                global.failModelStartup(runId, reasons, value);
            }
        });
    }

    private void startAccessActivity(){
        Intent intent = new Intent(this, AccessActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    private void startVoiceTranslationActivity() {
        if(!START_IMAGE) {
            startingActivity = true;
            Intent intent = new Intent(LoadingActivity.this, VoiceTranslationActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            // Eventually we forward the start_mode value to VoiceTranslationActivity
            Intent mainIntent = getIntent();
            if(mainIntent != null && mainIntent.hasExtra("start_mode")){
                String mode = mainIntent.getStringExtra("start_mode");
                intent.putExtra("start_mode", mode);
                /* We remove the extra from the activity intent.
                * This is because otherwise, the next time we launch this Activity without an intent, this intent will be used again and again.
                * For this reason, the intent request must be executed only one time for each time it is passed from outside.
                */
                mainIntent.removeExtra("start_mode");
            }

            startActivity(intent);
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
            finish();
        }else{
            startImageActivity();
        }
    }

    private void startSettingsActivityWithModelManager(){
        Intent intent = new Intent(this, SettingsActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra("startWithModelManager", true);
        startActivity(intent);
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    private void startImageActivity() {
        startingActivity = true;
        Intent intent = new Intent(LoadingActivity.this, ImageActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    private boolean checkIfResourcesPreferencesRespectDownloads(){
        long initTime = System.currentTimeMillis();
        // check of translation model preference
        ArrayList<DownloadGroupInfo> saved = DownloadManager.getSavedDownloadStatus(this);
        int translationMode = global.getTranslationMode();
        switch (translationMode) {
            case Translator.MOZILLA:
                if(!checkMozillaModelsPresence(saved)){
                    return false;
                }
                break;
            case Translator.HY_MT:
                if(!DownloadManager.isDownloadCompleted(saved, global.getHyMtDownloadInfo())){
                    return false;
                }
                break;
            case Translator.MADLAD_CACHE:
                if(!DownloadManager.isDownloadCompleted(saved, global.getMadladDownloadInfo())){
                    return false;
                }
                break;
            case Translator.TRANSLATEGEMMA:
                if(!DownloadManager.isDownloadCompleted(saved, global.getTranslateGemmaDownloadInfo())){
                    return false;
                }
                break;
            case Translator.QWEN35:
                if(!DownloadManager.isDownloadCompleted(saved, global.getQwen35DownloadInfo())){
                    return false;
                }
                break;
        }
        // check of Mozilla for voice translation modes preference
        if(translationMode != Translator.MOZILLA && global.isUseMozillaForVoiceTranslation() && !checkMozillaModelsPresence(saved)){
            return false;
        }
        // check of Tatoeba preference
        if(global.isUseTatoeba() && !DownloadManager.isDownloadCompleted(saved, global.getTatoebaDownloadInfo())){
            return false;
        }
        if (global.isSimultaneousVadSegmentation() && !DownloadManager.isDownloadCompleted(saved, global.getSileroVadDownloadInfo())) {
            return false;
        }
        android.util.Log.i("performance", "checkIfResourcesPreferencesRespectDownloads done in: " + (System.currentTimeMillis() - initTime) + "ms");
        return true;
    }

    /**
     * This method must be called before the app initialization (initializeApp()).
     * In the case the user has deleted some resources in the ModelManagerFragment and then
     * terminated the app before applying the settings (with the relative checks).
     * When the app is restarted there can be some preferences that require some resources that
     * have been deleted. This method will restore the correct settings to match the downloaded resource.
     * <p>
     * Note: When deleting the resource the user cannot cancel all the translation models downloaded,
     * so this method can always change the preferences to make the app work (the app needs at least
     * a downloaded translation model, and nothing else)
     */
    private void adaptResourcesPreferencesToDownloads(){
        long initTime = System.currentTimeMillis();
        // eventual adaptation of translation model preference
        ArrayList<DownloadGroupInfo> saved = DownloadManager.getSavedDownloadStatus(this);
        int translationMode = global.getTranslationMode();
        ArrayList<Integer> availableModels = new ArrayList<>();
        if(checkMozillaModelsPresence(saved)){
            availableModels.add(Translator.MOZILLA);
        }
        if(DownloadManager.isDownloadCompleted(saved, global.getHyMtDownloadInfo())){
            availableModels.add(Translator.HY_MT);
        }
        if(DownloadManager.isDownloadCompleted(saved, global.getMadladDownloadInfo())){
            availableModels.add(Translator.MADLAD_CACHE);
        }
        if(DownloadManager.isDownloadCompleted(saved, global.getTranslateGemmaDownloadInfo())){
            availableModels.add(Translator.TRANSLATEGEMMA);
        }
        if(DownloadManager.isDownloadCompleted(saved, global.getQwen35DownloadInfo())){
            availableModels.add(Translator.QWEN35);
        }
        if(!availableModels.contains(translationMode)){
            int newTranslationMode = -1;
            if(availableModels.contains(Translator.MOZILLA)){
                newTranslationMode = Translator.MOZILLA;
            }else if(availableModels.contains(Translator.HY_MT)){
                newTranslationMode = Translator.HY_MT;
            }else if(availableModels.contains(Translator.TRANSLATEGEMMA)){
                newTranslationMode = Translator.TRANSLATEGEMMA;
            }else if(availableModels.contains(Translator.QWEN35)){
                newTranslationMode = Translator.QWEN35;
            }else if(availableModels.contains(Translator.MADLAD_CACHE)){
                newTranslationMode = Translator.MADLAD_CACHE;
            }
            if(newTranslationMode != -1) {
                final SharedPreferences sharedPreferences = this.getSharedPreferences("default", Context.MODE_PRIVATE);
                SharedPreferences.Editor editor = sharedPreferences.edit();
                editor.putInt("selectedTranslationModel", newTranslationMode);
                editor.apply();
            }
        }
        // eventual adaptation of Mozilla for voice translation modes preference
        if(!availableModels.contains(Translator.MOZILLA)){
            final SharedPreferences sharedPreferences = this.getSharedPreferences("default", Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putBoolean("useMozillaForVoiceTranslation", false);
            editor.apply();
        }
        // eventual adaptation of Tatoeba preference
        if(!DownloadManager.isDownloadCompleted(saved, global.getTatoebaDownloadInfo())){
            final SharedPreferences sharedPreferences = this.getSharedPreferences("default", Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putBoolean("useTatoeba", false);
            editor.apply();
        }
        if (!DownloadManager.isDownloadCompleted(saved, global.getSileroVadDownloadInfo())) {
            global.setSimultaneousVadSegmentation(false);
        }
        android.util.Log.i("performance", "adaptResourcesPreferencesToDownloads done in: " + (System.currentTimeMillis() - initTime) + "ms");
    }

    private void notifyGoogleTTSErrorDialog() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!isVisible || isFinishing()) {
                    errorDialogShowing = false;
                    return;
                }
                showGoogleTTSErrorDialog(new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        retryStartup(true);
                    }
                });
            }
        });
    }

    public void notifyInternetLack() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!isVisible || isFinishing()) {
                    errorDialogShowing = false;
                    return;
                }
                // creation of the dialog.
                AlertDialog.Builder builder = new AlertDialog.Builder(LoadingActivity.this);
                //builder.setCancelable(true);
                builder.setMessage(R.string.error_internet_lack_loading);
                builder.setNegativeButton(R.string.exit, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        finish();
                    }
                });
                builder.setPositiveButton(R.string.retry, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        retryStartup(false);
                    }
                });
                AlertDialog dialog = builder.create();
                dialog.setCanceledOnTouchOutside(false);
                dialog.show();
            }
        });
    }

    public void notifyModelsLoadingError() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!isVisible || isFinishing()) {
                    errorDialogShowing = false;
                    return;
                }
                // creation of the dialog.
                AlertDialog.Builder builder = new AlertDialog.Builder(LoadingActivity.this);
                //builder.setCancelable(true);
                builder.setMessage(R.string.error_models_loading);
                builder.setPositiveButton(R.string.fix, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if(global != null){
                            restartDownload();
                        }
                    }
                });
                builder.setNegativeButton(R.string.exit, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        finish();
                    }
                });
                AlertDialog dialog = builder.create();
                dialog.setCanceledOnTouchOutside(false);
                dialog.show();
            }
        });
    }

    private void notifyMissingGoogleTTSDialog() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!isVisible || isFinishing()) {
                    errorDialogShowing = false;
                    return;
                }
                showMissingGoogleTTSDialog(new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        retryStartup(true);
                    }
                });
            }
        });
    }


    private void restartDownload(){
        //we reset all the download shared preferences
        SharedPreferences sharedPreferences = getSharedPreferences("default", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor;
        editor = sharedPreferences.edit();
        editor.putLong("currentDownloadId", -1);
        editor.apply();
        editor = sharedPreferences.edit();
        editor.putString("lastDownloadSuccess", "");
        editor.apply();
        editor = sharedPreferences.edit();
        editor.putString("lastTransferSuccess", "");
        editor.apply();
        editor = sharedPreferences.edit();
        editor.putString("lastTransferFailure", "");
        editor.apply();
        // The failed startup must not be shown again after the files are downloaded once more.
        global.clearModelStartupFailure();
        //we restart the download (only the corrupted files will be re-downloaded)
        global.setFirstStart(true);
        Intent intent = new Intent(LoadingActivity.this, AccessActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    private void onFailure(int[] reasons, long value) {
        for (int aReason : reasons) {
            switch (aReason) {
                case ErrorCodes.ERROR_LOADING_MODEL:
                    notifyModelsLoadingError();
                    break;
                case ErrorCodes.SAFETY_NET_EXCEPTION:
                case ErrorCodes.MISSED_CONNECTION:
                    notifyInternetLack();
                    break;
                case ErrorCodes.MISSING_GOOGLE_TTS:
                    notifyMissingGoogleTTSDialog();
                    break;
                case ErrorCodes.GOOGLE_TTS_ERROR:
                    notifyGoogleTTSErrorDialog();
                    break;
                default:
                    onError(aReason, value);
                    break;
            }
        }
    }
}
