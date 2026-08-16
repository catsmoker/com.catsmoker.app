package com.catsmoker.app.features.gamingtools.tools.firewall

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class GameVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val gamePkg = intent.getStringExtra(EXTRA_GAME_PACKAGE)
                establishVpn(gamePkg)
                _isRunningState.value = true
            }
            ACTION_DISCONNECT -> {
                stopVpn()
                _isRunningState.value = false
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun establishVpn(gamePkg: String?) {
        val builder = Builder()
            .setSession("Catsmoker Firewall")
            .addAddress("10.0.0.2", 24)
            .addDnsServer("8.8.8.8")
            .addRoute("0.0.0.0", 0)

        if (gamePkg != null) {
            try { builder.addAllowedApplication(gamePkg) } catch (_: Exception) {}
        }
        
        vpnInterface = builder.establish()
        
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Network Firewall Active")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .build()
        
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "VPN", NotificationManager.IMPORTANCE_LOW))
        startForeground(103, notification)
    }

    private fun stopVpn() {
        vpnInterface?.close()
        vpnInterface = null
    }

    override fun onDestroy() {
        stopVpn()
        _isRunningState.value = false
        super.onDestroy()
    }

    companion object {
        const val ACTION_CONNECT = "com.catsmoker.app.VPN_CONNECT"
        const val ACTION_DISCONNECT = "com.catsmoker.app.VPN_DISCONNECT"
        const val EXTRA_GAME_PACKAGE = "game_pkg"
        private const val CHANNEL_ID = "vpn_channel"

        private val _isRunningState = MutableStateFlow(false)
        val isRunningState = _isRunningState.asStateFlow()
    }
}
