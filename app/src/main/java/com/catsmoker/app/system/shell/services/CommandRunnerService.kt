package com.catsmoker.app.system.shell.services

import android.content.Context
import com.catsmoker.app.shizuku.CommandResult
import com.catsmoker.app.shizuku.ICommandRunner
import java.lang.reflect.Method
import kotlin.system.exitProcess

class CommandRunnerService : ICommandRunner.Stub {
    constructor() : super()
    constructor(context: Context) : super()

    override fun executeCommand(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            process.inputStream.bufferedReader().use { it.readText() }.trim()
        } catch (_: Exception) { "" }
    }

    override fun executeCommandWithResult(command: String): CommandResult {
        return CommandResult().apply {
            output = executeCommand(command)
        }
    }

    override fun getThermalTemperatures(): String {
        return "" // Simplified
    }

    override fun killCurrentProcess() {}
    override fun destroy() { exitProcess(0) }
}
