package com.catsmoker.app;

import android.content.Intent;
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
    private MaterialButton btnRefresh;
    private MaterialButton btnLsposedModuleEnabled; // New button for LSPosed status
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
        btnRefresh = findViewById(R.id.btn_refresh);
        btnLsposedModuleEnabled = findViewById(R.id.btn_lsposed_module_enabled); // Initialize new button
    }

    private void setupListeners() {
        btnRefresh.setOnClickListener(v -> refreshStatus());

        findViewById(R.id.btn_install_lsposed).setOnClickListener(v -> openUrl());

        // Improved Root Manager Launcher (Checks Magisk, KernelSU, APatch)
        findViewById(R.id.btn_open_manager).setOnClickListener(v -> {
            Intent intent = getPackageManager().getLaunchIntentForPackage("com.topjohnwu.magisk");
            if (intent == null) intent = getPackageManager().getLaunchIntentForPackage("me.weishu.kernelsu"); // KernelSU
            if (intent == null) intent = getPackageManager().getLaunchIntentForPackage("me.bmax.apatch"); // APatch

            if (intent != null) {
                startActivity(intent);
            } else {
                showSnackbar(getString(R.string.root_manager_not_found));
            }
        });
    }

    private void refreshStatus() {
        btnRefresh.setEnabled(false);
        btnRefresh.setText(R.string.status_checking);

        statusRootText.setText(R.string.root_access_checking);
        statusRootText.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray));
        statusRootText.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0); // Clear drawable

        executor.execute(() -> {
            // Check Root
            boolean isRooted = Shell.rootAccess();

            // Check LSPosed Module status
            LsposedStatus lsposedModuleStatus = getLsposedModuleStatus();

            mainHandler.post(() -> {
                updateUi(isRooted, lsposedModuleStatus);
                btnRefresh.setEnabled(true);
                btnRefresh.setText(R.string.refresh_status);
                showSnackbar(getString(R.string.status_refreshed));
            });
        });
    }

    private void updateUi(boolean isRooted, LsposedStatus lsposedModuleStatus) {
        // Update Root Status
        if (isRooted) {
            statusRootText.setText(R.string.root_access_granted);
            statusRootText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark));
            statusRootText.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_check_circle, 0, 0, 0);
        } else {
            statusRootText.setText(R.string.root_access_denied);
            statusRootText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark));
            statusRootText.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_error, 0, 0, 0);
        }

        // Update LSPosed Module Enabled Button
        if (btnLsposedModuleEnabled != null) {
            if (lsposedModuleStatus == LsposedStatus.ACTIVE) {
                btnLsposedModuleEnabled.setVisibility(View.VISIBLE);
                btnLsposedModuleEnabled.setEnabled(false); // Make it non-clickable
            } else {
                btnLsposedModuleEnabled.setVisibility(View.GONE);
            }
        }
    }

    private LsposedStatus getLsposedModuleStatus() {
        if (isModuleActive) {
            return LsposedStatus.ACTIVE;
        }
        return LsposedStatus.NOT_ACTIVE;
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
        Snackbar.make(rootView, message, Snackbar.LENGTH_SHORT).show();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}