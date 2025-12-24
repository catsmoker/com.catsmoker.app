package com.catsmoker.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class GameVpnService extends VpnService {
    private static final String TAG = "GameVpnService";
    private ParcelFileDescriptor vpnInterface = null;
    private Thread vpnThread;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    public static final String ACTION_CONNECT = "com.catsmoker.app.action.CONNECT";
    public static final String ACTION_DISCONNECT = "com.catsmoker.app.action.DISCONNECT";
    public static final String EXTRA_GAME_PACKAGE = "com.catsmoker.app.extra.GAME_PACKAGE";

    private static final int NOTIFICATION_ID = 1337;
    private static final String CHANNEL_ID = "GameVpnChannel";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_CONNECT.equals(action)) {
                String gamePackage = intent.getStringExtra(EXTRA_GAME_PACKAGE);
                startVpn(gamePackage);
                return START_STICKY;
            } else if (ACTION_DISCONNECT.equals(action)) {
                stopVpn();
                return START_NOT_STICKY;
            }
        }
        return START_NOT_STICKY;
    }

    private void startVpn(String gamePackage) {
        if (vpnInterface != null || isRunning.get()) {
            return;
        }

        // 1. Start Foreground Service (Required for Android 8+)
        startForeground(NOTIFICATION_ID, createNotification(gamePackage));

        try {
            VpnService.Builder builder = new VpnService.Builder();
            builder.setSession("Game Booster VPN");
            builder.setMtu(1500);

            // IPv4
            builder.addAddress("10.0.0.2", 24);
            builder.addRoute("0.0.0.0", 0);

            // IPv6 (Prevent leaks)
            builder.addAddress("fd00::1", 128);
            builder.addRoute("::", 0);

            // --- Custom DNS Logic ---
            SharedPreferences dnsPrefs = getSharedPreferences(GameLauncherActivity.DNS_PREFS, MODE_PRIVATE);
            String customDns = dnsPrefs.getString(GameLauncherActivity.KEY_CUSTOM_DNS, "");

            if (!TextUtils.isEmpty(customDns)) {
                String[] dnsServers = customDns.split(",");
                for (String dns : dnsServers) {
                    try {
                        InetAddress address = InetAddress.getByName(dns.trim());
                        builder.addDnsServer(address);
                    } catch (Exception e) {
                        Log.e(TAG, "Invalid DNS server: " + dns, e);
                    }
                }
            }

            // --- Split Tunneling Logic ---
            // Goal: Allow 'gamePackage' to use real internet, force everyone else into this VPN (which drops packets)
            if (gamePackage != null && !gamePackage.isEmpty()) {
                // API 29 (Q) is definitely > 27, so we keep this specific check
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        builder.addDisallowedApplication(gamePackage);
                    } catch (PackageManager.NameNotFoundException e) {
                        Log.e(TAG, "Package not found to disallow: " + gamePackage);
                    }
                } else {
                    // Legacy API: Must explicitly add everyone else to the VPN
                    PackageManager pm = getPackageManager();
                    List<ApplicationInfo> packages = pm.getInstalledApplications(PackageManager.GET_META_DATA);
                    for (ApplicationInfo app : packages) {
                        if (!app.packageName.equals(gamePackage) && !app.packageName.equals(getPackageName())) {
                            try {
                                builder.addAllowedApplication(app.packageName);
                            } catch (Exception e) {
                                // Ignore specific package errors
                            }
                        }
                    }
                }
            }

            Intent configureIntent = new Intent(this, GameLauncherActivity.class);
            // SDK check for IMMUTABLE removed as API 27 supports it
            PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, configureIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.setConfigureIntent(pendingIntent);

            vpnInterface = builder.establish();
            if (vpnInterface == null) {
                Log.e(TAG, "VPN interface is null. Permission denied?");
                stopSelf();
                return;
            }

            Log.i(TAG, "VPN established. Blocking background data for others.");
            isRunning.set(true);

            vpnThread = new Thread(this::runVpnLoop, "GameVpnThread");
            vpnThread.start();

        } catch (Exception e) {
            Log.e(TAG, "Error starting VPN", e);
            stopVpn();
        }
    }

    private void runVpnLoop() {
        try (FileInputStream in = new FileInputStream(vpnInterface.getFileDescriptor())) {
            byte[] buffer = new byte[4096];
            while (isRunning.get() && !Thread.interrupted()) {
                // Drain the queue (block internet)
                int bytesRead = in.read(buffer);
                if (bytesRead == -1) {
                    break;
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "VPN loop stopped or error occurred: " + e.getMessage());
        } finally {
            Log.i(TAG, "VPN thread finished.");
        }
    }

    private void stopVpn() {
        isRunning.set(false);

        if (vpnThread != null) {
            vpnThread.interrupt();
            vpnThread = null;
        }

        if (vpnInterface != null) {
            try {
                vpnInterface.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing VPN interface", e);
            }
            vpnInterface = null;
        }

        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
        Log.i(TAG, "VPN stopped.");
    }

    private Notification createNotification(String gamePackage) {
        String channelName = "Game Booster Service";
        // SDK check removed as NotificationChannel is mandatory for API 27+
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, channelName, NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Optimizing network for games");
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }

        Intent notificationIntent = new Intent(this, GameLauncherActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE);

        String contentText = (gamePackage != null)
                ? "Optimizing network for " + getAppName(gamePackage)
                : "Background data restricted";

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Game Booster Active")
                .setContentText(contentText)
                // Ensure you have an icon named ic_launcher or similar in drawable/mipmap
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
            return "Game";
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopVpn();
    }
}