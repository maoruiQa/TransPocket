package nie.translator.rtranslator.settings;

import static nie.translator.rtranslator.tools.DownloaderTools.checkMozillaModelsPresence;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.icu.text.DecimalFormat;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.cardview.widget.CardView;
import androidx.core.view.AccessibilityDelegateCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Set;

import nie.translator.rtranslator.Global;
import nie.translator.rtranslator.LoadingActivity;
import nie.translator.rtranslator.R;
import nie.translator.rtranslator.access.AccessActivity;
import nie.translator.rtranslator.downloader.DownloadGroupInfo;
import nie.translator.rtranslator.downloader.DownloadInfo;
import nie.translator.rtranslator.downloader.DownloadManager;
import nie.translator.rtranslator.tools.DownloaderTools;
import nie.translator.rtranslator.tools.ErrorCodes;
import nie.translator.rtranslator.tools.LiveDraftVoices;
import nie.translator.rtranslator.tools.Tools;
import nie.translator.rtranslator.tools.gui.ResourceManagerView;
import nie.translator.rtranslator.tools.gui.SegmentProgressBar;
import nie.translator.rtranslator.voice_translation.neural_networks.translation.Translator;
import worker8.com.github.radiogroupplus.RadioGroupPlus;

public class ModelManagerFragment extends Fragment {
    private enum ApplySettingsStage{
        TRANSLATION_MODEL,
        MOZILLA_FOR_VOICE,
        WHISPER_RAM_REDUCTION,
        TATOEBA,
        TRANSLATION_DICT,
        ASR_MODEL,
        VAD_SEGMENTATION
    }
    private static int WHISPER_RAM_CONSUMPTION_MB = 900;
    private static int WHISPER_RAM_CONSUMPTION_REDUCED_MB = 500;
    private static int QWEN06_RAM_CONSUMPTION_MB = 2200;
    private static int QWEN17_RAM_CONSUMPTION_MB = 4200;
    private static int WHISPER_TURBO_RAM_CONSUMPTION_MB = 2600;
    private static int MOZILLA_RAM_CONSUMPTION_MB = 100;  //todo: measure it better
    private static int HY_RAM_CONSUMPTION_MB = 1900;
    private static int MADLAD_RAM_CONSUMPTION_MB = 1800;
    private static int GEMMA_RAM_CONSUMPTION_MB = 3600;
    private static int QWEN35_RAM_CONSUMPTION_MB = 1400;
    private static int TATOEBA_RAM_CONSUMPTION_MB = 5;    //todo: measure it better
    private static int VAD_RAM_CONSUMPTION_MB = 20;
    private static int DICT_RAM_CONSUMPTION_MB = 140;
    private Activity activity;
    private Global global;
    private DownloadManager downloadManager;
    private HashMap<String, ResourceManager> resourceManagers = new HashMap<>();
    private DownloadManager.Callback downloadManagerCallback;
    // gui
    private RadioGroupPlus radioGroup;
    private RadioButton radioMozilla;
    private RadioButton radioHy;
    private RadioButton radioMadlad;
    private RadioButton radioGemma;
    private RadioButton radioQwen35;
    private RadioGroupPlus asrRadioGroup;
    private RadioButton radioWhisper;
    private RadioButton radioWhisperTurbo;
    private RadioButton radioQwen06;
    private RadioButton radioQwen17;
    private ResourceManagerView hyManagerView;
    private ResourceManagerView madladManagerView;
    private ResourceManagerView gemmaManagerView;
    private ResourceManagerView qwen35ManagerView;
    private ResourceManagerView whisperManagerView;
    private ResourceManagerView whisperTurboManagerView;
    private ResourceManagerView qwen06ManagerView;
    private ResourceManagerView qwen17ManagerView;
    private ResourceManagerView tatoebaManagerView;
    private ResourceManagerView vadManagerView;
    private Button applyButton;
    private SegmentProgressBar barRam;
    private SwitchMaterial switchMozillaForVoiceModes;
    private SwitchMaterial switchWhisperReducedRam;
    private SwitchMaterial switchTatoeba;
    private SwitchMaterial switchVadSegmentation;
    private SwitchMaterial switchTranslationDict;
    private TextView textRamUsage;
    private TextView textRamUsage2;
    private TextView recommendationText;
    private ImageView arrowMozilla;
    private LinearLayout liveDraftList;
    private boolean guiStateRestored = false;
    private int baselineTranslationRadioId = View.NO_ID;
    private int baselineAsrRadioId = View.NO_ID;
    private boolean baselineMozillaForVoice;
    private boolean baselineWhisperReducedRam;
    private boolean baselineTatoeba;
    private boolean baselineVadSegmentation;
    private boolean baselineTranslationDict;
    private ArrayList<DownloadGroupInfo> savedDownloadStatus;
    private int managerLoadGeneration = 0;


    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_models_manager, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        radioGroup = view.findViewById(R.id.model_radios);
        radioMozilla = view.findViewById(R.id.radioMozilla);
        radioHy = view.findViewById(R.id.radioHY);
        radioMadlad = view.findViewById(R.id.radioMadlad);
        radioGemma = view.findViewById(R.id.radioGemma);
        radioQwen35 = view.findViewById(R.id.radioQwen35);
        asrRadioGroup = view.findViewById(R.id.asr_radios);
        radioWhisper = view.findViewById(R.id.radioWhisper);
        radioWhisperTurbo = view.findViewById(R.id.radioWhisperTurbo);
        radioQwen06 = view.findViewById(R.id.radioQwen06);
        radioQwen17 = view.findViewById(R.id.radioQwen17);
        whisperManagerView = view.findViewById(R.id.modelWhisper);
        whisperTurboManagerView = view.findViewById(R.id.modelWhisperTurbo);
        qwen06ManagerView = view.findViewById(R.id.modelQwen06);
        qwen17ManagerView = view.findViewById(R.id.modelQwen17);
        hyManagerView = view.findViewById(R.id.modelHy);
        madladManagerView = view.findViewById(R.id.modelMadlad);
        gemmaManagerView = view.findViewById(R.id.modelGemma);
        qwen35ManagerView = view.findViewById(R.id.modelQwen35);
        tatoebaManagerView = view.findViewById(R.id.resourceTatoeba);
        vadManagerView = view.findViewById(R.id.modelVad);
        applyButton = view.findViewById(R.id.buttonApply);
        barRam = view.findViewById(R.id.barRam);
        switchMozillaForVoiceModes = view.findViewById(R.id.switchMozillaForVoiceModes);
        switchWhisperReducedRam = view.findViewById(R.id.switchWhisperReducedRam);
        switchTatoeba = view.findViewById(R.id.switchTatoeba);
        switchVadSegmentation = view.findViewById(R.id.switchVadSegmentation);
        switchTranslationDict = view.findViewById(R.id.switchTranslationDict);
        textRamUsage = view.findViewById(R.id.textRamUsage);
        textRamUsage2 = view.findViewById(R.id.textRamUsage2);
        recommendationText = view.findViewById(R.id.recommendationText);
        arrowMozilla = view.findViewById(R.id.arrowMozilla);
        liveDraftList = view.findViewById(R.id.liveDraftList);
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        activity = requireActivity();
        global = (Global) activity.getApplication();
        if (activity instanceof SettingsActivity) {
            ((SettingsActivity) activity).onModelManagerShown();
        }

