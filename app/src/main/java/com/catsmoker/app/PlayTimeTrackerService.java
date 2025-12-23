package com.catsmoker.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

public class PlayTimeTrackerService extends Service {

    public static final String EXTRA_PACKAGE_NAME = "extra_package_name";
    private static final String TAG = "PlayTimeTrackerService";
    private static final String NOTIFICATION_CHANNEL_ID = "PlayTimeTrackerChannel";
    private static final int NOTIFICATION_ID = 1;

    private Handler handler;
    private Runnable runnable;
    private String trackedPackageName;
    private long sessionPlayTime = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra(EXTRA_PACKAGE_NAME)) {
            trackedPackageName = intent.getStringExtra(EXTRA_PACKAGE_NAME);
            sessionPlayTime = 0;

            Notification notification = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                    .setContentTitle("Tracking Play Time")
                    .setContentText("Tracking play time for " + trackedPackageName)
                    .setSmallIcon(R.drawable.ic_check_circle)
                    .build();

            startForeground(NOTIFICATION_ID, notification);

            startTracking();
        }
        return START_STICKY;
    }

    private void startTracking() {
        runnable = new Runnable() {
            @Override
            public void run() {
                if (isPackageInForeground()) {
                    sessionPlayTime++;
                    Log.d(TAG, "Play time for " + trackedPackageName + ": " + sessionPlayTime + "s");
                } else {
                    stopTrackingAndSelf();
                }
                handler.postDelayed(this, 1000);
            }
        };
        handler.post(runnable);
    }

    private void stopTrackingAndSelf() {
        handler.removeCallbacks(runnable);
        savePlayTime();
        stopForeground(true);
        stopSelf();
    }

    private boolean isPackageInForeground() {
        UsageStatsManager usageStatsManager = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        long time = System.currentTimeMillis();
        List<UsageStats> stats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, time - 1000 * 10, time);
        if (stats != null) {
            SortedMap<Long, UsageStats> mySortedMap = new TreeMap<>();
            for (UsageStats usageStats : stats) {
                mySortedMap.put(usageStats.getLastTimeUsed(), usageStats);
            }
            if (!mySortedMap.isEmpty()) {
                String currentApp = mySortedMap.get(mySortedMap.lastKey()).getPackageName();
                return currentApp.equals(trackedPackageName);
            }
        }
        return false;
    }

    private void savePlayTime() {
        if (trackedPackageName != null) {
            SharedPreferences prefs = getSharedPreferences("PlayTime", MODE_PRIVATE);
            long totalPlayTime = prefs.getLong(trackedPackageName, 0);
            totalPlayTime += sessionPlayTime;
            prefs.edit().putLong(trackedPackageName, totalPlayTime).apply();
        }
    }

    @Override
    public void onDestroy() {
        stopTrackingAndSelf();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "Play Time Tracker",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(serviceChannel);
        }
    }
}
