package nie.translator.rtranslator.downloader;

import static android.content.Context.BIND_ABOVE_CLIENT;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.util.ArrayList;

import nie.translator.rtranslator.tools.DownloaderTools;

public class DownloadManager implements ServiceConnection {
    private final Context context;
    @Nullable
    private Callback callback;
    @Nullable
    private DownloaderService downloaderService;
    private final Downloader.ClientCallback serviceCallback;
    private ArrayList<DownloadGroupInfo> downloadsToStart = new ArrayList<>();
    private boolean shouldStartAllDownloads = false;
    private boolean serviceBound = false;
    private Handler mainHandler;


    public DownloadManager(Context context) {
        this.context = context;
        this.mainHandler = new android.os.Handler(Looper.getMainLooper());
        this.serviceCallback = new Downloader.ClientCallback() {
            @Override
            public void onProgress(DownloadGroupInfo downloadGroup, DownloadInfo download, int totalProgress, int progress, boolean unzipping, boolean testingIntegrity) {
                mainHandler.post(() -> {
                    if(callback != null) callback.onProgress(downloadGroup, download, totalProgress, progress, unzipping, testingIntegrity);
                });
            }

            @Override
            public void onCompleted(DownloadGroupInfo downloadGroup, DownloadInfo download) {
                mainHandler.post(() -> {
                    if(callback != null) callback.onCompleted(downloadGroup, download);
                });
            }

            @Override
            public void onAllCompleted(DownloadGroupInfo downloadGroup) {
                mainHandler.post(() -> {
                    if(callback != null) callback.onAllCompleted(downloadGroup);
                });
            }

            @Override
            public void onError(DownloadGroupInfo downloadGroup, DownloadInfo download, int reason) {
                mainHandler.post(() -> {
                    if(callback != null) callback.onError(downloadGroup, download, reason);
                });
            }
        };
    }

    /**
     * This method will start the download service (if there are downloads to resume) and resume all the unfinished downloads
     */
    public boolean subscribeAndResumeDownload(@Nullable Callback callback) {
        return subscribeAndResumeDownload(callback, null);
    }

    /** Same as {@link #subscribeAndResumeDownload(Callback)} but uses a status list that was already parsed. */
    public boolean subscribeAndResumeDownload(@Nullable Callback callback, @Nullable ArrayList<DownloadGroupInfo> savedStatus) {
        if(this.callback == null) {
            this.callback = callback;
            if (downloaderService != null) {
                downloaderService.registerClient(serviceCallback);
                mainHandler.post(() -> {
                    if (this.callback != null) this.callback.onServiceConnected();
                });
                return true;
            }
            boolean shouldStartService = savedStatus != null
                    ? hasActiveDownload(savedStatus, true)
                    : areDownloadsRunning(true);
            // A start requested before the previous screen stopped still has to be delivered.
            if(shouldStartService || serviceBound || !downloadsToStart.isEmpty() || shouldStartAllDownloads) {
                startAndBindService();
                return true;
            }
        }
        return false;
    }

    public void unsubscribe() {
        if(callback != null) {
            boolean pendingStart = !downloadsToStart.isEmpty() || shouldStartAllDownloads;
            // Preferences are the fallback for the moment before onServiceConnected. The live
            // service wins once it is bound: a transfer whose running index has not been saved yet
            // must not be stopped when the screen goes to the background.
            boolean keepService = pendingStart || areDownloadsRunning(false);
            if (downloaderService != null) {
                downloaderService.unregisterClient(serviceCallback);
                keepService = keepService || downloaderService.hasActiveDownload();
            }
            if(!keepService) {
                stopAndUnbindService();
            } else if (!pendingStart && serviceBound) {
                // Stay bound while a start is still queued, otherwise onServiceConnected never runs
                // and the service only sees the old paused status.
                unbindOnly();
            }
            this.callback = null;
        }
    }

    public void startDownload(DownloadGroupInfo downloadGroup){
        DownloadGroupInfo downloadGroupCopy = downloadGroup.clone();
        // Persist the running file before the service exists. Leaving the screen used to stop the
        // service, because the saved status still had runningDownloadIndex == -1 until the first
        // file reached 100%.
        markNextFileRunning(downloadGroupCopy);
        if(downloaderService != null) {
            downloaderService.startDownload(downloadGroupCopy);
            return;
        }
        persistRunningDownload(downloadGroupCopy);
        if(!downloadsToStart.contains(downloadGroupCopy)){
            downloadsToStart.add(downloadGroupCopy);
        }
        startAndBindService();
    }

    public boolean pauseDownload(DownloadGroupInfo downloadGroup){
        if(downloaderService != null) {
            downloaderService.pauseDownload(downloadGroup);
            return true;
        }
        return false;
    }

    public boolean cancelDownload(DownloadGroupInfo downloadGroup){
        if(downloaderService != null) {
            downloaderService.cancelDownload(downloadGroup);
            return true;
        }else{
            ArrayList<DownloadGroupInfo> savedDownloadStatus = getSavedDownloadStatus();
            int index = savedDownloadStatus.indexOf(downloadGroup);
            if(index != -1) {
                // we delete the already downloaded files of this group of download
                DownloaderTools.deleteDownloadedFiles(savedDownloadStatus.get(index));
                // we delete the download status from the preferences
                DownloaderTools.deleteDownloadGroupInfoPreference(context, savedDownloadStatus.get(index));
            }
        }
        return false;
    }

    public boolean startAllDownloads() {
        if(downloaderService != null) {
            downloaderService.startAllDownloads();
            return true;
        }
        if(!shouldStartAllDownloads){
            shouldStartAllDownloads = true;
            markIncompleteDownloadsRunning();
        }
        startAndBindService();
        return false;
    }

