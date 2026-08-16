package com.catsmoker.app.features.gamingtools.tools.booster

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.catsmoker.app.R
import com.catsmoker.app.features.gamingtools.engine.GamingEngine
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class AppBoosterService : Service() {

    @Inject
    lateinit var gamingEngine: GamingEngine

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var workJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopWorkAndSelf()
            return START_NOT_STICKY
        }

        val mode = intent?.getStringExtra(EXTRA_MODE) ?: "speed-profile"
        val force = intent?.getBooleanExtra(EXTRA_FORCE, false) ?: false

        startWork(mode, force)
        
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.booster_running_title))
            .setContentText(getString(R.string.booster_running_desc))
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        return START_NOT_STICKY
    }

    private fun startWork(mode: String, force: Boolean) {
        workJob?.cancel()
        workJob = serviceScope.launch {
            gamingEngine.runArtOptimization(mode, force)
            stopSelf()
        }
    }

    private fun stopWorkAndSelf() {
        workJob?.cancel()
        serviceScope.launch {
            gamingEngine.cancelArtOptimization()
            stopSelf()
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "App Booster", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_STOP = "com.catsmoker.app.ACTION_STOP_BOOSTER"
        const val EXTRA_MODE = "mode"
        const val EXTRA_FORCE = "force"
        private const val CHANNEL_ID = "app_booster_channel"
        private const val NOTIFICATION_ID = 101

        fun startIntent(context: Context, mode: String, force: Boolean): Intent {
            return Intent(context, AppBoosterService::class.java).apply {
                putExtra(EXTRA_MODE, mode)
                putExtra(EXTRA_FORCE, force)
            }
        }

        fun stopIntent(context: Context): Intent {
            return Intent(context, AppBoosterService::class.java).apply {
                action = ACTION_STOP
            }
        }
    }
}
