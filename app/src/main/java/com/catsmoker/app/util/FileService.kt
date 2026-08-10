package com.catsmoker.app.util

import com.catsmoker.app.IFileService
import kotlin.system.exitProcess

class FileService : IFileService.Stub() {
    override fun executeCommand(commands: Array<out String>?): Int {
        if (commands == null) return -1
        return try {
            val process = Runtime.getRuntime().exec(commands)
            process.waitFor()
        } catch (e: Exception) {
            -1
        }
    }

    override fun destroy() {
        exitProcess(0)
    }
}
