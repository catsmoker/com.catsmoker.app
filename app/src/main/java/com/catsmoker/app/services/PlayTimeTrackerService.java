package com.catsmoker.app.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
    public static final String EXTRA_VPN_ENABLED = "extra_vpn_enabled";

    private static final String TAG = "PlayTimeTrackerService";
    private static final String NOTIFICATION_CHANNEL_ID = "PlayTimeTrackerChannel";
    private static final int NOTIFICATION_ID = 1;

    // Increased interval to 2 seconds to save battery while remaining accurate enough
    private static final long CHECK_INTERVAL_MS = 2000;

    private Handler handler;
    private Runnable runnable;
    private String trackedPackageName;
    private long sessionPlayTime = 0;
    private boolean vpnEnabledForSession = false;
    private boolean isTracking = false;

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
            vpnEnabledForSession = intent.getBooleanExtra(EXTRA_VPN_ENABLED, false);

            // Reset session time
            sessionPlayTime = 0;
            isTracking = true;

            Notification notification = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                    .setContentTitle("Game Booster Running")
                    .setContentText("Tracking play time for " + getAppName(trackedPackageName))
                    .setSmallIcon(android.R.drawable.ic_media_play) // Use a valid system icon or R.drawable.ic_launcher
                    .setOngoing(true)
                    .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                    .build();

            // Note: Manifest defines foregroundServiceType="specialUse" (Android 14+)
            startForeground(NOTIFICATION_ID, notification);

            startTracking();
        } else {
            // If started without a package name, stop immediately
            stopSelf();
        }
        return START_STICKY;
    }

    private void startTracking() {
        runnable = new Runnable() {
            @Override
            public void run() {
                if (!isTracking) return;

                if (isPackageInForeground()) {
                    // Add the interval time (in seconds) to the counter
                    sessionPlayTime += (CHECK_INTERVAL_MS / 1000);
                    Log.d(TAG, "Play time for " + trackedPackageName + ": " + sessionPlayTime + "s");
                    handler.postDelayed(this, CHECK_INTERVAL_MS);
                } else {
                    // Game closed or moved to background
                    Log.i(TAG, "Game no longer in foreground. Stopping tracking.");
                    stopTrackingAndSelf();
                }
            }
        };
        handler.post(runnable);
    }

    private void stopTrackingAndSelf() {
        if (!isTracking) return;

        isTracking = false; // Prevent re-entry
        handler.removeCallbacks(runnable);
        savePlayTime();

        if (vpnEnabledForSession) {
            Intent vpnIntent = new Intent(this, GameVpnService.class);
            vpnIntent.setAction(GameVpnService.ACTION_DISCONNECT);
            startService(vpnIntent);
        }

        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private boolean isPackageInForeground() {
        if (trackedPackageName == null) return false;

        UsageStatsManager usageStatsManager = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        if (usageStatsManager == null) return false;

        long time = System.currentTimeMillis();
        // Look back slightly longer than the check interval to ensure we catch the event
        List<UsageStats> stats = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY, time - (CHECK_INTERVAL_MS * 2), time);

        if (stats != null && !stats.isEmpty()) {
            SortedMap<Long, UsageStats> mySortedMap = new TreeMap<>();
            for (UsageStats usageStats : stats) {
                mySortedMap.put(usageStats.getLastTimeUsed(), usageStats);
            }

            if (!mySortedMap.isEmpty()) {
                // Fix: NPE Protection by breaking the chain
                UsageStats topStats = mySortedMap.get(mySortedMap.lastKey());
                if (topStats != null) {
                    return trackedPackageName.equals(topStats.getPackageName());
                }
            }
        }
        return false;
    }

    private void savePlayTime() {
        if (trackedPackageName != null && sessionPlayTime > 0) {
            SharedPreferences prefs = getSharedPreferences("PlayTime", MODE_PRIVATE);
            long totalPlayTime = prefs.getLong(trackedPackageName, 0);
            totalPlayTime += sessionPlayTime;
            prefs.edit().putLong(trackedPackageName, totalPlayTime).apply();
            Log.i(TAG, "Saved " + sessionPlayTime + "s for " + trackedPackageName + ". Total: " + totalPlayTime + "s");
        }
    }

    private String getAppName(String packageName) {
        try {
            return getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(packageName, 0)
            ).toString();
        } catch (Exception e) {
            return packageName;
        }
    }

    @Override
    public void onDestroy() {
        // Only perform cleanup, do not call stopSelf() here
        isTracking = false;
        if (handler != null && runnable != null) {
            handler.removeCallbacks(runnable);
        }
        // Ensure time is saved if the service is killed abruptly (e.g., swiped away)
        if (sessionPlayTime > 0) {
            savePlayTime();
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        // SDK Check removed (Min SDK 27 supports NotificationChannel)
        NotificationChannel serviceChannel = new NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Play Time Tracker",
                NotificationManager.IMPORTANCE_LOW // Lower importance for background service
        );
        serviceChannel.setDescription("Shows when a game is being tracked");
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(serviceChannel);
        }
    }
}