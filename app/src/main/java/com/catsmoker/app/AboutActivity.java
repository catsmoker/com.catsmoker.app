package com.catsmoker.app;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
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

    private static final String TAG = "AboutActivity";
    private int iconClickCount = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        setupToolbar();
        setupBackButton();
        displayAppVersion();
        setupEasterEgg();

        Button checkForUpdatesButton = findViewById(R.id.check_for_updates_button);
        ToggleButton releaseToggle = findViewById(R.id.release_toggle);
        Button donateButton = findViewById(R.id.donate_button);
        Button legalButton = findViewById(R.id.legal_button);
        Button githubButton = findViewById(R.id.github_button);

        donateButton.setOnClickListener(v -> openUrl("https://catsmoker.vercel.app/#donate-section"));
        legalButton.setOnClickListener(v -> openUrl("https://catsmoker.vercel.app/Legal"));
        githubButton.setOnClickListener(v -> openUrl("https://github.com/catsmoker/com.catsmoker.app"));

        checkForUpdatesButton.setOnClickListener(v -> {
            // CRITICAL FIX: Access UI element on the main thread, then pass value to thread
            boolean isPreRelease = releaseToggle.isChecked();
            performUpdateCheck(isPreRelease);
        });
    }

    private void openUrl(String url) {
        try {
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(browserIntent);
        } catch (Exception e) {
            Toast.makeText(this, "Could not open browser", Toast.LENGTH_SHORT).show();
        }
    }

    private void performUpdateCheck(boolean isPreRelease) {
        new Thread(() -> {
            HttpURLConnection urlConnection = null;
            try {
                URL url = new URL("https://api.github.com/repos/catsmoker/com.catsmoker.app/releases");
                urlConnection = (HttpURLConnection) url.openConnection();
                urlConnection.setConnectTimeout(5000);
                urlConnection.setReadTimeout(5000);

                StringBuilder stringBuilder = new StringBuilder();
                // Improved: Try-with-resources auto-closes the reader
                try (BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(urlConnection.getInputStream()))) {
                    String line;
                    while ((line = bufferedReader.readLine()) != null) {
                        stringBuilder.append(line).append("\n");
                    }
                }

                JSONArray releases = new JSONArray(stringBuilder.toString());
                if (releases.length() > 0) {
                    JSONObject latestRelease = null;

                    // Filter for pre-release vs stable
                    for (int i = 0; i < releases.length(); i++) {
                        JSONObject release = releases.getJSONObject(i);
                        if (release.getBoolean("prerelease") == isPreRelease) {
                            latestRelease = release;
                            break;
                        }
                    }

                    // Fallback: If user wanted pre-release but none found, get latest stable
                    if (latestRelease == null && isPreRelease) {
                        latestRelease = releases.getJSONObject(0);
                    }

                    if (latestRelease != null) {
                        processReleaseData(latestRelease);
                    }
                }
            } catch (Exception e) {
                // Fixed: Replaced printStackTrace with Log
                Log.e(TAG, "Update check failed", e);
                runOnUiThread(() -> Toast.makeText(this, "Failed to check for updates.", Toast.LENGTH_SHORT).show());
            } finally {
                if (urlConnection != null) {
                    urlConnection.disconnect();
                }
            }
        }).start();
    }

    private void processReleaseData(JSONObject latestRelease) {
        try {
            String tagName = latestRelease.getString("tag_name");
            String htmlUrl = latestRelease.getString("html_url");

            // Extracted method as requested
            String githubVersion = parseVersionFromTag(tagName);
            String currentVersion = BuildConfig.VERSION_NAME;

            if (compareVersions(githubVersion, currentVersion) > 0) {
                runOnUiThread(() -> showUpdateDialog(tagName, htmlUrl));
            } else {
                runOnUiThread(() -> Toast.makeText(this, "You are on the latest version.", Toast.LENGTH_SHORT).show());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing release data", e);
        }
    }

    private void showUpdateDialog(String tagName, String downloadUrl) {
        if (isFinishing()) return; // Prevent crash if activity is closed

        new AlertDialog.Builder(this)
                .setTitle("Update Available")
                .setMessage("A new version (" + tagName + ") is available. Would you like to update?")
                .setPositiveButton("Update", (dialog, which) -> openUrl(downloadUrl))
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Extracts the version string from a GitHub tag.
     * Handles formats like "v1.0.0", "12-1.0.0", "1.0.0"
     */
    private String parseVersionFromTag(String tagName) {
        if (tagName == null) return "0.0.0";

        // Remove "v" prefix if present
        String cleanTag = tagName.startsWith("v") ? tagName.substring(1) : tagName;

        // Extract versionName from "versionCode-versionName" format (e.g., "15-1.2.0")
        if (cleanTag.contains("-")) {
            return cleanTag.substring(cleanTag.indexOf("-") + 1);
        } else {
            return cleanTag;
        }
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
     *   > 0 if version1 is newer
     *   < 0 if version1 is older
     *   0 if equal
     */
    private int compareVersions(String version1, String version2) {
        try {
            String[] parts1 = version1.split("\\.");
            String[] parts2 = version2.split("\\.");

            int length = Math.max(parts1.length, parts2.length);
            for (int i = 0; i < length; i++) {
                // Parse int safely, treating missing parts as 0
                int v1 = (i < parts1.length) ? Integer.parseInt(parts1[i]) : 0;
                int v2 = (i < parts2.length) ? Integer.parseInt(parts2[i]) : 0;

                if (v1 < v2) return -1;
                if (v1 > v2) return 1;
            }
        } catch (NumberFormatException e) {
            Log.e(TAG, "Error comparing versions: " + version1 + " vs " + version2, e);
            // If parsing fails (e.g. "beta"), assume current version is fine to avoid loops
            return 0;
        }
        return 0;
    }
}