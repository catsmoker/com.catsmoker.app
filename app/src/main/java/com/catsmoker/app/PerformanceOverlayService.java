package com.catsmoker.app;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
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
import java.io.FileFilter;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Pattern;

public class PerformanceOverlayService extends android.app.Service {

    private static final String TAG = "PerfOverlayService";

    private ViewGroup overlayView;
    private WindowManager windowManager;
    private Display defaultDisplay;

    private HandlerThread monitoringHandlerThread;
    private Handler monitoringHandler;
    private Runnable monitoringRunnable;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private TextView powerNumberTextView;
    private TextView cpuNumberTextView;
    private TextView memoryNumberTextView;
    private TextView fpsNumberTextView;

    private static final Pattern CPU_PATTERN = Pattern.compile("cpu[0-9]+");

    // --- Notification Constants ---
    private static final String CHANNEL_ID = "PerformanceOverlay";
    private static final int NOTIFICATION_ID = 1;

    // --- Root & FPS Variables ---
    private boolean isRooted = false;
    private java.lang.Process suProcess = null;
    private DataOutputStream suOutputStream = null;
    private BufferedReader suReader = null;

    private int lastCalculatedFps = 0;
    private String gameLayerName = null;
    private int layerCheckCounter = 0;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Performance Overlay")
                .setContentText("Monitoring performance.")
                .setSmallIcon(R.drawable.ic_shield) // Re-using an existing icon
                .build();

        startForeground(NOTIFICATION_ID, notification);

        isRooted = checkRootMethod();

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        defaultDisplay = windowManager.getDefaultDisplay();

        LayoutInflater inflater = (LayoutInflater) getBaseContext().getSystemService(LAYOUT_INFLATER_SERVICE);
        overlayView = (ViewGroup) inflater.inflate(R.layout.overlay, null);

        // Extracted method as requested
        WindowManager.LayoutParams overlayLayoutParams = createLayoutParams();

        powerNumberTextView = overlayView.findViewById(R.id.powerNumber);
        cpuNumberTextView = overlayView.findViewById(R.id.cpuNumber);
        memoryNumberTextView = overlayView.findViewById(R.id.memoryNumber);
        fpsNumberTextView = overlayView.findViewById(R.id.fpsNumber);

        monitoringHandlerThread = new HandlerThread("MonitoringThread", Process.THREAD_PRIORITY_BACKGROUND);
        monitoringHandlerThread.start();
        monitoringHandler = new Handler(monitoringHandlerThread.getLooper());

        monitoringHandler.post(this::initPersistentRootShell);
        monitoringRunnable = this::monitorPerformance;
        monitoringHandler.post(monitoringRunnable);

