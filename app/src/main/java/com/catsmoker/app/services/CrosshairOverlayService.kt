package com.catsmoker.app.services

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import com.catsmoker.app.R

class CrosshairOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var crosshairView: ImageView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startForegroundService()
        sendBroadcast(Intent(ACTION_CROSSHAIR_SERVICE_STARTED))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            if (ACTION_STOP == intent.action) {
                stopSelf()
                return START_NOT_STICKY
            }
            val scopeResourceId = intent.getIntExtra(EXTRA_SCOPE_RESOURCE_ID, R.drawable.scope2)
            setupOverlay(scopeResourceId)
        }
        return START_NOT_STICKY
    }

    private fun startForegroundService() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Crosshair Overlay",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)

        val stopIntent = Intent(this, CrosshairOverlayService::class.java).apply {
            action = ACTION_STOP
        }
        
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        
        val pendingStopIntent = PendingIntent.getService(this, 0, stopIntent, pendingIntentFlags)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Crosshair is active")
            .setSmallIcon(R.drawable.icon)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", pendingStopIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupOverlay(scopeResourceId: Int) {
        removeOverlayView()

        // Fix for "does not override performClick": Subclass ImageView
        crosshairView = object : androidx.appcompat.widget.AppCompatImageView(this) {
        }.apply {
            setImageResource(scopeResourceId)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        val sizePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            60f,
            resources.displayMetrics
        ).toInt()

        val layoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

        layoutParams = WindowManager.LayoutParams(
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            width = sizePx
            height = sizePx
            gravity = Gravity.TOP or Gravity.START
            
            val center = screenCenter
            x = center.x - (sizePx / 2)
            y = center.y - (sizePx / 2)
        }

        crosshairView?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        layoutParams?.let {
                            initialX = it.x
                            initialY = it.y
                        }
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        layoutParams?.let {
                            it.x = initialX + (event.rawX - initialTouchX).toInt()
                            it.y = initialY + (event.rawY - initialTouchY).toInt()
                            try {
                                windowManager?.updateViewLayout(crosshairView, it)
                            } catch (_: IllegalArgumentException) {}
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        v.performClick()
                        return true
                    }
                }
                return false
            }
        })

        try {
            windowManager?.addView(crosshairView, layoutParams)
            Log.d(TAG, "Overlay added successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error adding overlay view", e)
        }
    }

    @Suppress("DEPRECATION")
    private val screenCenter: Point
        get() {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val metrics = windowManager?.currentWindowMetrics
                val bounds = metrics?.bounds
                Point((bounds?.width() ?: 0) / 2, (bounds?.height() ?: 0) / 2)
            } else {
                val display = windowManager?.defaultDisplay
                val size = Point()
                display?.getRealSize(size)
                Point(size.x / 2, size.y / 2)
            }
        }

    private fun removeOverlayView() {
        if (crosshairView != null) {
            try {
                if (crosshairView?.isAttachedToWindow == true) {
                    windowManager?.removeView(crosshairView)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error removing overlay view", e)
            }
            crosshairView = null
        }
    }

    override fun onDestroy() {
        isRunning = false
        sendBroadcast(Intent(ACTION_CROSSHAIR_SERVICE_STARTED).apply {
            action = ACTION_CROSSHAIR_SERVICE_STOPPED
        })
        removeOverlayView()
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping foreground", e)
        }
        super.onDestroy()
    }

    companion object {
        var isRunning = false
        private const val TAG = "CrosshairService"
        private const val NOTIFICATION_ID = 1337
        private const val CHANNEL_ID = "CrosshairServiceChannel"
        private const val ACTION_STOP = "com.catsmoker.app.ACTION_STOP"

        const val ACTION_CROSSHAIR_SERVICE_STARTED = "com.catsmoker.app.CROSSHAIR_SERVICE_STARTED"
        const val ACTION_CROSSHAIR_SERVICE_STOPPED = "com.catsmoker.app.CROSSHAIR_SERVICE_STOPPED"
        const val EXTRA_SCOPE_RESOURCE_ID = "scope_resource_id"
    }
}
