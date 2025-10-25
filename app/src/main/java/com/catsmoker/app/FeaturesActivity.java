package com.catsmoker.app;

import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.Toast;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.topjohnwu.superuser.Shell;
import java.util.HashMap;
import java.util.Map;

public class FeaturesActivity extends AppCompatActivity {



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
        Shell.getShell();
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_features);

        Button btnOpenLteCleaner = findViewById(R.id.btn_open_lte_cleaner);
        btnOpenLteCleaner.setOnClickListener(v -> {
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/TheRedSpy15/LTECleanerFOSS"));
            startActivity(browserIntent);
        });

        Spinner dnsSpinner = findViewById(R.id.dns_spinner);
        Button btnApplyDns = findViewById(R.id.btn_apply_dns);
        setupDnsSpinner(dnsSpinner);
        btnApplyDns.setOnClickListener(v -> applyDnsChanges(dnsSpinner));

        btnToggleCrosshair = findViewById(R.id.btn_toggle_crosshair);
        updateCrosshairButtonState(CrosshairOverlayService.isRunning);
        updateScopeSelectionUI(selectedScopeResourceId);
        btnToggleCrosshair.setOnClickListener(v -> toggleCrosshair());
        setupScopeSelection();
    }



    private void setupDnsSpinner(Spinner spinner) {
        String[] dnsOptions = {"Default", "Google (8.8.8.8)", "Cloudflare (1.1.1.1)"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, dnsOptions);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
    }

    private void applyDnsChanges(Spinner spinner) {
        if (!isDeviceRooted()) {
            Toast.makeText(this, getString(R.string.dns_changer_toast_root_required), Toast.LENGTH_SHORT).show();
            return;
        }

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

        Toast.makeText(this, getString(R.string.dns_changer_toast_root_required), Toast.LENGTH_SHORT).show();
        Shell.cmd(
            "setprop net.dns1 " + (dns1.isEmpty() ? "" : dns1),
            "setprop net.dns2 " + (dns2.isEmpty() ? "" : dns2),
            "settings put global private_dns_mode off"
        ).submit(result -> {
            if (result.isSuccess()) {
                Toast.makeText(FeaturesActivity.this, getString(R.string.dns_changer_toast_success), Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(FeaturesActivity.this, getString(R.string.dns_changer_toast_failure), Toast.LENGTH_LONG).show();
            }
        });
    }

    private void toggleCrosshair() {
        if (CrosshairOverlayService.isRunning) {
            stopService(new Intent(this, CrosshairOverlayService.class));
            Toast.makeText(this, getString(R.string.crosshair_toast_deactivated), Toast.LENGTH_SHORT).show();
            updateCrosshairButtonState(false);
        } else {
            if (Settings.canDrawOverlays(this)) {
                Intent serviceIntent = new Intent(this, CrosshairOverlayService.class);
                serviceIntent.putExtra(CrosshairOverlayService.EXTRA_SCOPE_RESOURCE_ID, selectedScopeResourceId);
                startService(serviceIntent);
                Toast.makeText(this, getString(R.string.crosshair_toast_activated), Toast.LENGTH_SHORT).show();
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
        Toast.makeText(this, getString(R.string.crosshair_toast_scope_selected), Toast.LENGTH_SHORT).show();
        updateScopeSelectionUI(scopeResourceId);
        if (CrosshairOverlayService.isRunning) {
            Intent serviceIntent = new Intent(this, CrosshairOverlayService.class);
            serviceIntent.putExtra(CrosshairOverlayService.EXTRA_SCOPE_RESOURCE_ID, selectedScopeResourceId);
            startService(serviceIntent);
            Toast.makeText(this, getString(R.string.crosshair_toast_scope_updated), Toast.LENGTH_SHORT).show();
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



    private void requestOverlayPermission() {
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                .setData(android.net.Uri.parse("package:" + getPackageName()));
        overlayPermissionLauncher.launch(intent);
    }

    private boolean isDeviceRooted() {
        return Shell.cmd("su -c 'echo'").exec().isSuccess();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateCrosshairButtonState(CrosshairOverlayService.isRunning);

    }
}