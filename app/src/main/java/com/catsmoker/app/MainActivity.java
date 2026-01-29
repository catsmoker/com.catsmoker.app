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
import android.view.View;
import android.view.ViewStub;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SearchView;
import android.widget.TextView;
import android.widget.ViewFlipper;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.splashscreen.SplashScreen;

import com.startapp.sdk.ads.banner.Banner;
import com.startapp.sdk.adsbase.Ad;
import com.startapp.sdk.adsbase.StartAppAd;
import com.startapp.sdk.adsbase.adlisteners.AdDisplayListener;
import com.startapp.sdk.adsbase.adlisteners.AdEventListener;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final String WEBSITE_URL = "https://catsmoker.vercel.app";
    private static final int UPDATE_INTERVAL_MS = 2000;

    private TextView appInfoTextView;
    private ExecutorService backgroundExecutor;
    private Handler uiHandler;
    private Runnable statsUpdaterRunnable;
    private boolean isActivityVisible = false;
    private boolean isViewStubInflated = false;

    // Ads
    private StartAppAd interstitialAd;
    private boolean isAdLoaded = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize Tools
        backgroundExecutor = Executors.newSingleThreadExecutor();
        uiHandler = new Handler(Looper.getMainLooper());

        initAds();
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
        // Stop updates to save battery
        if (uiHandler != null && statsUpdaterRunnable != null) {
            uiHandler.removeCallbacks(statsUpdaterRunnable);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (backgroundExecutor != null && !backgroundExecutor.isShutdown()) {
            backgroundExecutor.shutdownNow();
        }
    }

    // --- Ads Logic (Fixed for Deprecation) ---

    private void initAds() {
        interstitialAd = new StartAppAd(this);
        loadInterstitialAd();

        // Create a temporary view to check if we're in design mode
        View tempView = new View(this);

        // Check if we're in design mode (preview) or runtime
        if (tempView.isInEditMode()) {
            // In preview mode - the mock banner is already in the layout
            return; // Early return to avoid executing runtime code
            // Nothing more to do for design time; just fall through
        } else {
            // Runtime - replace the mock banner content with the real StartApp banner
            FrameLayout bannerContainer = findViewById(R.id.startio_banner);
            if (bannerContainer != null) {
                // Clear the existing content (the mock banner text)
                bannerContainer.removeAllViews();

                // Create and add the real StartApp banner
                Banner realBanner = new Banner(this);
                realBanner.setId(R.id.startio_banner); // Use the same ID

                // Add the real banner to the container
                bannerContainer.addView(realBanner);

                // Show the banner
                realBanner.showBanner();
            }
        }
    }

    private void loadInterstitialAd() {
        isAdLoaded = false; // Reset flag
        interstitialAd.loadAd(StartAppAd.AdMode.FULLPAGE, new AdEventListener() {
            @Override
            public void onReceiveAd(@NonNull Ad ad) {
                isAdLoaded = true;
                Log.d(TAG, "Ad Loaded Successfully");
            }
            @Override
            public void onFailedToReceiveAd(Ad ad) {
                isAdLoaded = false;
                Log.w(TAG, "Ad Load Failed: " + (ad != null ? ad.getErrorMessage() : "Unknown"));
            }
        });
    }

    private void openFeaturesThenShowAd() {
        startActivity(new Intent(this, FeaturesActivity.class));

        // Replaces deprecated isReady() check
        if (isAdLoaded) {
            interstitialAd.showAd(new AdDisplayListener() {
                @Override
                public void adHidden(Ad ad) {
                    loadInterstitialAd(); // Load next ad when closed
                }
                @Override
                public void adDisplayed(Ad ad) { }
                @Override
                public void adClicked(Ad ad) { }
                @Override
                public void adNotDisplayed(Ad ad) {
                    loadInterstitialAd(); // Reload if failed to show
                }
            });
        } else {
            loadInterstitialAd(); // Try loading again for next time
        }
    }

    // --- UI Init ---

    private void initViews() {
        appInfoTextView = findViewById(R.id.app_info);
    }

    private void setupButtons() {
        setupActivityButton(R.id.btn_root_lsposed, RootActivity.class);
        setupActivityButton(R.id.btn_shizuku, NonRootActivity.class);
        setupActivityButton(R.id.btn_about, AboutActivity.class);

        // Full app exit
        View btnExit = findViewById(R.id.btn_exit);
        if (btnExit != null) {
            btnExit.setOnClickListener(v -> finishAffinity());
        }

        // Website
        View btnWebsite = findViewById(R.id.btn_website);
        if (btnWebsite != null) {
            btnWebsite.setOnClickListener(v -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(WEBSITE_URL)));
                } catch (Exception e) {
                    Log.e(TAG, "Browser not found", e);
                }
            });
        }

        // Features + Ad
        View btnCrosshair = findViewById(R.id.btn_crosshair);
        if (btnCrosshair != null) {
            btnCrosshair.setOnClickListener(v -> openFeaturesThenShowAd());
        }
    }

    private void setupActivityButton(int btnId, Class<?> targetClass) {
        View btn = findViewById(btnId);
        if (btn != null) {
            btn.setOnClickListener(v -> startActivity(new Intent(this, targetClass)));
        }
    }

    private void showSupportedGamesDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, com.google.android.material.R.style.Theme_Material3_DayNight_Dialog_Alert);
        builder.setTitle(getString(R.string.supported_games_dialog_title));

        // Inflate custom layout
        View dialogView = getLayoutInflater().inflate(R.layout.supp_games, null);
        SearchView searchView = dialogView.findViewById(R.id.search_view_games);
        ListView listView = dialogView.findViewById(R.id.list_view_games);

        String[] games = getResources().getStringArray(R.array.supported_games);
        final ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.list_item, games);
        listView.setAdapter(adapter);

        searchView.setIconifiedByDefault(false);
        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                adapter.getFilter().filter(query);
                searchView.clearFocus();
                return true;
            }
            @Override
            public boolean onQueryTextChange(String newText) {
                adapter.getFilter().filter(newText);
                return true;
            }
        });

        builder.setView(dialogView);
        builder.setPositiveButton("OK", null);
        builder.show();
    }

    private void setupViewStub() {
        ViewStub viewStub = findViewById(R.id.view_stub_flipper);
        if (viewStub == null || isViewStubInflated) return;

        viewStub.setOnInflateListener((stub, inflated) -> {
            isViewStubInflated = true;
            ViewFlipper viewFlipper = inflated.findViewById(R.id.game_flipper);
            populateViewFlipper(viewFlipper);

            LinearLayout flipperContainer = inflated.findViewById(R.id.flipper_container_layout);
            if (flipperContainer != null) {
                flipperContainer.setOnClickListener(v -> showSupportedGamesDialog());
            }
        });
        viewStub.inflate();
    }

    private void populateViewFlipper(ViewFlipper viewFlipper) {
        if (viewFlipper == null) return;
        String[] supportedGames = getResources().getStringArray(R.array.supported_games);

        TypedValue typedValue = new TypedValue();
        getTheme().resolveAttribute(androidx.appcompat.R.attr.colorAccent, typedValue, true);
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
        viewFlipper.startFlipping();
    }

    // --- Stats Monitoring (Optimized) ---

    private void startStatsMonitoring() {
        statsUpdaterRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isActivityVisible || isFinishing()) return;

                backgroundExecutor.execute(() -> {
                    // Fetch battery stats via Sticky Intent (No BroadcastReceiver needed)
                    Intent batteryStatus = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                    final String stats = buildSystemInfoString(batteryStatus);

                    uiHandler.post(() -> {
                        if (appInfoTextView != null) appInfoTextView.setText(stats);
                    });
                });

                // Keep the loop going
                uiHandler.postDelayed(this, UPDATE_INTERVAL_MS);
            }
        };
        uiHandler.post(statsUpdaterRunnable);
    }

    private String buildSystemInfoString(Intent batteryStatus) {
        int level = 0;
        int health = BatteryManager.BATTERY_HEALTH_UNKNOWN;
        float temp = 0;

        if (batteryStatus != null) {
            level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            health = batteryStatus.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN);
            temp = batteryStatus.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f;
        }

        // Replaced StringBuilder with standard concatenation
        return "CatSmoker V" + BuildConfig.VERSION_NAME + "\n" +
                "Arch: " + System.getProperty("os.arch") + "\n" +
                "Model: " + Build.MODEL + "\n" +
                "RAM Used: " + getMemoryUsage() + "\n" +
                "Battery: " + getHealthString(health) + " (" + temp + "°C)\n" +
                "Level: " + level + "%\n" +
                "Network: " + getNetworkUsage() + "\n" +
                "Storage Used: " + getDiskUsage();
    }

    private String getHealthString(int healthInt) {
        // Enhanced switch
        return switch (healthInt) {
            case BatteryManager.BATTERY_HEALTH_GOOD -> "Good";
            case BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat";
            case BatteryManager.BATTERY_HEALTH_DEAD -> "Dead";
            case BatteryManager.BATTERY_HEALTH_COLD -> "Cold";
            default -> "Unknown";
        };
    }

    @SuppressLint("DefaultLocale")
    private String getMemoryUsage() {
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        ActivityManager activityManager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (activityManager != null) {
            activityManager.getMemoryInfo(mi);
            long used = mi.totalMem - mi.availMem;
            float percent = (float) used / mi.totalMem * 100;
            return String.format("%.1f%%", percent);
        }
        return "N/A";
    }

    @SuppressLint("DefaultLocale")
    private String getDiskUsage() {
        try {
            File path = getFilesDir();
            StatFs stat = new StatFs(path.getAbsolutePath());
            long total = stat.getTotalBytes();
            long used = total - stat.getAvailableBytes();
            float percent = (float) used / total * 100;
            return String.format("%.1f%%", percent);
        } catch (Exception e) {
            return "N/A";
        }
    }

    private String getNetworkUsage() {
        long total = TrafficStats.getTotalRxBytes() + TrafficStats.getTotalTxBytes();
        return (total < 0) ? "Unsupported" : Formatter.formatFileSize(this, total);
    }
}