        //initialize download manager
        downloadManager = new DownloadManager(global);
        resourceManagers.put("hyManager", new ResourceManager(activity, global.getHyMtDownloadInfo(), hyManagerView, downloadManager));
        resourceManagers.put("madladManager", new ResourceManager(activity, global.getMadladDownloadInfo(), madladManagerView, downloadManager));
        resourceManagers.put("gemmaManager", new ResourceManager(activity, global.getTranslateGemmaDownloadInfo(), gemmaManagerView, downloadManager));
        resourceManagers.put("qwen35Manager", new ResourceManager(activity, global.getQwen35DownloadInfo(), qwen35ManagerView, downloadManager));
        resourceManagers.put("whisperManager", new ResourceManager(activity, global.getWhisperDownloadInfo(), whisperManagerView, downloadManager));
        resourceManagers.put("whisperTurboManager", new ResourceManager(activity, global.getWhisperTurboDownloadInfo(), whisperTurboManagerView, downloadManager));
        resourceManagers.put("qwen06Manager", new ResourceManager(activity, global.getQwen3Asr06DownloadInfo(), qwen06ManagerView, downloadManager));
        resourceManagers.put("qwen17Manager", new ResourceManager(activity, global.getQwen3Asr17DownloadInfo(), qwen17ManagerView, downloadManager));
        resourceManagers.put("tatoebaManager", new ResourceManager(activity, global.getTatoebaDownloadInfo(), tatoebaManagerView, downloadManager));
        resourceManagers.put("vadManager", new ResourceManager(activity, global.getSileroVadDownloadInfo(), vadManagerView, downloadManager));
        showLiveDraftLoading();

        // initialize GUI based on shared preferences
        restoreGuiPreferenceState();

