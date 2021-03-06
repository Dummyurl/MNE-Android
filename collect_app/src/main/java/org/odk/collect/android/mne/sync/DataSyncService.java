package org.odk.collect.android.mne.sync;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.AsyncTask;
import android.os.IBinder;
import android.support.annotation.NonNull;
import android.support.v4.app.NotificationCompat;
import android.text.TextUtils;

import org.odk.collect.android.R;
import org.odk.collect.android.listeners.DownloadFormsTaskListener;
import org.odk.collect.android.listeners.FormListDownloaderListener;
import org.odk.collect.android.logic.FormDetails;
import org.odk.collect.android.mne.activitygroup.ActivityGroupRemoteSource;
import org.odk.collect.android.mne.activitygroup.model.Activity;
import org.odk.collect.android.mne.activitygroup.model.ActivityGroup;
import org.odk.collect.android.mne.api.RetrofitException;
import org.odk.collect.android.mne.beneficary.BeneficaryResponse;
import org.odk.collect.android.mne.beneficary.BeneficiaryRemoteSource;
import org.odk.collect.android.mne.cluster.ClusterRemoteSource;
import org.odk.collect.android.mne.common.Constant;
import org.odk.collect.android.tasks.DownloadFormListTask;
import org.odk.collect.android.tasks.DownloadFormsTask;
import org.odk.collect.android.utilities.ToastUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.reactivex.Observable;
import io.reactivex.ObservableSource;
import io.reactivex.functions.Function;
import io.reactivex.functions.Function3;
import io.reactivex.observers.DisposableObserver;
import io.reactivex.schedulers.Schedulers;
import timber.log.Timber;

import static org.odk.collect.android.utilities.NotificationUtils.CHANNEL_ID;
import static org.odk.collect.android.utilities.NotificationUtils.showNotification;

