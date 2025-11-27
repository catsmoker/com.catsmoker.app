package com.catsmoker.app;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.net.TrafficStats;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.text.format.Formatter;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewStub;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.ViewFlipper;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.splashscreen.SplashScreen;

import com.startapp.sdk.ads.banner.Banner;
import com.startapp.sdk.adsbase.Ad;
import com.startapp.sdk.adsbase.StartAppAd;
import com.startapp.sdk.adsbase.adlisteners.AdEventListener;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final String WEBSITE_URL = "https://catsmoker.vercel.app";
    private static final int UPDATE_INTERVAL_MS = 2000; // Update stats every 2 seconds

    private TextView appInfoTextView;
    private ExecutorService backgroundExecutor;
    private Handler uiHandler;
    private Runnable statsUpdaterRunnable;
    private boolean isActivityVisible = false;

    // Ad Object for Pre-loading
    private StartAppAd interstitialAd;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize background tools
        backgroundExecutor = Executors.newSingleThreadExecutor();
        uiHandler = new Handler(Looper.getMainLooper());

        // Initialize Ads and Pre-load
        initAds();

        // Setup UI Components
        initViews();
        setupButtons();
        setupViewStub();
    }

    @Override
    protected void onResume() {
        super.onResume();
        isActivityVisible = true;
        startStatsMonitoring();
    }

    @Override
    protected void onPause() {
        super.onPause();
        isActivityVisible = false;
        // Stop updates to save battery when app is not in focus
        uiHandler.removeCallbacks(statsUpdaterRunnable);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (backgroundExecutor != null && !backgroundExecutor.isShutdown()) {
            backgroundExecutor.shutdown();
        }
    }

    // --- Initialization Methods ---

    private void initAds() {
        try {
            // 1. Initialize the Interstitial Ad object
            interstitialAd = new StartAppAd(this);

            // 2. Pre-load the ad immediately so it's ready when button is clicked
            // We use AUTO mode or FULLPAGE.
            interstitialAd.loadAd(StartAppAd.AdMode.FULLPAGE, new AdEventListener() {
                @Override
                public void onReceiveAd(@NonNull Ad ad) {
                    Log.d(TAG, "Ad Pre-loaded successfully");
                }
                @Override
                public void onFailedToReceiveAd(Ad ad) {
                    Log.d(TAG, "Ad Pre-load failed");
                }
            });

            // 3. Initialize Banner
            Banner banner = findViewById(R.id.startio_banner);
            if (banner != null) {
                banner.showBanner();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error initializing Ads", e);
        }
    }

    private void initViews() {
        appInfoTextView = findViewById(R.id.app_info);
    }

    private void setupButtons() {
        setupActivityButton(R.id.btn_root_lsposed, RootActivity.class);
        setupActivityButton(R.id.btn_shizuku, NonRootActivity.class);
        setupActivityButton(R.id.btn_about, AboutActivity.class);

        // Exit Button
        findViewById(R.id.btn_exit).setOnClickListener(v -> finish());

        // Website Button
        findViewById(R.id.btn_website).setOnClickListener(v -> {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(WEBSITE_URL));
                startActivity(intent);
            } catch (Exception e) {
                Log.e(TAG, "Could not open website", e);
            }
        });

        // Features (Crosshair) Button
        findViewById(R.id.btn_crosshair).setOnClickListener(v -> openFeaturesThenShowAd());
    }

    private void setupActivityButton(int btnId, Class<?> targetClass) {
        findViewById(btnId).setOnClickListener(v -> startActivity(new Intent(this, targetClass)));
    }

    private void setupViewStub() {
        ViewStub viewStub = findViewById(R.id.view_stub_flipper);
        if (viewStub == null) return;

        viewStub.setOnInflateListener((stub, inflated) -> {
            ViewFlipper viewFlipper = inflated.findViewById(R.id.game_flipper);
            populateViewFlipper(viewFlipper);

            LinearLayout flipperContainer = inflated.findViewById(R.id.flipper_container_layout);
            flipperContainer.setOnClickListener(v -> showSupportedGamesDialog());
        });
        viewStub.inflate();
    }

    // --- Feature Logic & Ad Handling ---

    private void openFeaturesThenShowAd() {
        // 1. Navigate Immediately (Fastest Response)
        // This ensures the user sees the feature tab instantly.
        Intent intent = new Intent(MainActivity.this, FeaturesActivity.class);
        startActivity(intent);

        // 2. Show Ad afterwards (if available and loaded)
        // Since we navigated, the Ad Activity will stack ON TOP of FeaturesActivity.
        // When user closes Ad, they are already in FeaturesActivity.
        if (interstitialAd != null && interstitialAd.isReady()) {
            Log.d(TAG, "Showing pre-loaded ad over Features");
            interstitialAd.showAd();
        } else {
            Log.d(TAG, "Ad not ready or no internet, user is already at Features.");
            // Optional: Reload for next time
            if (interstitialAd != null) {
                interstitialAd.loadAd(StartAppAd.AdMode.FULLPAGE);
            }
        }
    }

    private void showSupportedGamesDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, com.google.android.material.R.style.Theme_MaterialComponents_DayNight_Dialog_Alert);
        builder.setTitle(getString(R.string.supported_games_dialog_title));

        ListView listView = new ListView(this);
        String[] games = getResources().getStringArray(R.array.supported_games);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.list_item_black_and_white, games);
        listView.setAdapter(adapter);

        builder.setView(listView);
        builder.setPositiveButton("OK", null);
        builder.show();
    }

    private void populateViewFlipper(ViewFlipper viewFlipper) {
        if (viewFlipper == null) return;

        String[] supportedGames = getResources().getStringArray(R.array.supported_games);

        TypedValue typedValue = new TypedValue();
        getTheme().resolveAttribute(R.attr.colorSecondary, typedValue, true);
        int accentColor = typedValue.data;

        for (String game : supportedGames) {
            TextView textView = new TextView(this);
            textView.setText(game);
            textView.setTextSize(18);
            textView.setGravity(Gravity.CENTER);
            textView.setTextColor(accentColor);
            textView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            viewFlipper.addView(textView);
        }
    }

    // --- Device Stats Monitoring ---

    private void startStatsMonitoring() {
        statsUpdaterRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isActivityVisible) return;

                // Execute heavy stats gathering on background thread
                backgroundExecutor.execute(() -> {
                    final String stats = buildSystemInfoString();
                    // Update UI on main thread
                    uiHandler.post(() -> {
                        if (appInfoTextView != null) {
                            appInfoTextView.setText(stats);
                        }
                    });
                });

                // Schedule next update
                uiHandler.postDelayed(this, UPDATE_INTERVAL_MS);
            }
        };
        // Start the loop
        uiHandler.post(statsUpdaterRunnable);
    }

    private String buildSystemInfoString() {
        return "CatSmoker V" + BuildConfig.VERSION_NAME + "\n" +
                "Arch: " + System.getProperty("os.arch") + "\n" +
                "Model: " + Build.MODEL + "\n" +
                "RAM Used: " + getMemoryUsage() + "\n" +
                "Battery: " + getBatteryHealth() + " (" + getBatteryTemperature() + "°C)\n" +
                "Network (Total): " + getNetworkUsage() + "\n" +
                "Storage Used: " + getDiskUsage();
    }

    @SuppressLint("DefaultLocale")
    private String getBatteryTemperature() {
        try {
            Intent intent = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (intent != null) {
                float temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f;
                return String.format("%.1f", temp);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting battery temp", e);
        }
        return "N/A";
    }

    private String getBatteryHealth() {
        try {
            Intent intent = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            int health = intent != null ? intent.getIntExtra(BatteryManager.EXTRA_HEALTH, 0) : 0;

            return switch (health) {
                case BatteryManager.BATTERY_HEALTH_GOOD -> "Good";
                case BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat";
                case BatteryManager.BATTERY_HEALTH_DEAD -> "Dead";
                case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over Voltage";
                case BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Failure";
                case BatteryManager.BATTERY_HEALTH_COLD -> "Cold";
                default -> "Unknown";
            };
        } catch (Exception e) {
            return "Unknown";
        }
    }

    @SuppressLint("DefaultLocale")
    private String getMemoryUsage() {
        try {
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            ActivityManager activityManager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (activityManager != null) {
                activityManager.getMemoryInfo(mi);
                long used = mi.totalMem - mi.availMem;
                float percent = (float) used / mi.totalMem * 100;
                return String.format("%.1f%%", percent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting memory info", e);
        }
        return "N/A";
    }

    @SuppressLint("DefaultLocale")
    private String getDiskUsage() {
        try {
            StatFs stat = new StatFs(getFilesDir().getAbsolutePath());
            long total = stat.getTotalBytes();
            long available = stat.getAvailableBytes();
            long used = total - available;
            float percent = (float) used / total * 100;
            return String.format("%.1f%%", percent);
        } catch (Exception e) {
            Log.e(TAG, "Error getting disk info", e);
            return "N/A";
        }
    }

    private String getNetworkUsage() {
        try {
            long rx = TrafficStats.getTotalRxBytes();
            long tx = TrafficStats.getTotalTxBytes();
            if (rx == TrafficStats.UNSUPPORTED || tx == TrafficStats.UNSUPPORTED) {
                return "Unsupported";
            }
            return Formatter.formatFileSize(this, rx + tx);
        } catch (Exception e) {
            return "N/A";
        }
    }
}