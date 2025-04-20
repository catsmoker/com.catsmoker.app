package com.catsmoker.app;

import android.app.ActivityManager;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.StatFs;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        TextView appInfo = findViewById(R.id.app_info);
        String info = "CatSmoker V1.5\n" +
                "Processor: " + System.getProperty("os.arch") + "\n" +
                "Model: " + Build.MODEL + "\n" +
                "Memory Usage: " + getMemoryUsage() + "%\n" +
                "Battery Health: " + getBatteryHealth() + "\n" +
                "Battery Temp: " + getBatteryTemperature() + "°C\n" +
                "Network Usage: " + getNetworkUsage() + "\n" +
                "Disk Usage: " + getDiskUsage();
        appInfo.setText(info);

        setupButton(R.id.btn_root_lsposed, RootLSPosedActivity.class);
        setupButton(R.id.btn_shizuku, ShizukuActivity.class);
        setupButton(R.id.btn_crosshair, FeaturesActivity.class);
        setupButton(R.id.btn_website, WebsiteActivity.class);
        setupButton(R.id.btn_about, AboutActivity.class);
        setupButton(R.id.btn_exit, this::finish);
    }

    private void setupButton(int buttonId, Class<?> activityClass) {
        Button button = findViewById(buttonId);
        button.setOnClickListener(v -> {
            Intent intent = new Intent(this, activityClass);
            startActivity(intent);
        });
    }

    private void setupButton(int buttonId, Runnable action) {
        Button button = findViewById(buttonId);
        button.setOnClickListener(v -> action.run());
    }

    private String getBatteryTemperature() {
        Intent intent = registerReceiver(null, new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (intent != null) {
            float temp = intent.getIntExtra("temperature", 0) / 10f;
            return String.format("%.1f", temp);
        }
        return "N/A";
    }

    private String getBatteryHealth() {
        Intent intent = registerReceiver(null, new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int health = intent != null ? intent.getIntExtra("health", 0) : 0;

        return switch (health) {
            case android.os.BatteryManager.BATTERY_HEALTH_GOOD -> "Good";
            case android.os.BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat";
            case android.os.BatteryManager.BATTERY_HEALTH_DEAD -> "Dead";
            case android.os.BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over Voltage";
            case android.os.BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Failure";
            default -> "Unknown";
        };
    }

    private String getMemoryUsage() {
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        ActivityManager activityManager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        activityManager.getMemoryInfo(mi);

        long total = mi.totalMem;
        long avail = mi.availMem;
        return String.format("%.2f", (float) (total - avail) / total * 100);
    }
    private String getDiskUsage() {
        StatFs stat = new StatFs(getFilesDir().getPath());
        long total = stat.getTotalBytes();
        long free = stat.getAvailableBytes();
        return String.format("%.2f", (float) (total - free) / total * 100) + "%";
    }
    private String getNetworkUsage() {
        long rx = android.net.TrafficStats.getTotalRxBytes();
        long tx = android.net.TrafficStats.getTotalTxBytes();
        return android.text.format.Formatter.formatFileSize(this, rx + tx);
    }

}
