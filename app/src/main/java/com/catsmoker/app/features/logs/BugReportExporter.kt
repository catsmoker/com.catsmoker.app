package com.catsmoker.app.features.logs

import android.content.Context
import android.os.Build
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds the self-contained diagnostics ZIP offered by the Logs screen (M228).
 *
 * Contents:
 * - `report.html` — human-readable report with device facts, crash records and the live log
 * - `logs.txt` — the same log tail as plain text
 * - `device.txt` — app/device facts
 * - `crashes/` — persisted crash records ([CrashLogStore]), one text file each
 *
 * Adapted from the reference `debug-overlay` bug-report bundle (ZIP + HTML + crash chain),
 * not copied verbatim: the reference's exit history, network capture, jank stats and
 * queued-draft UI are deliberately absent — exit history was removed from this app by
 * design, and no network client is instrumented here. The screenshot stays a manual
 * artifact on the Logs screen, as before.
 */
object BugReportExporter {

    private const val SHARE_DIR = "shared"
    private const val MAX_KEPT_FILES = 3

    /** Writes the ZIP into the FileProvider-served cache dir and returns it, or null on failure. */
    fun export(context: Context, logs: List<String>): File? {
        return runCatching {
            val dir = File(context.cacheDir, SHARE_DIR).apply { mkdirs() }
            dir.listFiles()
                ?.sortedByDescending { it.lastModified() }
                ?.drop(MAX_KEPT_FILES - 1)
                ?.forEach { runCatching { it.delete() } }
            CrashLogStore.prune(context)
            val crashes = CrashLogStore.list(context)
            val device = deviceInfo(context)
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val file = File(dir, "catsmoker-report-$stamp.zip")
            ZipOutputStream(BufferedOutputStream(FileOutputStream(file))).use { zip ->
                put(zip, "report.html", html(device, logs, crashes))
                put(zip, "logs.txt", logs.joinToString("\n"))
                put(zip, "device.txt", device)
                crashes.forEach { crash ->
                    put(zip, "crashes/${crash.name}", crash.readText())
                }
            }
            file
        }.getOrNull()
    }

    private fun put(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun deviceInfo(context: Context): String {
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
        return buildString {
            appendLine("app=${context.packageName} $version")
            appendLine("device=${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            appendLine("product=${Build.PRODUCT}")
            appendLine("android=${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("abis=${Build.SUPPORTED_ABIS.joinToString(", ")}")
            appendLine("locale=${Locale.getDefault()}")
            appendLine("time=${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())}")
        }
    }

    private fun html(device: String, logs: List<String>, crashes: List<File>): String = buildString {
        append("<!doctype html><html><head><meta charset=\"utf-8\">")
        append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        append("<title>Catsmoker bug report</title><style>")
        append(
            "body{background:#111418;color:#e6e6e6;font:14px/1.45 system-ui,sans-serif;" +
                "margin:0;padding:20px}h1{font-size:20px;margin:0 0 4px}" +
                "h2{font-size:15px;margin:24px 0 8px;color:#9fd0ff}" +
                ".meta{color:#9aa4ad;font-size:12px;margin-bottom:16px}" +
                "pre{white-space:pre-wrap;word-break:break-word;background:#1b2026;" +
                "border:1px solid #2b333c;border-radius:8px;padding:12px;font:12px/1.45 monospace}" +
                ".empty{color:#9aa4ad;font-style:italic}"
        )
        append("</style></head><body>")
        append("<h1>Catsmoker bug report</h1>")
        append("<div class=\"meta\">").append(escape(device.trim())).append("</div>")
        append("<h2>Crash records (").append(crashes.size).append(")</h2>")
        if (crashes.isEmpty()) {
            append("<p class=\"empty\">No crash captured.</p>")
        } else {
            crashes.forEach { crash ->
                append("<h3>").append(escape(crash.name)).append("</h3><pre>")
                append(escape(crash.readText()))
                append("</pre>")
            }
        }
        append("<h2>Live app logs (").append(logs.size).append(" lines)</h2>")
        if (logs.isEmpty()) {
            append("<p class=\"empty\">No logs captured.</p>")
        } else {
            append("<pre>").append(escape(logs.joinToString("\n"))).append("</pre>")
        }
        append("</body></html>")
    }

    private fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
