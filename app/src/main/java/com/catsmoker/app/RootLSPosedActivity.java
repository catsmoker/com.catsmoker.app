package com.catsmoker.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class RootLSPosedActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_root_lsposed);

        TextView instructions = findViewById(R.id.instructions);

        String instructionText = """
                Root & LSPosed Instructions:
                
                1. Check Root Access
                   - Ensure device is rooted
                   - Use 'Root Checker' to verify
                
                2. Install Magisk
                   - Download Magisk Canary
                
                3. (Optional) Install Shamiko Module
                   - Hides root detection
                
                4. Install LSPosed_mod Module
                   - Open Magisk and install
                
                5. Launch LSPosed Manager
                   - Open the app
                
                6. Enable the CatSmoker Module
                   - Modules → Search CatSmoker → Enable
                
                7. Manage Supported Games
                   - Supported games spoofed as OnePlus 12:
                
                8. Force Stop the Game
                   - Apply changes via LSPosed""";

        instructions.setText(instructionText);

        TextView status = findViewById(R.id.status);
        boolean isRooted = checkRootStatus();
        status.setText(isRooted ? "Activated" : "Disabled");

        Button refreshButton = findViewById(R.id.btn_refresh);
        refreshButton.setOnClickListener(v -> {
            boolean updatedRootStatus = checkRootStatus();
            status.setText(updatedRootStatus ? "Activated" : "Disabled");
            Toast.makeText(this, "Status refreshed", Toast.LENGTH_SHORT).show();
        });

        Button installLSPosedButton = findViewById(R.id.btn_install_lsposed);
        installLSPosedButton.setOnClickListener(v -> {
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/LSPosed/LSPosed/releases"));
            startActivity(browserIntent);
        });

        Button launchLSPosedButton = findViewById(R.id.btn_launch_lsposed);
        launchLSPosedButton.setOnClickListener(v -> {
            Intent intent = getPackageManager().getLaunchIntentForPackage("org.lsposed.manager");
            if (intent != null) {
                startActivity(intent);
            } else {
                Toast.makeText(this, "LSPosed Manager not installed", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private boolean checkRootStatus() {
        try {
            Runtime.getRuntime().exec("su");
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}