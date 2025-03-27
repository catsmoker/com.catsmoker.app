package com.catsmoker.app;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        TextView appInfo = findViewById(R.id.app_info);
        String info = "CatSmoker V1.5\n" +
                "Processor: " + System.getProperty("os.arch") + "\n" +
                "Model: " + Build.MODEL;
        appInfo.setText(info);

        setupButton(R.id.btn_root_lsposed, RootLSPosedActivity.class);
        setupButton(R.id.btn_shizuku, ShizukuActivity.class);
        setupButton(R.id.btn_crosshair, FeaturesActivity.class);
        setupButton(R.id.btn_website, WebsiteActivity.class);
        setupButton(R.id.btn_about, AboutActivity.class);
        setupButton(R.id.btn_exit, this::finish);
    }

    private void setupButton(int buttonId, Class<?> activityClass) {
        Button button = findViewById(buttonId);
        button.setOnClickListener(v -> {
            Intent intent = new Intent(this, activityClass);
            startActivity(intent);
        });
    }

    private void setupButton(int buttonId, Runnable action) {
        Button button = findViewById(buttonId);
        button.setOnClickListener(v -> action.run());
    }
}
