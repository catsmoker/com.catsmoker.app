package com.catsmoker.app;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.widget.ImageView;

import androidx.core.app.NotificationCompat;

public class CrosshairOverlayService extends Service {

    public static boolean isRunning = false;
    private static final String TAG = "CrosshairService";

    // Notification Constants
    private static final int NOTIFICATION_ID = 1337;
    private static final String CHANNEL_ID = "CrosshairServiceChannel";
    private static final String ACTION_STOP = "com.catsmoker.app.ACTION_STOP";

    // Intent Extras
    public static final String EXTRA_SCOPE_RESOURCE_ID = "scope_resource_id";

    private WindowManager windowManager;
    private ImageView crosshairView;
    private WindowManager.LayoutParams layoutParams;

    @Override
    public IBinder onBind(Intent intent) {
        return null; // Not using bound service pattern
    }

    @Override
    public void onCreate() {
        super.onCreate();
        isRunning = true;
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        startForegroundService();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            // Handle "Stop" action from Notification button
            if (ACTION_STOP.equals(intent.getAction())) {
                stopSelf();
                return START_NOT_STICKY;
            }

            // Get resource ID safely with default fallback
            int scopeResourceId = intent.getIntExtra(EXTRA_SCOPE_RESOURCE_ID, R.drawable.scope2);
            setupOverlay(scopeResourceId);
        }
        return START_NOT_STICKY;
    }

    /**
     * Creates the notification channel and starts the foreground service.
     * Includes a "Stop" action button for better UX.
     */
    @SuppressLint("ForegroundServiceType")
    private void startForegroundService() {
        NotificationManager manager = getSystemService(NotificationManager.class);

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Crosshair Overlay",
                NotificationManager.IMPORTANCE_LOW // Low importance = no sound/vibration
        );
        manager.createNotificationChannel(channel);

        // Create the "Stop" action intent
        Intent stopIntent = new Intent(this, CrosshairOverlayService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent pendingStopIntent = PendingIntent.getService(
                this,
                0,
                stopIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText("Crosshair is active")
                .setSmallIcon(R.drawable.icon) // Ensure you use your app icon here
                .setOngoing(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", pendingStopIntent)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build();

        // Android 14+ requirement regarding foreground service types
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupOverlay(int scopeResourceId) {
        // Remove existing view if service is restarted without being destroyed
        removeOverlayView();

        crosshairView = new ImageView(this);
        crosshairView.setImageResource(scopeResourceId);
        crosshairView.setScaleType(ImageView.ScaleType.FIT_CENTER);

        // Convert 60dp to pixels. This ensures the crosshair is physically consistent
        // across different screen densities (approx same size as your original 165px on high res).
        int sizePx = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                60,
                getResources().getDisplayMetrics()
        );

        int layoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;

        layoutParams = new WindowManager.LayoutParams(
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, // Allows clicks outside view to pass to game
                PixelFormat.TRANSLUCENT
        );
        layoutParams.width = sizePx;
        layoutParams.height = sizePx;

        layoutParams.gravity = Gravity.TOP | Gravity.START;

        // Center the crosshair
        Point center = getScreenCenter();
        layoutParams.x = center.x - (sizePx / 2);
        layoutParams.y = center.y - (sizePx / 2);

        // Add touch listener for dragging
        crosshairView.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float initialTouchX, initialTouchY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = layoutParams.x;
                        initialY = layoutParams.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        layoutParams.x = initialX + (int) (event.getRawX() - initialTouchX);
                        layoutParams.y = initialY + (int) (event.getRawY() - initialTouchY);
                        try {
                            windowManager.updateViewLayout(crosshairView, layoutParams);
                        } catch (IllegalArgumentException e) {
                            // View might have been removed
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                        v.performClick(); // Accessibility compliance
                        return true;
                }
                return false;
            }
        });

        try {
            windowManager.addView(crosshairView, layoutParams);
            Log.d(TAG, "Overlay added successfully");
        } catch (Exception e) {
            Log.e(TAG, "Error adding overlay view", e);
        }
    }

    /**
     * Calculates the center of the screen handling different Android versions API.
     */
    private Point getScreenCenter() {
        int width, height;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowMetrics metrics = windowManager.getCurrentWindowMetrics();
            Rect bounds = metrics.getBounds();
            width = bounds.width();
            height = bounds.height();
        } else {
            // Deprecated for compileSdk 36+, but necessary for API < 30.
            Point size = new Point();
            windowManager.getDefaultDisplay().getRealSize(size);
            width = size.x;
            height = size.y;
        }

        return new Point(width / 2, height / 2);
    }

    private void removeOverlayView() {
        if (crosshairView != null && windowManager != null) {
            try {
                if (crosshairView.isAttachedToWindow()) {
                    windowManager.removeView(crosshairView);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error removing overlay view", e);
            }
            crosshairView = null;
        }
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        removeOverlayView();
        stopForeground(Service.STOP_FOREGROUND_REMOVE); // Updated to non-deprecated API
        Log.d(TAG, "Service destroyed");
        super.onDestroy();
    }
}