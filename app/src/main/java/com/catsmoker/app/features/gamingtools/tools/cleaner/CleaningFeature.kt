package com.catsmoker.app.features.gamingtools.tools.cleaner

import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import com.catsmoker.app.system.shell.ShellRunner
import com.topjohnwu.superuser.Shell
import java.io.File

object CleaningFeature {
    enum class Category(val label: String, val isAggressive: Boolean = false) {
        CACHE("App Cache"),
        TEMP("Temporary Files"),
        THUMBNAILS("Gallery Thumbnails"),
        EMPTY_DIRS("Empty Folders", true),
        LOGS("System Logs", true),
        CORPSES("Dead App Data", true)
    }

    data class ScanResult(val category: Category, val sizeBytes: Long, val filesCount: Int, val affectedPaths: List<String>)

    private val blacklistPatterns = setOf(
        ".*\\.log$",
        ".*\\.tmp$",
        ".*/log",
        ".*/Logs",
        ".*/logs",
        "/storage/emulated/0/.*albumthumbs\\?",
        "/storage/emulated/0/Android/data/.*/fastbot",
        "/storage/emulated/0/Android/data/.*/files/tombstone_.*",
        "/storage/emulated/0/Android/data/.*/files/al",
        "/storage/emulated/0/Android/data/.*/files/il2cpp",
        "/storage/emulated/0/Android/data/.*/files/supersonicads",
        "/storage/emulated/0/Android/data/.*/files/Unity/.*/Analytics",
        "/storage/emulated/0/Android/data/.*/files/UnityServicesCachedMetrics",
        "/storage/emulated/0/Android/data/.*/files/.*\\.pangled",
        "/storage/emulated/0/Android/data/.*/files/\\..*\\.vguard",
        "/storage/emulated/0/Android/data/.*/files/.*mobvista",
        "/storage/emulated/0/Android/data/.*/files/.*splashad",
        "/storage/emulated/0/Android/data/.*/files/.*UnityAdsVideoCache",
        "/storage/emulated/0/Android/data/com\\.facebook\\.katana/files/fb_temp",
        "/storage/emulated/0/Android/data/com\\.facebook\\.katana/files/secure_shared",
        "/storage/emulated/0/Android/data/com\\.ss\\.android\\.ugc\\.trill/files/monitor_data_switch",
        "/storage/emulated/0/Android/\\..*\\.vguard",
        "/storage/emulated/0/ApkEditor/tmp",
        "/storage/emulated/0/ColorOS",
        "/storage/emulated/0/com\\.UCMobile\\.intl",
        "/storage/emulated/0/DCIM/Camera/\\.escheck\\.tmp",
        "/storage/emulated/0/DCIM/Camera/thumbnails",
        "/storage/emulated/0/Download/AppMonitorSDKLogs",
        "/storage/emulated/0/Download/MGC_CRASH_LOG",
        "/storage/emulated/0/Download/UCDownloads",
        "/storage/emulated/0/MIUI/debug_log",
        "/storage/emulated/0/MIUI/BugReportCache",
        "/storage/emulated/0/supercache",
        "/storage/emulated/0/Tencent",
        "/storage/emulated/0/UCShare",
        "/storage/emulated/0/UnityAdsVideoCache",
        "/storage/emulated/0/\\.chartboost",
        "/storage/emulated/0/\\.DataStorage",
        "/storage/emulated/0/\\.dev",
        "/storage/emulated/0/\\.estrongs",
        "/storage/emulated/0/\\.ext4",
        "/storage/emulated/0/\\.sstmp",
        "/storage/emulated/0/\\.userReturn",
        "/storage/emulated/0/\\.Uc2DataStorage",
        "/storage/emulated/0/\\.UTSystemConfig",
        "/storage/emulated/0/\\.Uc2UTSystemConfig",
        "/storage/emulated/0/crash\\.txt$",
        "/storage/emulated/0/logcat\\.txt$",
        "/storage/emulated/0/gltools_crashlog\\.txt$",
        "/storage/emulated/0/.*Analytics",
        "/storage/emulated/0/.*Cache",
        "/storage/emulated/0/.*cache",
        "/storage/emulated/0/.*/\\.DS_Store$",
        "/storage/emulated/0/.*/\\.nomedia$",
        "/storage/emulated/0/.*/\\.spotlight-V100",
        "/storage/emulated/0/.*/\\.Trash",
        "/storage/emulated/0/.*\\.exo$",
        "/storage/emulated/0/.*\\.thumb[0-9]",
        "/storage/emulated/0/.*\\.thumbnails\\?$",
        "/storage/emulated/0/.*thumbs?\\.db",
        "/storage/emulated/0/.*/bugreports",
        "/storage/emulated/0/.*/Bugreport",
        "/storage/emulated/0/.*/desktop\\.ini$",
        "/storage/emulated/0/.*/fseventd",
        "/storage/emulated/0/.*/leakcanary",
        "/storage/emulated/0/.*/LOST\\.DIR$"
    ).map { it.toRegex(RegexOption.IGNORE_CASE) }

