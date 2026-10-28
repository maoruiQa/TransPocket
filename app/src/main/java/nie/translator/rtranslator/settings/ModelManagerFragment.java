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
import android.widget.CompoundButton;
import android.widget.ImageView;
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

import nie.translator.rtranslator.Global;
import nie.translator.rtranslator.LoadingActivity;
import nie.translator.rtranslator.R;
import nie.translator.rtranslator.access.AccessActivity;
import nie.translator.rtranslator.downloader.DownloadGroupInfo;
import nie.translator.rtranslator.downloader.DownloadInfo;
import nie.translator.rtranslator.downloader.DownloadManager;
import nie.translator.rtranslator.tools.DownloaderTools;
import nie.translator.rtranslator.tools.ErrorCodes;
import nie.translator.rtranslator.tools.Tools;
import nie.translator.rtranslator.tools.gui.ResourceManagerView;
import nie.translator.rtranslator.tools.gui.SegmentProgressBar;
import nie.translator.rtranslator.voice_translation.neural_networks.translation.GgufModelFiles;
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
    // GGUF models: the weights file plus this much for the context, compute buffers and runtime.
    // Measured once per model on its first quantization (Qwen3.5 Q4_K_M 1400 MB, TranslateGemma Q4_K_M 3600 MB).
    private static int QWEN35_RAM_OVERHEAD_MB = 870;
    private static int INDEX_RAM_OVERHEAD_MB = 650;
    private static int GEMMA_RAM_OVERHEAD_MB = 1100;
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
    private RadioButton radioGemma;
    private RadioButton radioQwen35;
    private RadioButton radioIndex;
    private RadioButton radioOther;
    private RadioGroupPlus asrRadioGroup;
    private RadioButton radioWhisper;
    private RadioButton radioWhisperTurbo;
    private RadioButton radioQwen06;
    private RadioButton radioQwen17;
    private ResourceManagerView gemmaManagerView;
    private ResourceManagerView qwen35ManagerView;
    private ResourceManagerView indexManagerView;
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
    private View rowOther;
    private ImageView arrowOther;
    private TextView descriptionOther;
    /** Model behind the "Other" radio: Mozilla, HY-MT or Madlad. */
    private int otherModel;
    private int baselineOtherModel;
    private boolean guiStateRestored = false;
    private int baselineTranslationRadioId = View.NO_ID;
    private int baselineAsrRadioId = View.NO_ID;
    private boolean baselineMozillaForVoice;
    private boolean baselineWhisperReducedRam;
    private boolean baselineTatoeba;
    private boolean baselineVadSegmentation;
    private boolean baselineTranslationDict;
    private int baselineWhisperQuant;
    private int baselineTurboQuant;
    private int baselineQwen06Quant;
    private int baselineQwen17Quant;
    private int baselineVadModel;
    private String baselineQwen35Quant;
    private String baselineIndexQuant;
    private String baselineGemmaQuant;
    private ArrayList<DownloadGroupInfo> savedDownloadStatus;
    private int managerLoadGeneration = 0;

    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_models_manager, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        radioGroup = view.findViewById(R.id.model_radios);
        radioGemma = view.findViewById(R.id.radioGemma);
        radioQwen35 = view.findViewById(R.id.radioQwen35);
        radioIndex = view.findViewById(R.id.radioIndex);
        radioOther = view.findViewById(R.id.radioOther);
        asrRadioGroup = view.findViewById(R.id.asr_radios);
        radioWhisper = view.findViewById(R.id.radioWhisper);
        radioWhisperTurbo = view.findViewById(R.id.radioWhisperTurbo);
        radioQwen06 = view.findViewById(R.id.radioQwen06);
        radioQwen17 = view.findViewById(R.id.radioQwen17);
        whisperManagerView = view.findViewById(R.id.modelWhisper);
        whisperTurboManagerView = view.findViewById(R.id.modelWhisperTurbo);
        qwen06ManagerView = view.findViewById(R.id.modelQwen06);
        qwen17ManagerView = view.findViewById(R.id.modelQwen17);
        gemmaManagerView = view.findViewById(R.id.modelGemma);
        qwen35ManagerView = view.findViewById(R.id.modelQwen35);
        indexManagerView = view.findViewById(R.id.modelIndex);
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
        rowOther = view.findViewById(R.id.rowOther);
        arrowOther = view.findViewById(R.id.arrowOther);
        descriptionOther = view.findViewById(R.id.descriptionOther);
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
        resourceManagers.put("gemmaManager", new ResourceManager(activity, global.getTranslateGemmaDownloadInfo(), gemmaManagerView, downloadManager));
        resourceManagers.put("qwen35Manager", new ResourceManager(activity, global.getQwen35DownloadInfo(), qwen35ManagerView, downloadManager));
        resourceManagers.put("indexManager", new ResourceManager(activity, global.getIndexTranslateDownloadInfo(), indexManagerView, downloadManager));
        resourceManagers.put("whisperManager", new ResourceManager(activity, global.getWhisperDownloadInfo(), whisperManagerView, downloadManager));
        resourceManagers.put("whisperTurboManager", new ResourceManager(activity, global.getWhisperTurboDownloadInfo(), whisperTurboManagerView, downloadManager));
        resourceManagers.put("qwen06Manager", new ResourceManager(activity, global.getQwen3Asr06DownloadInfo(), qwen06ManagerView, downloadManager));
        resourceManagers.put("qwen17Manager", new ResourceManager(activity, global.getQwen3Asr17DownloadInfo(), qwen17ManagerView, downloadManager));
        resourceManagers.put("tatoebaManager", new ResourceManager(activity, global.getTatoebaDownloadInfo(), tatoebaManagerView, downloadManager));
        resourceManagers.put("vadManager", new ResourceManager(activity, global.getVadDownloadInfo(), vadManagerView, downloadManager));

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
        View.OnClickListener openOtherModels = v -> {
            Bundle bundle = new Bundle();
            bundle.putInt(OtherModelsFragment.ARG_MODEL, otherModel);
            if (activity instanceof SettingsActivity) {
                ((SettingsActivity) activity).startFragment(SettingsActivity.OTHER_MODELS, bundle);
            } else if (activity instanceof AccessActivity) {
                ((AccessActivity) activity).startFragment(AccessActivity.OTHER_MODELS, bundle);
            }
        };
        rowOther.setOnClickListener(openOtherModels);
        arrowOther.setOnClickListener(openOtherModels);
        asrRadioGroup.setOnCheckedChangeListener((group, checkedId) -> {
            switchWhisperReducedRam.setEnabled(checkedId == R.id.radioWhisper);
            updateRamUsageSpeechRecognition();
        });
        radioGroup.setOnCheckedChangeListener((group, checkedId) -> {
            updateMozillaForVoiceSwitch();
            updateOtherDescription();
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
            host.runOnUiThread(() -> {
                if (generation != managerLoadGeneration || !isAdded()) {
                    return;
                }
                savedDownloadStatus = saved;
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
        otherModel = Global.isOtherTranslationModel(mode) ? (mode == Translator.MADLAD ? Translator.MADLAD_CACHE : mode) : global.getOtherTranslationModel();
        baselineOtherModel = otherModel;
        switch (mode) {
            case Translator.MOZILLA:
            case Translator.MADLAD:
            case Translator.MADLAD_CACHE:
            case Translator.HY_MT:
                radioGroup.check(R.id.radioOther);
                break;
            case Translator.TRANSLATEGEMMA:
                radioGroup.check(R.id.radioGemma);
                break;
            case Translator.QWEN35:
                radioGroup.check(R.id.radioQwen35);
                break;
            case Translator.INDEX_TRANSLATE:
                radioGroup.check(R.id.radioIndex);
                break;
        }
        // switches initialization
        switchMozillaForVoiceModes.setChecked(sharedPreferences.contains("useMozillaForVoiceTranslation") ? global.isUseMozillaForVoiceTranslation() : recommendation.useMozillaForVoice);
        switchWhisperReducedRam.setChecked(sharedPreferences.contains("whisperReducedRam") ? global.isWhisperReducedRam() : recommendation.whisperReducedRam);
        switchTatoeba.setChecked(sharedPreferences.contains("useTatoeba") ? global.isUseTatoeba() : false);
        switchVadSegmentation.setChecked(sharedPreferences.contains("simultaneousVadSegmentation") ? global.isSimultaneousVadSegmentation() : true);
        switchTranslationDict.setChecked(sharedPreferences.contains("useTranslationDictionaries") ? global.isUseTranslationDictionaries() : false);
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
        updateMozillaForVoiceSwitch();

        // ram consumption bar redline and orangeLine initialization
        barRam.setRedLine(100F - (float) (global.getRamThreshold() * 100) / global.getTotalRamSize());
        barRam.setOrangeLine(80F);

        // ram consumption bar initialization
        setRamUsageSystem(global.getTotalRamSize() - global.getMaxAllocatableRAM());
        updateRamUsageSpeechRecognition();
        updateRamUsageTranslation();
        updateRamUsageTranslationEnhancements();
        updateTextRamUsage();
        bindTranslationQuant(Translator.QWEN35, qwen35ManagerView, "qwen35Manager");
        bindTranslationQuant(Translator.INDEX_TRANSLATE, indexManagerView, "indexManager");
        bindTranslationQuant(Translator.TRANSLATEGEMMA, gemmaManagerView, "gemmaManager");
        bindAsrQuant(Global.ASR_WHISPER, whisperManagerView, "whisperManager");
        bindAsrQuant(Global.ASR_WHISPER_TURBO, whisperTurboManagerView, "whisperTurboManager");
        bindAsrQuant(Global.ASR_QWEN3_06, qwen06ManagerView, "qwen06Manager");
        bindAsrQuant(Global.ASR_QWEN3_17, qwen17ManagerView, "qwen17Manager");
        bindVadModel();
        captureSettingsBaseline();
        // A choice made on the "Other" page counts as an edit, so it is applied after the baseline.
        if (global.hasPendingOtherTranslationModel()) {
            otherModel = global.getOtherTranslationModel();
            global.clearPendingOtherTranslationModel();
            radioGroup.check(R.id.radioOther);
        }
        updateMozillaForVoiceSwitch();
        updateOtherDescription();
        updateRamUsageTranslation();
        updateTextRamUsage();
    }

    /** The translation model the checked radio stands for. */
    private int checkedTranslationModel() {
        int checked = radioGroup.getCheckedRadioButtonId();
        if (checked == R.id.radioQwen35) {
            return Translator.QWEN35;
        }
        if (checked == R.id.radioIndex) {
            return Translator.INDEX_TRANSLATE;
        }
        if (checked == R.id.radioGemma) {
            return Translator.TRANSLATEGEMMA;
        }
        return otherModel;
    }

    private void updateMozillaForVoiceSwitch() {
        if (checkedTranslationModel() == Translator.MOZILLA) {  // Mozilla for voice modes means nothing when Mozilla is the model
            switchMozillaForVoiceModes.setChecked(false);
            switchMozillaForVoiceModes.setEnabled(false);
        } else {
            switchMozillaForVoiceModes.setEnabled(true);
        }
    }

    private void updateOtherDescription() {
        if (radioGroup.getCheckedRadioButtonId() == R.id.radioOther) {
            descriptionOther.setText(getString(R.string.other_model_selected, getString(OtherModelsFragment.titleOf(otherModel))));
        } else {
            descriptionOther.setText(R.string.description_other_models);
        }
    }

    private void bindTranslationQuant(int mode, ResourceManagerView view, String managerKey) {
        final String[] quants = GgufModelFiles.quants(mode);
        String current = global.getTranslationQuant(mode);
        int selected = 0;
        for (int i = 0; i < quants.length; i++) {
            if (quants[i].equals(current)) {
                selected = i;
            }
        }
        view.setModelSize(global.ggufDownloadSizeMb(mode));
        view.setQuantChoices(quants, selected, index -> {
            String quant = quants[index];
            if (quant.equals(global.getTranslationQuant(mode))) {
                return;
            }
            global.setTranslationQuant(mode, quant);
            ResourceManager manager = resourceManagers.get(managerKey);
            if (manager != null) {
                manager.replaceDownload(global.getGgufDownloadInfo(mode));
                refreshManagerState(manager);
            }
            view.setModelSize(global.ggufDownloadSizeMb(mode));
            updateRamUsageTranslation();
            updateTextRamUsage();
        });
    }

    private void bindAsrQuant(int model, ResourceManagerView view, String managerKey) {
        int[] choices = Global.asrQuantChoices(model);
        String[] labels = new String[choices.length];
        int selected = 0;
        int current = global.getAsrQuant(model);
        for (int i = 0; i < choices.length; i++) {
            labels[i] = Global.asrQuantLabel(choices[i]);
            if (choices[i] == current) {
                selected = i;
            }
        }
        view.setModelSize(global.asrDownloadSizeMb(model));
        view.setQuantChoices(labels, selected, index -> {
            int quant = choices[index];
            if (quant == global.getAsrQuant(model)) {
                return;
            }
            global.setAsrQuant(model, quant);
            ResourceManager manager = resourceManagers.get(managerKey);
            if (manager != null) {
                manager.replaceDownload(global.getAsrDownloadInfo(model));
                refreshManagerState(manager);
            }
            view.setModelSize(global.asrDownloadSizeMb(model));
            updateRamUsageSpeechRecognition();
            updateTextRamUsage();
        });
    }

    private void bindVadModel() {
        final String[] labels = getResources().getStringArray(R.array.vad_model_entries);
        int selected = global.getVadModel() == Global.VAD_FIRERED ? 0 : 1;
        vadManagerView.setModelSize(global.vadDownloadSizeMb());
        vadManagerView.setModelChoices(labels, selected, getString(R.string.title_vad_model), index -> {
            int model = index == 0 ? Global.VAD_FIRERED : Global.VAD_SILERO;
            if (model == global.getVadModel()) {
                return;
            }
            global.setVadModel(model);
            ResourceManager manager = resourceManagers.get("vadManager");
            if (manager != null) {
                manager.replaceDownload(global.getVadDownloadInfo());
                refreshManagerState(manager);
            }
            vadManagerView.setModelSize(global.vadDownloadSizeMb());
            updateRamUsageTranslationEnhancements();
            updateTextRamUsage();
        });
    }

    private void refreshManagerState(ResourceManager manager) {
        ArrayList<DownloadGroupInfo> status = downloadManager.getDownloadsStatus();
        boolean matched = false;
        if (status != null) {
            for (DownloadGroupInfo download : status) {
                if (!download.equals(manager.getDownloadInfo())) {
                    continue;
                }
                matched = true;
                showDownloadStatus(manager, download);
            }
        }
        if (!matched && global.groupFilesPresent(manager.getDownloadInfo())) {
            manager.setState(ResourceManagerView.State.DOWNLOADED, false);
        }
    }

    /** Shows a saved download's state on its row. Shared with the "Other" page. */
    static void showDownloadStatus(ResourceManager manager, DownloadGroupInfo download) {
        ResourceManagerView.State state = ResourceManagerView.State.EMPTY;
        int index = DownloaderTools.findFirstIncompletedDownload(download);
        //todo: improve detection methods of status
        if (download.isAllDownloadCompleted()) {
            state = ResourceManagerView.State.DOWNLOADED;
        } else if (download.getRunningDownloadIndex() == -1 && download.getCurrentProgress() <= 0) {
            state = ResourceManagerView.State.EMPTY;
        } else if ((download.getRunningDownloadIndex() == -1 && download.getCurrentProgress() > 0)
                || (download.getRunningDownloadIndex() != -1 && download.getRunningDownload().getCurrentError() != -1)) {  //in case of an error the status will be PAUSED
            state = ResourceManagerView.State.PAUSED;
        } else if (download.getRunningDownloadIndex() != -1) {
            state = ResourceManagerView.State.DOWNLOADING;
        }
        if (index < download.downloadsInfo.length) {
            manager.setStatus(state, download.getCurrentProgress(), download.downloadsInfo[index].isUnzipping(), download.downloadsInfo[index].isTestingIntegrity());
            manager.setError(download.downloadsInfo[index].getCurrentError());
        } else {
            manager.setStatus(state, download.getCurrentProgress(), false, false);
        }
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
        baselineWhisperQuant = global.getAsrQuant(Global.ASR_WHISPER);
        baselineTurboQuant = global.getAsrQuant(Global.ASR_WHISPER_TURBO);
        baselineQwen06Quant = global.getAsrQuant(Global.ASR_QWEN3_06);
        baselineQwen17Quant = global.getAsrQuant(Global.ASR_QWEN3_17);
        baselineVadModel = global.getVadModel();
        baselineQwen35Quant = global.getTranslationQuant(Translator.QWEN35);
        baselineIndexQuant = global.getTranslationQuant(Translator.INDEX_TRANSLATE);
        baselineGemmaQuant = global.getTranslationQuant(Translator.TRANSLATEGEMMA);
    }

    private void restoreGuiDownloadState(ArrayList<DownloadGroupInfo> downloadStatus){
        // we change the GUI based on current download status
        if(downloadStatus != null){
            guiStateRestored = true;
            for(DownloadGroupInfo download: downloadStatus){
                for(ResourceManager manager: resourceManagers.values()) {
                    if (download.equals(manager.getDownloadInfo())) {
                        showDownloadStatus(manager, download);
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
                || switchTranslationDict.isChecked() != baselineTranslationDict
                || global.getAsrQuant(Global.ASR_WHISPER) != baselineWhisperQuant
                || global.getAsrQuant(Global.ASR_WHISPER_TURBO) != baselineTurboQuant
                || global.getAsrQuant(Global.ASR_QWEN3_06) != baselineQwen06Quant
                || global.getAsrQuant(Global.ASR_QWEN3_17) != baselineQwen17Quant
                || global.getVadModel() != baselineVadModel
                || (radioGroup.getCheckedRadioButtonId() == R.id.radioOther && otherModel != baselineOtherModel)
                || !global.getTranslationQuant(Translator.QWEN35).equals(baselineQwen35Quant)
                || !global.getTranslationQuant(Translator.INDEX_TRANSLATE).equals(baselineIndexQuant)
                || !global.getTranslationQuant(Translator.TRANSLATEGEMMA).equals(baselineGemmaQuant);
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
        final int asrRadio = asrRadioGroup.getCheckedRadioButtonId();
        final boolean mozillaVoice = switchMozillaForVoiceModes.isChecked();
        final boolean tatoeba = switchTatoeba.isChecked();
        final boolean vad = switchVadSegmentation.isChecked();
        final boolean dictionaries = switchTranslationDict.isChecked();
        final int translationModel = checkedTranslationModel();
        final DownloadGroupInfo hyDownload = global.getHyMtDownloadInfo();
        final DownloadGroupInfo madladDownload = global.getMadladDownloadInfo();
        final DownloadGroupInfo gemmaDownload = downloadOf("gemmaManager");
        final DownloadGroupInfo qwen35Download = downloadOf("qwen35Manager");
        final DownloadGroupInfo indexDownload = downloadOf("indexManager");
        final DownloadGroupInfo tatoebaDownload = downloadOf("tatoebaManager");
        final DownloadGroupInfo vadDownload = downloadOf("vadManager");
        new Thread(() -> {
            ArrayList<DownloadGroupInfo> saved = downloadManager.getSavedDownloadStatus();
            ApplyPlan plan = validateApply(saved, firstLaunch, translationModel, asrRadio, mozillaVoice, tatoeba, vad, hyDownload, madladDownload, gemmaDownload, qwen35Download, indexDownload, tatoebaDownload, vadDownload);
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
                            case Translator.INDEX_TRANSLATE:
                                textView.setText(getString(R.string.error_missing_index));
                                break;
                        }
                        break;
                    case MOZILLA_FOR_VOICE:
                        textView.setText(getString(R.string.error_missing_mozilla_voice));
                        break;
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

    private ApplyPlan validateApply(ArrayList<DownloadGroupInfo> saved, boolean firstLaunch, int translationModel, int asrRadio, boolean mozillaVoice, boolean tatoeba, boolean vad, DownloadGroupInfo hyDownload, DownloadGroupInfo madladDownload, DownloadGroupInfo gemmaDownload, DownloadGroupInfo qwen35Download, DownloadGroupInfo indexDownload, DownloadGroupInfo tatoebaDownload, DownloadGroupInfo vadDownload) {
        ApplyPlan plan = new ApplyPlan();
        plan.translationModel = translationModel;
        boolean present;
        switch (translationModel) {
            case Translator.MOZILLA:
                present = checkMozillaModelsPresence(saved);
                break;
            case Translator.HY_MT:
                present = firstLaunch || DownloadManager.isDownloadCompleted(saved, hyDownload);
                break;
            case Translator.MADLAD_CACHE:
                present = firstLaunch || DownloadManager.isDownloadCompleted(saved, madladDownload);
                break;
            case Translator.TRANSLATEGEMMA:
                present = firstLaunch || DownloadManager.isDownloadCompleted(saved, gemmaDownload);
                break;
            case Translator.QWEN35:
                present = firstLaunch || DownloadManager.isDownloadCompleted(saved, qwen35Download);
                break;
            case Translator.INDEX_TRANSLATE:
                present = firstLaunch || DownloadManager.isDownloadCompleted(saved, indexDownload);
                break;
            default:
                present = false;
                break;
        }
        if (!present) {
            plan.failure = new ApplyFailure(ApplySettingsStage.TRANSLATION_MODEL, ErrorCodes.NO_DOWNLOADED_RESOURCE, translationModel);
            return plan;
        }
        if (translationModel != Translator.MOZILLA && mozillaVoice && !checkMozillaModelsPresence(saved)) {
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
        int asrQuant = global.getAsrQuant(plan.asrModel);
        int minRam = 0;
        if (plan.asrModel == Global.ASR_QWEN3_17) {
            minRam = asrQuant == Global.ASR_QUANT_FP32 ? 12000 : 7000;
        } else if (plan.asrModel == Global.ASR_QWEN3_06) {
            minRam = asrQuant == Global.ASR_QUANT_FP32 ? 8000 : 4500;
        } else if (plan.asrModel == Global.ASR_WHISPER_TURBO) {
            minRam = asrQuant == Global.ASR_QUANT_FP32 ? 8000 : 6000;
        }
        if (minRam > 0 && global.getTotalRamSize() < minRam) {
            plan.failure = new ApplyFailure(ApplySettingsStage.ASR_MODEL, ErrorCodes.ERROR, plan.asrModel);
            return plan;
        }
        DownloadGroupInfo asrDownload = global.getAsrDownloadInfo(plan.asrModel);
        plan.asrPresent = global.asrReady(plan.asrModel) || DownloadManager.isDownloadCompleted(saved, asrDownload);
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
            setRamUsageSpeechRecognition(scaledAsrRam(QWEN17_RAM_CONSUMPTION_MB, Global.ASR_QUANT_INT4, global.getAsrQuant(Global.ASR_QWEN3_17)));
        } else if (checked == R.id.radioQwen06) {
            setRamUsageSpeechRecognition(scaledAsrRam(QWEN06_RAM_CONSUMPTION_MB, Global.ASR_QUANT_INT4, global.getAsrQuant(Global.ASR_QWEN3_06)));
        } else if (checked == R.id.radioWhisperTurbo) {
            setRamUsageSpeechRecognition(scaledAsrRam(WHISPER_TURBO_RAM_CONSUMPTION_MB, Global.ASR_QUANT_INT8, global.getAsrQuant(Global.ASR_WHISPER_TURBO)));
        } else if (!global.groupFilesPresent(global.getWhisperDownloadInfo()) && switchWhisperReducedRam.isChecked()) {
            setRamUsageSpeechRecognition(WHISPER_RAM_CONSUMPTION_REDUCED_MB);
        } else if (!global.groupFilesPresent(global.getWhisperDownloadInfo())) {
            setRamUsageSpeechRecognition(WHISPER_RAM_CONSUMPTION_MB);
        } else {
            setRamUsageSpeechRecognition(scaledAsrRam(WHISPER_RAM_CONSUMPTION_MB, Global.ASR_QUANT_INT8, global.getAsrQuant(Global.ASR_WHISPER)));
        }
    }

    /** Measured RAM stays on its original quant. Other levels are a weight-size estimate for the bar. */
    private static int scaledAsrRam(int measuredMb, int measuredQuant, int quant) {
        return Math.max(1, Math.round(measuredMb * quantRamFactor(quant) / quantRamFactor(measuredQuant)));
    }

    private static float quantRamFactor(int quant) {
        switch (quant) {
            case Global.ASR_QUANT_INT4:
                return 0.75f;
            case Global.ASR_QUANT_FP16:
                return 1.15f;
            case Global.ASR_QUANT_FP32:
                return 1.9f;
            default:
                return 1f;
        }
    }

    private void updateRamUsageTranslation(){
        int model = checkedTranslationModel();
        int ramUsed;
        switch (model) {
            case Translator.MOZILLA:
                setRamUsageTranslation(MOZILLA_RAM_CONSUMPTION_MB);
                return;
            case Translator.HY_MT:
                ramUsed = HY_RAM_CONSUMPTION_MB;
                break;
            case Translator.MADLAD_CACHE:
                ramUsed = MADLAD_RAM_CONSUMPTION_MB;
                break;
            case Translator.QWEN35:
                ramUsed = global.ggufDownloadSizeMb(model) + QWEN35_RAM_OVERHEAD_MB;
                break;
            case Translator.INDEX_TRANSLATE:
                ramUsed = global.ggufDownloadSizeMb(model) + INDEX_RAM_OVERHEAD_MB;
                break;
            default:
                ramUsed = global.ggufDownloadSizeMb(model) + GEMMA_RAM_OVERHEAD_MB;
                break;
        }
        if (switchMozillaForVoiceModes.isChecked()) {
            ramUsed += MOZILLA_RAM_CONSUMPTION_MB;
        }
        setRamUsageTranslation(ramUsed);
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
        if (!global.asrReady(global.getAsrModel()) && !isGroupDownloaded(asrDownload)) {
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
        if (mode == Translator.INDEX_TRANSLATE && !isGroupDownloaded(global.getIndexTranslateDownloadInfo())) {
            return true;
        }
        if (switchTatoeba.isChecked() && !isGroupDownloaded(global.getTatoebaDownloadInfo())) {
            return true;
        }
        if (switchVadSegmentation.isChecked() && !isGroupDownloaded(global.getVadDownloadInfo())) {
            return true;
        }
        if (switchTranslationDict.isChecked() && !isGroupDownloaded(global.getDictionaryDownloadInfo())) {
            return true;
        }
        return false;
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
