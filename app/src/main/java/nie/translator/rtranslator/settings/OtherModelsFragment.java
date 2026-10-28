package nie.translator.rtranslator.settings;

import android.app.Activity;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.fragment.app.Fragment;

import java.util.ArrayList;
import java.util.HashMap;

import nie.translator.rtranslator.Global;
import nie.translator.rtranslator.R;
import nie.translator.rtranslator.access.AccessActivity;
import nie.translator.rtranslator.downloader.DownloadGroupInfo;
import nie.translator.rtranslator.downloader.DownloadInfo;
import nie.translator.rtranslator.downloader.DownloadManager;
import nie.translator.rtranslator.tools.gui.ResourceManagerView;
import nie.translator.rtranslator.voice_translation.neural_networks.translation.Translator;
import worker8.com.github.radiogroupplus.RadioGroupPlus;

/**
 * Mozilla, HY-MT and Madlad, kept off the main model list. The page only picks one: the model
 * manager shows it under "Other" and the user still has to press Apply there.
 */
public class OtherModelsFragment extends Fragment {
    public static final String ARG_MODEL = "otherModel";
    private Activity activity;
    private Global global;
    private DownloadManager downloadManager;
    private DownloadManager.Callback downloadManagerCallback;
    private final HashMap<String, ResourceManager> resourceManagers = new HashMap<>();
    private RadioGroupPlus radioGroup;
    private ResourceManagerView hyManagerView;
    private ResourceManagerView madladManagerView;
    private ImageView arrowMozilla;
    private Button useButton;
    private int loadGeneration = 0;

    @StringRes
    public static int titleOf(int mode) {
        switch (mode) {
            case Translator.HY_MT:
                return R.string.title_model_hy;
            case Translator.MADLAD:
            case Translator.MADLAD_CACHE:
                return R.string.title_model_madlad;
            default:
                return R.string.title_model_mozilla;
        }
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_other_models, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        radioGroup = view.findViewById(R.id.other_model_radios);
        hyManagerView = view.findViewById(R.id.modelHy);
        madladManagerView = view.findViewById(R.id.modelMadlad);
        arrowMozilla = view.findViewById(R.id.arrowMozilla);
        useButton = view.findViewById(R.id.buttonUseOther);
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        activity = requireActivity();
        global = (Global) activity.getApplication();
        downloadManager = new DownloadManager(global);
        resourceManagers.put("hyManager", new ResourceManager(activity, global.getHyMtDownloadInfo(), hyManagerView, downloadManager));
        resourceManagers.put("madladManager", new ResourceManager(activity, global.getMadladDownloadInfo(), madladManagerView, downloadManager));

        int model = getArguments() != null ? getArguments().getInt(ARG_MODEL, global.getOtherTranslationModel()) : global.getOtherTranslationModel();
        switch (model) {
            case Translator.HY_MT:
                radioGroup.check(R.id.radioHY);
                break;
            case Translator.MADLAD:
            case Translator.MADLAD_CACHE:
                radioGroup.check(R.id.radioMadlad);
                break;
            default:
                radioGroup.check(R.id.radioMozilla);
                break;
        }

        downloadManagerCallback = new DownloadManager.Callback() {
            @Override
            public void onServiceConnected() {
                restoreDownloadState(downloadManager.getDownloadsStatus());
            }

            @Override
            public void onProgress(DownloadGroupInfo downloadGroup, DownloadInfo download, int totalProgress, int progress, boolean unzipping, boolean testingIntegrity) {
                for (ResourceManager manager : resourceManagers.values()) {
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
                for (ResourceManager manager : resourceManagers.values()) {
                    if (downloadGroup.equals(manager.getDownloadInfo())) {
                        manager.setState(ResourceManagerView.State.DOWNLOADED);
                    }
                }
            }

            @Override
            public void onError(DownloadGroupInfo downloadGroup, DownloadInfo download, int reason) {
                for (ResourceManager manager : resourceManagers.values()) {
                    if (downloadGroup.equals(manager.getDownloadInfo())) {
                        manager.setError(reason);
                    }
                }
            }
        };
        arrowMozilla.setOnClickListener(v -> {
            if (activity instanceof SettingsActivity) {
                ((SettingsActivity) activity).startFragment(SettingsActivity.MOZILLA_MANAGER, null);
            } else if (activity instanceof AccessActivity) {
                ((AccessActivity) activity).startFragment(AccessActivity.MOZILLA_MANAGER, null);
            }
        });
        useButton.setOnClickListener(v -> {
            global.setPendingOtherTranslationModel(checkedModel());
            if (activity instanceof SettingsActivity) {
                activity.onBackPressed();
            } else if (activity instanceof AccessActivity) {
                ((AccessActivity) activity).startFragment(AccessActivity.MODEL_MANAGER, null);
            }
        });
    }

    private int checkedModel() {
        int checked = radioGroup.getCheckedRadioButtonId();
        if (checked == R.id.radioHY) {
            return Translator.HY_MT;
        }
        if (checked == R.id.radioMadlad) {
            return Translator.MADLAD_CACHE;
        }
        return Translator.MOZILLA;
    }

    @Override
    public void onStart() {
        super.onStart();
        final int generation = ++loadGeneration;
        final Activity host = activity;
        if (host == null || downloadManager == null) {
            return;
        }
        new Thread(() -> {
            ArrayList<DownloadGroupInfo> saved = downloadManager.getSavedDownloadStatus();
            host.runOnUiThread(() -> {
                if (generation != loadGeneration || !isAdded()) {
                    return;
                }
                if (!downloadManager.subscribeAndResumeDownload(downloadManagerCallback, saved)) {
                    restoreDownloadState(saved);
                }
            });
        }, "otherModelsState").start();
    }

    @Override
    public void onStop() {
        super.onStop();
        loadGeneration++;
        if (downloadManager != null) {
            downloadManager.unsubscribe();
        }
    }

    private void restoreDownloadState(@Nullable ArrayList<DownloadGroupInfo> status) {
        if (status == null) {
            return;
        }
        for (DownloadGroupInfo download : status) {
            for (ResourceManager manager : resourceManagers.values()) {
                if (download.equals(manager.getDownloadInfo())) {
                    ModelManagerFragment.showDownloadStatus(manager, download);
                }
            }
        }
    }
}
