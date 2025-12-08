package com.catsmoker.app;

import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.snackbar.Snackbar;
import com.topjohnwu.superuser.Shell;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FeaturesActivity extends AppCompatActivity {

    private static final String TAG = "FeaturesActivity";

    // UI Components
    private MaterialButton btnToggleCrosshair;
    private MaterialButton btnToggleOverlay;
    private Spinner dnsSpinner;
    private Button btnApplyDns;
    private View rootLayout; // For showing Snackbars

    // State
    private int selectedScopeResourceId = R.drawable.scope2;
    private final Map<Integer, MaterialCardView> scopeCardMap = new HashMap<>();
    private boolean isRootedCached = false;
    private int selectedStrokeWidthPx;
    private boolean isServiceRunning = false;

    // Background Execution
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final ActivityResultLauncher<Intent> overlayPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (canDrawOverlays()) {
                    showSnackbar("Overlay permission granted");
                    // Automatically activate if permission just granted
                    toggleOverlayService();
                } else {
                    showSnackbar("Permission denied. Overlay requires overlay access.");
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_features);

        // Initialize Root Shell asynchronously to avoid UI freeze
        Shell.getShell();

        initViews();
        setupToolbar();

        // REMOVED: resolveThemeColors();

        selectedStrokeWidthPx = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 2, getResources().getDisplayMetrics());

        // Check root once at startup
        checkRootStatus();

        setupDnsFeature();
        setupCrosshairFeature();
        setupScopeSelection();
        setupOverlayFeature();
    }

    private void initViews() {
        rootLayout = findViewById(android.R.id.content);
        dnsSpinner = findViewById(R.id.dns_spinner);
        btnApplyDns = findViewById(R.id.btn_apply_dns);
        btnToggleCrosshair = findViewById(R.id.btn_toggle_crosshair);
        btnToggleOverlay = findViewById(R.id.btn_toggle_overlay);
    }

    private void setupToolbar() {
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setTitle(R.string.features_button);
        }
    }

    // REMOVED: private void resolveThemeColors() { ... }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    // --- DNS Feature ---

    private void setupDnsFeature() {
        String[] dnsOptions = {"Default (DHCP)", "Google (8.8.8.8)", "Cloudflare (1.1.1.1)"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, dnsOptions);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        dnsSpinner.setAdapter(adapter);

        btnApplyDns.setOnClickListener(v -> applyDnsChanges());
    }

    private void applyDnsChanges() {
        if (!isRootedCached) {
            showSnackbar(getString(R.string.dns_changer_toast_root_required));
            return;
        }

        btnApplyDns.setEnabled(false); // Prevent double clicks

        String selectedDns = dnsSpinner.getSelectedItem().toString();
        String dns1 = "";
        String dns2 = "";

        if (selectedDns.contains("Google")) {
            dns1 = "8.8.8.8";
            dns2 = "8.8.4.4";
        } else if (selectedDns.contains("Cloudflare")) {
            dns1 = "1.1.1.1";
            dns2 = "1.0.0.1";
        }

        // Run shell commands in background
        final String d1 = dns1;
        final String d2 = dns2;

        Shell.cmd(
                "setprop net.dns1 " + d1,
                "setprop net.dns2 " + d2,
                "settings put global private_dns_mode off"
        ).submit(result -> {
            // Callback runs on Main Thread by default in libsu
            btnApplyDns.setEnabled(true);
            if (result.isSuccess()) {
                showSnackbar(getString(R.string.dns_changer_toast_success));
            } else {
                showSnackbar(getString(R.string.dns_changer_toast_failure));
                Log.e(TAG, "DNS Error: " + result.getErr());
            }
        });
    }

    // --- Crosshair Feature ---

    private void setupCrosshairFeature() {
        updateCrosshairButtonState(CrosshairOverlayService.isRunning);
        btnToggleCrosshair.setOnClickListener(v -> toggleCrosshair());
    }

    private void toggleCrosshair() {
        if (CrosshairOverlayService.isRunning) {
            // Stop Service
            stopService(new Intent(this, CrosshairOverlayService.class));
            showSnackbar(getString(R.string.crosshair_toast_deactivated));
            updateCrosshairButtonState(false);
        } else {
            // Start Service
            if (canDrawOverlays()) {
                startCrosshairService();
                showSnackbar(getString(R.string.crosshair_toast_activated));
                updateCrosshairButtonState(true);
            } else {
                requestOverlayPermission();
            }
        }
    }

    private void startCrosshairService() {
        Intent serviceIntent = new Intent(this, CrosshairOverlayService.class);
        serviceIntent.putExtra(CrosshairOverlayService.EXTRA_SCOPE_RESOURCE_ID, selectedScopeResourceId);
        startForegroundService(serviceIntent);
    }

    private void updateCrosshairButtonState(boolean isRunning) {
        btnToggleCrosshair.setText(isRunning ? "Deactivate Crosshair" : "Activate Crosshair");
    }

    // --- Scope Selection ---

    private void setupScopeSelection() {
        // IDs of the cards in XML
        int[] cardIds = {R.id.card_scope1, R.id.card_scope2, R.id.card_scope3, R.id.card_scope4, R.id.card_scope5, R.id.card_scope6, R.id.card_scope7};
        // Resource IDs of the drawables
        int[] drawables = {R.drawable.scope1, R.drawable.scope2, R.drawable.scope3, R.drawable.scope4, R.drawable.scope5, R.drawable.scope6, R.drawable.scope7};

        for (int i = 0; i < cardIds.length; i++) {
            MaterialCardView card = findViewById(cardIds[i]);
            if (card != null) {
                int resourceId = drawables[i];
                scopeCardMap.put(resourceId, card);
                card.setOnClickListener(v -> selectScope(resourceId));
            }
        }

        // Initial UI update
        updateScopeSelectionUI(selectedScopeResourceId);
    }

    private void selectScope(int scopeResourceId) {
        selectedScopeResourceId = scopeResourceId;
        updateScopeSelectionUI(scopeResourceId);

        if (CrosshairOverlayService.isRunning) {
            // Restart/Update service to show new scope immediately
            startCrosshairService();
            showSnackbar(getString(R.string.crosshair_toast_scope_updated));
        } else {
            showSnackbar(getString(R.string.crosshair_toast_scope_selected));
        }
    }

    private void updateScopeSelectionUI(int selectedId) {
        // CHANGED: Get the color directly from resources instead of resolving an attribute
        int highlightColor = getColor(R.color.md_theme_primary);

        for (Map.Entry<Integer, MaterialCardView> entry : scopeCardMap.entrySet()) {
            MaterialCardView card = entry.getValue();
            if (entry.getKey() == selectedId) {
                card.setStrokeColor(highlightColor);
                card.setStrokeWidth(selectedStrokeWidthPx); // Make selection more visible
            } else {
                card.setStrokeColor(Color.TRANSPARENT);
                card.setStrokeWidth(0);
            }
        }
    }

    // --- Overlay Feature ---

    private void setupOverlayFeature() {
        isServiceRunning = isServiceRunning();
        updateOverlayButtonText();
        btnToggleOverlay.setOnClickListener(v -> toggleOverlayService());
    }

    private boolean isServiceRunning() {
        android.app.ActivityManager manager = (android.app.ActivityManager) getSystemService(android.content.Context.ACTIVITY_SERVICE);
        for (android.app.ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (PerformanceOverlayService.class.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    private void toggleOverlayService() {
        if (!isServiceRunning) {
            if (!canDrawOverlays()) {
                requestOverlayPermission();
            } else {
                startService(new Intent(this, PerformanceOverlayService.class));
                isServiceRunning = true;
                updateOverlayButtonText();
            }
        } else {
            stopService(new Intent(this, PerformanceOverlayService.class));
            isServiceRunning = false;
            updateOverlayButtonText();
        }
    }

    private void updateOverlayButtonText() {
        if (isServiceRunning) {
            btnToggleOverlay.setText(R.string.stop_overlay);
        } else {
            btnToggleOverlay.setText(R.string.launch_overlay);
        }
    }

    // --- Helpers ---

    private void checkRootStatus() {
        executorService.execute(() -> {
            boolean rooted = Shell.cmd("su -c 'echo root_check'").exec().isSuccess();
            mainHandler.post(() -> isRootedCached = rooted);
        });
    }

    private boolean canDrawOverlays() {
        return Settings.canDrawOverlays(this);
    }

    private void requestOverlayPermission() {
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                .setData(Uri.parse("package:" + getPackageName()));
        overlayPermissionLauncher.launch(intent);
    }

    private void showSnackbar(String message) {
        Snackbar.make(rootLayout, message, Snackbar.LENGTH_SHORT).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateCrosshairButtonState(CrosshairOverlayService.isRunning);
        updateOverlayButtonText();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executorService.shutdown();
    }
}