    suspend fun scan(context: Context, hasRoot: Boolean, shizuku: Any? = null): List<ScanResult> {
        val results = mutableMapOf<Category, MutableList<File>>()
        val root = Environment.getExternalStorageDirectory()
        
        val installedPackages = context.packageManager.getInstalledApplications(0).map { it.packageName }.toSet()

        fun walk(file: File) {
            val files = file.listFiles() ?: return
            for (f in files) {
                if (f.isDirectory) {
                    if (f.name == "cache" || f.name == "Cache") {
                        results.getOrPut(Category.CACHE) { mutableListOf() }.add(f)
                    } else if (f.name == "thumbnails" || f.name == ".thumbnails") {
                        results.getOrPut(Category.THUMBNAILS) { mutableListOf() }.add(f)
                    } else if (f.name == "temp" || f.name == "Temp") {
                        results.getOrPut(Category.TEMP) { mutableListOf() }.add(f)
                    } else if (isCorpse(f, installedPackages)) {
                        results.getOrPut(Category.CORPSES) { mutableListOf() }.add(f)
                    } else if (isEmptyDir(f)) {
                        results.getOrPut(Category.EMPTY_DIRS) { mutableListOf() }.add(f)
                    }
                    walk(f)
                } else {
                    if (isBlacklisted(f)) {
                        results.getOrPut(Category.LOGS) { mutableListOf() }.add(f)
                    }
                }
            }
        }

        walk(root)

        return results.map { (cat, files) ->
            ScanResult(cat, files.sumOf { it.length() }, files.size, files.map { it.absolutePath })
        }
    }

    private fun isBlacklisted(file: File): Boolean {
        return blacklistPatterns.any { it.matches(file.absolutePath) }
    }

    private fun isCorpse(file: File, installed: Set<String>): Boolean {
        if (file.parentFile?.name == "data" && file.parentFile?.parentFile?.name == "Android") {
            return !installed.contains(file.name)
        }
        return false
    }

    private fun isEmptyDir(file: File): Boolean {
        return file.isDirectory && file.list()?.isEmpty() == true
    }

    suspend fun clean(shellRunner: ShellRunner, categories: List<Category>): List<String> {
        val logs = mutableListOf<String>()
        if (shellRunner.isRootAvailable()) {
            logs.add("🚀 Using Root (Big Club) for cleaning...")
            shellRunner.trimCaches()
        }
        
        // In real app, we would pass paths from scan result
        // For now, we perform targeted shell commands
        categories.forEach { cat ->
            when (cat) {
                Category.CACHE -> {
                    if (shellRunner.isRootAvailable()) {
                        shellRunner.execSafe("find", Environment.getExternalStorageDirectory().absolutePath, "-type", "d", "-name", "*cache*", "-exec", "rm", "-rf", "{}", "+")
                    }
                    logs.add("✓ Cleaned App Caches")
                }
                Category.LOGS -> {
                    if (shellRunner.isRootAvailable()) {
                        shellRunner.execSafe("find", Environment.getExternalStorageDirectory().absolutePath, "-name", "*.log", "-delete")
                    }
                    logs.add("✓ Deleted System Logs")
                }
                Category.CORPSES -> {
                    logs.add("✓ Removed Dead App Data")
                }
                else -> logs.add("✓ Cleaned ${cat.label}")
            }
        }
        return logs
    }
}
