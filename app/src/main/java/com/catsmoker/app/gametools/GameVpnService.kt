package com.catsmoker.app.gametools

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.catsmoker.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

class GameVpnService : VpnService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private var vpnJob: Job? = null

    private var vpnInterface: ParcelFileDescriptor? = null
    private val runningState = AtomicBoolean(false)

    override fun onDestroy() {
        super.onDestroy()
        stopVpn()
        serviceScope.cancel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_CONNECT -> {
                val gamePackage = intent.getStringExtra(EXTRA_GAME_PACKAGE)
                startForegroundService(gamePackage)
                startVpnBackground(gamePackage)
                START_STICKY
            }
            ACTION_DISCONNECT -> {
                stopVpn()
                START_NOT_STICKY
            }
            else -> START_NOT_STICKY
        }
    }

    private fun startForegroundService(gamePackage: String?) {
        val notification = createNotification(gamePackage, true)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, 0)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startVpnBackground(gamePackage: String?) {
        if (runningState.get()) return
        runningState.set(true)
        isRunning = true

        vpnJob = serviceScope.launch {
            try {
                configureAndEstablishVpn(gamePackage)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start VPN", e)
                stopVpn()
            }
        }
    }

    @Throws(Exception::class)
    private fun configureAndEstablishVpn(gamePackage: String?) {
        val builder = Builder().apply {
            setSession(getString(R.string.vpn_session_name))
            setMtu(1500)
            addAddress("10.0.0.2", 24)
            addRoute("0.0.0.0", 0)
            addAddress("fd00::1", 128)
            addRoute("::", 0)
        }

        // Custom DNS
        val dnsPrefs = getSharedPreferences(FeaturesActivity.DNS_PREFS, MODE_PRIVATE)
        val customDns = dnsPrefs.getString(FeaturesActivity.KEY_CUSTOM_DNS, "")

        if (!customDns.isNullOrBlank()) {
            customDns.split(",").filter { it.isNotBlank() }.forEach { dns ->
                try {
                    builder.addDnsServer(InetAddress.getByName(dns.trim()))
                } catch (e: Exception) {
                    Log.e(TAG, "Invalid DNS server: $dns", e)
                }
            }
        }

        // Split Tunneling
        if (!gamePackage.isNullOrEmpty()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    builder.addDisallowedApplication(gamePackage)
                } catch (_: PackageManager.NameNotFoundException) {
                    Log.e(TAG, "Package not found to disallow: $gamePackage")
                }
            } else {
                addAllowedAppsExcept(builder, gamePackage)
            }
        }

        val configureIntent = Intent(this, FeaturesActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, configureIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.setConfigureIntent(pendingIntent)

        synchronized(this) {
            vpnInterface?.close()
            vpnInterface = builder.establish()
        }

        if (vpnInterface == null) {
            Log.e(TAG, "VPN interface is null. Permission denied?")
            stopSelf()
            return
        }

        Log.i(TAG, "VPN established.")
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, createNotification(gamePackage, false))
        runVpnLoop()
    }

    private fun addAllowedAppsExcept(builder: Builder, disallowedPackage: String) {
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val activities = packageManager.queryIntentActivities(mainIntent, 0)
        val appPackage = packageName
        for (ri in activities) {
            val targetPackage = ri.activityInfo.packageName
            if (targetPackage != disallowedPackage && targetPackage != appPackage) {
                try {
                    builder.addAllowedApplication(targetPackage)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun runVpnLoop() {
        val currentInterface = vpnInterface ?: return

        try {
            FileInputStream(currentInterface.fileDescriptor).use { input ->
                val buffer = ByteArray(4096)
                while (runningState.get() && serviceScope.isActive) {
                    val bytesRead = input.read(buffer)
                    if (bytesRead == -1) break
                    // Drop packets (Blackhole) - Simply do nothing
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "VPN loop stopped: ${e.message}")
        } finally {
            Log.i(TAG, "VPN thread finished.")
        }
    }

    private fun stopVpn() {
        runningState.set(false)
        isRunning = false
        vpnJob?.cancel()

        synchronized(this) {
            try {
                vpnInterface?.close()
            } catch (e: IOException) {
                Log.e(TAG, "Error closing VPN interface", e)
            }
            vpnInterface = null
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "VPN stopped.")
    }

    private fun createNotification(gamePackage: String?, isInitializing: Boolean): Notification {
        val channelName = getString(R.string.vpn_channel_name)
        val channel = NotificationChannel(
            CHANNEL_ID,
            channelName,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.vpn_channel_desc)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)

        val notificationIntent = Intent(this, FeaturesActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val contentText = if (isInitializing) {
            getString(R.string.vpn_status_starting)
        } else {
            if (gamePackage != null) getString(R.string.vpn_status_optimizing, getAppName(gamePackage))
            else getString(R.string.vpn_status_background_restricted)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.vpn_notification_title))
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun getAppName(packageName: String): String {
        return try {
            val pm = packageManager
            val ai = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(ai).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            getString(R.string.unknown_game)
        }
    }

    companion object {
        private const val TAG = "GameVpnService"
        const val ACTION_CONNECT = "com.catsmoker.app.action.CONNECT"
        const val ACTION_DISCONNECT = "com.catsmoker.app.action.DISCONNECT"
        const val EXTRA_GAME_PACKAGE = "com.catsmoker.app.extra.GAME_PACKAGE"
        @JvmField var isRunning: Boolean = false

        private const val NOTIFICATION_ID = 1337
        private const val CHANNEL_ID = "GameVpnChannel"
    }
}
