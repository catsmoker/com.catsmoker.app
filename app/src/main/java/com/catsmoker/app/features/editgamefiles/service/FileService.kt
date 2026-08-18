package com.catsmoker.app.features.editgamefiles.service

import android.content.Context
import com.catsmoker.app.IFileService

class FileService : IFileService.Stub {

    constructor() : super()
    constructor(context: Context) : super()

    override fun destroy() {
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    override fun executeCommand(command: Array<out String>?): Int {
        if (command == null) return -1
        return try {
            val process = Runtime.getRuntime().exec(command)
            process.waitFor()
        } catch (_: Exception) {
            -1
        }
    }

    override fun executeAndGetOutput(command: Array<out String>?): MutableList<String> {
        if (command == null) return mutableListOf()
        return try {
            val process = Runtime.getRuntime().exec(command)
            process.inputStream.bufferedReader().readLines().toMutableList()
        } catch (_: Exception) {
            mutableListOf()
        }
    }
}
