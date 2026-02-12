package com.catsmoker.app.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageStatsManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.TreeMap

class PlayTimeTrackerService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main)
    private var trackingJob: Job? = null
    
    private var trackedPackageName: String? = null
    private var sessionPlayTime: Long = 0
    private var vpnEnabledForSession = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null && intent.hasExtra(EXTRA_PACKAGE_NAME)) {
            trackedPackageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
            vpnEnabledForSession = intent.getBooleanExtra(EXTRA_VPN_ENABLED, false)

            sessionPlayTime = 0

            val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("Game Booster Running")
                .setContentText("Tracking play time for ${getAppName(trackedPackageName ?: "Unknown")}")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()

            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, 0)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            startTracking()
        } else {
            stopSelf()
        }
        return START_STICKY
    }

    private fun startTracking() {
        trackingJob?.cancel()
        trackingJob = serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                if (isPackageInForeground) {
                    sessionPlayTime += (CHECK_INTERVAL_MS / 1000)
                    Log.d(TAG, "Play time for $trackedPackageName: ${sessionPlayTime}s")
                    delay(CHECK_INTERVAL_MS)
                } else {
                    Log.i(TAG, "Game no longer in foreground. Stopping tracking.")
                    stopTrackingAndSelf()
                    break
                }
            }
        }
    }

    private fun stopTrackingAndSelf() {
        trackingJob?.cancel()
        savePlayTime()

        if (vpnEnabledForSession) {
            val vpnIntent = Intent(this, GameVpnService::class.java).apply {
                action = GameVpnService.ACTION_DISCONNECT
            }
            startService(vpnIntent)
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private val isPackageInForeground: Boolean
        get() {
            if (trackedPackageName == null) return false

            val usageStatsManager = getSystemService(USAGE_STATS_SERVICE) as? UsageStatsManager ?: return false
            val time = System.currentTimeMillis()
            
            val stats = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                time - (CHECK_INTERVAL_MS * 2),
                time
            )

            if (!stats.isNullOrEmpty()) {
                val sortedMap = TreeMap<Long, android.app.usage.UsageStats>()
                for (usageStats in stats) {
                    sortedMap[usageStats.lastTimeUsed] = usageStats
                }

                if (sortedMap.isNotEmpty()) {
                    return trackedPackageName == sortedMap.lastEntry()?.value?.packageName
                }
            }
            return false
        }

    private fun savePlayTime() {
        if (trackedPackageName != null && sessionPlayTime > 0) {
            val prefs = getSharedPreferences("PlayTime", MODE_PRIVATE)
            val totalPlayTime = prefs.getLong(trackedPackageName, 0) + sessionPlayTime
            prefs.edit { putLong(trackedPackageName, totalPlayTime) }
            Log.i(TAG, "Saved ${sessionPlayTime}s for $trackedPackageName. Total: ${totalPlayTime}s")
        }
    }

    private fun getAppName(packageName: String): String {
        return try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(packageName, 0)
            ).toString()
        } catch (_: Exception) {
            packageName
        }
    }

    override fun onDestroy() {
        // Ensure time is saved if killed
        if (sessionPlayTime > 0) {
            savePlayTime()
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Play Time Tracker",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows when a game is being tracked"
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(serviceChannel)
    }

    companion object {
        const val EXTRA_PACKAGE_NAME = "extra_package_name"
        const val EXTRA_VPN_ENABLED = "extra_vpn_enabled"

        private const val TAG = "PlayTimeTrackerService"
        private const val NOTIFICATION_CHANNEL_ID = "PlayTimeTrackerChannel"
        private const val NOTIFICATION_ID = 1
        private const val CHECK_INTERVAL_MS = 2000L
    }
}


