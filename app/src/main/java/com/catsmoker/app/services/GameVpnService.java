package com.catsmoker.app.services;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.catsmoker.app.FeaturesActivity;
import com.catsmoker.app.R;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@SuppressLint("VpnServicePolicy")
public class GameVpnService extends VpnService {
    private static final String TAG = "GameVpnService";
    private ParcelFileDescriptor vpnInterface = null;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private ExecutorService executorService;

    public static final String ACTION_CONNECT = "com.catsmoker.app.action.CONNECT";
    public static final String ACTION_DISCONNECT = "com.catsmoker.app.action.DISCONNECT";
    public static final String EXTRA_GAME_PACKAGE = "com.catsmoker.app.extra.GAME_PACKAGE";

    private static final int NOTIFICATION_ID = 1337;
    private static final String CHANNEL_ID = "GameVpnChannel";

    @Override
    public void onCreate() {
        super.onCreate();
        executorService = Executors.newSingleThreadExecutor();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_CONNECT.equals(action)) {
                String gamePackage = intent.getStringExtra(EXTRA_GAME_PACKAGE);

                // FIXED: Android 14 (API 34) Foreground Service compliance
                Notification notification = createNotification(gamePackage, true);
                if (Build.VERSION.SDK_INT >= 34) { // Android 14+
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
                } else if (Build.VERSION.SDK_INT >= 29) { // Android 10+
                    // Use standard method for versions between 10 and 13
                    // '0' means no specific type enforcement, or use generic type if needed
                    startForeground(NOTIFICATION_ID, notification, 0);
                } else {
                    startForeground(NOTIFICATION_ID, notification);
                }

                startVpnBackground(gamePackage);
                return START_STICKY;
            } else if (ACTION_DISCONNECT.equals(action)) {
                stopVpn();
                return START_NOT_STICKY;
            }
        }
        return START_NOT_STICKY;
    }

    private void startVpnBackground(String gamePackage) {
        if (isRunning.get()) {
            return;
        }
        isRunning.set(true);

        executorService.execute(() -> {
            try {
                configureAndEstablishVpn(gamePackage);
            } catch (Exception e) {
                Log.e(TAG, "Failed to start VPN", e);
                stopVpn();
            }
        });
    }

    private void configureAndEstablishVpn(String gamePackage) throws Exception {
        VpnService.Builder builder = new VpnService.Builder();
        builder.setSession(getString(R.string.vpn_session_name));
        builder.setMtu(1500);

        // IPv4 - Blackhole range
        builder.addAddress("10.0.0.2", 24);
        builder.addRoute("0.0.0.0", 0);

        // IPv6 - Blackhole range
        builder.addAddress("fd00::1", 128);
        builder.addRoute("::", 0);

        // --- Custom DNS Logic ---
        SharedPreferences dnsPrefs = getSharedPreferences(FeaturesActivity.DNS_PREFS, MODE_PRIVATE);
        String customDns = dnsPrefs.getString(FeaturesActivity.KEY_CUSTOM_DNS, "");

        if (!TextUtils.isEmpty(customDns)) {
            String[] dnsServers = customDns.split(",");
            for (String dns : dnsServers) {
                try {
                    String cleanDns = dns.trim();
                    if (!cleanDns.isEmpty()) {
                        InetAddress address = InetAddress.getByName(cleanDns);
                        builder.addDnsServer(address);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Invalid DNS server: " + dns, e);
                }
            }
        }

        // --- Split Tunneling Logic ---
        if (gamePackage != null && !gamePackage.isEmpty()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    builder.addDisallowedApplication(gamePackage);
                } catch (PackageManager.NameNotFoundException e) {
                    Log.e(TAG, "Package not found to disallow: " + gamePackage);
                }
            } else {
                PackageManager pm = getPackageManager();
                Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
                mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
                List<ResolveInfo> activities = pm.queryIntentActivities(mainIntent, 0);

                for (ResolveInfo ri : activities) {
                    String packageName = ri.activityInfo.packageName;
                    if (!packageName.equals(gamePackage) && !packageName.equals(getPackageName())) {
                        try {
                            builder.addAllowedApplication(packageName);
                        } catch (Exception e) {
                            // Ignore specific package errors
                        }
                    }
                }
            }
        }

        Intent configureIntent = new Intent(this, FeaturesActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, configureIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        builder.setConfigureIntent(pendingIntent);

        synchronized (this) {
            if (vpnInterface != null) {
                vpnInterface.close();
            }
            vpnInterface = builder.establish();
        }

        if (vpnInterface == null) {
            Log.e(TAG, "VPN interface is null. Permission denied?");
            stopSelf();
            return;
        }

        Log.i(TAG, "VPN established.");

        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, createNotification(gamePackage, false));
        }

        runVpnLoop();
    }

    private void runVpnLoop() {
        ParcelFileDescriptor currentInterface = vpnInterface;
        if (currentInterface == null) return;

        try (FileInputStream in = new FileInputStream(currentInterface.getFileDescriptor())) {
            byte[] buffer = new byte[4096];
            while (isRunning.get() && !Thread.interrupted()) {
                // Drop packets (Blackhole)
                int bytesRead = in.read(buffer);
                if (bytesRead == -1) break;
            }
        } catch (IOException e) {
            Log.w(TAG, "VPN loop stopped: " + e.getMessage());
        } finally {
            Log.i(TAG, "VPN thread finished.");
        }
    }

    private void stopVpn() {
        isRunning.set(false);

        synchronized (this) {
            if (vpnInterface != null) {
                try {
                    vpnInterface.close();
                } catch (IOException e) {
                    Log.e(TAG, "Error closing VPN interface", e);
                }
                vpnInterface = null;
            }
        }

        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
        Log.i(TAG, "VPN stopped.");
    }

    private Notification createNotification(String gamePackage, boolean isInitializing) {
        String channelName = getString(R.string.vpn_channel_name);
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, channelName, NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.vpn_channel_desc));
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }

        Intent notificationIntent = new Intent(this, FeaturesActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE);

        String contentText;
        if (isInitializing) {
            contentText = getString(R.string.vpn_status_starting);
        } else {
            contentText = (gamePackage != null)
                    ? getString(R.string.vpn_status_optimizing, getAppName(gamePackage))
                    : getString(R.string.vpn_status_background_restricted);
        }

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.vpn_notification_title))
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.ic_menu_directions)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private String getAppName(String packageName) {
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(packageName, 0);
            return pm.getApplicationLabel(ai).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return getString(R.string.unknown_game);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopVpn();
        if (executorService != null) {
            executorService.shutdownNow();
        }
    }
}