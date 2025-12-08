package com.catsmoker.app;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;

public class AboutActivity extends AppCompatActivity {

    private int iconClickCount = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        // 1. Setup the Toolbar (Top Back Arrow)
        setupToolbar();

        // 2. Setup the Bottom Back Button
        setupBackButton();

        // 3. Display Version Name
        displayAppVersion();

        // 4. Setup the Easter Egg
        setupEasterEgg();
    }

    private void setupToolbar() {
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
    }

    private void setupBackButton() {
        Button backButton = findViewById(R.id.btn_back);
        if (backButton != null) {
            backButton.setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        }
    }

    @SuppressLint("SetTextI18n")
    private void displayAppVersion() {
        TextView tvVersion = findViewById(R.id.tv_version);
        if (tvVersion != null) {
            tvVersion.setText("v" + com.catsmoker.app.BuildConfig.VERSION_NAME);
        }
    }

    private void setupEasterEgg() {
        ImageView appIcon = findViewById(R.id.iv_app_icon);
        if (appIcon != null) {
            appIcon.setOnClickListener(v -> {
                iconClickCount++;
                if (iconClickCount == 7) {
                    Toast.makeText(this, "Meow... you found the secret stash!", Toast.LENGTH_SHORT).show();
                    iconClickCount = 0; // Reset counter
                }
            });
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            getOnBackPressedDispatcher().onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}