package com.catsmoker.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;

public class RootLSPosedActivity extends AppCompatActivity {

    private TextView statusTextView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_root_lsposed);
        
        statusTextView = findViewById(R.id.status);

        Button refreshButton = findViewById(R.id.btn_refresh);
        refreshButton.setOnClickListener(v -> refreshStatus());

        Button installLSPosedButton = findViewById(R.id.btn_install_lsposed);
        installLSPosedButton.setOnClickListener(v -> {
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/LSPosed/LSPosed/releases"));
            startActivity(browserIntent);
        });

        refreshStatus();
    }

    private void refreshStatus() {
        boolean isRooted = isDeviceRooted();
        String statusText = "Root Status: " + (isRooted ? "Activated" : "Disabled");
        statusTextView.setText(statusText);
        Toast.makeText(this, "Status refreshed", Toast.LENGTH_SHORT).show();
    }

    private boolean isDeviceRooted() {
        String[] paths = {
                "/system/app/Superuser.apk", "/sbin/su", "/system/bin/su",
                "/system/xbin/su", "/data/local/xbin/su", "/data/local/bin/su",
                "/system/sd/xbin/su", "/system/bin/failsafe/su", "/data/local/su"
        };
        for (String path : paths) {
            if (new File(path).exists()) return true;
        }

        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"/system/xbin/which", "su"});
            BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream()));
            return in.readLine() != null;
        } catch (Throwable t) {
            return false;
        } finally {
            if (process != null) process.destroy();
        }
    }
}