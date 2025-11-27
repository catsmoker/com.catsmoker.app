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

    private TextView statusRootText;
    private TextView statusLsposedText;
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
            actionBar.setTitle(R.string.root_lsposed_title);
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
    }

    private void initViews() {
        rootView = findViewById(android.R.id.content);
        statusRootText = findViewById(R.id.tv_root_status);
        statusLsposedText = findViewById(R.id.tv_lsposed_status);
        btnRefresh = findViewById(R.id.btn_refresh);
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

        executor.execute(() -> {
            // 1. Check Root
            boolean isRooted = Shell.cmd("id").exec().isSuccess();

            // 2. Check LSPosed
            boolean isLsposedInstalled = isPackageInstalled();

            mainHandler.post(() -> {
                updateUi(isRooted, isLsposedInstalled);
                btnRefresh.setEnabled(true);
                btnRefresh.setText(R.string.refresh_status);
                showSnackbar(getString(R.string.status_refreshed));
            });
        });
    }

    private void updateUi(boolean isRooted, boolean isLsposedInstalled) {
        // Update Root Status
        if (isRooted) {
            statusRootText.setText(R.string.root_access_granted);
            statusRootText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark));
            // Ensure R.drawable.ic_check_circle exists in your res/drawable folder!
            statusRootText.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_check_circle, 0, 0, 0);
        } else {
            statusRootText.setText(R.string.root_access_denied);
            statusRootText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark));
            // Ensure R.drawable.ic_error exists in your res/drawable folder!
            statusRootText.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_error, 0, 0, 0);
        }

        // Update LSPosed Status
        if (isLsposedInstalled) {
            statusLsposedText.setText(R.string.lsposed_manager_installed);
            statusLsposedText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark));
        } else {
            statusLsposedText.setText(R.string.lsposed_manager_not_found);
            statusLsposedText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark));
        }
    }

    private boolean isPackageInstalled() {
        try {
            getPackageManager().getPackageInfo("org.lsposed.manager", 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
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