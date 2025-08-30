package com.catsmoker.app;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FeaturesActivity extends AppCompatActivity {

    private static final String TAG = "FeaturesActivity";
    private TextView memoryStatusText;

    // Crosshair variables
    private MaterialButton btnToggleCrosshair;
    private int selectedScopeResourceId = R.drawable.scope2;
    private final Map<Integer, MaterialCardView> scopeCardMap = new HashMap<>();

    private final androidx.activity.result.ActivityResultLauncher<Intent> overlayPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "Overlay permission granted", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "Overlay permission is required", Toast.LENGTH_SHORT).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_features);

        memoryStatusText = findViewById(R.id.memory_status_text);
        Button btnCleanRam = findViewById(R.id.btn_clean_ram);
        Button btnCleanCache = findViewById(R.id.btn_clean_cache);
        btnCleanRam.setOnClickListener(v -> cleanRam());
        btnCleanCache.setOnClickListener(v -> cleanCache());
        updateMemoryInfo();

        Button btnOpenGameLauncher = findViewById(R.id.btn_open_game_launcher);
        btnOpenGameLauncher.setOnClickListener(v -> {
            Toast.makeText(this, "Game Launcher coming soon!", Toast.LENGTH_SHORT).show();
        });

        Spinner dnsSpinner = findViewById(R.id.dns_spinner);
        Button btnApplyDns = findViewById(R.id.btn_apply_dns);
        setupDnsSpinner(dnsSpinner);
        btnApplyDns.setOnClickListener(v -> applyDnsChanges(dnsSpinner));

        btnToggleCrosshair = findViewById(R.id.btn_toggle_crosshair);
        setupScopeSelection();
        updateCrosshairButtonState(isServiceRunning());
        updateScopeSelectionUI(selectedScopeResourceId);
        btnToggleCrosshair.setOnClickListener(v -> toggleCrosshair());
    }

    private void updateMemoryInfo() {
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        ActivityManager activityManager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        activityManager.getMemoryInfo(mi);
        double percentAvail = (double) mi.availMem / mi.totalMem * 100.0;
        double percentUsed = 100.0 - percentAvail;
        memoryStatusText.setText(String.format("RAM Usage: %.2f%%", percentUsed));
    }

    private void cleanRam() {
        Toast.makeText(this, "Cleaning RAM...", Toast.LENGTH_SHORT).show();
        ActivityManager activityManager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        List<ActivityManager.RunningAppProcessInfo> runningProcesses = activityManager.getRunningAppProcesses();
        for (ActivityManager.RunningAppProcessInfo processInfo : runningProcesses) {
            if (processInfo.importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
                    && !processInfo.processName.equals(getPackageName())) {
                activityManager.killBackgroundProcesses(processInfo.processName);
            }
        }
        Toast.makeText(this, "RAM Cleaned!", Toast.LENGTH_SHORT).show();
        updateMemoryInfo();
    }

    private void cleanCache() {
        Toast.makeText(this, "Cleaning app cache...", Toast.LENGTH_SHORT).show();
        try {
            File dir = getCacheDir();
            deleteDir(dir);
            Toast.makeText(this, "Cache cleaned successfully!", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Failed to clean cache.", Toast.LENGTH_SHORT).show();
        }
    }

    public static boolean deleteDir(File dir) {
        if (dir != null && dir.isDirectory()) {
            String[] children = dir.list();
            for (String child : children) {
                boolean success = deleteDir(new File(dir, child));
                if (!success) {
                    return false;
                }
            }
            return dir.delete();
        } else if(dir!= null && dir.isFile()) {
            return dir.delete();
        } else {
            return false;
        }
    }

    private void setupDnsSpinner(Spinner spinner) {
        String[] dnsOptions = {"Default", "Google (8.8.8.8)", "Cloudflare (1.1.1.1)"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, dnsOptions);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
    }

    private void applyDnsChanges(Spinner spinner) {
        String selectedDns = spinner.getSelectedItem().toString();
        String dns1 = "";
        String dns2 = "";

        if (selectedDns.contains("Google")) {
            dns1 = "8.8.8.8";
            dns2 = "8.8.4.4";
        } else if (selectedDns.contains("Cloudflare")) {
            dns1 = "1.1.1.1";
            dns2 = "1.0.0.1";
        }

        Toast.makeText(this, "Applying DNS... (Root Required)", Toast.LENGTH_SHORT).show();
        try {
            String command1 = "setprop net.dns1 " + (dns1.isEmpty() ? "" : dns1);
            String command2 = "setprop net.dns2 " + (dns2.isEmpty() ? "" : dns2);
            String command3 = "settings put global private_dns_mode off";

            Runtime.getRuntime().exec(new String[]{"su", "-c", command1});
            Runtime.getRuntime().exec(new String[]{"su", "-c", command2});
            Runtime.getRuntime().exec(new String[]{"su", "-c", command3});

            Toast.makeText(this, "DNS applied successfully!", Toast.LENGTH_LONG).show();
        } catch (IOException e) {
            Toast.makeText(this, "Failed to apply DNS. Is your device rooted?", Toast.LENGTH_LONG).show();
            Log.e(TAG, "DNS Change Failed", e);
        }
    }

    private void toggleCrosshair() {
        if (isServiceRunning()) {
            stopService(new Intent(this, CrosshairOverlayService.class));
            Toast.makeText(this, "Crosshair Deactivated", Toast.LENGTH_SHORT).show();
            updateCrosshairButtonState(false);
        } else {
            if (Settings.canDrawOverlays(this)) {
                Intent serviceIntent = new Intent(this, CrosshairOverlayService.class);
                serviceIntent.putExtra(CrosshairOverlayService.EXTRA_SCOPE_RESOURCE_ID, selectedScopeResourceId);
                startService(serviceIntent);
                Toast.makeText(this, "Crosshair Activated", Toast.LENGTH_SHORT).show();
                updateCrosshairButtonState(true);
            } else {
                requestOverlayPermission();
            }
        }
    }

    private void setupScopeSelection() {
        MaterialCardView cardScope1 = findViewById(R.id.card_scope1);
        MaterialCardView cardScope2 = findViewById(R.id.card_scope2);
        MaterialCardView cardScope3 = findViewById(R.id.card_scope3);
        MaterialCardView cardScope4 = findViewById(R.id.card_scope4);

        scopeCardMap.put(R.drawable.scope1, cardScope1);
        scopeCardMap.put(R.drawable.scope2, cardScope2);
        scopeCardMap.put(R.drawable.scope3, cardScope3);
        scopeCardMap.put(R.drawable.scope4, cardScope4);

        cardScope1.setOnClickListener(v -> selectScope(R.drawable.scope1));
        cardScope2.setOnClickListener(v -> selectScope(R.drawable.scope2));
        cardScope3.setOnClickListener(v -> selectScope(R.drawable.scope3));
        cardScope4.setOnClickListener(v -> selectScope(R.drawable.scope4));
    }

    private void selectScope(int scopeResourceId) {
        selectedScopeResourceId = scopeResourceId;
        Toast.makeText(this, "Scope selected", Toast.LENGTH_SHORT).show();
        updateScopeSelectionUI(scopeResourceId);
        if (isServiceRunning()) {
            Intent serviceIntent = new Intent(this, CrosshairOverlayService.class);
            serviceIntent.putExtra(CrosshairOverlayService.EXTRA_SCOPE_RESOURCE_ID, selectedScopeResourceId);
            startService(serviceIntent);
            Toast.makeText(this, "Crosshair updated", Toast.LENGTH_SHORT).show();
        }
    }

    private void updateScopeSelectionUI(int selectedId) {
        int accentColor = ContextCompat.getColor(this, R.color.colorSecondary);
        for (Map.Entry<Integer, MaterialCardView> entry : scopeCardMap.entrySet()) {
            MaterialCardView card = entry.getValue();
            if (entry.getKey() == selectedId) {
                card.setStrokeColor(accentColor);
            } else {
                card.setStrokeColor(Color.TRANSPARENT);
            }
        }
    }

    private void updateCrosshairButtonState(boolean isRunning) {
        btnToggleCrosshair.setText(isRunning ? "Deactivate Crosshair" : "Activate Crosshair");
    }

    private boolean isServiceRunning() {
        ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (CrosshairOverlayService.class.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    private void requestOverlayPermission() {
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                .setData(android.net.Uri.parse("package:" + getPackageName()));
        overlayPermissionLauncher.launch(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateCrosshairButtonState(isServiceRunning());
        updateMemoryInfo();
    }
}