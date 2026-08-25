package com.catsmoker.app.features.gamingtools.tools.overlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.catsmoker.app.R
import com.catsmoker.app.features.main.engine.MetricsEngine
import com.catsmoker.app.shared.data.model.FpsSource
import com.catsmoker.app.shared.data.model.MetricReadStatus
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class PerformanceOverlayService : Service() {

    @Inject
    lateinit var metricsEngine: MetricsEngine

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Performance Monitor Active")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .build()
        startForeground(NOTIFICATION_ID, notification)

        showOverlay()
        metricsEngine.start()
        startUpdating()
        
        isRunning = true
        return START_NOT_STICKY
    }

    private fun showOverlay() {
        @android.annotation.SuppressLint("InflateParams")
        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_performance, null)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 100
        }
        windowManager?.addView(overlayView, params)
    }

    private fun startUpdating() {
        serviceScope.launch {
            val fpsView = overlayView?.findViewById<TextView>(R.id.fpsNumber)
            val cpuView = overlayView?.findViewById<TextView>(R.id.cpuNumber)
            val powerView = overlayView?.findViewById<TextView>(R.id.powerNumber)
            val ramView = overlayView?.findViewById<TextView>(R.id.ramNumber)
            val tempView = overlayView?.findViewById<TextView>(R.id.tempNumber)

            metricsEngine.state.collect { state ->
                // Frames per second, labelled by where the number came from: the vsync fallback
                // measures this app's frames, not the game's, and must not be passed off as FPS.
                val fpsLabel = if (state.fpsSource == FpsSource.Choreographer) "UI FPS" else "FPS"
                fpsView?.text = row(fpsLabel, state.fps?.toString(), state.fpsReadStatus)
                cpuView?.text = row("CPU", state.cpuPercentage?.let { "$it%" }, state.cpuReadStatus)
                powerView?.text = row(
                    "Power",
                    state.powerW?.let { String.format(Locale.US, "%.2f W", it) },
                    state.powerReadStatus
                )
                ramView?.text = row(
                    "RAM",
                    state.ramUsedGb?.let { used ->
                        val total = state.ramTotalGb
                        if (total != null) {
                            String.format(Locale.US, "%.1f / %.1f GB", used, total)
                        } else {
                            String.format(Locale.US, "%.1f GB", used)
                        }
                    },
                    state.ramReadStatus
                )
                tempView?.text = row(
                    if (state.displayTempIsSoc) "SoC" else "Batt",
                    state.displayTempC?.let { String.format(Locale.US, "%.1f°C", it) },
                    state.displayTempReadStatus
                )
            }
        }
    }

    /**
     * One overlay line.
     *
     * With no [value] the metric's own reason is printed — "needs root/Shizuku", "unsupported" —
     * because a 0 or a dash would claim a reading the device never gave.
     */
    private fun row(label: String, value: String?, status: MetricReadStatus): String =
        "$label: ${value ?: status.label}"

    override fun onDestroy() {
        if (overlayView != null) {
            windowManager?.removeView(overlayView)
        }
        metricsEngine.stop()
        serviceScope.cancel()
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Performance Overlay", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    companion object {
        var isRunning = false
        private const val CHANNEL_ID = "perf_overlay_channel"
        private const val NOTIFICATION_ID = 104
    }
}