    public boolean pauseAllDownloads() {
        if(downloaderService != null) {
            downloaderService.pauseAllDownloads();
            return true;
        }
        return false;
    }

    public boolean cancelAllDownloads() {
        if(downloaderService != null) {
            downloaderService.cancelAllDownloads();
            return true;
        }
        return false;
    }

    public ArrayList<DownloadGroupInfo> getDownloadsStatus() {
        if (downloaderService != null) {
            ArrayList<DownloadGroupInfo> serviceDownloadStatus = downloaderService.getDownloadsStatus();  //this contains the status of only the incompleted downloads
            ArrayList<DownloadGroupInfo> savedDownloadStatus = getSavedDownloadStatus();    //this contains the status of all the downloads, even the completed ones
            //we add the completed downloads status to the status of the running downloads from the service
            for(DownloadGroupInfo savedDownloadGroup: savedDownloadStatus){
                if(!serviceDownloadStatus.contains(savedDownloadGroup)){
                    serviceDownloadStatus.add(savedDownloadGroup);
                }
            }
            return serviceDownloadStatus;
        }
        return null;
    }

    public ArrayList<DownloadGroupInfo> getSavedDownloadStatus(){
        return getSavedDownloadStatus(context);
    }

    public static ArrayList<DownloadGroupInfo> getSavedDownloadStatus(Context context){
        SharedPreferences sharedPreferences = context.getSharedPreferences("default", Context.MODE_PRIVATE);
        String downloadsStatusString = sharedPreferences.getString("downloadsStatus", "");
        if (!downloadsStatusString.isEmpty()) {
            //we check if there are unfinished downloads that are not paused (or also paused ones if includePaused is true)
            Gson gson = new Gson();
            return gson.fromJson(downloadsStatusString, new TypeToken<ArrayList<DownloadGroupInfo>>() {}.getType());
        }
        return new ArrayList<>();
    }

    public boolean checkDownloadCompleted(DownloadGroupInfo downloadGroupInfo){
        return isDownloadCompleted(getSavedDownloadStatus(), downloadGroupInfo);
    }

    public static boolean isDownloadCompleted(@Nullable ArrayList<DownloadGroupInfo> saved, @Nullable DownloadGroupInfo group) {
        if (saved == null || group == null) {
            return false;
        }
        for (DownloadGroupInfo item : saved) {
            if (item.equals(group)) {
                return item.isAllDownloadCompleted();
            }
        }
        return false;
    }

    private void startAndBindService(){
        final Intent intent = new Intent(context, DownloaderService.class);
        try {
            // startService() from the background is rejected, and a dataSync service must be
            // promoted with startForeground() within a few seconds of this call.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (RuntimeException e) {
            Log.e("download", "could not start download service", e);
            return;
        }
        if (!serviceBound) {
            try {
                serviceBound = context.bindService(intent, this, Context.BIND_AUTO_CREATE | BIND_ABOVE_CLIENT);
                Log.d("bind download", serviceBound ? "success" : "failed");
            } catch (RuntimeException e) {
                serviceBound = false;
                Log.e("download", "could not bind download service", e);
            }
        }
    }

    private void unbindOnly() {
        if (!serviceBound) {
            downloaderService = null;
            return;
        }
        try {
            context.unbindService(this);
        } catch (RuntimeException e) {
            Log.w("download", "unbind failed", e);
        }
        serviceBound = false;
        downloaderService = null;
    }

    private void stopAndUnbindService(){
        unbindOnly();
        context.stopService(new Intent(context, DownloaderService.class));
    }

    /** Remember that this group is in progress so a later process start resumes it instead of treating it as paused. */
    private void persistRunningDownload(DownloadGroupInfo downloadGroup) {
        DownloaderTools.addDownloadGroupInfoPreference(context, downloadGroup);
        DownloaderTools.updateDownloadGroupInfoPreference(context, downloadGroup);
    }

    private static void markNextFileRunning(DownloadGroupInfo group) {
        if (group.isAllDownloadCompleted() || group.getRunningDownloadIndex() != -1) {
            return;
        }
        int first = DownloaderTools.findFirstIncompletedDownload(group);
        if (first < group.downloadsInfo.length) {
            group.setRunningDownloadIndex(first);
        }
    }

    private void markIncompleteDownloadsRunning() {
        for (DownloadGroupInfo group : getSavedDownloadStatus()) {
            if (group.isAllDownloadCompleted()) {
                continue;
            }
            markNextFileRunning(group);
            DownloaderTools.updateDownloadGroupInfoPreference(context, group);
        }
    }

    private boolean areDownloadsRunning(boolean includePaused){
        return hasActiveDownload(getSavedDownloadStatus(), includePaused);
    }

    private static boolean hasActiveDownload(@Nullable ArrayList<DownloadGroupInfo> downloadGroupInfos, boolean includePaused) {
        if (downloadGroupInfos == null) {
            return false;
        }
        for (DownloadGroupInfo groupInfo : downloadGroupInfos) {
            if (!groupInfo.isAllDownloadCompleted() && (includePaused || groupInfo.getRunningDownloadIndex() != -1)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
        this.downloaderService = ((DownloaderService.LocalBinder) iBinder).getService();
        downloaderService.registerClient(serviceCallback);
        mainHandler.post(() -> {
            if(callback != null) callback.onServiceConnected();
        });
        for(DownloadGroupInfo downloadGroup: downloadsToStart) {
            downloaderService.startDownload(downloadGroup);
        }
        downloadsToStart.clear();
        if(shouldStartAllDownloads){
            downloaderService.startAllDownloads();
            shouldStartAllDownloads = false;
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName componentName) {
        this.downloaderService = null;
        this.serviceBound = false;
    }

    public static abstract class Callback extends Downloader.ClientCallback {
        public abstract void onServiceConnected();
    }
}
