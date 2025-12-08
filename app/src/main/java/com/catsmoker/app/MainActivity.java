package com.catsmoker.app;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
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

    // Battery Stats Cache (Optimized)
    private int currentBatteryLevel = 0;
    private int currentBatteryHealth = 0;
    private float currentBatteryTemp = 0;

    // Ads
    private StartAppAd interstitialAd;

    // Receiver to handle battery changes efficiently
    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) {
                currentBatteryLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
                currentBatteryHealth = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN);
                currentBatteryTemp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f;
            }
        }
    };

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

        // Register Battery Receiver
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        registerReceiver(batteryReceiver, filter);

        startStatsMonitoring();
    }

    @Override
    protected void onPause() {
        super.onPause();
        isActivityVisible = false;

        // Cleanup to save battery
        uiHandler.removeCallbacks(statsUpdaterRunnable);
        try {
            unregisterReceiver(batteryReceiver);
        } catch (IllegalArgumentException e) {
            // Receiver was not registered
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (backgroundExecutor != null && !backgroundExecutor.isShutdown()) {
            backgroundExecutor.shutdownNow();
        }
    }

    // --- Ads & UI Init ---

    private void initAds() {
        interstitialAd = new StartAppAd(this);
        loadInterstitialAd();

        Banner banner = findViewById(R.id.startio_banner);
        if (banner != null) {
            banner.showBanner();
        }
    }

    private void loadInterstitialAd() {
        interstitialAd.loadAd(StartAppAd.AdMode.FULLPAGE, new AdEventListener() {
            @Override
            public void onReceiveAd(@NonNull Ad ad) {
                Log.d(TAG, "Ad Loaded");
            }
            @Override
            public void onFailedToReceiveAd(Ad ad) {
                Log.w(TAG, "Ad Load Failed");
            }
        });
    }

    private void initViews() {
        appInfoTextView = findViewById(R.id.app_info);
    }

    private void setupButtons() {
        setupActivityButton(R.id.btn_root_lsposed, RootActivity.class);
        setupActivityButton(R.id.btn_shizuku, NonRootActivity.class);
        setupActivityButton(R.id.btn_about, AboutActivity.class);

        // Full app exit
        findViewById(R.id.btn_exit).setOnClickListener(v -> finishAffinity());

        // Website
        findViewById(R.id.btn_website).setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(WEBSITE_URL)));
            } catch (Exception e) {
                Log.e(TAG, "Browser not found", e);
            }
        });

        // Features + Ad
        findViewById(R.id.btn_crosshair).setOnClickListener(v -> openFeaturesThenShowAd());
    }

    private void setupActivityButton(int btnId, Class<?> targetClass) {
        findViewById(btnId).setOnClickListener(v -> startActivity(new Intent(this, targetClass)));
    }

    // --- Feature Logic ---

    private void openFeaturesThenShowAd() {
        startActivity(new Intent(this, FeaturesActivity.class));

        if (interstitialAd.isReady()) {
            interstitialAd.showAd();
            loadInterstitialAd(); // Pre-load next one
        } else {
            loadInterstitialAd(); // Try loading again
        }
    }

    private void showSupportedGamesDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(getString(R.string.supported_games_dialog_title));

        // Inflate custom layout
        View dialogView = getLayoutInflater().inflate(R.layout.supp_games, null);
        SearchView searchView = dialogView.findViewById(R.id.search_view_games);
        ListView listView = dialogView.findViewById(R.id.list_view_games);

        String[] games = getResources().getStringArray(R.array.supported_games);
        // Use custom list item layout
        final ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.list_item, games);
        listView.setAdapter(adapter);

        searchView.setIconifiedByDefault(false); // Open ready to type
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
        if (viewStub == null) return;

        viewStub.setOnInflateListener((stub, inflated) -> {
            ViewFlipper viewFlipper = inflated.findViewById(R.id.game_flipper);
            populateViewFlipper(viewFlipper);

            LinearLayout flipperContainer = inflated.findViewById(R.id.flipper_container_layout);
            flipperContainer.setOnClickListener(v -> showSupportedGamesDialog());
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

    // --- Stats Monitoring ---

    private void startStatsMonitoring() {
        statsUpdaterRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isActivityVisible || isFinishing()) return;

                backgroundExecutor.execute(() -> {
                    final String stats = buildSystemInfoString();
                    uiHandler.post(() -> {
                        if (appInfoTextView != null) appInfoTextView.setText(stats);
                    });
                });
                uiHandler.postDelayed(this, UPDATE_INTERVAL_MS);
            }
        };
        uiHandler.post(statsUpdaterRunnable);
    }

    private String buildSystemInfoString() {
        return "CatSmoker V" + BuildConfig.VERSION_NAME + "\n" +
                "Arch: " + System.getProperty("os.arch") + "\n" +
                "Model: " + Build.MODEL + "\n" +
                "RAM Used: " + getMemoryUsage() + "\n" +
                "Battery: " + getHealthString(currentBatteryHealth) + " (" + currentBatteryTemp + "°C)\n" +
                "Level: " + currentBatteryLevel + "%\n" +
                "Network: " + getNetworkUsage() + "\n" +
                "Storage Used: " + getDiskUsage();
    }

    private String getHealthString(int healthInt) {
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