        // initialize GUI listeners
        downloadManagerCallback = new DownloadManager.Callback() {
            @Override
            public void onServiceConnected() {
                if(!guiStateRestored) {
                    ArrayList<DownloadGroupInfo> downloadsStatus = downloadManager.getDownloadsStatus();
                    // we change the GUI based on current download status
                    restoreGuiDownloadState(downloadsStatus);
                }
            }

            @Override
            public void onProgress(DownloadGroupInfo downloadGroup, DownloadInfo download, int totalProgress, int progress, boolean unzipping, boolean testingIntegrity) {
                for(ResourceManager manager: resourceManagers.values()) {
                    if (downloadGroup.equals(manager.getDownloadInfo())) {
                        manager.setProgress(totalProgress, unzipping, testingIntegrity);
                    }
                }
            }

            @Override
            public void onCompleted(DownloadGroupInfo downloadGroup, DownloadInfo download) {

            }

            @Override
            public void onAllCompleted(DownloadGroupInfo downloadGroup) {
                for(ResourceManager manager: resourceManagers.values()) {
                    if (downloadGroup.equals(manager.getDownloadInfo())) {
                        manager.setState(ResourceManagerView.State.DOWNLOADED);
                    }
                }
            }

            @Override
            public void onError(DownloadGroupInfo downloadGroup, DownloadInfo download, int reason) {
                for(ResourceManager manager: resourceManagers.values()) {
                    if (downloadGroup.equals(manager.getDownloadInfo())) {
                        manager.setError(reason);
                    }
                }
            }
        };
        arrowMozilla.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(activity instanceof SettingsActivity) {
                    ((SettingsActivity) activity).startFragment(SettingsActivity.MOZILLA_MANAGER, null);
                }else if(activity instanceof AccessActivity){
                    ((AccessActivity) activity).startFragment(AccessActivity.MOZILLA_MANAGER, null);
                }
            }
        });
        asrRadioGroup.setOnCheckedChangeListener((group, checkedId) -> {
            switchWhisperReducedRam.setEnabled(checkedId == R.id.radioWhisper);
            updateRamUsageSpeechRecognition();
        });
        radioGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if(checkedId == R.id.radioMozilla){  //eventual deactivation of switchMozillaForVoiceModes if Mozilla is the model selected
                switchMozillaForVoiceModes.setChecked(false);
                switchMozillaForVoiceModes.setEnabled(false);
            }else{
                switchMozillaForVoiceModes.setEnabled(true);
            }
            updateRamUsageTranslation();
        });
        switchMozillaForVoiceModes.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(@NonNull CompoundButton buttonView, boolean isChecked) {
                //update the ram bar based on the new switch value
                updateRamUsageTranslation();
            }
        });
        switchWhisperReducedRam.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(@NonNull CompoundButton buttonView, boolean isChecked) {
                //update the ram bar based on the new switch value
                updateRamUsageSpeechRecognition();
            }
        });
        switchTatoeba.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(@NonNull CompoundButton buttonView, boolean isChecked) {
                updateRamUsageTranslationEnhancements();
            }
        });
        switchVadSegmentation.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(@NonNull CompoundButton buttonView, boolean isChecked) {
                updateRamUsageTranslationEnhancements();
            }
        });
        switchTranslationDict.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(@NonNull CompoundButton buttonView, boolean isChecked) {
                updateRamUsageTranslationEnhancements();
            }
        });
        applyButton.setOnClickListener((v) -> {
            applySettings();
        });
        barRam.setSegmentProgressChangeListener(new SegmentProgressBar.SegmentChangeListener() {
            @Override
            public void onSegmentProgressChanged() {
                //update the RAM text
                updateTextRamUsage();
            }
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        final int generation = ++managerLoadGeneration;
        final Activity host = activity;
        if (host == null || downloadManager == null) {
            return;
        }
        new Thread(() -> {
            ArrayList<DownloadGroupInfo> saved = downloadManager.getSavedDownloadStatus();
            HashMap<String, Boolean> ready = new HashMap<>();
            for (LiveDraftVoices.Voice voice : LiveDraftVoices.all()) {
                ready.put(voice.code, LiveDraftVoices.isReady(host, voice.code));
            }
            host.runOnUiThread(() -> {
                if (generation != managerLoadGeneration || !isAdded()) {
                    return;
                }
                savedDownloadStatus = saved;
                buildLiveDraftRows(ready);
                boolean serviceStarted = downloadManager.subscribeAndResumeDownload(downloadManagerCallback, saved);
                if (!serviceStarted && !guiStateRestored) {
                    restoreGuiDownloadState(saved);
                }
            });
        }, "modelManagerState").start();
    }

    @Override
    public void onStop() {
        super.onStop();
        managerLoadGeneration++;
        guiStateRestored = false;
        if (downloadManager != null) {
            downloadManager.unsubscribe();
        }
    }

    public void restoreGuiPreferenceState(){
        // model selection initialization
        SharedPreferences sharedPreferences = global.getSharedPreferences("default", Context.MODE_PRIVATE);
        ModelRecommendation recommendation = ModelRecommendation.forDevice(global);
        String recommendationMessage = getString(recommendation.messageRes, recommendation.totalRamGb, recommendation.freeStorageGb);
        if (recommendation.whisperReducedRam) {
            recommendationMessage = recommendationMessage + getString(R.string.model_recommendation_whisper_reduced);
        }
        recommendationText.setText(recommendationMessage);
        boolean userHasChosenModel = sharedPreferences.contains("selectedTranslationModel");
        int mode = sharedPreferences.getInt("selectedTranslationModel", recommendation.translationModel);
        if (!userHasChosenModel) {
            mode = recommendation.translationModel;
        }
        switch (mode) {
            case Translator.MOZILLA:
                radioGroup.check(R.id.radioMozilla);
                switchMozillaForVoiceModes.setActivated(false);
                break;
            case Translator.MADLAD:
            case Translator.MADLAD_CACHE:
                radioGroup.check(R.id.radioMadlad);
                break;
            case Translator.HY_MT:
                radioGroup.check(R.id.radioHY);
                break;
            case Translator.TRANSLATEGEMMA:
                radioGroup.check(R.id.radioGemma);
                break;
            case Translator.QWEN35:
                radioGroup.check(R.id.radioQwen35);
                break;
        }
        // switches initialization
        switchMozillaForVoiceModes.setChecked(sharedPreferences.contains("useMozillaForVoiceTranslation") ? global.isUseMozillaForVoiceTranslation() : recommendation.useMozillaForVoice);
        switchWhisperReducedRam.setChecked(sharedPreferences.contains("whisperReducedRam") ? global.isWhisperReducedRam() : recommendation.whisperReducedRam);
        switchTatoeba.setChecked(sharedPreferences.contains("useTatoeba") ? global.isUseTatoeba() : recommendation.useTatoeba);
        switchVadSegmentation.setChecked(sharedPreferences.contains("simultaneousVadSegmentation") ? global.isSimultaneousVadSegmentation() : true);
        switchTranslationDict.setChecked(sharedPreferences.contains("useTranslationDictionaries") ? global.isUseTranslationDictionaries() : recommendation.useDictionaries);
        switch (global.getAsrModel()) {
            case Global.ASR_QWEN3_17:
                asrRadioGroup.check(R.id.radioQwen17);
                break;
            case Global.ASR_QWEN3_06:
                asrRadioGroup.check(R.id.radioQwen06);
                break;
            case Global.ASR_WHISPER_TURBO:
                asrRadioGroup.check(R.id.radioWhisperTurbo);
                break;
            default:
                asrRadioGroup.check(R.id.radioWhisper);
                break;
        }
        switchWhisperReducedRam.setEnabled(asrRadioGroup.getCheckedRadioButtonId() == R.id.radioWhisper);
        if(mode == Translator.MOZILLA){  //eventual deactivation of switchMozillaForVoiceModes if Mozilla is the model selected
            switchMozillaForVoiceModes.setChecked(false);
            switchMozillaForVoiceModes.setEnabled(false);
        }else{
            switchMozillaForVoiceModes.setEnabled(true);
        }

        // ram consumption bar redline and orangeLine initialization
        barRam.setRedLine(100F - (float) (global.getRamThreshold() * 100) / global.getTotalRamSize());
        barRam.setOrangeLine(80F);

        // ram consumption bar initialization
        setRamUsageSystem(global.getTotalRamSize() - global.getMaxAllocatableRAM());
        updateRamUsageSpeechRecognition();
        updateRamUsageTranslation();
        updateRamUsageTranslationEnhancements();
        updateTextRamUsage();
        captureSettingsBaseline();
    }

    /** Snapshot of the controls after they are filled from preferences. Later edits are compared to this, not to values the screen itself rewrites on open. */
    private void captureSettingsBaseline() {
        baselineTranslationRadioId = radioGroup.getCheckedRadioButtonId();
        baselineAsrRadioId = asrRadioGroup.getCheckedRadioButtonId();
        baselineMozillaForVoice = switchMozillaForVoiceModes.isChecked();
        baselineWhisperReducedRam = switchWhisperReducedRam.isChecked();
        baselineTatoeba = switchTatoeba.isChecked();
        baselineVadSegmentation = switchVadSegmentation.isChecked();
        baselineTranslationDict = switchTranslationDict.isChecked();
    }

    private void restoreGuiDownloadState(ArrayList<DownloadGroupInfo> downloadStatus){
        // we change the GUI based on current download status
        if(downloadStatus != null){
            guiStateRestored = true;
            for(DownloadGroupInfo download: downloadStatus){
                for(ResourceManager manager: resourceManagers.values()) {
                    if (download.equals(manager.getDownloadInfo())) {
                        ResourceManagerView.State state = ResourceManagerView.State.EMPTY;
                        int index = DownloaderTools.findFirstIncompletedDownload(download);
                        //todo: improve detection methods of status
                        if(download.isAllDownloadCompleted()){
                            state = ResourceManagerView.State.DOWNLOADED;
                        } else if (download.getRunningDownloadIndex() == -1 && download.getCurrentProgress() <= 0) {
                            state = ResourceManagerView.State.EMPTY;
                        } else if (
                                (download.getRunningDownloadIndex() == -1 && download.getCurrentProgress() > 0) ||
                                (download.getRunningDownloadIndex() != -1 && download.getRunningDownload().getCurrentError() != -1)  //in case of an error the status will be PAUSED
                        ) {
                            state = ResourceManagerView.State.PAUSED;
                        } else if (download.getRunningDownloadIndex() != -1) {
                            state = ResourceManagerView.State.DOWNLOADING;
                        }
                        if(index < download.downloadsInfo.length) {
                            manager.setStatus(state, download.getCurrentProgress(), download.downloadsInfo[index].isUnzipping(), download.downloadsInfo[index].isTestingIntegrity());
                            manager.setError(download.downloadsInfo[index].getCurrentError());
                        }else{
                            manager.setStatus(state, download.getCurrentProgress(), false, false);
                        }
                    }
                }
            }
        }
    }

    public boolean checkSettingsChanged(){
        if (radioGroup == null || asrRadioGroup == null) {
            return false;
        }
        return radioGroup.getCheckedRadioButtonId() != baselineTranslationRadioId
                || asrRadioGroup.getCheckedRadioButtonId() != baselineAsrRadioId
                || switchMozillaForVoiceModes.isChecked() != baselineMozillaForVoice
                || switchWhisperReducedRam.isChecked() != baselineWhisperReducedRam
                || switchTatoeba.isChecked() != baselineTatoeba
                || switchVadSegmentation.isChecked() != baselineVadSegmentation
                || switchTranslationDict.isChecked() != baselineTranslationDict;
    }

    public void applySettings() {
        final View editDialogLayout = activity.getLayoutInflater().inflate(R.layout.dialog_loading, null);

        final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity, R.style.MyThemeOverlay_MaterialComponents_MaterialAlertDialog);
        builder.setCancelable(true);

        AlertDialog dialog = builder.create();
        dialog.setView(editDialogLayout, 0, Tools.convertDpToPixels(activity, 16), 0, 0);
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {

            }
        });
        editDialogLayout.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                if (editDialogLayout.getViewTreeObserver().isAlive()) {
                    editDialogLayout.getViewTreeObserver().removeOnPreDrawListener(this);
                }
                editDialogLayout.post(() -> beginApply(dialog, editDialogLayout));
                return true;
            }
        });
        dialog.show();

        TextView textView = editDialogLayout.findViewById(R.id.textView);
        MaterialButton okButton = editDialogLayout.findViewById(R.id.ok_button);

        okButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });

        showApplyStage(editDialogLayout, 0);
    }

    private void showApplyStage(View layout, int stage) {
        TextView textView = layout.findViewById(R.id.textView);
        android.widget.ProgressBar stageBar = layout.findViewById(R.id.progressStage);
        int text;
        switch (stage) {
            case 0:
                text = R.string.apply_stage_check;
                break;
            case 1:
                text = R.string.apply_stage_translation;
                break;
            case 2:
                text = R.string.apply_stage_speech;
                break;
            default:
                text = R.string.apply_stage_languages;
                break;
        }
        textView.setText(text);
        if (stageBar != null) {
            stageBar.setMax(4);
            stageBar.setProgress(stage + 1);
            stageBar.setVisibility(View.VISIBLE);
        }
    }

    private void beginApply(AlertDialog dialog, View layout) {
        if (!isAdded() || radioGroup == null || asrRadioGroup == null) {
            return;
        }
        final boolean firstLaunch = activity instanceof AccessActivity;
        final int translationRadio = radioGroup.getCheckedRadioButtonId();
        final int asrRadio = asrRadioGroup.getCheckedRadioButtonId();
        final boolean mozillaVoice = switchMozillaForVoiceModes.isChecked();
        final boolean tatoeba = switchTatoeba.isChecked();
        final boolean vad = switchVadSegmentation.isChecked();
        final boolean dictionaries = switchTranslationDict.isChecked();
        final DownloadGroupInfo hyDownload = downloadOf("hyManager");
        final DownloadGroupInfo madladDownload = downloadOf("madladManager");
        final DownloadGroupInfo gemmaDownload = downloadOf("gemmaManager");
        final DownloadGroupInfo qwen35Download = downloadOf("qwen35Manager");
        final DownloadGroupInfo tatoebaDownload = downloadOf("tatoebaManager");
        final DownloadGroupInfo vadDownload = downloadOf("vadManager");
        new Thread(() -> {
            ArrayList<DownloadGroupInfo> saved = downloadManager.getSavedDownloadStatus();
            ApplyPlan plan = validateApply(saved, firstLaunch, translationRadio, asrRadio, mozillaVoice, tatoeba, vad, hyDownload, madladDownload, gemmaDownload, qwen35Download, tatoebaDownload, vadDownload);
            activity.runOnUiThread(() -> {
                if (!isAdded()) {
                    return;
                }
                savedDownloadStatus = saved;
                if (plan.failure != null) {
                    notifyApplyError(plan.failure.stage, plan.failure.error, plan.failure.value, layout);
                    return;
                }
                showApplyStage(layout, 1);
                global.setSimultaneousVadSegmentation(vad);
                global.setTranslationStatus(plan.translationModel, mozillaVoice, tatoeba, dictionaries, new Translator.GeneralListener() {
                    @Override
                    public void onSuccess() {
                        showApplyStage(layout, 2);
                        global.setAsrModel(plan.asrModel, plan.asrPresent, new Translator.GeneralListener() {
                            @Override
                            public void onSuccess() {
                                applyWhisperAfterAsr();
                            }

                            @Override
                            public void onFailure(int[] reasons, long value) {
                                int error = reasons.length > 0 ? reasons[0] : ErrorCodes.ERROR_LOADING_MODEL;
                                notifyApplyError(ApplySettingsStage.ASR_MODEL, error, value, layout);
                            }
                        });
                    }

                    private void applyWhisperAfterAsr() {
                        applyWhisperRamReduction(new Translator.GeneralListener() {
                            @Override
                            public void onSuccess() {
                                showApplyStage(layout, 3);
                                // Language lists are rebuilt off the main thread. Mozilla downloads are not tracked one by one, so this runs on every apply.
                                global.updateLanguagesAndResources(new Translator.GeneralListener() {
                                    @Override
                                    public void onSuccess() {
                                        dialog.cancel();
                                        if (activity instanceof SettingsActivity) {
                                            ((SettingsActivity) activity).leaveModelManager();
                                        } else if (activity instanceof AccessActivity) {
                                            if (needsDownloadBeforeEnter()) {
                                                ((AccessActivity) activity).startFragment(AccessActivity.DOWNLOAD_FRAGMENT, null);
                                            } else {
                                                startRTranslator();
                                            }
                                        }
                                    }
                                });
                            }

                            @Override
                            public void onFailure(int[] reasons, long value) {
                                int error = reasons.length > 0 ? reasons[0] : ErrorCodes.ERROR;
                                notifyApplyError(ApplySettingsStage.WHISPER_RAM_REDUCTION, error, value, layout);
                            }
                        });
                    }

                    @Override
                    public void onFailure(int[] reasons, long value) {
                        int error = reasons.length > 0 ? reasons[0] : ErrorCodes.ERROR_LOADING_MODEL;
                        notifyApplyError(ApplySettingsStage.TRANSLATION_MODEL, error, value, layout);
                    }
                });
            });
        }, "applySettings").start();
    }

    private DownloadGroupInfo downloadOf(String managerKey) {
        ResourceManager manager = resourceManagers.get(managerKey);
        return manager == null ? null : manager.getDownloadInfo();
    }

    private void notifyApplyError(ApplySettingsStage stage, int error, long value, View dialogLayout){
        TextView textView = dialogLayout.findViewById(R.id.textView);
        MaterialButton okButton = dialogLayout.findViewById(R.id.ok_button);
        android.widget.ProgressBar progressBar = dialogLayout.findViewById(R.id.progressBar);

        progressBar.setVisibility(View.INVISIBLE);
        android.widget.ProgressBar stageBar = dialogLayout.findViewById(R.id.progressStage);
        if (stageBar != null) {
            stageBar.setVisibility(View.INVISIBLE);
        }
        okButton.setVisibility(View.VISIBLE);

        switch (error) {
            case ErrorCodes.ERROR_LOADING_MODEL: {
                //todo: manage better the error here (with an eventual fix, see loading activity management of this error for reference)
                textView.setText(getString(R.string.error_models_loading_short));
                break;
            }
            case ErrorCodes.NO_DOWNLOADED_RESOURCE: {
                switch (stage) {
                    case TRANSLATION_MODEL:
                        switch ((int) value) {
                            case Translator.MOZILLA:
                                textView.setText(getString(R.string.error_missing_mozilla));
                                break;
                            case Translator.HY_MT:
                                textView.setText(getString(R.string.error_missing_hy));
                                break;
                            case Translator.MADLAD_CACHE:
                                textView.setText(getString(R.string.error_missing_madlad));
                                break;
                            case Translator.TRANSLATEGEMMA:
                                textView.setText(getString(R.string.error_missing_gemma));
                                break;
                            case Translator.QWEN35:
                                textView.setText(getString(R.string.error_missing_qwen35));
                                break;
                        }
                        break;
                    case MOZILLA_FOR_VOICE:
                        textView.setText(getString(R.string.error_missing_mozilla_voice));
                    case TATOEBA:
                        textView.setText(getString(R.string.error_missing_tatoeba));
                        break;
                    case ASR_MODEL:
                        textView.setText(getString(R.string.error_missing_asr));
                        break;
                    case VAD_SEGMENTATION:
                        textView.setText(getString(R.string.error_missing_vad));
                        break;
                    default:
                        textView.setText(getString(R.string.error_missing_general));
                        break;
                }
                break;
            }
            default: {
                if (stage == ApplySettingsStage.ASR_MODEL && error == ErrorCodes.ERROR) {
                    textView.setText(getString(R.string.error_asr_ram));
                    break;
                }
                // general error dialog
                Log.e("error", "Unknown error apply: "+error);
                textView.setText(getString(R.string.error_apply_unknown));
                break;
            }
        }
    }

    private ApplyPlan validateApply(ArrayList<DownloadGroupInfo> saved, boolean firstLaunch, int translationRadio, int asrRadio, boolean mozillaVoice, boolean tatoeba, boolean vad, DownloadGroupInfo hyDownload, DownloadGroupInfo madladDownload, DownloadGroupInfo gemmaDownload, DownloadGroupInfo qwen35Download, DownloadGroupInfo tatoebaDownload, DownloadGroupInfo vadDownload) {
        ApplyPlan plan = new ApplyPlan();
        plan.translationModel = Translator.MOZILLA;
        if (translationRadio == R.id.radioMozilla) {
            plan.translationModel = Translator.MOZILLA;
            if (!checkMozillaModelsPresence(saved)) {
                plan.failure = new ApplyFailure(ApplySettingsStage.TRANSLATION_MODEL, ErrorCodes.NO_DOWNLOADED_RESOURCE, Translator.MOZILLA);
                return plan;
            }
        } else if (translationRadio == R.id.radioHY) {
            plan.translationModel = Translator.HY_MT;
            if (!DownloadManager.isDownloadCompleted(saved, hyDownload) && !firstLaunch) {
                plan.failure = new ApplyFailure(ApplySettingsStage.TRANSLATION_MODEL, ErrorCodes.NO_DOWNLOADED_RESOURCE, Translator.HY_MT);
                return plan;
            }
        } else if (translationRadio == R.id.radioMadlad) {
            plan.translationModel = Translator.MADLAD_CACHE;
            if (!DownloadManager.isDownloadCompleted(saved, madladDownload) && !firstLaunch) {
                plan.failure = new ApplyFailure(ApplySettingsStage.TRANSLATION_MODEL, ErrorCodes.NO_DOWNLOADED_RESOURCE, Translator.MADLAD_CACHE);
                return plan;
            }
        } else if (translationRadio == R.id.radioGemma) {
            plan.translationModel = Translator.TRANSLATEGEMMA;
            if (!DownloadManager.isDownloadCompleted(saved, gemmaDownload) && !firstLaunch) {
                plan.failure = new ApplyFailure(ApplySettingsStage.TRANSLATION_MODEL, ErrorCodes.NO_DOWNLOADED_RESOURCE, Translator.TRANSLATEGEMMA);
                return plan;
            }
        } else if (translationRadio == R.id.radioQwen35) {
            plan.translationModel = Translator.QWEN35;
            if (!DownloadManager.isDownloadCompleted(saved, qwen35Download) && !firstLaunch) {
                plan.failure = new ApplyFailure(ApplySettingsStage.TRANSLATION_MODEL, ErrorCodes.NO_DOWNLOADED_RESOURCE, Translator.QWEN35);
                return plan;
            }
        }
        if (translationRadio != R.id.radioMozilla && mozillaVoice && !checkMozillaModelsPresence(saved)) {
            plan.failure = new ApplyFailure(ApplySettingsStage.MOZILLA_FOR_VOICE, ErrorCodes.NO_DOWNLOADED_RESOURCE, 0);
            return plan;
        }
        if (tatoeba && !DownloadManager.isDownloadCompleted(saved, tatoebaDownload)) {
            plan.failure = new ApplyFailure(ApplySettingsStage.TATOEBA, ErrorCodes.NO_DOWNLOADED_RESOURCE, Translator.HY_MT);
            return plan;
        }
        if (vad && !DownloadManager.isDownloadCompleted(saved, vadDownload) && !firstLaunch) {
            plan.failure = new ApplyFailure(ApplySettingsStage.VAD_SEGMENTATION, ErrorCodes.NO_DOWNLOADED_RESOURCE, 0);
            return plan;
        }
        plan.asrModel = asrModelFor(asrRadio);
        if (plan.asrModel == Global.ASR_QWEN3_17 && global.getTotalRamSize() < 7000) {
            plan.failure = new ApplyFailure(ApplySettingsStage.ASR_MODEL, ErrorCodes.ERROR, plan.asrModel);
            return plan;
        }
        if (plan.asrModel == Global.ASR_QWEN3_06 && global.getTotalRamSize() < 4500) {
            plan.failure = new ApplyFailure(ApplySettingsStage.ASR_MODEL, ErrorCodes.ERROR, plan.asrModel);
            return plan;
        }
        if (plan.asrModel == Global.ASR_WHISPER_TURBO && global.getTotalRamSize() < 6000) {
            plan.failure = new ApplyFailure(ApplySettingsStage.ASR_MODEL, ErrorCodes.ERROR, plan.asrModel);
            return plan;
        }
        DownloadGroupInfo asrDownload = global.getAsrDownloadInfo(plan.asrModel);
        plan.asrPresent = global.groupFilesPresent(asrDownload) || DownloadManager.isDownloadCompleted(saved, asrDownload);
        if (!plan.asrPresent && !firstLaunch) {
            plan.failure = new ApplyFailure(ApplySettingsStage.ASR_MODEL, ErrorCodes.NO_DOWNLOADED_RESOURCE, plan.asrModel);
        }
        return plan;
    }

    private static final class ApplyFailure {
        final ApplySettingsStage stage;
        final int error;
        final long value;

        ApplyFailure(ApplySettingsStage stage, int error, long value) {
            this.stage = stage;
            this.error = error;
            this.value = value;
        }
    }

    private static final class ApplyPlan {
        int translationModel;
        int asrModel;
        boolean asrPresent;
        ApplyFailure failure;
    }

    private int asrModelFor(int checked) {
        if (checked == R.id.radioQwen17) {
            return Global.ASR_QWEN3_17;
        }
        if (checked == R.id.radioQwen06) {
            return Global.ASR_QWEN3_06;
        }
        if (checked == R.id.radioWhisperTurbo) {
            return Global.ASR_WHISPER_TURBO;
        }
        return Global.ASR_WHISPER;
    }

    private void applyWhisperRamReduction(Translator.GeneralListener listener){
        global.setWhisperReducedRam(switchWhisperReducedRam.isChecked(), listener);
    }


    private void updateRamUsageSpeechRecognition() {
        int checked = asrRadioGroup == null ? R.id.radioWhisper : asrRadioGroup.getCheckedRadioButtonId();
        if (checked == R.id.radioQwen17) {
            setRamUsageSpeechRecognition(QWEN17_RAM_CONSUMPTION_MB);
        } else if (checked == R.id.radioQwen06) {
            setRamUsageSpeechRecognition(QWEN06_RAM_CONSUMPTION_MB);
        } else if (checked == R.id.radioWhisperTurbo) {
            setRamUsageSpeechRecognition(WHISPER_TURBO_RAM_CONSUMPTION_MB);
        } else if (switchWhisperReducedRam.isChecked()) {
            setRamUsageSpeechRecognition(WHISPER_RAM_CONSUMPTION_REDUCED_MB);
        } else {
            setRamUsageSpeechRecognition(WHISPER_RAM_CONSUMPTION_MB);
        }
    }

    private void updateRamUsageTranslation(){
        int checkRadioId = radioGroup.getCheckedRadioButtonId();
        if(checkRadioId == R.id.radioMozilla) {
            setRamUsageTranslation(MOZILLA_RAM_CONSUMPTION_MB);
        } else if(checkRadioId == R.id.radioHY) {
            int ramUsed = HY_RAM_CONSUMPTION_MB;
            if (switchMozillaForVoiceModes.isChecked()) {
                ramUsed += MOZILLA_RAM_CONSUMPTION_MB;
            }
            setRamUsageTranslation(ramUsed);
        } else if(checkRadioId == R.id.radioMadlad) {
            int ramUsed = MADLAD_RAM_CONSUMPTION_MB;
            if (switchMozillaForVoiceModes.isChecked()) {
                ramUsed += MOZILLA_RAM_CONSUMPTION_MB;
            }
            setRamUsageTranslation(ramUsed);
        } else if(checkRadioId == R.id.radioGemma) {
            int ramUsed = GEMMA_RAM_CONSUMPTION_MB;
            if (switchMozillaForVoiceModes.isChecked()) {
                ramUsed += MOZILLA_RAM_CONSUMPTION_MB;
            }
            setRamUsageTranslation(ramUsed);
        } else if(checkRadioId == R.id.radioQwen35) {
            int ramUsed = QWEN35_RAM_CONSUMPTION_MB;
            if (switchMozillaForVoiceModes.isChecked()) {
                ramUsed += MOZILLA_RAM_CONSUMPTION_MB;
            }
            setRamUsageTranslation(ramUsed);
        }
    }

    private void updateRamUsageTranslationEnhancements(){
        int ramConsumptionTranslationEnhancements = 0;
        if(switchTatoeba.isChecked()){
            ramConsumptionTranslationEnhancements += TATOEBA_RAM_CONSUMPTION_MB;
        }
        if (switchVadSegmentation.isChecked()) {
            ramConsumptionTranslationEnhancements += VAD_RAM_CONSUMPTION_MB;
        }
        if(switchTranslationDict.isChecked()){
            ramConsumptionTranslationEnhancements += DICT_RAM_CONSUMPTION_MB;
        }
        setRamUsageTranslationEnhancements(ramConsumptionTranslationEnhancements);
    }

    private void setRamUsageSystem(long ramUsedMb){
        long totalRam = global.getTotalRamSize();
        long percentageUsed = (ramUsedMb * 100L)/totalRam;
        if(percentageUsed <= 0 && ramUsedMb > 0) percentageUsed = 1;
        barRam.setSegmentProgress(0, percentageUsed);
    }

    private void setRamUsageSpeechRecognition(long ramUsedMb){
        long totalRam = global.getTotalRamSize();
        long percentageUsed = (ramUsedMb * 100L)/totalRam;
        if(percentageUsed <= 0 && ramUsedMb > 0) percentageUsed = 1;
        barRam.setSegmentProgress(1, percentageUsed);
    }

    private void setRamUsageTranslation(long ramUsedMb){
        long totalRam = global.getTotalRamSize();
        long percentageUsed = (ramUsedMb * 100L)/totalRam;
        if(percentageUsed <= 0 && ramUsedMb > 0) percentageUsed = 1;
        barRam.setSegmentProgress(2, percentageUsed);
    }

    private void setRamUsageTranslationEnhancements(long ramUsedMb){
        long totalRam = global.getTotalRamSize();
        long percentageUsed = (ramUsedMb * 100L)/totalRam;
        if(percentageUsed <= 0 && ramUsedMb > 0) percentageUsed = 1;
        barRam.setSegmentProgress(3, percentageUsed);
    }

    private void updateTextRamUsage() {
        int totalRamGB = Math.round(((float) global.getTotalRamSize()) / 1000);
        float usedRAMPercentage = 0;
        for(SegmentProgressBar.Segment segment : barRam.getSegments()){
            usedRAMPercentage += segment.progress;
        }
        float usedRamGB = (usedRAMPercentage * totalRamGB) / 100;
        DecimalFormat df = new DecimalFormat("0.#");
        textRamUsage.setText(df.format(usedRamGB));
        textRamUsage2.setText("/" + totalRamGB + " GB");
        if(barRam.getRedLineValue() >= 0 && usedRAMPercentage > barRam.getRedLineValue()){
            textRamUsage.setTextColor(getResources().getColor(R.color.red));
        }else if(barRam.getOrangeLineValue() >= 0 && usedRAMPercentage > barRam.getOrangeLineValue()){
            textRamUsage.setTextColor(getResources().getColor(R.color.orange));
        }else{
            textRamUsage.setTextColor(textRamUsage2.getTextColors().getDefaultColor());
        }
    }

    private boolean isGroupDownloaded(DownloadGroupInfo group) {
        if (savedDownloadStatus != null) {
            return DownloadManager.isDownloadCompleted(savedDownloadStatus, group);
        }
        return downloadManager.checkDownloadCompleted(group);
    }

    private boolean needsDownloadBeforeEnter() {
        DownloadGroupInfo asrDownload = global.getAsrDownloadInfo(global.getAsrModel());
        if (!global.groupFilesPresent(asrDownload) && !isGroupDownloaded(asrDownload)) {
            return true;
        }
        int mode = global.getTranslationMode();
        if (mode == Translator.HY_MT && !isGroupDownloaded(global.getHyMtDownloadInfo())) {
            return true;
        }
        if (mode == Translator.MADLAD_CACHE && !isGroupDownloaded(global.getMadladDownloadInfo())) {
            return true;
        }
        if (mode == Translator.TRANSLATEGEMMA && !isGroupDownloaded(global.getTranslateGemmaDownloadInfo())) {
            return true;
        }
        if (mode == Translator.QWEN35 && !isGroupDownloaded(global.getQwen35DownloadInfo())) {
            return true;
        }
        if (switchTatoeba.isChecked() && !isGroupDownloaded(global.getTatoebaDownloadInfo())) {
            return true;
        }
        if (switchVadSegmentation.isChecked() && !isGroupDownloaded(global.getSileroVadDownloadInfo())) {
            return true;
        }
        if (switchTranslationDict.isChecked() && !isGroupDownloaded(global.getDictionaryDownloadInfo())) {
            return true;
        }
        for (DownloadGroupInfo voice : LiveDraftVoices.wantedDownloads(activity)) {
            if (!LiveDraftVoices.isReadyGroup(activity, voice) && !isGroupDownloaded(voice)) {
                return true;
            }
        }
        return false;
    }

    private void showLiveDraftLoading() {
        if (liveDraftList == null || activity == null) {
            return;
        }
        liveDraftList.removeAllViews();
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        android.widget.ProgressBar bar = new android.widget.ProgressBar(activity);
        int size = Tools.convertDpToPixels(activity, 24);
        row.addView(bar, new LinearLayout.LayoutParams(size, size));
        TextView label = new TextView(activity);
        label.setText(R.string.loading_model_files);
        int pad = Tools.convertDpToPixels(activity, 12);
        label.setPadding(pad, pad, 0, pad);
        row.addView(label);
        int margin = Tools.convertDpToPixels(activity, 16);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMarginStart(margin);
        params.topMargin = margin;
        params.bottomMargin = margin;
        liveDraftList.addView(row, params);
    }

    private void buildLiveDraftRows(HashMap<String, Boolean> ready) {
        if (liveDraftList == null) {
            return;
        }
        liveDraftList.removeAllViews();
        Set<String> wanted = LiveDraftVoices.wanted(activity);
        Locale locale = Locale.getDefault();
        for (LiveDraftVoices.Voice voice : LiveDraftVoices.all()) {
            DownloadGroupInfo download = voice.downloadInfo(activity);
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            ResourceManagerView view = new ResourceManagerView(activity);
            view.setTitle(voice.displayName(locale));
            view.setDescription(getString(R.string.description_live_draft_voice, voice.sizeMb()));
            view.setModelSize(voice.sizeMb());
            LinearLayout.LayoutParams viewParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            viewParams.setMarginStart(Tools.convertDpToPixels(activity, 8));
            row.addView(view, viewParams);
            CheckBox box = new CheckBox(activity);
            box.setContentDescription(voice.displayName(locale));
            LinearLayout.LayoutParams boxParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            boxParams.topMargin = Tools.convertDpToPixels(activity, 16);
            boxParams.setMarginEnd(Tools.convertDpToPixels(activity, 16));
            row.addView(box, boxParams);
            liveDraftList.addView(row, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            ResourceManager manager = new ResourceManager(activity, download, view, downloadManager);
            if (ready != null && Boolean.TRUE.equals(ready.get(voice.code))) {
                manager.setState(ResourceManagerView.State.DOWNLOADED, false);
            }
            resourceManagers.put("liveDraft-" + voice.code, manager);
            box.setChecked(wanted.contains(voice.code));
            box.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    LiveDraftVoices.setWanted(activity, voice.code, isChecked);
                    if (isChecked && !LiveDraftVoices.isReady(activity, voice.code)) {
                        manager.setState(ResourceManagerView.State.DOWNLOADING);
                        downloadManager.startDownload(download);
                    } else if (!isChecked && !LiveDraftVoices.isReady(activity, voice.code)) {
                        downloadManager.cancelDownload(download);
                        manager.setState(ResourceManagerView.State.EMPTY);
                    }
                }
            });
        }
    }

    private void startRTranslator(){
        if (activity != null) {
            //modification of the firstStart
            global.setFirstStart(false);
            //start activity
            Intent intent = new Intent(activity, LoadingActivity.class);
            intent.putExtra("activity", "download");
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
            activity.finish();
        }
    }
}
