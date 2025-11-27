package com.catsmoker.app;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;

public class AboutActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        // 1. Setup the Toolbar (Top Back Arrow)
        setupToolbar();

        // 2. Setup the Bottom Back Button
        setupBackButton();

        // 3. Display Version Name (Fixed: No longer uses reflection)
        displayAppVersion();
    }

    private void setupToolbar() {
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            // Optional: Title is usually handled in AndroidManifest, but you can set it here too
            // actionBar.setTitle(R.string.about_title);
        }
    }

    private void setupBackButton() {
        // We use safe findViewById checks to prevent crashes if the ID changes in XML
        Button backButton = findViewById(R.id.btn_back);
        if (backButton != null) {
            backButton.setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        }
    }

    @SuppressLint("SetTextI18n")
    private void displayAppVersion() {
        // FIX: Removed getIdentifier() reflection.
        // We now reference R.id.tv_version directly. This is faster and enables build optimizations.
        TextView tvVersion = findViewById(R.id.tv_version);

        if (tvVersion != null) {
            // BuildConfig.VERSION_NAME is automatically generated from your build.gradle
            tvVersion.setText("v" + BuildConfig.VERSION_NAME);
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        // Handle the top-left arrow click
        if (item.getItemId() == android.R.id.home) {
            getOnBackPressedDispatcher().onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}