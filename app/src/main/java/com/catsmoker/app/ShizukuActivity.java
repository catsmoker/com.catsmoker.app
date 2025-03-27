package com.catsmoker.app;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;

import rikka.shizuku.Shizuku;

public class ShizukuActivity extends AppCompatActivity {
    private static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";
    private static final String PUBG_DIR = Environment.getExternalStorageDirectory().getPath() +
            "/Android/data/com.tencent.ig/files/UE4Game/ShadowTrackerExtra/ShadowTrackerExtra/Saved/SaveGames/";
    private static final String PUBG_PATH = PUBG_DIR + "Active.sav";
    private static final String ASSET_FILE = "PUBG/Active.sav";

    private Spinner gameSpinner;
    private ActivityResultLauncher<Intent> safLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_shizuku);
        setTitle("Shizuku & SAF");

        TextView instructions = findViewById(R.id.instructions);
        instructions.setText(getString(R.string.shizuku_instructions));

        gameSpinner = findViewById(R.id.game_spinner);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new String[]{"None", "PUBG"});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        gameSpinner.setAdapter(adapter);

        Button btnStartShizuku = findViewById(R.id.btn_start_shizuku);
        Button btnStartSaf = findViewById(R.id.btn_start_saf);

        btnStartShizuku.setOnClickListener(v -> {
            if (isShizukuInstalled() && Shizuku.pingBinder()) {
                if ("PUBG".equals(gameSpinner.getSelectedItem())) {
                    replaceFileWithShizuku();
                } else {
                    Toast.makeText(this, "Please select PUBG from the spinner.", Toast.LENGTH_SHORT).show();
                }
            } else {
                launchShizukuOrInstall();
            }
        });

        safLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                Uri treeUri = result.getData().getData();
                if (treeUri != null) {
                    getContentResolver().takePersistableUriPermission(treeUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    if ("PUBG".equals(gameSpinner.getSelectedItem())) {
                        replacePubgActiveSavWithSAF(treeUri);
                    } else {
                        Toast.makeText(this, "Directory access granted, but no game selected.", Toast.LENGTH_SHORT).show();
                    }
                }
            }
        });

        btnStartSaf.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(Environment.getExternalStorageDirectory().getPath() + "/Android/data/"));
            }
            safLauncher.launch(intent);
        });
    }

    private boolean isShizukuInstalled() {
        try {
            getPackageManager().getPackageInfo(SHIZUKU_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void launchShizukuOrInstall() {
        Intent intent = getPackageManager().getLaunchIntentForPackage(SHIZUKU_PACKAGE);
        if (intent != null) {
            startActivity(intent);
            Toast.makeText(this, "Shizuku not running. Opening Shizuku app...", Toast.LENGTH_LONG).show();
        } else {
            intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/RikkaApps/Shizuku/releases"));
            startActivity(intent);
        }
    }

    private void copyAssetToFile(File targetFile) throws IOException {
        try (InputStream inputStream = getAssets().open(ASSET_FILE);
             FileOutputStream outputStream = new FileOutputStream(targetFile)) {
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
            outputStream.flush();
        }
    }

    private void executeShellCommandWithShizuku(String command) {
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
            Process process = pb.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            StringBuilder errorOutput = new StringBuilder();
            try (BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = errorReader.readLine()) != null) {
                    errorOutput.append(line).append("\n");
                }
            }
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                Toast.makeText(this, "Active.sav replaced successfully via Shizuku", Toast.LENGTH_SHORT).show();
            } else {
                String errorMsg = "Shizuku replace failed with exit code " + exitCode + ":\n" + errorOutput;
                Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Shizuku command failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void replaceFileWithShizuku() {
        try {
            File tempFile = new File(getCacheDir(), "Active.sav");
            copyAssetToFile(tempFile);
            executeShellCommandWithShizuku("cp " + tempFile.getAbsolutePath() + " " + PUBG_PATH);
            if (!tempFile.delete()) {
                Toast.makeText(this, "Failed to delete temp file.", Toast.LENGTH_SHORT).show();
            }
        } catch (IOException e) {
            Toast.makeText(this, "Shizuku replace failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void replacePubgActiveSavWithSAF(Uri treeUri) {
        try {
            String treeDocId = DocumentsContract.getTreeDocumentId(treeUri);
            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId);
            String relativePath = PUBG_PATH.replace(Environment.getExternalStorageDirectory().getPath() + "/", "");
            Uri targetUri = Uri.withAppendedPath(childrenUri, relativePath);

            try (InputStream inputStream = getAssets().open(ASSET_FILE);
                 OutputStream outputStream = getContentResolver().openOutputStream(targetUri, "rwt")) {
                if (outputStream == null) {
                    Toast.makeText(this, "Failed to open output stream", Toast.LENGTH_SHORT).show();
                    return;
                }
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }
                outputStream.flush();
                Toast.makeText(this, "Active.sav replaced successfully via SAF", Toast.LENGTH_SHORT).show();
            }
        } catch (IOException e) {
            Toast.makeText(this, "SAF replace failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}