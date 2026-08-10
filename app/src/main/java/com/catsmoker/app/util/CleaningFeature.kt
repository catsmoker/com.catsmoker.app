package com.catsmoker.app.util

import android.content.Context
import com.catsmoker.app.IFileService
import com.topjohnwu.superuser.Shell

object CleaningFeature {
    
    fun executeRootClean(isDeep: Boolean): List<String> {
        val results = mutableListOf<String>()
        val commands = mutableListOf<String>()
        
        // Use sh -c to ensure wildcard expansion works across all apps
        commands.add("sh -c 'rm -rf /data/user/0/*/cache/*'")
        commands.add("sh -c 'rm -rf /data/user/0/*/code_cache/*'")
        commands.add("sh -c 'rm -rf /storage/emulated/0/Android/data/*/cache/*'")
        commands.add("sh -c 'rm -rf /storage/emulated/0/Android/data/*/files/logs/*'")
        
        // Temp files and Logs
        commands.add("find /storage/emulated/0/Android/data -name '*.log' -exec rm -f {} +")
        commands.add("find /storage/emulated/0/Android/data -name '*.tmp' -exec rm -f {} +")
        commands.add("find /storage/emulated/0/Android/data -name '*.temp' -exec rm -f {} +")

        if (isDeep) {
            // Hidden files/folders and Empty directories (Aggressive)
            commands.add("find /storage/emulated/0/Android/data -name '.*' -not -name '.' -not -name '..' -exec rm -rf {} +")
            commands.add("sh -c 'find /storage/emulated/0/Android/data -type d -empty -delete'")
            commands.add("sh -c 'find /storage/emulated/0/Android/obb -type d -empty -delete'")
        }

        commands.forEach { cmd ->
            val result = Shell.cmd(cmd).exec()
            if (result.isSuccess) {
                results.add("SUCCESS: Cleaned ${cmd.substringAfter("'").substringBefore("'")}")
            } else {
                results.add("SKIP/FAIL: ${cmd.substringBefore(" ")}")
            }
        }
        return results
    }

    suspend fun executeShizukuClean(fileService: IFileService, isDeep: Boolean): List<String> {
        val results = mutableListOf<String>()
        val commands = mutableListOf<String>()
        
        // Shizuku is restricted to External Storage (/Android/data)
        commands.add("rm -rf /storage/emulated/0/Android/data/*/cache/*")
        commands.add("find /storage/emulated/0/Android/data -name '*.log' -exec rm -f {} +")
        
        if (isDeep) {
            commands.add("find /storage/emulated/0/Android/data -name '.*' -not -name '.' -not -name '..' -exec rm -rf {} +")
            commands.add("find /storage/emulated/0/Android/data -type d -empty -delete")
        }

        commands.forEach { cmd ->
            val exitCode = fileService.executeCommand(arrayOf("sh", "-c", cmd))
            if (exitCode == 0) {
                results.add("SUCCESS: Cleaned ${cmd.substringBefore(" ")}")
            } else {
                results.add("SKIP/FAIL: ${cmd.substringBefore(" ")}")
            }
        }
        return results
    }

    fun executeNonRootClean(context: Context) {
        context.cacheDir.deleteRecursively()
        context.externalCacheDir?.deleteRecursively()
    }
}
