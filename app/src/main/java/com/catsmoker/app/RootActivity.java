package com.catsmoker.app;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;
import com.topjohnwu.superuser.Shell;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RootActivity extends AppCompatActivity {

    // This field will be set to true by the Xposed module if it's active.
    public static boolean isModuleActive = false;

    private enum LsposedStatus {
        NOT_ACTIVE, // Module is not active
        ACTIVE      // Module is active
    }

    private TextView statusRootText;
    private TextView tvLsposedStatus;
    private MaterialButton btnRefresh;
    private View rootView;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_root);

        setupToolbar();
        initViews();
        setupListeners();

        refreshStatus();
    }

    private void setupToolbar() {
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setTitle(R.string.root_status_title);
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
    }

    private void initViews() {
        rootView = findViewById(android.R.id.content);
        statusRootText = findViewById(R.id.tv_root_status);
        tvLsposedStatus = findViewById(R.id.tv_lsposed_status);
        btnRefresh = findViewById(R.id.btn_refresh);
    }

    private void setupListeners() {
        btnRefresh.setOnClickListener(v -> refreshStatus());

        View btnInstall = findViewById(R.id.btn_install_lsposed);
        if (btnInstall != null) {
            btnInstall.setOnClickListener(v -> openUrl());
        }

        findViewById(R.id.btn_open_manager).setOnClickListener(v -> launchRootManager());
    }

    /**
     * Attempts to find and launch Magisk, KernelSU, or APatch.
     */
    private void launchRootManager() {
        Intent intent = getRootManagerIntent();
        if (intent != null) {
            startActivity(intent);
        } else {
            showSnackbar(getString(R.string.root_manager_not_found));
        }
    }

    private Intent getRootManagerIntent() {
        PackageManager pm = getPackageManager();
        String[] packages = {
                "com.topjohnwu.magisk", // Magisk
                "me.weishu.kernelsu",   // KernelSU
                "me.bmax.apatch"        // APatch
        };

        for (String pkg : packages) {
            Intent intent = pm.getLaunchIntentForPackage(pkg);
            if (intent != null) return intent;
        }
        return null;
    }

    private void refreshStatus() {
        btnRefresh.setEnabled(false);
        btnRefresh.setText(R.string.status_checking);

        statusRootText.setText(R.string.root_access_checking);
        statusRootText.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray));
        statusRootText.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0);
        
        tvLsposedStatus.setText(R.string.checking_lsposed);
        tvLsposedStatus.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray));
        tvLsposedStatus.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0);

        executor.execute(() -> {
            // FIX: 'rootAccess()' is deprecated.
            // Use Shell.getShell().isRoot() instead.
            // This acquires a shell (if needed) and checks uid == 0.
            boolean isRooted;
            try {
                isRooted = Shell.getShell().isRoot();
            } catch (Exception e) {
                isRooted = false;
            }

            // Check LSPosed Module status
            LsposedStatus lsposedModuleStatus = getLsposedModuleStatus();

            final boolean finalIsRooted = isRooted;
            mainHandler.post(() -> {
                if (isFinishing() || isDestroyed()) return; // Safety check

                updateUi(finalIsRooted, lsposedModuleStatus);
                btnRefresh.setEnabled(true);
                btnRefresh.setText(R.string.refresh_status);
                showSnackbar(getString(R.string.status_refreshed));
            });
        });
    }

    private void updateUi(boolean isRooted, LsposedStatus lsposedModuleStatus) {
        // Update Root Status UI
        if (isRooted) {
            statusRootText.setText(R.string.root_access_granted);
            statusRootText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark));
            statusRootText.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_check_circle, 0, 0, 0);
        } else {
            statusRootText.setText(R.string.root_access_denied);
            statusRootText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark));
            statusRootText.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_error, 0, 0, 0);
        }

        // Update LSPosed Status UI
        if (lsposedModuleStatus == LsposedStatus.ACTIVE) {
            tvLsposedStatus.setText(R.string.lsposed_module_enabled);
            tvLsposedStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark));
            tvLsposedStatus.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_check_circle, 0, 0, 0);
        } else {
            tvLsposedStatus.setText(R.string.lsposed_module_disabled);
            tvLsposedStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark));
            tvLsposedStatus.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_error, 0, 0, 0);
        }
    }

    private LsposedStatus getLsposedModuleStatus() {
        return isModuleActive ? LsposedStatus.ACTIVE : LsposedStatus.NOT_ACTIVE;
    }

    private void openUrl() {
        try {
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/LSPosed/LSPosed/releases"));
            startActivity(browserIntent);
        } catch (Exception e) {
            showSnackbar(getString(R.string.could_not_open_browser));
        }
    }

    private void showSnackbar(String message) {
        if (rootView != null) {
            Snackbar.make(rootView, message, Snackbar.LENGTH_SHORT).show();
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        getOnBackPressedDispatcher().onBackPressed();
        return true;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
        }
    }
}