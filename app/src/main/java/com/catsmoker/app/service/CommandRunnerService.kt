package com.catsmoker.app.service

import android.content.Context
import com.catsmoker.app.shizuku.ICommandRunner
import com.catsmoker.app.shizuku.CommandResult
import com.catsmoker.app.shizuku.SuspendResult
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Method
import kotlin.system.exitProcess

class CommandRunnerService : ICommandRunner.Stub {

    constructor() : super()

    @Suppress("UNUSED_PARAMETER")
    constructor(context: Context) : super()

    private enum class ThermalReadStrategy {
        REFLECTION,
        DUMPSYS,
        SYSFS
    }

    @Volatile
    private var resolvedThermalStrategy: ThermalReadStrategy? = null

    @Volatile
    private var currentProcess: Process? = null

    override fun executeCommand(command: String): String {
        return try {
            val process = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(true)
                .start()
            currentProcess = process
            val output = BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                reader.readText().trim()
            }
            process.waitFor()
            currentProcess = null
            output
        } catch (e: Exception) {
            currentProcess = null
            "Error: ${e.message}"
        }
    }

    override fun executeCommandWithExitCode(command: String): Int {
        return try {
            val process = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(true)
                .start()
            currentProcess = process
            val code = process.waitFor()
            currentProcess = null
            code
        } catch (e: Exception) {
            currentProcess = null
            -1
        }
    }

    override fun executeCommandWithResult(command: String): CommandResult {
        return try {
            val process = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(true)
                .start()
            currentProcess = process
            val output = BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                reader.readText().trim()
            }
            val exitCode = process.waitFor()
            currentProcess = null
            CommandResult().apply {
                this.output = output
                this.exitCode = exitCode
            }
        } catch (e: Exception) {
            currentProcess = null
            CommandResult().apply {
                this.output = "Error: ${e.message}"
                this.exitCode = -1
            }
        }
    }

    override fun readProcStat(): String {
        return try {
            java.io.File("/proc/stat").readText()
        } catch (e: Exception) {
            executeCommand("cat /proc/stat")
        }
    }

    override fun getThermalTemperatures(): String {
        resolvedThermalStrategy?.let { cached ->
            val cachedResult = when (cached) {
                ThermalReadStrategy.REFLECTION -> readViaReflection()
                ThermalReadStrategy.DUMPSYS -> readViaDumpsys()
                ThermalReadStrategy.SYSFS -> readViaSysfs()
            }
            if (cachedResult != null) return cachedResult
            resolvedThermalStrategy = null
        }

        readViaReflection()?.let {
            resolvedThermalStrategy = ThermalReadStrategy.REFLECTION
            return it
        }
        readViaDumpsys()?.let {
            resolvedThermalStrategy = ThermalReadStrategy.DUMPSYS
            return it
        }
        readViaSysfs()?.let {
            resolvedThermalStrategy = ThermalReadStrategy.SYSFS
            return it
        }

        return readViaDumpsys() ?: ""
    }

    private fun readViaReflection(): String? {
        return try {
            val binderClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = binderClass.getMethod("getService", String::class.java)
            val binder = getServiceMethod.invoke(null, "thermalservice") as? android.os.IBinder ?: return null
            val stubClass = Class.forName("android.os.IThermalService\$Stub")
            val asInterfaceMethod = stubClass.getMethod("asInterface", android.os.IBinder::class.java)
            val service = asInterfaceMethod.invoke(null, binder) ?: return null

            val candidateMethods = service.javaClass.methods.filter { it.name.contains("Temperature", ignoreCase = true) }
            var temps: Array<*>? = null
            for (method in candidateMethods) {
                val result = runCatching { invokeWithBestGuessArgs(method, service) }.getOrNull()
                if (result is Array<*> && result.isNotEmpty()) {
                    temps = result
                    break
                } else if (result is List<*> && result.isNotEmpty()) {
                    temps = result.toTypedArray()
                    break
                }
            }

            temps?.let { parseTemperatureObjects(it) }
        } catch (e: Exception) {
            null
        }
    }

    private fun invokeWithBestGuessArgs(method: Method, service: Any): Any? {
        val paramTypes = method.parameterTypes
        return when (paramTypes.size) {
            0 -> method.invoke(service)
            1 -> {
                val isBoolean = paramTypes[0] == Boolean::class.javaPrimitiveType || paramTypes[0] == Boolean::class.java
                if (isBoolean) method.invoke(service, false) else method.invoke(service, 0)
            }
            2 -> method.invoke(service, false, 0)
            else -> null
        }
    }

    private fun parseTemperatureObjects(temps: Array<*>): String? {
        val builder = StringBuilder()
        var hasValidData = false
        for (temp in temps) {
            if (temp == null) continue
            var name = runCatching { temp.javaClass.getMethod("getName").invoke(temp) as? String }.getOrNull() ?: ""
            var value = runCatching { (temp.javaClass.getMethod("getValue").invoke(temp) as? Number)?.toFloat() }.getOrNull() ?: 0f
            var type = runCatching { (temp.javaClass.getMethod("getType").invoke(temp) as? Number)?.toInt() }.getOrNull() ?: 0

            if (value == 0f && name.isBlank()) {
                for (field in temp.javaClass.declaredFields) {
                    field.isAccessible = true
                    when (field.name.lowercase()) {
                        "mname", "name" -> name = field.get(temp) as? String ?: ""
                        "mvalue", "value" -> value = (field.get(temp) as? Number)?.toFloat() ?: 0f
                        "mtype", "type" -> type = (field.get(temp) as? Number)?.toInt() ?: 0
                    }
                }
            }

            if (value != 0f || name.isNotBlank()) hasValidData = true
            builder.append("Temperature{mValue=").append(value)
                .append(", mType=").append(type)
                .append(", mName=").append(name).append("}\n")
        }
        return if (hasValidData && builder.isNotEmpty()) builder.toString() else null
    }

    private fun readViaDumpsys(): String? {
        val dump = executeCommand("dumpsys thermalservice")
        return if (dump.contains("Temperature{") || dump.contains("mValue=")) dump else null
    }

    private fun readViaSysfs(): String? {
        val sb = StringBuilder()
        try {
            val thermalDir = java.io.File("/sys/class/thermal")
            val zones = thermalDir.listFiles { f -> f.isDirectory && f.name.startsWith("thermal_zone") } ?: emptyArray()
            for (z in zones) {
                val type = java.io.File(z, "type").readText().trim()
                val temp = java.io.File(z, "temp").readText().trim()
                if (type.isNotEmpty() && temp.isNotEmpty()) {
                    sb.append("$type:$temp\n")
                }
            }
        } catch (e: Exception) {}
        return if (sb.isNotEmpty()) sb.toString() else null
    }

    override fun suspendPackages(packageNames: Array<out String>?, suspended: Boolean): SuspendResult {
        val failed = mutableListOf<String>()
        var successCount = 0
        val action = if (suspended) "suspend" else "unsuspend"
        packageNames?.forEach { pkg ->
            val result = executeCommandWithResult("cmd package $action --user 0 $pkg")
            if (result.exitCode == 0) successCount++ else failed.add(pkg)
        }
        return SuspendResult().apply {
            this.failedPackages = failed.toTypedArray()
            this.successCount = successCount
        }
    }

    override fun setAppOpMode(packageNames: Array<out String>?, opCode: Int, mode: Int): Int {
        var count = 0
        val modeStr = when (mode) {
            1 -> "ignore"
            2 -> "deny"
            else -> "allow"
        }
        packageNames?.forEach { pkg ->
            executeCommand("cmd appops set $pkg $opCode $modeStr")
            count++
        }
        return count
    }

    override fun killCurrentProcess() {
        try {
            currentProcess?.destroy()
            currentProcess = null
        } catch (_: Exception) {}
    }

    override fun destroy() {
        exitProcess(0)
    }
}
