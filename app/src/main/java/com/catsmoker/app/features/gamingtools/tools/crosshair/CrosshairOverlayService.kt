package com.catsmoker.app.features.gamingtools.tools.crosshair

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import com.catsmoker.app.R

class CrosshairOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val scopeAsset = intent?.getStringExtra("scope_asset_name") ?: "scope2.png"
        
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Crosshair Active")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()
        startForeground(NOTIFICATION_ID, notification)

        showOverlay(scopeAsset)
        
        isRunning = true
        sendBroadcast(Intent(ACTION_CROSSHAIR_SERVICE_STARTED))
        
        return START_NOT_STICKY
    }

    private fun showOverlay(assetName: String) {
        if (overlayView != null) {
            windowManager?.removeView(overlayView)
        }

        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_crosshair, null)
        val image = overlayView?.findViewById<ImageView>(R.id.crosshair_image)
        
        try {
            assets.open("crosshair/$assetName").use { input ->
                val bitmap = BitmapFactory.decodeStream(input)
                image?.setImageBitmap(bitmap)
            }
        } catch (_: Exception) {}

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        windowManager?.addView(overlayView, params)
    }

    override fun onDestroy() {
        if (overlayView != null) {
            windowManager?.removeView(overlayView)
        }
        isRunning = false
        sendBroadcast(Intent(ACTION_CROSSHAIR_SERVICE_STOPPED))
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Crosshair Overlay", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    companion object {
        var isRunning = false
        const val ACTION_CROSSHAIR_SERVICE_STARTED = "com.catsmoker.app.CROSSHAIR_STARTED"
        const val ACTION_CROSSHAIR_SERVICE_STOPPED = "com.catsmoker.app.CROSSHAIR_STOPPED"
        private const val CHANNEL_ID = "crosshair_channel"
        private const val NOTIFICATION_ID = 102
    }
}
