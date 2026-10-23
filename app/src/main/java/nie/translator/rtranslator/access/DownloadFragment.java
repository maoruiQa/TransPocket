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

package nie.translator.rtranslator.access;

import android.annotation.SuppressLint;
import android.icu.text.DecimalFormat;
import android.os.Bundle;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.util.ArrayList;

import nie.translator.rtranslator.Global;
import nie.translator.rtranslator.R;
import nie.translator.rtranslator.downloader.DownloadGroupInfo;
import nie.translator.rtranslator.downloader.DownloadInfo;
import nie.translator.rtranslator.downloader.DownloadManager;
import nie.translator.rtranslator.tools.DownloaderTools;

public class DownloadFragment extends Fragment {
    private ArrayList<DownloadGroupInfo> requiredGroups = new ArrayList<>();
    private static final long INTERVAL_TIME_FOR_GUI_UPDATES_MS = 100;  //500
    private AccessActivity activity;
    private Global global;
    private DownloadManager downloader;
    private android.os.Handler mainHandler;   // handler that can be used to post to the main thread

    //Gui components
    private ImageButton retryButton;
    private ImageButton pauseButton;
    private TextView downloadErrorText;
    private TextView transferErrorText;
    private TextView storageWarningText;
    private LinearProgressIndicator progressBar;
    private TextView progressDescriptionText;
    private TextView progressNumbersText;
    private DownloadManager.Callback downloadManagerCallback;
    private boolean guiStateRestored = false;

