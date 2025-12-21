package com.catsmoker.app;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

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

        Button checkForUpdatesButton = findViewById(R.id.check_for_updates_button);
        ToggleButton releaseToggle = findViewById(R.id.release_toggle);

        checkForUpdatesButton.setOnClickListener(v -> new Thread(() -> {
            try {
                boolean isPreRelease = releaseToggle.isChecked();
                URL url = new URL("https://api.github.com/repos/catsmoker/com.catsmoker.app/releases");
                HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
                try {
                    BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(urlConnection.getInputStream()));
                    StringBuilder stringBuilder = new StringBuilder();
                    String line;
                    while ((line = bufferedReader.readLine()) != null) {
                        stringBuilder.append(line).append("\n");
                    }
                    bufferedReader.close();
                    JSONArray releases = new JSONArray(stringBuilder.toString());
                    if (releases.length() > 0) {
                        JSONObject latestRelease = null;
                        for (int i = 0; i < releases.length(); i++) {
                            JSONObject release = releases.getJSONObject(i);
                            if (release.getBoolean("prerelease") == isPreRelease) {
                                latestRelease = release;
                                break;
                            }
                        }

                        if (latestRelease == null && isPreRelease) { // if no prerelease found, check for latest stable
                            latestRelease = releases.getJSONObject(0);
                        }


                        if (latestRelease != null) {
                            String tagName = latestRelease.getString("tag_name");
                            // Remove "v" prefix if present
                            String githubVersionWithCode = tagName.startsWith("v") ? tagName.substring(1) : tagName;
                            // Extract versionName from "versionCode-versionName" format
                            String githubVersion;
                            if (githubVersionWithCode.contains("-")) {
                                githubVersion = githubVersionWithCode.substring(githubVersionWithCode.indexOf("-") + 1);
                            } else {
                                githubVersion = githubVersionWithCode; // Fallback if format is unexpected
                            }

                            String currentVersion = BuildConfig.VERSION_NAME;
                            if (compareVersions(githubVersion, currentVersion) > 0) {
                                String finalLatestRelease = latestRelease.getString("html_url");
                                runOnUiThread(() -> new AlertDialog.Builder(this)
                                        .setTitle("Update Available")
                                        .setMessage("A new version (" + tagName + ") is available. Would you like to update?")
                                        .setPositiveButton("Update", (dialog, which) -> {
                                            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(finalLatestRelease));
                                            startActivity(browserIntent);
                                        })
                                        .setNegativeButton("Cancel", null)
                                        .show());
                            } else {
                                runOnUiThread(() -> Toast.makeText(this, "You are on the latest version.", Toast.LENGTH_SHORT).show());
                            }
                        }
                    }
                } finally {
                    urlConnection.disconnect();
                }
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> Toast.makeText(this, "Failed to check for updates.", Toast.LENGTH_SHORT).show());
            }
        }).start());
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

    /**
     * Compares two version strings (e.g., "1.0.0", "1.2.1").
     * Returns:
     *   - an integer > 0 if version1 is newer than version2
     *   - an integer < 0 if version1 is older than version2
     *   - 0 if version1 and version2 are the same
     */
    private int compareVersions(String version1, String version2) {
        String[] parts1 = version1.split("\\.");
        String[] parts2 = version2.split("\\.");

        int length = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < length; i++) {
            int v1 = (i < parts1.length) ? Integer.parseInt(parts1[i]) : 0;
            int v2 = (i < parts2.length) ? Integer.parseInt(parts2[i]) : 0;

            if (v1 < v2) {
                return -1;
            } else if (v1 > v2) {
                return 1;
            }
        }
        return 0;
    }
}