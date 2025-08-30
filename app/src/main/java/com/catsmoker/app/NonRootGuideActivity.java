package com.catsmoker.app;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuRemoteProcess;

public class NonRootGuideActivity extends AppCompatActivity {

    private Spinner gameSpinner;
    private Button btnLaunchGame, btnStartZArchiver, btnStartShizuku, btnStartSaf;
    private ProgressBar progressBar;

    private ActivityResultLauncher<Intent> safLauncher;
    private ActivityResultLauncher<Intent> storagePermissionLauncher;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final String TAG = "NonRootGuideActivity";
    private static final int LEGACY_REQUEST_STORAGE_PERMISSION = 1001;
    private static final String ZARCHIVER_PACKAGE = "ru.zdevs.zarchiver";

    private final Map<GameType, GameConfig> gameConfigs = new HashMap<>();

    enum GameType {
        NONE("Select a game"),
        PUBG("PUBG Mobile"),
        COD("Call of Duty Mobile");

        private final String displayName;
        GameType(String displayName) { this.displayName = displayName; }
        @NonNull @Override public String toString() { return displayName; }
    }

    static class GameConfig {
        final String packageName;
        final String saveDir;
        final String saveFile;
        final String assetPath;

        GameConfig(String packageName, String saveDir, String saveFile, String assetPath) {
            this.packageName = packageName;
            this.saveDir = saveDir;
            this.saveFile = saveFile;
            this.assetPath = assetPath;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_non_root_guide);
        setTitle("Advanced Game File Manager");

        initializeLaunchers();
        initializeGameConfigs();
        initializeUI();
        setupListeners();
        setupShizuku();
    }

