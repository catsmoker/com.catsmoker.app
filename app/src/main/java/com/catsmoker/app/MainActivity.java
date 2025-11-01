package com.catsmoker.app;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.StatFs;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.ViewFlipper;
import android.view.ViewStub;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdView;
import android.util.TypedValue;
import com.google.android.gms.ads.MobileAds;


import androidx.core.splashscreen.SplashScreen;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private ExecutorService executor;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        MobileAds.initialize(this, initStatus -> {
        });

        AdView mAdView = findViewById(R.id.adView);
        AdRequest adRequest = new AdRequest.Builder().build();
        mAdView.loadAd(adRequest);

        TextView appInfo = findViewById(R.id.app_info);
        String versionName = BuildConfig.VERSION_NAME;
        executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            String info = "CatSmoker V" + versionName + "\n" +
                    "Processor: " + System.getProperty("os.arch") + "\n" +
                    "Model: " + Build.MODEL + "\n" +
                    "Memory Usage: " + getMemoryUsage() + "%\n" +
                    "Battery Health: " + getBatteryHealth() + "\n" +
                    "Battery Temp: " + getBatteryTemperature() + "°C\n" +
                    "Network Usage: " + getNetworkUsage() + "\n" +
                    "Disk Usage: " + getDiskUsage();
            runOnUiThread(() -> appInfo.setText(info));
        });

        setupButton(R.id.btn_root_lsposed, RootLSPosedActivity.class);
        setupButton(R.id.btn_shizuku, NonRootActivity.class);
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

        ViewStub viewStub = findViewById(R.id.view_stub_flipper);
        viewStub.setOnInflateListener((stub, inflated) -> {
            ViewFlipper viewFlipper = inflated.findViewById(R.id.game_flipper);
            populateViewFlipper(viewFlipper);

            LinearLayout flipperContainerLayout = inflated.findViewById(R.id.flipper_container_layout);
            flipperContainerLayout.setOnClickListener(v -> {
                AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this, com.google.android.material.R.style.Theme_MaterialComponents_DayNight_Dialog_Alert);
                builder.setTitle(getString(R.string.supported_games_dialog_title));

                ListView listView = new ListView(MainActivity.this);
                ArrayAdapter<String> adapter = new ArrayAdapter<>(MainActivity.this, R.layout.list_item_black_and_white, getResources().getStringArray(R.array.supported_games));
                listView.setAdapter(adapter);

                builder.setView(listView);
                builder.setPositiveButton("OK", null);
                builder.show();
            });
        });
        viewStub.inflate();
    }

    private void populateViewFlipper(ViewFlipper viewFlipper) {
        String[] supportedGames = getResources().getStringArray(R.array.supported_games);

                TypedValue typedValue = new TypedValue();
        getTheme().resolveAttribute(R.attr.colorSecondary, typedValue, true);
        int color = typedValue.data;

        for (String game : supportedGames) {
            TextView textView = new TextView(this);
            textView.setText(game);
            textView.setTextSize(18);
            textView.setGravity(android.view.Gravity.CENTER);
            textView.setTextColor(color);
            textView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            textView.setClickable(false);
            textView.setFocusable(false);
            viewFlipper.addView(textView);
        }
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

    @SuppressLint("DefaultLocale")
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

    @SuppressLint("DefaultLocale")
    private String getMemoryUsage() {
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        ActivityManager activityManager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        activityManager.getMemoryInfo(mi);

        long total = mi.totalMem;
        long avail = mi.availMem;
        return String.format("%.2f", (float) (total - avail) / total * 100);
    }
    @SuppressLint("DefaultLocale")
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executor != null) {
            executor.shutdown();
        }
    }
}
