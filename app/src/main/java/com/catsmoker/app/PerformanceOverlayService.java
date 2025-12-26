package com.catsmoker.app;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.core.app.NotificationCompat;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class PerformanceOverlayService extends Service {

    private static final String TAG = "OverlayService";
    private static final String CHANNEL_ID = "overlay_service_channel";

    // UI Reference
    private ViewGroup overlayView;
    private TextView powerText, cpuText, memText, fpsText, tempText;
    private WindowManager windowManager;
    private Display display;

    // Background Thread
    private HandlerThread bgThread;
    private Handler bgHandler;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private Runnable updateRunnable;

    // Stats Data - Explicitly using java.lang.Process to avoid android.os.Process collision
    private java.lang.Process rootProcess;
    private DataOutputStream rootOut;
    private BufferedReader rootIn;
    private boolean isRooted = false;

    // FPS Tracking
    private String trackedLayer = null;
    private int cachedFps = 0;
    private int zeroFpsRetry = 0;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();

        // 1. Start Foreground Notification
        createNotificationChannel();
        startForeground(999, new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("FPS Monitor Active")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build());

        // 2. Setup the Floating View
        try {
            initOverlay();
        } catch (Exception e) {
            Log.e(TAG, "Overlay Init Failed", e);
            stopSelf();
            return;
        }

        // 3. Start Background Worker
        // Used full path here to avoid import collision
        bgThread = new HandlerThread("OverlayWorker", android.os.Process.THREAD_PRIORITY_DISPLAY);
        bgThread.start();
        bgHandler = new Handler(bgThread.getLooper());

        updateRunnable = () -> {
            try {
                updateMetrics();
            } catch (Exception e) {
                Log.e(TAG, "Update Loop Error", e);
            } finally {
                bgHandler.postDelayed(updateRunnable, 800);
            }
        };

        // 4. Init Root (Async) and start loop
        bgHandler.post(() -> {
            initRootShell();
            bgHandler.post(updateRunnable);
        });
    }

    private void createNotificationChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Overlay", NotificationManager.IMPORTANCE_LOW));
        }
    }

    @SuppressLint("InflateParams")
    private void initOverlay() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        display = windowManager.getDefaultDisplay();

        LayoutInflater inflater = LayoutInflater.from(this);
        overlayView = (ViewGroup) inflater.inflate(R.layout.overlay, null);

        if (overlayView != null) {
            powerText = overlayView.findViewById(R.id.powerNumber);
            cpuText = overlayView.findViewById(R.id.cpuNumber);
            memText = overlayView.findViewById(R.id.memoryNumber);
            fpsText = overlayView.findViewById(R.id.fpsNumber);
            tempText = overlayView.findViewById(R.id.tempNumber);
        }

        // Fixed: Use TYPE_APPLICATION_OVERLAY directly (API 26+)
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 20;
        params.y = 80;

        windowManager.addView(overlayView, params);
    }

    private void initRootShell() {
        try {
            rootProcess = Runtime.getRuntime().exec("su");
            // Check for null before using, though exec usually throws exception instead of returning null
            if (rootProcess != null) {
                rootOut = new DataOutputStream(rootProcess.getOutputStream());
                rootIn = new BufferedReader(new InputStreamReader(rootProcess.getInputStream()));

                // Validate Root
                rootOut.writeBytes("id\n");
                rootOut.flush();
                String line = rootIn.readLine();
                isRooted = (line != null && line.contains("uid=0"));
            }
        } catch (Exception e) {
            isRooted = false;
        }
    }

    // --- Main Logic Loop ---
    private void updateMetrics() {
        // 1. FPS (Only if Rooted)
        if (isRooted) {
            calculateFps();
        } else {
            cachedFps = 0;
        }

        // 2. Battery / Power
        double watts = 0;
        float temp = 0;
        try {
            BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
            Intent batteryIntent = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (batteryIntent != null) {
                long ua = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
                int uv = batteryIntent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 3700);
                watts = Math.abs((ua / 1000000.0) * (uv / 1000.0));
                temp = batteryIntent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0f;
            }
        } catch (Exception ignored) {}

        // 3. CPU & RAM
        int cpu = getCpuUsage();
        int ram = 0;
        try {
            ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am != null) {
                am.getMemoryInfo(memInfo);
                ram = (int) ((memInfo.totalMem - memInfo.availMem) * 100 / memInfo.totalMem);
            }
        } catch (Exception ignored) {}

        // 4. Update UI
        String sPwr = String.format(Locale.US, "PWR: %.1f W", watts);
        String sCpu = String.format(Locale.US, "CPU: %d%%", cpu);
        String sRam = String.format(Locale.US, "RAM: %d%%", ram);
        String sTmp = String.format(Locale.US, "TMP: %.1f C", temp);
        String sFps = String.format(Locale.US, "%d FPS / %.0f Hz", cachedFps, display.getRefreshRate());

        uiHandler.post(() -> {
            if (overlayView != null && overlayView.isAttachedToWindow()) {
                if (powerText != null) powerText.setText(sPwr);
                if (cpuText != null) cpuText.setText(sCpu);
                if (memText != null) memText.setText(sRam);
                if (tempText != null) tempText.setText(sTmp);
                if (fpsText != null) fpsText.setText(sFps);
            }
        });
    }

    // --- FPS Logic ---
    private void calculateFps() {
        if (rootOut == null) return;

        int fps = -1;
        // Check current target
        if (trackedLayer != null) {
            fps = getFpsForLayer(trackedLayer);
        }

        // Lost signal logic
        if (fps <= 0) {
            zeroFpsRetry++;
            // If we have 0 FPS for > 2 seconds (was 3 loops approx) OR no layer yet
            if (zeroFpsRetry > 3 || trackedLayer == null) {
                String newLayer = findActiveLayer();
                if (newLayer != null) {
                    trackedLayer = newLayer;
                    fps = getFpsForLayer(newLayer);
                    zeroFpsRetry = 0;
                }
            }
        } else {
            zeroFpsRetry = 0;
        }
        cachedFps = Math.max(0, fps);
    }

    private String findActiveLayer() {
        List<String> allLayers = getRawLayers();
        if (allLayers.isEmpty()) return null;

        String focusedPkg = getFocusedPackage();

        // 1. Try to find a moving SurfaceView belonging to the focused App
        if (focusedPkg != null) {
            for (String layer : allLayers) {
                if (layer.contains(focusedPkg) && layer.contains("SurfaceView") && !layer.contains("com.catsmoker.app")) {
                    if (getFpsForLayer(layer) > 0) return layer;
                }
            }
        }

        // 2. Fallback: Find *any* moving layer
        for (String layer : allLayers) {
            if (layer.contains("com.catsmoker.app")) continue; // Skip self
            if (getFpsForLayer(layer) > 0) return layer;
        }

        return null; // Nothing moving
    }

    private int getFpsForLayer(String layerName) {
        try {
            if (rootOut == null) return 0;
            // Clear buffer
            while(rootIn.ready()) rootIn.readLine();

            // Send CMD
            rootOut.writeBytes("dumpsys SurfaceFlinger --latency \"" + layerName + "\"\n");
            // Add sentinel
            rootOut.writeBytes("echo STOP_LATENCY\n");
            rootOut.flush();

            String line = rootIn.readLine(); // Ignore refresh period
            if (line == null) return 0;

            long nowNs = System.nanoTime();
            long threshold = nowNs - 1_000_000_000L;
            int count = 0;

            while ((line = rootIn.readLine()) != null) {
                if (line.contains("STOP_LATENCY")) break;
                if (line.trim().isEmpty()) continue;

                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 2) {
                    try {
                        long t = Long.parseLong(parts[1]);
                        if (t > threshold && t != Long.MAX_VALUE) count++;
                    } catch (NumberFormatException ignored) {}
                }
            }
            return count;
        } catch (Exception e) { return 0; }
    }

    private List<String> getRawLayers() {
        List<String> list = new ArrayList<>();
        try {
            while(rootIn.ready()) rootIn.readLine();
            rootOut.writeBytes("dumpsys SurfaceFlinger --list\n");
            rootOut.writeBytes("echo STOP_LIST\n");
            rootOut.flush();
            String line;
            while ((line = rootIn.readLine()) != null) {
                if (line.contains("STOP_LIST")) break;
                if (!line.trim().isEmpty() && !line.equals("Output Layer")) {
                    list.add(line.trim());
                }
            }
        } catch (Exception ignored) {}
        return list;
    }

    // Uses Dumpsys instead of grep to prevent blocking
    private String getFocusedPackage() {
        try {
            while(rootIn.ready()) rootIn.readLine();
            rootOut.writeBytes("dumpsys window windows\n");
            rootOut.writeBytes("echo STOP_WIN\n");
            rootOut.flush();

            String line;
            String foundPkg = null;
            while ((line = rootIn.readLine()) != null) {
                if (line.contains("STOP_WIN")) break;
                if (line.contains("mCurrentFocus") && line.contains("u0")) {
                    int start = line.indexOf("u0 ");
                    int slash = line.indexOf("/");
                    if (start > -1 && slash > start) {
                        foundPkg = line.substring(start + 3, slash).trim();
                    }
                }
            }
            return foundPkg;
        } catch (Exception ignored) { return null; }
    }

    // --- CPU Utils ---
    private int getCpuUsage() {
        try {
            File[] files = new File("/sys/devices/system/cpu/").listFiles(f -> f.getName().matches("cpu[0-9]+"));
            if (files == null) return 0;
            int sum = 0, count = 0;
            for (File f : files) {
                int min = readInt(f + "/cpufreq/cpuinfo_min_freq");
                int max = readInt(f + "/cpufreq/cpuinfo_max_freq");
                int cur = readInt(f + "/cpufreq/scaling_cur_freq");
                if (max > 0) {
                    sum += (cur - min) * 100 / (max - min);
                    count++;
                }
            }
            return count > 0 ? sum / count : 0;
        } catch (Exception e) { return 0; }
    }

    private int readInt(String path) {
        try (RandomAccessFile r = new RandomAccessFile(path, "r")) {
            String l = r.readLine();
            return l != null ? Integer.parseInt(l) : 0;
        } catch (Exception e) { return 0; }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (overlayView != null) windowManager.removeView(overlayView);
        if (rootProcess != null) rootProcess.destroy();
        if (bgThread != null) bgThread.quitSafely();
    }
}