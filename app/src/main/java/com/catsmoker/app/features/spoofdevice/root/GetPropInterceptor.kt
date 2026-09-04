package com.catsmoker.app.features.spoofdevice.root

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import com.catsmoker.app.shared.data.model.LSPosedConfig
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Makes `getprop` subprocess calls agree with the hooked `SystemProperties` reads.
 *
 * Device-info and anti-cheat code routinely gathers properties twice — once through
 * `SystemProperties`, once by shelling out to `getprop` — precisely because a module that only
 * hooks the former leaves the two disagreeing. That mismatch is a stronger signal than the
 * original values would have been, so a partial spoof is worse than none.
 *
 * @param lookup resolves one property key against the active profile, or null to leave it alone.
 * @param properties every profile entry that is actually a system property, for the full dump.
 */
internal class GetPropInterceptor(
    private val lookup: (String) -> String?,
    private val properties: () -> Map<String, String>
) {
    /** What a parsed command line is asking for. */
    private sealed interface Request {
        /** Bare `getprop`, which prints every property. */
        object FullDump : Request
        data class SingleKey(val key: String) : Request
    }

    fun install() {
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val original = param.result as? Process ?: return
                // Runtime.exec funnels into ProcessBuilder.start, so the inner hook has usually
                // replaced the process already. Re-wrapping would only redo the same work.
                if (original is CannedProcess) return
                val command = commandOf(param) ?: return
                val request = parse(command) ?: return
                replacement(original, request)?.let { param.result = it }
            }
        }

        runCatching { XposedHelpers.findAndHookMethod(ProcessBuilder::class.java, "start", hook) }
        runCatching {
            XposedHelpers.findAndHookMethod(
                Runtime::class.java, "exec",
                Array<String>::class.java, Array<String>::class.java, File::class.java, hook
            )
        }
    }

    /** Reads the argv out of whichever of the two hooked methods fired. */
    @Suppress("UNCHECKED_CAST")
    private fun commandOf(param: XC_MethodHook.MethodHookParam): List<String>? {
        (param.args.getOrNull(0) as? Array<*>)?.let { argv ->
            return argv.mapNotNull { it as? String }
        }
        return runCatching { XposedHelpers.callMethod(param.thisObject, "command") as? List<String> }
            .getOrNull()
            ?.toList()
    }

    private fun parse(command: List<String>): Request? {
        val argv = command.map { it.trim() }.filter { it.isNotEmpty() }
        val first = argv.firstOrNull()?.let(::leafName) ?: return null

        if (first == "getprop") {
            return argv.getOrNull(1)?.let { Request.SingleKey(it) } ?: Request.FullDump
        }
        // `sh -c "getprop ro.product.model"` is just as common as the direct form.
        if (first == "sh" || first == "su") {
            val flagIndex = argv.indexOf("-c")
            if (flagIndex >= 0) return argv.getOrNull(flagIndex + 1)?.let(::parseShellLine)
        }
        return null
    }

    private fun parseShellLine(line: String): Request? {
        val parts = line.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        if (parts.firstOrNull()?.let(::leafName) != "getprop") return null
        return parts.getOrNull(1)?.let { Request.SingleKey(it) } ?: Request.FullDump
    }

    /**
     * @return a process serving the spoofed answer, or null when the profile has nothing to say
     *   about this request and the real output should be passed through untouched.
     */
    private fun replacement(original: Process, request: Request): Process? = when (request) {
        is Request.SingleKey -> {
            val spoofed = lookup(request.key)
            if (spoofed == null) {
                null
            } else {
                // The real subprocess still has to be drained and reaped, or its pipes and its
                // zygote-forked child both leak.
                drain(original.inputStream)
                CannedProcess("$spoofed\n", drain(original.errorStream), exitCodeOf(original))
            }
        }

        Request.FullDump -> {
            val realDump = drain(original.inputStream)
            val stderr = drain(original.errorStream)
            val exitCode = exitCodeOf(original)
            val merged = mergeDump(realDump)
            if (merged == null) null else CannedProcess(merged, stderr, exitCode)
        }
    }

    /**
     * Overlays the profile onto a real `getprop` dump, keeping every untouched property.
     *
     * @return the rewritten dump, or null when the profile holds no properties to overlay.
     */
    private fun mergeDump(realDump: String): String? {
        val spoofed = properties().filterKeys(LSPosedConfig::isSystemProperty)
        if (spoofed.isEmpty()) return null

        // LinkedHashMap so the real device's property order survives; ours append at the end.
        val merged = LinkedHashMap<String, String>()
        realDump.lineSequence().forEach { line ->
            parseDumpLine(line)?.let { (key, value) -> merged[key] = value }
        }
        merged.putAll(spoofed)

        return buildString {
            for ((key, value) in merged) append('[').append(key).append("]: [").append(value).append("]\n")
        }
    }

    /** Parses one `[key]: [value]` line of `getprop` output. */
    private fun parseDumpLine(line: String): Pair<String, String>? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return null
        val separator = trimmed.indexOf("]: [")
        if (separator <= 1) return null
        return trimmed.substring(1, separator) to
            trimmed.substring(separator + 4, trimmed.length - 1)
    }

    private fun drain(stream: InputStream?): String =
        runCatching { stream?.readBytes()?.toString(Charsets.UTF_8) }.getOrNull().orEmpty()

    private fun exitCodeOf(process: Process): Int = try {
        process.waitFor()
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        0
    }

    /** `/system/bin/getprop` and `getprop` are the same command. */
    private fun leafName(value: String): String =
        value.replace('\\', '/').substringAfterLast('/').lowercase()

    /**
     * A [Process] over fixed bytes. The app reads it exactly like a real one; nothing was forked,
     * so [destroy] has nothing to do.
     */
    private class CannedProcess(
        stdout: String,
        stderr: String,
        private val exitCode: Int
    ) : Process() {
        private val stdoutStream = ByteArrayInputStream(stdout.toByteArray())
        private val stderrStream = ByteArrayInputStream(stderr.toByteArray())
        private val stdinSink = ByteArrayOutputStream()

        override fun getOutputStream(): OutputStream = stdinSink
        override fun getInputStream(): InputStream = stdoutStream
        override fun getErrorStream(): InputStream = stderrStream
        override fun waitFor(): Int = exitCode
        override fun exitValue(): Int = exitCode
        override fun destroy() {}
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}
