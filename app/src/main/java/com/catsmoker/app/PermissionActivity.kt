package com.catsmoker.app;

import android.Manifest;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;

import android.os.PowerManager;
import com.topjohnwu.superuser.Shell;
import rikka.shizuku.Shizuku;

public class PermissionActivity extends AppCompatActivity {

    private static final int RC_NOTIFICATION = 101;
    private static final int RC_SHIZUKU = 102;

    private View cardNotification, cardStorage, cardOverlay, cardUsage, cardShizuku, cardRoot, cardBattery;
    private MaterialButton btnContinue, btnSkip;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_permissions);

        initViews();
        setupTexts();
        setupListeners();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStates();
    }

    private void initViews() {
        cardRoot = findViewById(R.id.layout_perm_root);
        cardNotification = findViewById(R.id.layout_perm_notification);
        cardStorage = findViewById(R.id.layout_perm_storage);
        cardBattery = findViewById(R.id.layout_perm_battery);
        cardOverlay = findViewById(R.id.layout_perm_overlay);
        cardUsage = findViewById(R.id.layout_perm_usage);
        cardShizuku = findViewById(R.id.layout_perm_shizuku);
        btnContinue = findViewById(R.id.btn_continue);
        btnSkip = findViewById(R.id.btn_skip);
    }

    private void setupTexts() {
        setCardText(cardRoot, R.string.perm_root_title, R.string.perm_root_desc);
        setCardText(cardNotification, R.string.perm_notification_title, R.string.perm_notification_desc);
        setCardText(cardStorage, R.string.perm_storage_title, R.string.perm_storage_desc);
        setCardText(cardBattery, R.string.perm_battery_title, R.string.perm_battery_desc);
        setCardText(cardOverlay, R.string.perm_overlay_title, R.string.perm_overlay_desc);
        setCardText(cardUsage, R.string.perm_usage_title, R.string.perm_usage_desc);
        setCardText(cardShizuku, R.string.perm_shizuku_title, R.string.perm_shizuku_desc);
    }

    private void setCardText(View card, int titleRes, int descRes) {
        TextView title = card.findViewById(R.id.perm_title);
        TextView desc = card.findViewById(R.id.perm_desc);
        title.setText(titleRes);
        desc.setText(descRes);
    }

    private void setupListeners() {
        getBtn(cardRoot).setOnClickListener(v -> requestRootPermission());
        getBtn(cardNotification).setOnClickListener(v -> requestNotificationPermission());
        getBtn(cardStorage).setOnClickListener(v -> requestStoragePermission());
        getBtn(cardBattery).setOnClickListener(v -> requestBatteryPermission());
        getBtn(cardOverlay).setOnClickListener(v -> requestOverlayPermission());
        getBtn(cardUsage).setOnClickListener(v -> requestUsagePermission());
        getBtn(cardShizuku).setOnClickListener(v -> requestShizukuPermission());

        btnContinue.setOnClickListener(v -> {
            // User intends to continue, so we reset the skip flag
            getSharedPreferences("app_prefs", MODE_PRIVATE).edit().putBoolean("permissions_skipped", false).apply();
            startActivity(new Intent(this, MainActivity.class));
            finish();
        });

        btnSkip.setOnClickListener(v -> {
            // User wants to skip permission checks
            getSharedPreferences("app_prefs", MODE_PRIVATE).edit().putBoolean("permissions_skipped", true).apply();
            startActivity(new Intent(this, MainActivity.class));
            finish();
        });
    }

    private MaterialButton getBtn(View card) {
        return card.findViewById(R.id.perm_action_btn);
    }

    private void refreshStates() {
        updateCardState(cardRoot, checkRootPermission());
        updateCardState(cardNotification, checkNotificationPermission());
        updateCardState(cardStorage, checkStoragePermission());
        updateCardState(cardBattery, checkBatteryPermission());
        updateCardState(cardOverlay, checkOverlayPermission());
        updateCardState(cardUsage, checkUsagePermission());
        updateCardState(cardShizuku, checkShizukuPermission());
    }

    private void updateCardState(View card, boolean isGranted) {
        MaterialButton btn = getBtn(card);
        if (isGranted) {
            btn.setText(R.string.perm_granted);
            btn.setIconResource(R.mipmap.ic_launcher_foreground); // Placeholder if no check icon
            // Better: use system check icon or just text
            // Since we don't have a check drawable ready (except standard android), let's just use text
            btn.setEnabled(false);
            btn.setAlpha(0.5f);
        } else {
            btn.setText(R.string.perm_grant);
            btn.setEnabled(true);
            btn.setAlpha(1.0f);
        }
    }

    // --- Permission Checks & Requests ---

    // 0. Root
    private boolean checkRootPermission() {
        // Non-blocking check if possible, or assume false if not cached
        // Using Shell.rootAccess() which is a synchronous check
        // Ideally should be async but for this UI update we do it here
        // If it hangs UI, we should wrap it. For now, assuming libsu is fast or cached.
        try {
            return Shell.getShell().isRoot();
        } catch (Exception e) {
            return false;
        }
    }

    private void requestRootPermission() {
        new Thread(() -> {
            boolean result = Shell.cmd("id").exec().isSuccess();
            runOnUiThread(this::refreshStates);
        }).start();
    }

    // Battery
    private boolean checkBatteryPermission() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void requestBatteryPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            try {
                startActivity(intent);
            } catch (Exception e) {
                Toast.makeText(this, "Could not open battery settings.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    // 1. Notification (Android 13+)
    private boolean checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        }
        return true; // Implied for older versions
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, RC_NOTIFICATION);
        }
    }

    // 2. Storage (All Files Access for Android 11+)
    private boolean checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        } else {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }
    }

    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.addCategory("android.intent.category.DEFAULT");
                intent.setData(Uri.parse(String.format("package:%s", getPackageName())));
                startActivity(intent);
            } catch (Exception e) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                startActivity(intent);
            }
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 103);
        }
    }

    // 3. Overlay
    private boolean checkOverlayPermission() {
        return Settings.canDrawOverlays(this);
    }

    private void requestOverlayPermission() {
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    // 4. Usage Access
    private boolean checkUsagePermission() {
        AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
        int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    private void requestUsagePermission() {
        Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
        startActivity(intent);
    }

    // 5. Shizuku
    private boolean checkShizukuPermission() {
        try {
            if (Shizuku.isPreV11()) return false; 
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable e) {
            return false; // Shizuku likely not running
        }
    }

    private void requestShizukuPermission() {
        try {
            if (Shizuku.isPreV11()) {
                // Not supported
                return;
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                if (Shizuku.shouldShowRequestPermissionRationale()) {
                    // Show explanation?
                }
                Shizuku.requestPermission(RC_SHIZUKU);
            }
        } catch (Throwable e) {
            Toast.makeText(this, "Shizuku not running or not installed.", Toast.LENGTH_SHORT).show();
        }
    }
}
