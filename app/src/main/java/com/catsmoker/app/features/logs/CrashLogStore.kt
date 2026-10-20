package com.catsmoker.app.features.logs

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crash chain for the diagnostics bundle (M228): persists the last few uncaught
 * exceptions so the next launch can include them in an exported bug report.
 *
 * Design notes:
 * - The handler always delegates to the previously installed handler in `finally`, so
 *   the platform's normal crash behavior (and any other reporter) is untouched. Capture
 *   failure can never swallow a crash.
 * - Only in-memory data is read on the crashing thread: exception/stack plus the current
 *   app log tail ([AppLogCollector.currentLogs], own PID only). One small file write,
 *   then the delegate runs — no coroutines, no network, no blocking work.
 * - Retention is five files; eviction happens on the crash path after the write (cheap
 *   directory listing) and defensively again on export.
 */
object CrashLogStore {

    private const val DIR = "crash_reports"
    private const val MAX_FILES = 5
    private const val MAX_LOG_LINES = 150

    /** Installs the handler once per process. [logs] must be safe to call on the crashing thread. */
    @Synchronized
    fun install(context: Context, logs: () -> List<String>) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is CrashHandler) return
        Thread.setDefaultUncaughtExceptionHandler(
            CrashHandler(context.applicationContext ?: context, previous, logs)
        )
    }

    /** Persisted crash reports, newest first. */
    fun list(context: Context): List<File> =
        File(context.filesDir, DIR)
            .listFiles { file -> file.isFile }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

    /** Crash reports beyond the retention limit (export-time backstop). */
    fun prune(context: Context) {
        list(context).drop(MAX_FILES).forEach { runCatching { it.delete() } }
    }

    private class CrashHandler(
        private val context: Context,
        private val previous: Thread.UncaughtExceptionHandler?,
        private val logs: () -> List<String>,
    ) : Thread.UncaughtExceptionHandler {

        override fun uncaughtException(thread: Thread, throwable: Throwable) {
            try {
                capture(thread, throwable)
            } catch (_: Throwable) {
                // Never block the platform handler because a diagnostic write failed.
            } finally {
                previous?.uncaughtException(thread, throwable)
            }
        }

        private fun capture(thread: Thread, throwable: Throwable) {
            val dir = File(context.filesDir, DIR).apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val file = File(dir, "crash-$stamp-${System.currentTimeMillis() % 1000}.txt")
            file.bufferedWriter().use { writer ->
                writer.write("time=${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\n")
                writer.write("thread=${thread.name}\n")
                writer.write("exception=${throwable.javaClass.name}: ${throwable.message}\n")
                writer.write("device=${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})\n")
                writer.write("android=${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n")
                writer.write("\n--- stack trace ---\n")
                val trace = PrintWriter(writer)
                throwable.printStackTrace(trace)
                trace.flush()
                writer.write("\n--- recent app logs ---\n")
                runCatching { logs() }
                    .getOrDefault(emptyList())
                    .takeLast(MAX_LOG_LINES)
                    .forEach { line ->
                        writer.write(line)
                        writer.newLine()
                    }
            }
            dir.listFiles { f -> f.isFile }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(MAX_FILES)
                ?.forEach { runCatching { it.delete() } }
        }
    }
}
