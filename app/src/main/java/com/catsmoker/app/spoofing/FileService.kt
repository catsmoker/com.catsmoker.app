package com.catsmoker.app.spoofing

import android.os.RemoteException
import android.util.Log
import com.catsmoker.app.IFileService
import kotlin.system.exitProcess

// CRITICAL: Extends IFileService.Stub, NOT android.app.Service
class FileService : IFileService.Stub() {
    // Default constructor required by Shizuku
    init {
        Log.d(TAG, "FileService instance created")
    }

    @Throws(RemoteException::class)
    override fun destroy() {
        Log.d(TAG, "Destroying process")
        exitProcess(0) // Kills the standalone process
    }

    @Throws(RemoteException::class)
    override fun executeCommand(command: Array<String?>?): Int {
        return try {
            val process = Runtime.getRuntime().exec(command)
            val exitCode = process.waitFor()
            Log.d(TAG, "Command executed. Exit code: $exitCode")
            exitCode
        } catch (e: Exception) {
            Log.e(TAG, "Command execution failed", e)
            -1
        }
    }

    companion object {
        private const val TAG = "FileService"
    }
}