        try {
            windowManager.addView(overlayView, overlayLayoutParams);
        } catch (Exception e) {
            Log.e(TAG, "Error adding overlay view", e);
            stopSelf();
        }
    }

    // Extracted method to clean up onCreate
    private WindowManager.LayoutParams createLayoutParams() {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, // SDK >= 26
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 0;
        params.y = 0;
        return params;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Performance Overlay Service Channel",
                    NotificationManager.IMPORTANCE_LOW // Use low importance to avoid sound/vibration
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    private void initPersistentRootShell() {
        if (!isRooted) return;
        try {
            suProcess = Runtime.getRuntime().exec("su");
            suOutputStream = new DataOutputStream(suProcess.getOutputStream());
            suReader = new BufferedReader(new InputStreamReader(suProcess.getInputStream()));
        } catch (Exception e) {
            Log.e(TAG, "Failed to init root shell", e);
            isRooted = false;
        }
    }

    private void monitorPerformance() {
        // 1. Power
        BatteryManager batteryManager = (BatteryManager) getSystemService(Context.BATTERY_SERVICE);
        Intent batteryStatus = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        double voltageVolts = 3.7;
        if (batteryStatus != null) {
            int voltageMv = batteryStatus.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
            if (voltageMv > 0) voltageVolts = voltageMv / 1000.0;
        }
        long currentNow = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
        double energyWatts = Math.abs((currentNow / 1000000.0) * voltageVolts);

        // 2. CPU
        int cpuUtilization = getCpuUsageFromFreq();

        // 3. Memory
        ActivityManager activityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memoryInfo);
        long usedMemory = memoryInfo.totalMem - memoryInfo.availMem;
        long memoryUtilization = usedMemory / (1024 * 1024);

        // 4. Hz & FPS
        float refreshRate = defaultDisplay.getRefreshRate();
        String fpsDisplayText;

        if (isRooted && suOutputStream != null) {
            // Update the target layer every 3 seconds to save CPU
            if (layerCheckCounter++ >= 3 || gameLayerName == null) {
                detectGameLayer();
                layerCheckCounter = 0;
            }

            int realFps = getFpsFromPersistentShell();
            fpsDisplayText = getString(R.string.hz_and_fps_value, refreshRate, realFps);
        } else {
            fpsDisplayText = getString(R.string.hz_only_value, refreshRate);
        }

        // 5. Update UI
        String powerText = getString(R.string.power_consumption, String.format(Locale.US, "%.2f", energyWatts));
        String cpuText = getString(R.string.cpu_utilization, String.format(Locale.US, "%d", cpuUtilization));
        String memoryText = getString(R.string.memory_utilization, String.format(Locale.US, "%d", memoryUtilization));

        mainHandler.post(() -> {
            if (overlayView.isAttachedToWindow()) {
                powerNumberTextView.setText(powerText);
                cpuNumberTextView.setText(cpuText);
                memoryNumberTextView.setText(memoryText);
                fpsNumberTextView.setText(fpsDisplayText);
            }
        });

        monitoringHandler.postDelayed(monitoringRunnable, 1000);
    }

    /**
     * Retrieves the list of visible layers and picks the best candidate for the Game.
     */
    private void detectGameLayer() {
        if (suOutputStream == null) return;
        try {
            suOutputStream.writeBytes("dumpsys SurfaceFlinger --list\n");
            suOutputStream.writeBytes("echo LIST_DONE\n");
            suOutputStream.flush();

            String line;
            String candidate = null;
            // Removed unused 'listStarted' variable

            while ((line = suReader.readLine()) != null) {
                if (line.trim().equals("LIST_DONE")) break;
                if (line.trim().isEmpty()) continue; // Replaced length() == 0

                // Exclusion list
                if (line.contains("com.catsmoker.app")) continue;
                if (line.contains("StatusBar")) continue;
                if (line.contains("NavigationBar")) continue;
                if (line.contains("InputMethod")) continue;
                if (line.contains("Screenshot")) continue;
                if (line.contains("Background")) continue;
                if (line.equals("ImageWallpaper")) continue;

                if (line.startsWith("SurfaceView")) {
                    gameLayerName = line;
                    return;
                }

                if (candidate == null && line.contains("/")) {
                    candidate = line;
                }
            }

            if (candidate != null) {
                gameLayerName = candidate;
            }

        } catch (Exception e) {
            Log.e(TAG, "Error detecting game layer", e);
        }
    }

    private int getFpsFromPersistentShell() {
        if (suOutputStream == null || gameLayerName == null) return lastCalculatedFps;

        try {
            String cmd = "dumpsys SurfaceFlinger --latency \"" + gameLayerName + "\"\n";
            suOutputStream.writeBytes(cmd);
            suOutputStream.writeBytes("echo FPS_CHECK_DONE\n");
            suOutputStream.flush();

            String line;
            long count = 0;
            long lastPresentTime = 0;
            long firstPresentTime = 0;
            boolean firstLineSkipped = false;
            boolean gotData = false;

            while ((line = suReader.readLine()) != null) {
                if (line.trim().equals("FPS_CHECK_DONE")) break;

                if (!firstLineSkipped) {
                    firstLineSkipped = true;
                    continue;
                }
                if (line.isEmpty()) continue; // Replaced length() == 0

                String[] parts = line.split("\\s+");
                if (parts.length >= 2) {
                    try {
                        long presentTime = Long.parseLong(parts[1]);
                        if (presentTime == 0 || presentTime == Long.MAX_VALUE) continue;

                        if (firstPresentTime == 0) firstPresentTime = presentTime;
                        lastPresentTime = presentTime;
                        count++;
                        gotData = true;
                    } catch (NumberFormatException ignored) {}
                }
            }

            if (gotData && count > 1) {
                long timeDiffNanos = lastPresentTime - firstPresentTime;
                if (timeDiffNanos > 0) {
                    double seconds = timeDiffNanos / 1_000_000_000.0;
                    lastCalculatedFps = (int) Math.round((count - 1) / seconds);
                }
            } else if (!gotData) {
                gameLayerName = null;
            }

        } catch (Exception e) {
            Log.e(TAG, "Root shell error, restarting...", e);
            closeRootShell();
            initPersistentRootShell();
        }
        return lastCalculatedFps;
    }

    private void closeRootShell() {
        try {
            if (suOutputStream != null) {
                suOutputStream.writeBytes("exit\n");
                suOutputStream.flush();
                suOutputStream.close();
            }
            if (suReader != null) suReader.close();
            if (suProcess != null) suProcess.destroy();
        } catch (Exception ignored) {}
        suProcess = null;
        suOutputStream = null;
        suReader = null;
    }

    private boolean checkRootMethod() {
        String[] paths = { "/system/app/Superuser.apk", "/sbin/su", "/system/bin/su", "/system/xbin/su", "/data/local/xbin/su", "/data/local/bin/su", "/system/sd/xbin/su", "/system/bin/failsafe/su", "/data/local/su" };
        for (String path : paths) {
            if (new File(path).exists()) return true;
        }
        return false;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (overlayView != null && overlayView.isAttachedToWindow()) {
            windowManager.removeView(overlayView);
        }
        closeRootShell();
        monitoringHandler.removeCallbacks(monitoringRunnable);
        monitoringHandlerThread.quitSafely();
    }

    // --- CPU Logic ---
    private ArrayList<CoreFreq> mCoresFreq;

    private int getCpuUsageFromFreq() {
        return getCpuUsage(getCoresUsageGuessFromFreq());
    }

    private int getCpuUsage(int[] coresUsage) {
        if (coresUsage.length < 2) return 0;
        int cpuUsage = 0;
        int activeCores = 0;
        for (int i = 1; i < coresUsage.length; i++) {
            if (coresUsage[i] > -1) {
                cpuUsage += coresUsage[i];
                activeCores++;
            }
        }
        return activeCores > 0 ? cpuUsage / activeCores : 0;
    }

    private synchronized int[] getCoresUsageGuessFromFreq() {
        initCoresFreq();
        int nbCores = (mCoresFreq != null) ? mCoresFreq.size() + 1 : 1;
        int[] coresUsage = new int[nbCores];
        coresUsage[0] = 0;
        if (mCoresFreq != null) {
            for (int i = 0; i < mCoresFreq.size(); i++) {
                coresUsage[i + 1] = mCoresFreq.get(i).getCurUsage();
                coresUsage[0] += coresUsage[i + 1];
            }
            if (!mCoresFreq.isEmpty()) {
                coresUsage[0] /= mCoresFreq.size();
            }
        }
        return coresUsage;
    }

    private void initCoresFreq() {
        if (mCoresFreq == null) {
            int nbCores = getNbCores();
            mCoresFreq = new ArrayList<>();
            for (int i = 0; i < nbCores; i++) {
                mCoresFreq.add(new CoreFreq(i));
            }
        }
    }

    private int getCurCpuFreq(int coreIndex) {
        return readIntegerFile("/sys/devices/system/cpu/cpu" + coreIndex + "/cpufreq/scaling_cur_freq");
    }

    private int getMinCpuFreq(int coreIndex) {
        return readIntegerFile("/sys/devices/system/cpu/cpu" + coreIndex + "/cpufreq/cpuinfo_min_freq");
    }

    private int getMaxCpuFreq(int coreIndex) {
        return readIntegerFile("/sys/devices/system/cpu/cpu" + coreIndex + "/cpufreq/cpuinfo_max_freq");
    }

    private int readIntegerFile(String path) {
        int ret = 0;
        try (RandomAccessFile reader = new RandomAccessFile(path, "r")) {
            String line = reader.readLine();
            if (line != null) {
                ret = Integer.parseInt(line);
            }
        } catch (Exception ignored) { }
        return ret;
    }

    private int getNbCores() {
        FileFilter cpuFilter = pathname -> CPU_PATTERN.matcher(pathname.getName()).matches();
        try {
            File dir = new File("/sys/devices/system/cpu/");
            File[] files = dir.listFiles(cpuFilter);
            return files != null ? files.length : 1;
        } catch (Exception e) {
            return 1;
        }
    }

    private class CoreFreq {
        private final int num;
        private int min;
        private int max;

        public CoreFreq(int num) {
            this.num = num;
            min = getMinCpuFreq(num);
            max = getMaxCpuFreq(num);
        }

        public int getCurUsage() {
            int cur = getCurCpuFreq(num);
            if (min == 0) min = getMinCpuFreq(num);
            if (max == 0) max = getMaxCpuFreq(num);
            if (max - min > 0 && max > 0 && cur > 0) {
                return (cur - min) * 100 / (max - min);
            }
            return 0;
        }
    }
}