    public DownloadFragment() {
        // Required empty public constructor
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_download, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        retryButton = view.findViewById(R.id.retryButton);
        downloadErrorText = view.findViewById(R.id.text_error_download);
        transferErrorText = view.findViewById(R.id.text_error_transfer);
        storageWarningText = view.findViewById(R.id.text_error_storage);
        progressBar = view.findViewById(R.id.barRam);
        progressDescriptionText = view.findViewById(R.id.progress_description);
        pauseButton = view.findViewById(R.id.pauseButton);
        pauseButton.setTag("iconPause");
        progressNumbersText = view.findViewById(R.id.progress_numbers);
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        activity = (AccessActivity) requireActivity();
        global = (Global) activity.getApplication();

        requiredGroups = requiredGroups();

        mainHandler = new android.os.Handler(Looper.getMainLooper());
        downloader = new DownloadManager(global);
        retryButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(downloadErrorText.getVisibility() == View.VISIBLE){  //that means that we should restart the download
                    downloadErrorText.setVisibility(View.GONE);
                    transferErrorText.setVisibility(View.GONE);
                    retryButton.setVisibility(View.GONE);
                    retryCurrentDownload();
                }
            }
        });
        pauseButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(pauseButton.getTag().equals("iconPause")){
                    //we pause the download
                    boolean success = downloader.pauseAllDownloads();
                    if(success) {
                        //we change the icon and tag
                        setPauseButtonStatus(true);
                    }
                }else{
                    startAllDownloads();
                }
            }
        });

        downloadManagerCallback = new DownloadManager.Callback() {
            public void onServiceConnected(){
                if(!guiStateRestored) {
                    ArrayList<DownloadGroupInfo> downloadStatus = downloader.getDownloadsStatus();
                    // we change the GUI based on current download status
                    restoreGuiState(downloadStatus);
                }
            }

            @Override
            public void onAllCompleted(DownloadGroupInfo downloadGroup) {
                if (allRequiredDownloadsComplete()) {
                    activity.enterApp();
                }
            }

            @Override
            public void onCompleted(DownloadGroupInfo downloadGroup, DownloadInfo download) {
                //for now we do nothing here
            }

            @SuppressLint("SetTextI18n")
            @Override
            public void onProgress(DownloadGroupInfo downloadGroup, DownloadInfo download, int totalProgress, int progress, boolean unzipping, boolean testingIntegrity) {
                //update of progress bar
                int progressNormalized = totalProgress * progressBar.getMax() / 100;
                progressBar.setProgress(progressNormalized, false);
                //we update the progressNumbersText
                double totalSize = 0;
                for (DownloadGroupInfo group : requiredGroups) {
                    for (DownloadInfo info : group.downloadsInfo) {
                        totalSize = totalSize + info.getSize();
                    }
                }
                totalSize = totalSize/1000000;   //we convert from Kb to Gb
                float downloadedGb = (float) (totalProgress*totalSize/100);    //progress : 100 = x : totalSize   (where x is downloadedGb)
                DecimalFormat decimalFormat = new DecimalFormat("#.##");
                progressNumbersText.setText(decimalFormat.format(downloadedGb)+" / "+decimalFormat.format(totalSize)+" GB");
                //update of the progress description
                if(testingIntegrity){
                    progressDescriptionText.setText(getString(R.string.description_integrity_check, download.getName()));
                }else if(unzipping) {
                    progressDescriptionText.setText(getString(R.string.description_unzip, download.getName()));
                }else{
                    progressDescriptionText.setText(getString(R.string.description_download, download.getName()));
                }
            }

            @Override
            public void onError(DownloadGroupInfo downloadGroup, DownloadInfo download, int reason) {
                showDownloadError();
            }
        };
    }

    @Override
    public void onStart() {
        super.onStart();
        if(global != null && !requiredGroups.isEmpty()) {
            //if the internal or external free memory are low, we show a warning
            double requiredSize = 0;
            for (DownloadGroupInfo group : requiredGroups) {
                for (DownloadInfo downloadInfo : group.downloadsInfo) {
                    requiredSize = requiredSize + downloadInfo.getSize();
                }
            }
            requiredSize = requiredSize / 1000;   //we convert from Kb to Mb
            requiredSize = requiredSize + 800;   //we add a margin (because the transfer process requires more space)
            if(global.getAvailableExternalMemorySize() < requiredSize || global.getAvailableInternalMemorySize() < requiredSize){
                //we show the warning
                storageWarningText.setVisibility(View.VISIBLE);
            }

            boolean serviceStarted = downloader.subscribeAndResumeDownload(downloadManagerCallback);

            ArrayList<DownloadGroupInfo> downloadStatus = downloader.getSavedDownloadStatus();
            // we eventually start the download if it is the first time
            boolean startedAny = false;
            for (DownloadGroupInfo group : requiredGroups) {
                if (!groupSatisfied(group, downloadStatus)) {
                    downloader.startDownload(group);
                    startedAny = true;
                }
            }
            if(!startedAny && allRequiredDownloadsComplete()){
                activity.enterApp();
            }else if(!serviceStarted && !startedAny){
                // we change the GUI based on current saved download status
                // normally we do this when the service starts, but if it won't start (paused download or other reasons)
                // we restore the GUI state based on the saved download state instead.
                restoreGuiState(downloadStatus);
            }
        }
    }

    private void restoreGuiState(ArrayList<DownloadGroupInfo> downloadStatus){
        // we change the GUI based on current download status
        if(downloadStatus != null){
            guiStateRestored = true;
            int index = downloadStatus.indexOf(requiredGroups.get(0));
            if(index != -1) {
                if (downloadStatus.get(index).isAllDownloadCompleted()) {
                    downloadManagerCallback.onAllCompleted(downloadStatus.get(index));
                } else {
                    DownloadInfo runningDownload = downloadStatus.get(index).getRunningDownload();
                    if (runningDownload != null) {
                        if (runningDownload.getCurrentError() != -1) {
                            //the download has an error
                            downloadManagerCallback.onError(downloadStatus.get(index), runningDownload, runningDownload.getCurrentError());
                        } else {
                            //the download is running
                            downloadManagerCallback.onProgress(downloadStatus.get(index), runningDownload, downloadStatus.get(index).getCurrentProgress(), runningDownload.getCurrentProgress(), runningDownload.isUnzipping(), runningDownload.isTestingIntegrity());
                        }
                    }else{
                        int firstIncompleteIndex = DownloaderTools.findFirstIncompletedDownload(downloadStatus.get(index));
                        if(firstIncompleteIndex < downloadStatus.get(index).downloadsInfo.length) {
                            //the download is paused
                            DownloadInfo pausedDownload = downloadStatus.get(index).downloadsInfo[firstIncompleteIndex];
                            downloadManagerCallback.onProgress(downloadStatus.get(index), pausedDownload, downloadStatus.get(index).getCurrentProgress(), pausedDownload.getCurrentProgress(), pausedDownload.isUnzipping(), pausedDownload.isTestingIntegrity());
                            //we change the pause icon and tag
                            setPauseButtonStatus(true);
                        }else{
                            // the download group is completed even if for some errors the group is not marked as completed
                            downloadManagerCallback.onAllCompleted(downloadStatus.get(index));
                        }
                    }
                }
            }
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        guiStateRestored = false;
        downloader.unsubscribe();
        //we cancel the storage warning (in this way when the user reopens the app the warning is shown only if the storage is still low)
        storageWarningText.setVisibility(View.GONE);
    }

    private void showDownloadError(){
        //we show the download error and the retry button
        mainHandler.post(() -> {
            downloadErrorText.setVisibility(View.VISIBLE);
            transferErrorText.setVisibility(View.GONE);
            retryButton.setVisibility(View.VISIBLE);
            //we change the icon and tag of the pauseButton
            setPauseButtonStatus(true);
        });
    }

    private void setPauseButtonStatus(boolean paused){
        if(paused){
            pauseButton.setImageResource(R.drawable.play_icon);
            pauseButton.setTag("iconPlay");
            pauseButton.setContentDescription(getResources().getString(R.string.resume));
        }else{
            pauseButton.setImageResource(R.drawable.pause_icon);
            pauseButton.setTag("iconPause");
            pauseButton.setContentDescription(getResources().getString(R.string.pause));
        }
    }

    private void retryCurrentDownload(){
        startAllDownloads();
    }

    private ArrayList<DownloadGroupInfo> requiredGroups() {
        ArrayList<DownloadGroupInfo> groups = new ArrayList<>();
        groups.add(global.getAsrDownloadInfo(global.getAsrModel()));
        int mode = global.getTranslationMode();
        if (mode == nie.translator.rtranslator.voice_translation.neural_networks.translation.Translator.HY_MT) {
            groups.add(global.getHyMtDownloadInfo());
        } else if (mode == nie.translator.rtranslator.voice_translation.neural_networks.translation.Translator.MADLAD_CACHE) {
            groups.add(global.getMadladDownloadInfo());
        } else if (mode == nie.translator.rtranslator.voice_translation.neural_networks.translation.Translator.TRANSLATEGEMMA) {
            groups.add(global.getTranslateGemmaDownloadInfo());
        } else if (mode == nie.translator.rtranslator.voice_translation.neural_networks.translation.Translator.QWEN35) {
            groups.add(global.getQwen35DownloadInfo());
        }
        if (global.isUseTatoeba()) {
            groups.add(global.getTatoebaDownloadInfo());
        }
        if (global.isSimultaneousVadSegmentation()) {
            groups.add(global.getSileroVadDownloadInfo());
        }
        if (global.isUseTranslationDictionaries()) {
            groups.add(global.getDictionaryDownloadInfo());
        }
        groups.addAll(nie.translator.rtranslator.tools.LiveDraftVoices.wantedDownloads(global));
        return groups;
    }

    private boolean allRequiredDownloadsComplete() {
        ArrayList<DownloadGroupInfo> saved = downloader.getSavedDownloadStatus();
        for (DownloadGroupInfo group : requiredGroups) {
            if (!groupSatisfied(group, saved)) {
                return false;
            }
        }
        return !requiredGroups.isEmpty();
    }

    private boolean groupSatisfied(DownloadGroupInfo group, ArrayList<DownloadGroupInfo> saved) {
        if (nie.translator.rtranslator.tools.LiveDraftVoices.isReadyGroup(global, group)) {
            return true;
        }
        if (global.groupFilesPresent(group)) {
            return true;
        }
        DownloadGroupInfo savedGroup = matchingSaved(saved, group);
        return savedGroup != null && savedGroup.isAllDownloadCompleted();
    }

    @Nullable
    private DownloadGroupInfo matchingSaved(ArrayList<DownloadGroupInfo> saved, DownloadGroupInfo group) {
        if (saved == null) {
            return null;
        }
        int index = saved.indexOf(group);
        if (index == -1) {
            return null;
        }
        return saved.get(index);
    }

    private void startAllDownloads(){
        downloader.startAllDownloads();
        //we change the icon and tag
        setPauseButtonStatus(false);
    }
}