    private void initializeLaunchers() {
        safLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                Uri treeUri = result.getData().getData();
                if (treeUri != null) {
                    getContentResolver().takePersistableUriPermission(treeUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

                    GameType selectedGame = (GameType) gameSpinner.getSelectedItem();
                    performSafFileCopy(treeUri, selectedGame);
                }
            } else {
                showToast("SAF access not granted.");
            }
        });

        storagePermissionLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    showToast("Storage permission granted. Please click the button again.");
                } else {
                    showToast("Storage permission is required for file operations.");
                }
            }
        });
    }

    private void initializeGameConfigs() {
        gameConfigs.put(GameType.PUBG, new GameConfig(
                "com.tencent.ig",
                "/Android/data/com.tencent.ig/files/UE4Game/ShadowTrackerExtra/ShadowTrackerExtra/Saved/SaveGames/",
                "Active.sav",
                "PUBG/Active.sav"
        ));
        gameConfigs.put(GameType.COD, new GameConfig(
                "com.activision.callofduty.shooter",
                "/Android/data/com.activision.callofduty.shooter/files/",
                "playerPrefs.dat",
                "COD/playerPrefs.dat"
        ));
    }

    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse(String.format("package:%s", getPackageName())));
                    storagePermissionLauncher.launch(intent);
                } catch (Exception e) {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    storagePermissionLauncher.launch(intent);
                }
            }
        } else {
            ActivityCompat.requestPermissions(this, new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, LEGACY_REQUEST_STORAGE_PERMISSION);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LEGACY_REQUEST_STORAGE_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                showToast("Storage permission granted. Please click the button again.");
            } else {
                showToast("Storage permission is required for file operations.");
            }
        }
    }

    private void initializeUI() {
        TextView instructions = findViewById(R.id.instructions);
        instructions.setText("Select a game to manage its save files:\n\n" +
                "1. Choose your game from the list\n" +
                "2. Select a method to replace the save file\n" +
                " - Shizuku: Uses ADB shell commands (Recommended)\n" +
                " - SAF: Uses Android's file picker\n" +
                " - ZArchiver: Pastes file to Downloads folder");

        gameSpinner = findViewById(R.id.game_spinner);
        btnLaunchGame = findViewById(R.id.btn_launch_game);
        btnStartZArchiver = findViewById(R.id.btn_start_zarchiver);
        btnStartShizuku = findViewById(R.id.btn_start_shizuku);
        btnStartSaf = findViewById(R.id.btn_start_saf);
        progressBar = findViewById(R.id.progress_bar);

        ArrayAdapter<GameType> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, GameType.values());
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        gameSpinner.setAdapter(adapter);

        updateButtonVisibility(GameType.NONE);
    }

    private void setupListeners() {
        gameSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                GameType selectedGame = (GameType) parent.getItemAtPosition(position);
                updateButtonVisibility(selectedGame);
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                updateButtonVisibility(GameType.NONE);
            }
        });

        btnLaunchGame.setOnClickListener(v -> launchGame());
        btnStartZArchiver.setOnClickListener(v -> handleZArchiverAction());
        btnStartShizuku.setOnClickListener(v -> handleShizukuAction());
        btnStartSaf.setOnClickListener(v -> launchSafPicker());
    }

    private void setupShizuku() {
        Shizuku.addRequestPermissionResultListener((requestCode, grantResult) -> {
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                showToast("Shizuku permission granted.");
            } else {
                showToast("Shizuku permission denied.");
            }
        });
    }

    private void updateButtonVisibility(GameType game) {
        boolean isGameSelected = (game != GameType.NONE);
        btnLaunchGame.setVisibility(isGameSelected ? View.VISIBLE : View.GONE);
        btnStartZArchiver.setVisibility(isGameSelected ? View.VISIBLE : View.GONE);
        btnStartShizuku.setVisibility(isGameSelected ? View.VISIBLE : View.GONE);
        btnStartSaf.setVisibility(isGameSelected ? View.VISIBLE : View.GONE);
    }

    private boolean hasStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        } else {
            return ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }
    }

    private void handleZArchiverAction() {
        if (!hasStoragePermission()) {
            showToast("Storage permission is needed to save the file to Downloads.");
            requestStoragePermission();
            return;
        }

        GameType selectedGame = (GameType) gameSpinner.getSelectedItem();
        if (selectedGame == GameType.NONE) {
            showToast("Please select a game first.");
            return;
        }

        final GameConfig config = gameConfigs.get(selectedGame);
        if (config == null) {
            showToast("Error: Game configuration not found.");
            return;
        }

        executor.execute(() -> {
            runOnUiThread(() -> progressBar.setVisibility(View.VISIBLE));
            try {
                pasteFileToDownloads(config);
                runOnUiThread(() -> {
                    showToast("File saved to Downloads folder. Please open ZArchiver to move it.");
                    Intent intent = getPackageManager().getLaunchIntentForPackage(ZARCHIVER_PACKAGE);
                    if (intent != null) {
                        startActivity(intent);
                    } else {
                        showToast("ZArchiver not found. Opening Play Store...");
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + ZARCHIVER_PACKAGE)));
                    }
                });
            } catch (IOException e) {
                Log.e(TAG, "ZArchiver prep failed", e);
                runOnUiThread(() -> showToast("Error: " + e.getMessage()));
            } finally {
                runOnUiThread(() -> progressBar.setVisibility(View.GONE));
            }
        });
    }

    private void pasteFileToDownloads(GameConfig config) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = getContentResolver();
            ContentValues contentValues = new ContentValues();
            contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, config.saveFile);
            contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream");
            contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);

            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues);
            if (uri == null) {
                throw new IOException("Failed to create new MediaStore entry.");
            }

            try (InputStream in = getAssets().open(config.assetPath);
                 OutputStream out = resolver.openOutputStream(uri)) {
                if (out == null) {
                    throw new IOException("Failed to get output stream.");
                }
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
            }
        } else {
            File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File destinationFile = new File(downloadsDir, config.saveFile);
            copyAssetToFile(config.assetPath, destinationFile);
        }
    }

    private void handleShizukuAction() {
        if (!hasStoragePermission()) {
            showToast("Storage permission is needed.");
            requestStoragePermission();
            return;
        }

        GameType selectedGame = (GameType) gameSpinner.getSelectedItem();
        if (selectedGame == GameType.NONE) {
            showToast("Please select a game first.");
            return;
        }

        GameConfig config = gameConfigs.get(selectedGame);
        if (config == null) {
            showToast("Error: Game configuration not found.");
            return;
        }

        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(0);
            return;
        }

        executor.execute(() -> {
            runOnUiThread(() -> progressBar.setVisibility(View.VISIBLE));
            try {
                File tempFile = new File(getCacheDir(), config.saveFile);
                copyAssetToFile(config.assetPath, tempFile);

                String sourcePath = tempFile.getAbsolutePath();
                String destDir = Environment.getExternalStorageDirectory().getPath() + config.saveDir;
                String destPath = destDir + config.saveFile;

                @SuppressWarnings("deprecation")
                ShizukuRemoteProcess mkdirProcess = Shizuku.newProcess(new String[]{"sh", "-c", "mkdir -p \"" + destDir + "\""}, null, null);
                mkdirProcess.waitFor();

                @SuppressWarnings("deprecation")
                ShizukuRemoteProcess cpProcess = Shizuku.newProcess(new String[]{"sh", "-c", "cp \"" + sourcePath + "\" \"" + destPath + "\""}, null, null);
                cpProcess.waitFor();

                runOnUiThread(() -> {
                    if (cpProcess.exitValue() == 0) {
                        showToast("File replaced successfully using Shizuku!");
                    } else {
                        showToast("Shizuku Error: Exit code " + cpProcess.exitValue());
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Shizuku operation failed", e);
                runOnUiThread(() -> showToast("Error: " + e.getMessage()));
            } finally {
                runOnUiThread(() -> progressBar.setVisibility(View.GONE));
            }
        });
    }

    private void launchSafPicker() {
        if (!hasStoragePermission()) {
            showToast("Storage permission is needed to select a folder.");
            requestStoragePermission();
            return;
        }

        GameType selectedGame = (GameType) gameSpinner.getSelectedItem();
        if (selectedGame == GameType.NONE) {
            showToast("Please select a game first.");
            return;
        }

        showToast("IMPORTANT: Please select the FOLDER where the file is located.");
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        safLauncher.launch(intent);
    }

    private void performSafFileCopy(Uri treeUri, GameType game) {
        if (game == GameType.NONE) return;

        GameConfig config = gameConfigs.get(game);
        if (config == null) {
            showToast("Error: Game configuration not found.");
            return;
        }

        executor.execute(() -> {
            runOnUiThread(() -> progressBar.setVisibility(View.VISIBLE));
            try {
                DocumentFile pickedDir = DocumentFile.fromTreeUri(this, treeUri);

                if (pickedDir == null || !pickedDir.canWrite()) {
                    throw new IOException("No write permission for the selected directory.");
                }

                DocumentFile targetFile = pickedDir.findFile(config.saveFile);
                if (targetFile != null && targetFile.exists()) {
                    if (!targetFile.delete()) {
                        throw new IOException("Failed to delete existing file.");
                    }
                }

                targetFile = pickedDir.createFile("application/octet-stream", config.saveFile);
                if (targetFile == null) {
                    throw new IOException("Failed to create file in the selected directory.");
                }

                try (InputStream in = getAssets().open(config.assetPath);
                     OutputStream out = getContentResolver().openOutputStream(targetFile.getUri())) {
                    if (out == null) throw new IOException("Failed to open output stream.");
                    byte[] buffer = new byte[8192];
                    int length;
                    while ((length = in.read(buffer)) > 0) {
                        out.write(buffer, 0, length);
                    }
                }
                runOnUiThread(() -> showToast("File replaced successfully using SAF!"));

            } catch (Exception e) {
                Log.e(TAG, "SAF operation failed", e);
                runOnUiThread(() -> showToast("SAF Error: " + e.getMessage()));
            } finally {
                runOnUiThread(() -> progressBar.setVisibility(View.GONE));
            }
        });
    }

    private void launchGame() {
        GameType selectedGame = (GameType) gameSpinner.getSelectedItem();
        if (selectedGame == GameType.NONE) return;

        GameConfig config = gameConfigs.get(selectedGame);
        if (config == null) return;

        Intent intent = getPackageManager().getLaunchIntentForPackage(config.packageName);
        if (intent != null) {
            startActivity(intent);
        } else {
            showToast(selectedGame + " is not installed.");
        }
    }

    private void copyAssetToFile(String assetPath, File targetFile) throws IOException {
        try (InputStream in = getAssets().open(assetPath);
             FileOutputStream out = new FileOutputStream(targetFile)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
        }
    }

    private void showToast(String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}