package com.catsmoker.app.services;

import android.os.RemoteException;
import android.util.Log;

import com.catsmoker.app.IFileService;

import java.io.IOException;

// CRITICAL: Extends IFileService.Stub, NOT android.app.Service
public class FileService extends IFileService.Stub {

    private static final String TAG = "FileService";

    // Default constructor required by Shizuku
    public FileService() {
        Log.d(TAG, "FileService instance created");
    }

    @Override
    public void destroy() throws RemoteException {
        Log.d(TAG, "Destroying process");
        System.exit(0); // Kills the standalone process
    }

    @Override
    public int executeCommand(String[] command) throws RemoteException {
        try {
            Process process = Runtime.getRuntime().exec(command);
            int exitCode = process.waitFor();
            Log.d(TAG, "Command executed. Exit code: " + exitCode);
            return exitCode;
        } catch (IOException | InterruptedException e) {
            Log.e(TAG, "Command execution failed", e);
            return -1;
        }
    }
}