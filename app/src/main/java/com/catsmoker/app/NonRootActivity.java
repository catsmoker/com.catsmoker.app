package com.catsmoker.app;

import android.app.Activity;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
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
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;

import com.google.android.material.snackbar.Snackbar;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import rikka.shizuku.Shizuku;

public class NonRootActivity extends AppCompatActivity implements Shizuku.OnRequestPermissionResultListener {

    private static final String TAG = "NonRootActivity";
    private static final String ZARCHIVER_PACKAGE = "ru.zdevs.zarchiver";
    private static final int LEGACY_REQUEST_STORAGE_PERMISSION = 1001;

    // UI
    private Spinner gameSpinner;
    private Button btnLaunchGame, btnStartZArchiver, btnStartShizuku, btnStartSaf, btnApplyMaxFps, btnApplyIpadView;
    private View tonalButtonsLayout;
    private TextView chooseOptionTitle, chooseMethodTitle;
    private ProgressBar progressBar;
    private View rootView;

    // Logic
    private ActivityResultLauncher<Intent> safLauncher;
    private ActivityResultLauncher<Intent> storagePermissionLauncher;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingAction = null;
    private String selectedAssetPath = null;
    private final Map<GameType, GameConfig> gameConfigs = new HashMap<>();

    // Shizuku Listeners
    private final Shizuku.OnBinderReceivedListener binderReceivedListener = () ->
            updateShizukuButtonState(Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED);

    private final Shizuku.OnBinderDeadListener binderDeadListener = () ->
            updateShizukuButtonState(false);

    public enum GameType {
        NONE("Select a game"),
        PUBG_GLOBAL("PUBG Global");
        private final String displayName;
        GameType(String displayName) { this.displayName = displayName; }
        @NonNull @Override public String toString() { return displayName; }
    }

    public static class GameConfig {
        final String packageName;
        final String saveDir;
        final String saveFile;
        final String maxFpsAssetPath;
        final String ipadViewAssetPath;

        public GameConfig(String packageName, String saveDir, String saveFile, String maxFpsAssetPath, String ipadViewAssetPath) {
            this.packageName = packageName;
            this.saveDir = saveDir;
            this.saveFile = saveFile;
            this.maxFpsAssetPath = maxFpsAssetPath;
            this.ipadViewAssetPath = ipadViewAssetPath;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_non_root);

        setupToolbar();
        initializeGameConfigs();
        initializeUI();
        initializeLaunchers();
        setupListeners();

        Shizuku.addBinderReceivedListener(binderReceivedListener);
        Shizuku.addBinderDeadListener(binderDeadListener);
        Shizuku.addRequestPermissionResultListener(this);
    }

    private void setupToolbar() {
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setTitle("Game File Manager");
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    private void initializeGameConfigs() {
        // NOTE: Ensure these asset paths actually exist in your src/main/assets folder!
        gameConfigs.put(GameType.PUBG_GLOBAL, new GameConfig(
                "com.tencent.ig",
                "/Android/data/com.tencent.ig/files/UE4Game/ShadowTrackerExtra/ShadowTrackerExtra/Saved/SaveGames/",
                "Active.sav",
                "PUBG Global/MaxFPS/Active.sav",
                "PUBG Global/IpadVew/Active.sav"
        ));
    }

    private void initializeUI() {
        rootView = findViewById(android.R.id.content);
        gameSpinner = findViewById(R.id.game_spinner);
        btnLaunchGame = findViewById(R.id.btn_launch_game);
        btnStartZArchiver = findViewById(R.id.btn_start_zarchiver);
        btnStartShizuku = findViewById(R.id.btn_start_shizuku);
        btnStartSaf = findViewById(R.id.btn_start_saf);
        btnApplyMaxFps = findViewById(R.id.btn_apply_max_fps);
        btnApplyIpadView = findViewById(R.id.btn_apply_ipad_view);
        chooseOptionTitle = findViewById(R.id.choose_option_title);
        chooseMethodTitle = findViewById(R.id.choose_method_title);
        tonalButtonsLayout = findViewById(R.id.tonal_buttons_layout);
        progressBar = findViewById(R.id.progress_bar);

        ArrayAdapter<GameType> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, GameType.values());
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        gameSpinner.setAdapter(adapter);
    }