public class DataSyncService extends Service {
    boolean isAlreadyUptoDate = false;
    private DisposableObserver<String> dis;
    private DownloadFormListTask downloadFormListTask;
    private DownloadFormsTask downloadFormsTask;
    private DisposableObserver<Object> disposable;


    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        String sentAction = intent.getAction();
        if (TextUtils.equals(sentAction, Constant.SERVICE.STARTFOREGROUND_SYNC)) {
            startIfNetworkAvailable();
        } else if (TextUtils.equals(sentAction, Constant.SERVICE.STOPFOREGROUND_SYNC)) {
            disposeTasks();
            stopSafely();
        }
        return START_STICKY;
    }

    private void startIfNetworkAvailable() {
        String notificationContentText = getString(R.string.downloading_data);
        startForeground(Constant.NOTIFICATION_ID.FOREGROUND_DATA_SYNC_SERVICE, buildNotification(notificationContentText));
        if (!InternetUtil.checkConnectedToNetwork()) {
            stopSafely();
            ToastUtils.showLongToast(R.string.no_connection);
        } else {
            if (isAlreadyUptoDate) {
                showNotification(null, Constant.NOTIFICATION_ID.DATA_UPTO_DATE, R.string.noti_title_already_upto_date, getString(R.string.noti_desc_already_upto_date));
                stopSafely();
            }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Timber.i("OnCreate()");
        if (InternetUtil.checkConnectedToNetwork()) {
            Observable<List<Activity>> actObservable = ClusterRemoteSource.getInstance()
                    .getAll()
                    .subscribeOn(Schedulers.io())
                    .flatMapIterable((Function<List<ActivityGroup>, Iterable<ActivityGroup>>) activityGroups -> activityGroups)
                    .flatMap((Function<ActivityGroup, ObservableSource<List<Activity>>>) activityGroup -> {
                        String actGroupId = activityGroup.getId();
                        return ActivityGroupRemoteSource.getInstance().getActivityGroup(actGroupId);
                    });

            Observable<List<BeneficaryResponse>> beneficiaryObservable = BeneficiaryRemoteSource.getInstance().getAll();

            Observable<String> formsObservable = Observable.create(emitter -> {
                downloadFormList(value -> {
                    ArrayList<FormDetails> filesToDownload = new ArrayList<FormDetails>();
                    for (Object o : value.entrySet()) {
                        FormDetails formDetails = (FormDetails) ((Map.Entry) o).getValue();
                        filesToDownload.add(formDetails);
                    }

                    startFormsDownload(filesToDownload, new DownloadFormsTaskListener() {
                        @Override
                        public void formsDownloadingComplete(HashMap<FormDetails, String> result) {
                            if (emitter.isDisposed()) {
                                return;
                            }
                            Iterator<Map.Entry<FormDetails, String>> iterator = result.entrySet().iterator();
                            ArrayList<FormDetails> failedFormsDetails = new ArrayList<>();

                            while (iterator.hasNext()) {
                                Map.Entry pair = iterator.next();
                                boolean downloadSuccess = TextUtils.equals("Success", pair.getValue().toString());
                                if (!downloadSuccess) {
                                    failedFormsDetails.add((FormDetails) pair.getKey());
                                }
                            }
                            int totalFailedForms = failedFormsDetails.size();
                            if (totalFailedForms > 0) {
                                emitter.onError(new RuntimeException("Failed to download " + totalFailedForms + " forms"));
                            } else {
                                emitter.onNext(String.format(Locale.getDefault(), "%d forms", result.size()));
                                emitter.onComplete();
                            }

                        }

                        @Override
                        public void progressUpdate(String currentFile, int progress, int total) {
                            //unused
                        }

                        @Override
                        public void formsDownloadingCancelled() {
                            emitter.onError(new RuntimeException("Download was canceled "));
                        }
                    });
                });
            });


            dis = Observable.zip(actObservable, beneficiaryObservable, formsObservable, new Function3<List<Activity>, List<BeneficaryResponse>, String, String>() {
                @Override
                public String apply(List<Activity> activityList, List<BeneficaryResponse> beneficaryResponses, String formResultMessage) throws Exception {
                    String title_activities = String.format("Downloaded %s activities", activityList.size());
                    String title_beneficiaries = String.format("%s beneficiaries", beneficaryResponses.size());
                    return title_activities.concat(",").concat(title_beneficiaries).concat(" and ").concat(formResultMessage);
                }
            }).subscribeWith(new DisposableObserver<String>() {
                @Override
                public void onNext(String s) {
                    Timber.i(s);
                    showNotification(null, Constant.NOTIFICATION_ID.DATA_UPTO_DATE, R.string.noti_title_download_complete, s);

                }

                @Override
                public void onError(Throwable e) {
                    String message = e.getMessage();
                    if (e instanceof RetrofitException) {
                        message = ((RetrofitException) e).getKind().getMessage();
                    }
                    showNotification(null, Constant.NOTIFICATION_ID.DATA_SYNC_ERROR, R.string.error_occured, message);
                    stopSafely();
                    Timber.e(e);
                }

                @Override
                public void onComplete() {
                    showNotification(null, Constant.NOTIFICATION_ID.DATA_UPTO_DATE, R.string.noti_title_download_complete, "Download complete");

                    cancelNotification(DataSyncService.this, Constant.NOTIFICATION_ID.FOREGROUND_DATA_SYNC_SERVICE);
                    stopSafely();
                }
            });
        }
    }


    @SuppressWarnings("unchecked")
    private void startFormsDownload
            (@NonNull ArrayList<FormDetails> filesToDownload, DownloadFormsTaskListener listener) {
        int totalCount = filesToDownload.size();
        if (totalCount > 0) {
            // show dialog box

            downloadFormsTask = new DownloadFormsTask();
            downloadFormsTask.setDownloaderListener(listener);
            downloadFormsTask.execute(filesToDownload);
        } else {
            ToastUtils.showShortToast(R.string.noselect_error);
        }
    }

    private void downloadFormList(FormListDownloaderListener listDownloaderListener) {
        ConnectivityManager connectivityManager =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo ni = connectivityManager.getActiveNetworkInfo();

        if (ni == null || !ni.isConnected()) {
            ToastUtils.showShortToast(R.string.no_connection);


        } else {


            if (downloadFormListTask != null
                    && downloadFormListTask.getStatus() != AsyncTask.Status.FINISHED) {
                return; // we are already doing the download!!!
            } else if (downloadFormListTask != null) {
                downloadFormListTask.setDownloaderListener(null);
                downloadFormListTask.cancel(true);
                downloadFormListTask = null;
            }

            downloadFormListTask = new DownloadFormListTask();
            downloadFormListTask.setDownloaderListener(listDownloaderListener);


            downloadFormListTask.execute();
        }
    }

    private void updateNotificationText(String text) {

        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(Constant.NOTIFICATION_ID.FOREGROUND_DATA_SYNC_SERVICE, buildNotification(text));
    }

    public static void cancelNotification(Context ctx, int notifyId) {
        NotificationManager nMgr = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        nMgr.cancel(notifyId);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Timber.i("OnDestroy()");
        disposeTasks();
    }

    private void disposeTasks() {
        if (dis != null) {
            dis.dispose();
        }
    }

    private void stopSafely() {
        stopForeground(true);
        stopSelf();
    }

    private Notification buildNotification(String contentText) {


        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.msg_download_new_data))
                .setTicker(getString(R.string.msg_download_new_data))
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_media_play, getString(R.string.action_stop_download),
                        buildStopDownloadIntent()).build();

    }

    private PendingIntent buildStopDownloadIntent() {
        Intent nextIntent = new Intent(this, DataSyncService.class);
        nextIntent.setAction(Constant.SERVICE.STOPFOREGROUND_SYNC);
        return PendingIntent.getService(this, 0,
                nextIntent, 0);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
