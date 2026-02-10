package com.catsmoker.app.services

import android.os.RemoteException
import android.util.Log
import com.catsmoker.app.IFileService
import java.io.IOException
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
        try {
            val process = Runtime.getRuntime().exec(command)
            val exitCode = process.waitFor()
            Log.d(TAG, "Command executed. Exit code: $exitCode")
            return exitCode
        } catch (e: IOException) {
            Log.e(TAG, "Command execution failed", e)
            return -1
        } catch (e: InterruptedException) {
            Log.e(TAG, "Command execution failed", e)
            return -1
        }
    }

    companion object {
        private const val TAG = "FileService"
    }
}