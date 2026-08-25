package com.catsmoker.app.features.editgamefiles.service

import android.content.Context
import com.catsmoker.app.IFileService
import com.catsmoker.app.shizuku.CommandResult
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs inside the Shizuku user-service process (shell UID). Every method must drain both
 * stdout and stderr, otherwise a chatty command fills the 64 KB pipe buffer and blocks
 * forever, taking the binder thread with it.
 */
class FileService : IFileService.Stub {

    constructor() : super()
    constructor(context: Context) : super()

    override fun destroy() {
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    override fun executeCommand(command: Array<out String>?): Int {
        if (command == null) return -1
        return try {
            val process = ProcessBuilder(*command).redirectErrorStream(true).start()
            // Drain before waiting so the child cannot block on a full pipe.
            process.inputStream.use { it.readBytes() }
            if (process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.exitValue()
            } else {
                process.destroyForcibly()
                -1
            }
        } catch (_: Exception) {
            -1
        }
    }

    override fun executeAndGetOutput(command: Array<out String>?): MutableList<String> {
        if (command == null) return mutableListOf()
        val result = executeForResult(command)
        val text = result.output.ifBlank { "" }
        return if (text.isEmpty()) mutableListOf() else text.lines().toMutableList()
    }

    override fun executeForResult(command: Array<out String>?): CommandResult {
        val result = CommandResult()
        result.output = ""
        result.error = ""
        result.exitCode = -1
        if (command == null) return result

        return try {
            val process = ProcessBuilder(*command).start()
            // stderr must be consumed on a separate thread; reading them serially deadlocks
            // as soon as either pipe fills up.
            val stderr = StringBuilder()
            val errDrainer = Thread {
                try {
                    process.errorStream.bufferedReader().forEachLine { stderr.append(it).append('\n') }
                } catch (_: Exception) {
                }
            }.apply { isDaemon = true; start() }

            val stdout = try {
                process.inputStream.bufferedReader().readText()
            } catch (_: Exception) {
                ""
            }

            val finished = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            errDrainer.join(DRAIN_JOIN_MILLIS)

            result.output = stdout.trimEnd('\n')
            result.error = stderr.toString().trimEnd('\n')
            result.exitCode = if (finished) process.exitValue() else -1
            result
        } catch (e: Exception) {
            result.error = e.message ?: e.javaClass.simpleName
            result
        }
    }

    override fun readSysfsThermal(): String {
        val root = File("/sys/class/thermal")
        val zones = root.listFiles { f -> f.isDirectory && f.name.startsWith("thermal_zone") }
            ?: return ""
        val sb = StringBuilder()
        for (zone in zones.sortedBy { it.name }) {
            val type = readTrimmed(File(zone, "type")) ?: continue
            val temp = readTrimmed(File(zone, "temp")) ?: continue
            sb.append(type).append(':').append(temp).append('\n')
        }
        return sb.toString()
    }

    override fun readProcStat(): String = try {
        File("/proc/stat").readText()
    } catch (_: Exception) {
        ""
    }

    private fun readTrimmed(file: File): String? = try {
        if (file.canRead()) file.readText().trim().ifEmpty { null } else null
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val COMMAND_TIMEOUT_SECONDS = 60L
        const val DRAIN_JOIN_MILLIS = 1000L
    }
}
