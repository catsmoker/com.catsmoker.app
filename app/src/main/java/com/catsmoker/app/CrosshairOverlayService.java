package com.catsmoker.app;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.os.Build;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.ImageView;
import androidx.core.app.NotificationCompat;

public class CrosshairOverlayService extends Service {

    private static final String TAG = "CrosshairService";
    private WindowManager windowManager;
    private ImageView crosshairView;
    private WindowManager.LayoutParams params;
    private int initialX, initialY;
    private float initialTouchX, initialTouchY;

    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "CrosshairServiceChannel";
    public static final String EXTRA_SCOPE_RESOURCE_ID = "scope_resource_id";

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service created");
        startForegroundService();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Service started with startId: " + startId);

        int scopeResourceId = intent.getIntExtra(EXTRA_SCOPE_RESOURCE_ID, R.drawable.scope2); // Default to scope2
        setupOverlay(scopeResourceId);

        return START_NOT_STICKY;
    }

    @SuppressLint("ForegroundServiceType")
    private void startForegroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Crosshair Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Crosshair Overlay")
                .setContentText("Crosshair is active")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build();

        startForeground(NOTIFICATION_ID, notification);
        Log.d(TAG, "Foreground service started");
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupOverlay(int scopeResourceId) {
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        if (crosshairView != null){
            windowManager.removeView(crosshairView);
        }
        crosshairView = new ImageView(this);
        crosshairView.setImageResource(scopeResourceId);

        int crosshairSize = 165;
        int layoutType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                WindowManager.LayoutParams.TYPE_PHONE;

        params = new WindowManager.LayoutParams(
                crosshairSize,
                crosshairSize,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        Point center = getScreenCenter();
        params.x = center.x - crosshairSize /2;
        params.y = center.y - crosshairSize /2;


        windowManager.addView(crosshairView, params);
        Log.d(TAG, "Overlay added");
    }

    private Point getScreenCenter() {
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getMetrics(metrics);

        int centerX = metrics.widthPixels / 2;
        int centerY = metrics.heightPixels / 2;

        return new Point(centerX, centerY);
    }

    @Override
    public void onDestroy() {
        if (crosshairView != null) {
            windowManager.removeView(crosshairView);
            crosshairView = null;
            Log.d(TAG, "Overlay removed");
        }
        stopForeground(true);
        Log.d(TAG, "Service destroyed");
        super.onDestroy();
    }
}