package com.catsmoker.app.features.gamingtools.tools.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.catsmoker.app.R
import com.catsmoker.app.features.gamingtools.engine.GamingEngine
import com.catsmoker.app.features.gamingtools.tools.graphics.AutoForceStopManager
import com.catsmoker.app.system.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class AutoForceStopService : Service() {

    @Inject
    lateinit var gamingEngine: GamingEngine

    @Inject
    lateinit var manager: AutoForceStopManager

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)
    private var pollingJob: Job? = null

    companion object {
        private const val CHANNEL_ID = "auto_force_stop_channel"
        private const val NOTIF_ID = 4201
        private const val POLL_INTERVAL_MS = 2000L

        fun start(context: Context) {
            val intent = Intent(context, AutoForceStopService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AutoForceStopService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (pollingJob?.isActive != true) {
            pollingJob = scope.launch { pollLoop() }
        }
        return START_STICKY
    }

    private suspend fun pollLoop() {
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        var previousForegroundPackage: String? = null
        var lastEventTime = System.currentTimeMillis() - POLL_INTERVAL_MS

        while (true) {
            delay(POLL_INTERVAL_MS)
            val selected = manager.getSelectedPackages()
            if (selected.isEmpty()) {
                stopSelf()
                return
            }

            val now = System.currentTimeMillis()
            val current = queryLatestForegroundPackage(usageStatsManager, lastEventTime, now)
            lastEventTime = now

            if (current != null && current != previousForegroundPackage) {
                val left = previousForegroundPackage
                if (left != null && left != packageName && selected.contains(left)) {
                    scope.launch(Dispatchers.IO) {
                        gamingEngine.execute("am force-stop $left")
                    }
                }
                previousForegroundPackage = current
            }
        }
    }

    private fun queryLatestForegroundPackage(usm: UsageStatsManager, begin: Long, end: Long): String? {
        val events = usm.queryEvents(begin, end)
        var latest: String? = null
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                latest = event.packageName
            }
        }
        return latest
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(CHANNEL_ID, "Auto Force Stop", NotificationManager.IMPORTANCE_LOW)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): android.app.Notification {
        val pendingIntent = android.app.PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), android.app.PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Auto Force Stop Active")
            .setContentText("Monitoring background applications...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    override fun onDestroy() {
        pollingJob?.cancel()
        job.cancel()
        super.onDestroy()
    }
}