    private void initializeLaunchers() {
        safLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                Uri treeUri = result.getData().getData();
                if (treeUri != null) {
                    try {
                        getContentResolver().takePersistableUriPermission(treeUri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                        GameType selectedGame = (GameType) gameSpinner.getSelectedItem();
                        performSafFileCopy(treeUri, selectedGame);
                    } catch (SecurityException e) {
                        showSnackbar("Failed to take permission: " + e.getMessage());
                    }
                }
            }
        });

        storagePermissionLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    showSnackbar("Storage permission granted");
                    if (pendingAction != null) pendingAction.run();
                }
            }
        });
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
        btnApplyMaxFps.setOnClickListener(v -> {
            setSelectedAssetPath(true);
            showMethods();
        });
        btnApplyIpadView.setOnClickListener(v -> {
            setSelectedAssetPath(false);
            showMethods();
        });
        btnStartZArchiver.setOnClickListener(v -> {
            pendingAction = this::handleZArchiverAction;
            handleZArchiverAction();
        });
        btnStartShizuku.setOnClickListener(v -> checkAndStartShizukuAction());
        btnStartSaf.setOnClickListener(v -> {
            pendingAction = this::launchSafPicker;
            launchSafPicker();
        });
    }

    private void updateButtonVisibility(GameType game) {
        boolean validGame = (game != GameType.NONE);
        int vis = validGame ? View.VISIBLE : View.GONE;
        btnLaunchGame.setVisibility(vis);
        chooseOptionTitle.setVisibility(vis);
        btnApplyMaxFps.setVisibility(vis);
        btnApplyIpadView.setVisibility(vis);
        chooseMethodTitle.setVisibility(View.GONE);
        btnStartShizuku.setVisibility(View.GONE);
        tonalButtonsLayout.setVisibility(View.GONE);
    }

    private void showMethods() {
        chooseOptionTitle.setVisibility(View.GONE);
        btnApplyMaxFps.setVisibility(View.GONE);
        btnApplyIpadView.setVisibility(View.GONE);
        chooseMethodTitle.setVisibility(View.VISIBLE);
        btnStartShizuku.setVisibility(View.VISIBLE);
        tonalButtonsLayout.setVisibility(View.VISIBLE);
    }

    private void setSelectedAssetPath(boolean isMaxFps) {
        GameConfig config = getSelectedConfig();
        if (config != null) {
            selectedAssetPath = isMaxFps ? config.maxFpsAssetPath : config.ipadViewAssetPath;
        }
    }

    private void updateShizukuButtonState(boolean granted) {
        mainHandler.post(() -> {
            // Ensure you have these strings in res/values/strings.xml
            btnStartShizuku.setText(granted ? "Apply via Shizuku" : "Grant Shizuku Permission");
        });
    }

    // --- Shizuku Logic ---

    private void checkAndStartShizukuAction() {
        final GameConfig config = getSelectedConfig();
        if (config == null) return;

        if (!Shizuku.pingBinder()) {
            showSnackbar("Shizuku is not running. Please start the Shizuku app.");
            return;
        }

        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                showSnackbar("Shizuku permission required.");
            }
            Shizuku.requestPermission(0);
        } else {
            performShizukuCopy(config);
        }
    }

    @Override
    public void onRequestPermissionResult(int requestCode, int grantResult) {
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            showSnackbar("Shizuku permission granted.");
            updateShizukuButtonState(true);
            checkAndStartShizukuAction();
        } else {
            showSnackbar("Shizuku permission denied.");
            updateShizukuButtonState(false);
        }
    }

    private void performShizukuCopy(GameConfig config) {
        setLoading(true);
        executor.execute(() -> {
            File tempFile = null;
            try {
                // 1. Copy asset to app cache
                tempFile = new File(getCacheDir(), config.saveFile);
                // Ensure parent dir of temp file exists
                tempFile.getParentFile().mkdirs();

                try (InputStream in = getAssets().open(selectedAssetPath);
                     OutputStream out = new FileOutputStream(tempFile)) {
                    copyStream(in, out);
                }

                String sourcePath = tempFile.getAbsolutePath();
                String destDir = Environment.getExternalStorageDirectory().getPath() + config.saveDir;
                String destPath = destDir + config.saveFile;

                // 2. Execute Shell Commands
                String cmdMkdir = "mkdir -p \"" + destDir + "\"";
                int exitMkdir = execShizukuCommand(new String[]{"sh", "-c", cmdMkdir});
                if (exitMkdir != 0) throw new IOException("Shizuku mkdir failed: " + exitMkdir);

                String cmdCp = "cp -f \"" + sourcePath + "\" \"" + destPath + "\"";
                int exitCp = execShizukuCommand(new String[]{"sh", "-c", cmdCp});

                mainHandler.post(() -> {
                    if (exitCp == 0) showSnackbar("Success! File replaced via Shizuku.");
                    else showSnackbar("Shizuku copy failed code: " + exitCp);
                });

            } catch (Exception e) {
                Log.e(TAG, "Shizuku failed", e);
                mainHandler.post(() -> showSnackbar("Error: " + e.getMessage()));
            } finally {
                if (tempFile != null && tempFile.exists()) tempFile.delete();
                mainHandler.post(() -> setLoading(false));
            }
        });
    }

    private int execShizukuCommand(String[] command) throws InterruptedException {
        final CountDownLatch latch = new CountDownLatch(1);
        final int[] returnCode = {-1};

        Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(
                new ComponentName(getPackageName(), FileService.class.getName()))
                .daemon(false)
                .processNameSuffix("file_service")
                .debuggable(BuildConfig.DEBUG)
                .version(1);

        final ServiceConnection[] conn = new ServiceConnection[1];
        conn[0] = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                try {
                    IFileService fileService = IFileService.Stub.asInterface(service);
                    returnCode[0] = fileService.executeCommand(command);
                } catch (Exception e) {
                    Log.e(TAG, "Remote ex", e);
                } finally {
                    try {
                        Shizuku.unbindUserService(args, conn[0], true);
                    } catch (Exception ignored) {}
                    latch.countDown();
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                latch.countDown();
            }
        };

        Shizuku.bindUserService(args, conn[0]);

        if (!latch.await(10, TimeUnit.SECONDS)) {
            Log.e(TAG, "Timeout");
            try { Shizuku.unbindUserService(args, conn[0], true); } catch (Exception ignored) {}
            return -1;
        }

        return returnCode[0];
    }

    // --- ZArchiver & SAF Logic --- (Kept mostly same, adjusted for context)

    private void handleZArchiverAction() {
        if (!checkStoragePermission()) return;
        final GameConfig config = getSelectedConfig();
        if (config == null) return;

        setLoading(true);
        executor.execute(() -> {
            try {
                pasteFileToDownloads(config);
                mainHandler.post(this::showZArchiverSuccessDialog);
            } catch (IOException e) {
                mainHandler.post(() -> showSnackbar("Error: " + e.getMessage()));
            } finally {
                mainHandler.post(() -> setLoading(false));
            }
        });
    }

    private void pasteFileToDownloads(GameConfig config) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues contentValues = new ContentValues();
            contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, config.saveFile);
            contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream");
            contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);

            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues);
            if (uri == null) throw new IOException("MediaStore failed");

            try (InputStream in = getAssets().open(selectedAssetPath);
                 OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IOException("Output null");
                copyStream(in, out);
            }
        } else {
            File dest = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), config.saveFile);
            try (InputStream in = getAssets().open(selectedAssetPath);
                 OutputStream out = new FileOutputStream(dest)) {
                copyStream(in, out);
            }
        }
    }

    private void showZArchiverSuccessDialog() {
        Snackbar.make(rootView, "File saved to Downloads. Open ZArchiver?", Snackbar.LENGTH_INDEFINITE)
                .setAction("OPEN", v -> {
                    Intent intent = getPackageManager().getLaunchIntentForPackage(ZARCHIVER_PACKAGE);
                    if (intent != null) startActivity(intent);
                    else {
                        try {
                            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + ZARCHIVER_PACKAGE)));
                        } catch (Exception e) { showSnackbar("Play Store not found"); }
                    }
                }).show();
    }

    private void launchSafPicker() {
        GameConfig config = getSelectedConfig();
        if (config == null) return;
        showSnackbar("Select folder: " + config.saveDir);
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        safLauncher.launch(intent);
    }

    private void performSafFileCopy(Uri treeUri, GameType game) {
        if (game == GameType.NONE) return;
        final GameConfig config = gameConfigs.get(game);
        if (config == null) return;

        setLoading(true);
        executor.execute(() -> {
            try {
                DocumentFile pickedDir = DocumentFile.fromTreeUri(this, treeUri);
                if (pickedDir == null || !pickedDir.canWrite()) throw new IOException("Cannot write");

                DocumentFile targetFile = pickedDir.findFile(config.saveFile);
                if (targetFile == null) targetFile = pickedDir.createFile("application/octet-stream", config.saveFile);

                try (InputStream in = getAssets().open(selectedAssetPath);
                     OutputStream out = getContentResolver().openOutputStream(targetFile.getUri())) {
                    copyStream(in, out);
                }
                mainHandler.post(() -> showSnackbar("Success via SAF!"));
            } catch (Exception e) {
                mainHandler.post(() -> showSnackbar("SAF Error: " + e.getMessage()));
            } finally {
                mainHandler.post(() -> setLoading(false));
            }
        });
    }

    private GameConfig getSelectedConfig() {
        GameType selectedGame = (GameType) gameSpinner.getSelectedItem();
        if (selectedGame == GameType.NONE) {
            showSnackbar("Please select a game first.");
            return null;
        }
        return gameConfigs.get(selectedGame);
    }

    private boolean checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    storagePermissionLauncher.launch(intent);
                } catch (Exception e) {
                    storagePermissionLauncher.launch(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                }
                return false;
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, LEGACY_REQUEST_STORAGE_PERMISSION);
                return false;
            }
        }
        return true;
    }

    private void launchGame() {
        GameConfig config = getSelectedConfig();
        if (config != null) {
            Intent intent = getPackageManager().getLaunchIntentForPackage(config.packageName);
            if (intent != null) startActivity(intent);
            else showSnackbar("Game not installed");
        }
    }

    private void copyStream(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
    }

    private void setLoading(boolean loading) {
        progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        btnStartZArchiver.setEnabled(!loading);
        btnStartShizuku.setEnabled(!loading);
        btnStartSaf.setEnabled(!loading);
        btnLaunchGame.setEnabled(!loading);
        btnApplyMaxFps.setEnabled(!loading);
        btnApplyIpadView.setEnabled(!loading);
    }

    private void showSnackbar(String message) {
        if (rootView != null) Snackbar.make(rootView, message, Snackbar.LENGTH_LONG).show();
        else Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
        Shizuku.removeBinderReceivedListener(binderReceivedListener);
        Shizuku.removeBinderDeadListener(binderDeadListener);
        Shizuku.removeRequestPermissionResultListener(this);
    }
}