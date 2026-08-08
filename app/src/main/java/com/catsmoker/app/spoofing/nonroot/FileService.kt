package com.catsmoker.app.spoofing.nonroot

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
            
            // Capture and log output/errors for debugging
            val outThread = Thread {
                process.inputStream.bufferedReader().use { reader ->
                    reader.forEachLine { Log.d(TAG, "[STDOUT] $it") }
                }
            }
            val errThread = Thread {
                process.errorStream.bufferedReader().use { reader ->
                    reader.forEachLine { Log.e(TAG, "[STDERR] $it") }
                }
            }
            outThread.start()
            errThread.start()

            val exitCode = process.waitFor()
            outThread.join(1000)
            errThread.join(1000)
            
            Log.d(TAG, "Command: ${command?.joinToString(" ")}")
            Log.d(TAG, "Exit code: $exitCode")
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
