package com.catsmoker.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuProvider;

public class ShizukuActivity extends AppCompatActivity {
    // Load native library
    static {
        System.loadLibrary("shizuku-native");
    }

    // Native method declaration
    private native boolean replaceFileWithShizuku(String sourcePath, String destPath);

    private Spinner gameSpinner;
    private Button btnLaunchGame, btnStartZArchiver, btnStartShizuku, btnStartSaf;
    private ProgressBar progressBar;
    private ActivityResultLauncher<Intent> safLauncher;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private static final String TAG = "ShizukuActivity";
    private static final int REQUEST_STORAGE_PERMISSION = 1001;
    private static final int BUFFER_SIZE = 8192;
    private static final String ZARCHIVER_PACKAGE = "ru.zdevs.zarchiver";

    private final Map<GameType, GameConfig> gameConfigs = new HashMap<>();

    enum GameType {
        NONE("Select a game"),
        PUBG("PUBG Mobile"),
        COD("Call of Duty Mobile");

        private final String displayName;

        GameType(String displayName) {
            this.displayName = displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    static class GameConfig {
        String packageName;
        String saveDir;
        String saveFile;
        String assetPath;

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
        setContentView(R.layout.activity_shizuku);
        setTitle("Advanced Game File Manager");

        // Initialize Shizuku provider
        ShizukuProvider.enableMultiProcessSupport(false);

        // Initialize game configurations
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

        requestStoragePermission();
        initializeUI();
        setupListeners();
        setupSafLauncher();
        setupShizuku();
    }

    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, REQUEST_STORAGE_PERMISSION);
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_STORAGE_PERMISSION);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_STORAGE_PERMISSION && (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED)) {
            showToast("Storage permission is required for file operations.");
        }
    }

    private void initializeUI() {
        TextView instructions = findViewById(R.id.instructions);
        instructions.setText("Select a game to manage its save files:\n\n" +
                "1. Choose your game from the list\n" +
                "2. Select a method to replace the save file\n" +
                " - Shizuku: Uses ADB shell commands\n" +
                " - SAF: Uses Android's file picker\n" +
                " - ZArchiver: Manual file replacement");

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

                if (selectedGame != GameType.NONE) {
                    showToast("Selected: " + selectedGame.toString());
                }
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
        Shizuku.addRequestPermissionResultListener(new Shizuku.OnRequestPermissionResultListener() {
            @Override
            public void onRequestPermissionResult(int requestCode, int grantResult) {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    showToast("Shizuku permission granted");
                } else {
                    showToast("Shizuku permission denied");
                }
            }
        });
    }

    private void setupSafLauncher() {
        safLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                Uri treeUri = result.getData().getData();
                if (treeUri != null) {
                    getContentResolver().takePersistableUriPermission(
                            treeUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    );
                    showToast("SAF access granted. You can now manage files.");
                }
            }
        });
    }

    private void updateButtonVisibility(GameType game) {
        if (game == GameType.NONE) {
            btnLaunchGame.setVisibility(View.GONE);
            btnStartZArchiver.setVisibility(View.GONE);
            btnStartShizuku.setVisibility(View.GONE);
            btnStartSaf.setVisibility(View.GONE);
        } else {
            btnLaunchGame.setVisibility(View.VISIBLE);
            btnStartZArchiver.setVisibility(View.VISIBLE);
            btnStartShizuku.setVisibility(View.VISIBLE);
            btnStartSaf.setVisibility(View.VISIBLE);
        }
    }

    private void handleShizukuAction() {
        GameType selectedGame = (GameType) gameSpinner.getSelectedItem();
        if (selectedGame == GameType.NONE) {
            showToast("Please select a game first.");
            return;
        }

        if (Shizuku.pingBinder()) {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                executor.execute(() -> {
                    runOnUiThread(() -> progressBar.setVisibility(View.VISIBLE));

                    try {
                        GameConfig config = gameConfigs.get(selectedGame);

                        // Prepare source file in app's internal storage
                        File tempFile = new File(getExternalFilesDir(null), config.saveFile);
                        copyAssetToFile(config.assetPath, tempFile);

                        // Destination path
                        String destPath = Environment.getExternalStorageDirectory() + config.saveDir + config.saveFile;

                        // Use Shizuku to replace the file
                        boolean success = replaceFileWithShizuku(tempFile.getAbsolutePath(), destPath);

                        runOnUiThread(() -> {
                            if (success) {
                                showToast("File replaced successfully using Shizuku!");
                            } else {
                                showToast("Failed to replace file using Shizuku");
                            }
                        });
                    } catch (IOException e) {
                        runOnUiThread(() -> showToast("Error: " + e.getMessage()));
                    } finally {
                        runOnUiThread(() -> progressBar.setVisibility(View.GONE));
                    }
                });
            } else {
                Shizuku.requestPermission(0);
            }
        } else {
            showToast("Shizuku is not available. Please install and start Shizuku first.");
            launchShizukuApp();
        }
    }

    private void launchSafPicker() {
        GameType selectedGame = (GameType) gameSpinner.getSelectedItem();
        if (selectedGame == GameType.NONE) {
            showToast("Please select a game first.");
            return;
        }

        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            GameConfig config = gameConfigs.get(selectedGame);
            String initialPath = Environment.getExternalStorageDirectory().getPath() + config.saveDir;
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(initialPath));
        }

        safLauncher.launch(intent);
    }

    private void launchGame() {
        GameType selectedGame = (GameType) gameSpinner.getSelectedItem();
        if (selectedGame == GameType.NONE) return;

        String packageName = gameConfigs.get(selectedGame).packageName;
        Intent intent = getPackageManager().getLaunchIntentForPackage(packageName);
        if (intent != null) {
            startActivity(intent);
            showToast("Launching " + selectedGame.toString() + "...");
        } else {
            showToast(selectedGame.toString() + " is not installed.");
        }
    }

    private void handleZArchiverAction() {
        GameType selectedGame = (GameType) gameSpinner.getSelectedItem();
        if (selectedGame == GameType.NONE) {
            showToast("Please select a game first.");
            return;
        }

        executor.execute(() -> prepareFileForZArchiver(selectedGame));
    }

    private void prepareFileForZArchiver(GameType game) {
        runOnUiThread(() -> progressBar.setVisibility(View.VISIBLE));

        try {
            GameConfig config = gameConfigs.get(game);
            File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File tempFile = new File(downloadsDir, config.saveFile);

            copyAssetToFile(config.assetPath, tempFile);

            runOnUiThread(() -> {
                if (isZArchiverInstalled()) {
                    launchZArchiver();
                    showToast("File saved to Downloads. Open ZArchiver and navigate to:\n" +
                            config.saveDir + "\nReplace " + config.saveFile);
                } else {
                    launchZArchiverInstall();
                }
            });
        } catch (IOException e) {
            Log.e(TAG, "Failed to prepare file for ZArchiver", e);
            runOnUiThread(() -> showToast("Error: " + e.getMessage()));
        } finally {
            runOnUiThread(() -> progressBar.setVisibility(View.GONE));
        }
    }

    private boolean isZArchiverInstalled() {
        try {
            getPackageManager().getPackageInfo(ZARCHIVER_PACKAGE, PackageManager.GET_ACTIVITIES);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void launchZArchiver() {
        Intent intent = getPackageManager().getLaunchIntentForPackage(ZARCHIVER_PACKAGE);
        if (intent != null) {
            startActivity(intent);
        }
    }

    private void launchZArchiverInstall() {
        Intent intent = new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://play.google.com/store/apps/details?id=ru.zdevs.zarchiver"));
        startActivity(intent);
        showToast("ZArchiver not installed. Opening Play Store...");
    }

    private void copyAssetToFile(String assetPath, File targetFile) throws IOException {
        try (InputStream in = getAssets().open(assetPath);
             FileOutputStream out = new FileOutputStream(targetFile)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
        }
    }

    private void launchShizukuApp() {
        Intent intent = new Intent("moe.shizuku.privileged.api.intent.action.MANAGER");
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        if (getPackageManager().resolveActivity(intent, 0) != null) {
            startActivity(intent);
        } else {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/RikkaApps/Shizuku/releases")));
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
