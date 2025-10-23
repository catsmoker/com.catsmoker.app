package com.catsmoker.app;

import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri; // <-- IMPORTANT: Make sure this import is added
import android.os.Build;
import android.os.Bundle;
import android.os.StatFs;
import android.util.TypedValue;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.ViewFlipper;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.catsmoker.app.BuildConfig;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.initialization.InitializationStatus;
import com.google.android.gms.ads.initialization.OnInitializationCompleteListener;

public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        MobileAds.initialize(this, new OnInitializationCompleteListener() {
            @Override
            public void onInitializationComplete(InitializationStatus initializationStatus) {
            }
        });

        AdView mAdView = findViewById(R.id.adView);
        AdRequest adRequest = new AdRequest.Builder().build();
        mAdView.loadAd(adRequest);

        TextView appInfo = findViewById(R.id.app_info);
        String versionName = BuildConfig.VERSION_NAME;
        String info = "CatSmoker V" + versionName + "\n" +
                "Processor: " + System.getProperty("os.arch") + "\n" +
                "Model: " + Build.MODEL + "\n" +
                "Memory Usage: " + getMemoryUsage() + "%\n" +
                "Battery Health: " + getBatteryHealth() + "\n" +
                "Battery Temp: " + getBatteryTemperature() + "°C\n" +
                "Network Usage: " + getNetworkUsage() + "\n" +
                "Disk Usage: " + getDiskUsage();
        appInfo.setText(info);

        setupButton(R.id.btn_root_lsposed, RootLSPosedActivity.class);
        setupButton(R.id.btn_shizuku, NonRootGuideActivity.class);
        setupButton(R.id.btn_crosshair, FeaturesActivity.class);
        setupButton(R.id.btn_about, AboutActivity.class);

        Button websiteButton = findViewById(R.id.btn_website);
        websiteButton.setOnClickListener(v -> {
            String url = "https://catsmoker.vercel.app";
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse(url));
            startActivity(intent);
        });

        setupButton(R.id.btn_exit, this::finish);

        populateViewFlipper();
    }

    private void populateViewFlipper() {
        ViewFlipper viewFlipper = findViewById(R.id.game_flipper);
        String[] supportedGames = getResources().getStringArray(R.array.supported_games);

        int color = ContextCompat.getColor(this, R.color.colorSecondary);

        for (String game : supportedGames) {
            TextView textView = new TextView(this);
            textView.setText(game);
            textView.setTextSize(18);
            textView.setTextColor(color);
            textView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            viewFlipper.addView(textView);
        }

        viewFlipper.setOnClickListener(v -> {
            AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
            builder.setTitle(getString(R.string.supported_games_dialog_title));

            ListView listView = new ListView(MainActivity.this);
            ArrayAdapter<String> adapter = new ArrayAdapter<>(MainActivity.this, android.R.layout.simple_list_item_1, supportedGames);
            listView.setAdapter(adapter);

            builder.setView(listView);
            builder.setPositiveButton("OK", null);
            builder.show();
        });
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
