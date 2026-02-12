package com.catsmoker.app.core

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.catsmoker.app.R
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

class PerformanceOverlayService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main)
    private var updateJob: Job? = null

    // UI Reference
    private var windowManager: WindowManager? = null
    private var display: Display? = null
    private var overlayView: android.view.ViewGroup? = null
    private var powerText: TextView? = null
    private var cpuText: TextView? = null
    private var memText: TextView? = null
    private var fpsText: TextView? = null
    private var tempText: TextView? = null

    // State
    private var isRooted = false
    private var trackedLayer: String? = null
    private var cachedFps = 0
    private var zeroFpsRetry = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true

        createNotificationChannel()
        startForeground(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("FPS Monitor Active")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build()
        )

        try {
            initOverlay()
        } catch (e: Exception) {
            Log.e(TAG, "Overlay Init Failed", e)
            stopSelf()
            return
        }

        startUpdateLoop()
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Overlay", NotificationManager.IMPORTANCE_LOW)
        )
    }

    @Suppress("DEPRECATION")
    private fun initOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // display is a property of Context in API 30+
            try { this.display } catch (_: NoSuchMethodError) { windowManager?.defaultDisplay }
        } else {
            windowManager?.defaultDisplay
        }

        val inflater = LayoutInflater.from(this)
        // Avoid passing null to inflate to resolve layout params correctly
        val root = android.widget.FrameLayout(this)
        overlayView = inflater.inflate(R.layout.overlay_performance, root, false) as android.view.ViewGroup?

        overlayView?.let {
            powerText = it.findViewById(R.id.powerNumber)
            cpuText = it.findViewById(R.id.cpuNumber)
            memText = it.findViewById(R.id.memoryNumber)
            fpsText = it.findViewById(R.id.fpsNumber)
            tempText = it.findViewById(R.id.tempNumber)
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 80
        }

        windowManager?.addView(overlayView, params)
    }

    private fun startUpdateLoop() {
        updateJob = serviceScope.launch(Dispatchers.IO) {
            // Check root once
            isRooted = try { Shell.getShell().isRoot } catch (_: Exception) { false }

            while (isActive) {
                val metrics = collectMetrics()
                withContext(Dispatchers.Main) {
                    updateUI(metrics)
                }
                delay(800)
            }
        }
    }

    data class Metrics(
        val watts: Double,
        val temp: Float,
        val cpu: Int,
        val ram: Int,
        val fps: Int,
        val refreshRate: Float
    )

    private fun collectMetrics(): Metrics {
        // 1. FPS
        if (isRooted) {
            calculateFps()
        } else {
            cachedFps = 0
        }

        // 2. Battery / Power
        var watts = 0.0
        var temp = 0f
        try {
            val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
            val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (batteryIntent != null) {
                val ua = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                val uv = batteryIntent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 3700)
                watts = abs((ua / 1000000.0) * (uv / 1000.0))
                temp = batteryIntent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0f
            }
        } catch (_: Exception) {}

        // 3. CPU & RAM
        val cpu = cpuUsage
        var ram = 0
        try {
            val memInfo = ActivityManager.MemoryInfo()
            val am = getSystemService(ACTIVITY_SERVICE) as? ActivityManager
            am?.getMemoryInfo(memInfo)
            if (memInfo.totalMem > 0) {
                ram = ((memInfo.totalMem - memInfo.availMem) * 100 / memInfo.totalMem).toInt()
            }
        } catch (_: Exception) {}

        val refreshRate = display?.mode?.refreshRate ?: 60f

        return Metrics(watts, temp, cpu, ram, cachedFps, refreshRate)
    }

    private fun updateUI(metrics: Metrics) {
        if (overlayView?.isAttachedToWindow == true) {
            powerText?.text = String.format(Locale.US, "PWR: %.1f W", metrics.watts)
            cpuText?.text = String.format(Locale.US, "CPU: %d%%", metrics.cpu)
            memText?.text = String.format(Locale.US, "RAM: %d%%", metrics.ram)
            tempText?.text = String.format(Locale.US, "TMP: %.1f C", metrics.temp)
            fpsText?.text = String.format(Locale.US, "%d FPS / %.0f Hz", metrics.fps, metrics.refreshRate)
        }
    }

    // --- FPS Logic with libsu ---
    private fun calculateFps() {
        var fps = -1
        if (trackedLayer != null) {
            fps = getFpsForLayer(trackedLayer)
        }

        if (fps <= 0) {
            zeroFpsRetry++
            if (zeroFpsRetry > 3 || trackedLayer == null) {
                val newLayer = findActiveLayer()
                if (newLayer != null) {
                    trackedLayer = newLayer
                    fps = getFpsForLayer(newLayer)
                    zeroFpsRetry = 0
                }
            }
        } else {
            zeroFpsRetry = 0
        }
        cachedFps = max(0, fps)
    }

    private fun findActiveLayer(): String? {
        val allLayers = rawLayers
        val focusedPkg = focusedPackage

        if (focusedPkg != null) {
            for (layer in allLayers) {
                if (layer.contains(focusedPkg) && layer.contains("SurfaceView") 
                    && !layer.contains("com.catsmoker.app")) {
                    if (getFpsForLayer(layer) > 0) return layer
                }
            }
        }

        for (layer in allLayers) {
            if (layer.contains("com.catsmoker.app")) continue
            if (getFpsForLayer(layer) > 0) return layer
        }
        return null
    }

    private fun getFpsForLayer(layerName: String?): Int {
        if (layerName == null) return 0
        try {
            // Using Shell.cmd for root commands
            val result = Shell.cmd(
                "dumpsys SurfaceFlinger --latency \"$layerName\""
            ).exec()

            if (!result.isSuccess) return 0
            val lines = result.out

            if (lines.isEmpty()) return 0

            val nowNs = System.nanoTime()
            val threshold = nowNs - 1000000000L
            var count = 0

            // Skip refresh period line (first line)
            for (i in 1 until lines.size) {
                val line = lines[i].trim()
                if (line.isEmpty()) continue

                val parts = line.split("\\s+".toRegex())
                if (parts.size >= 2) {
                    try {
                        val t = parts[1].toLong()
                        if (t > threshold && t != Long.MAX_VALUE) count++
                    } catch (_: NumberFormatException) {}
                }
            }
            return count
        } catch (_: Exception) {
            return 0
        }
    }

    private val rawLayers: List<String>
        get() {
            val result = Shell.cmd("dumpsys SurfaceFlinger --list").exec()
            return if (result.isSuccess) {
                result.out.filter { it.isNotBlank() && it != "Output Layer" }
            } else {
                emptyList()
            }
        }

    private val focusedPackage: String?
        get() {
            val result = Shell.cmd("dumpsys window windows").exec()
            if (!result.isSuccess) return null
            
            for (line in result.out) {
                if (line.contains("mCurrentFocus") && line.contains("u0")) {
                    val start = line.indexOf("u0 ")
                    val slash = line.indexOf("/")
                    if (start > -1 && slash > start) {
                        return line.substring(start + 3, slash).trim()
                    }
                }
            }
            return null
        }

    private val cpuUsage: Int
        get() {
            try {
                val dir = File("/sys/devices/system/cpu/")
                val files = dir.listFiles { f -> f.name.matches(Regex("cpu[0-9]+")) } ?: return 0
                var sum = 0
                var count = 0
                for (f in files) {
                    val min = readInt("${f.absolutePath}/cpufreq/cpuinfo_min_freq")
                    val max = readInt("${f.absolutePath}/cpufreq/cpuinfo_max_freq")
                    val cur = readInt("${f.absolutePath}/cpufreq/scaling_cur_freq")
                    if (max > 0) {
                        sum += (cur - min) * 100 / (max - min)
                        count++
                    }
                }
                return if (count > 0) sum / count else 0
            } catch (_: Exception) {
                return 0
            }
        }

    private fun readInt(path: String): Int {
        return try {
            RandomAccessFile(path, "r").use {
                it.readLine()?.toIntOrNull() ?: 0
            }
        } catch (_: Exception) {
            0
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        updateJob?.cancel()
        if (overlayView != null) {
            try {
                windowManager?.removeView(overlayView)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing view", e)
            }
        }
    }

    companion object {
        var isRunning = false
        private const val TAG = "OverlayService"
        private const val CHANNEL_ID = "overlay_service_channel"
        private const val NOTIFICATION_ID = 999
    }
}



