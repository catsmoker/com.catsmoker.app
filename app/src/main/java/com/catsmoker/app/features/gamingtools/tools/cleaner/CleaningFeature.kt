package com.catsmoker.app.features.gamingtools.tools.cleaner

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import com.catsmoker.app.system.shell.ShellRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

object CleaningFeature {
    enum class Category(val label: String, val isAggressive: Boolean = false) {
        CACHE("App Cache"),
        TEMP("Temporary Files"),
        THUMBNAILS("Gallery Thumbnails"),
        EMPTY_DIRS("Empty Folders", true),
        LOGS("System Logs", true),
        CORPSES("Dead App Data", true)
    }

    /**
     * One junk bucket.
     *
     * @param sizeBytes total measured size of [affectedPaths]. Measured with `File.length()` for
     *   paths this app can read and with `du -sk` for paths only the privileged shell can reach.
     * @param itemCount number of top-level paths claimed, not the number of files inside them.
     */
    data class ScanResult(
        val category: Category,
        val sizeBytes: Long,
        val itemCount: Int,
        val affectedPaths: List<String>
    )

    /**
     * Outcome of a scan, including what it could *not* reach.
     *
     * The distinction between "scanned and found nothing" and "could not scan" matters: reporting
     * 0 B for the second case is a fabricated result, so the flags below let the UI say which
     * happened.
     */
    data class ScanReport(
        val results: List<ScanResult> = emptyList(),
        /** True when the shared-storage tree was actually walked with file APIs. */
        val scannedSharedStorage: Boolean = false,
        /** True when `Android/data` + `Android/obb` were read through root/Shizuku. */
        val scannedAppData: Boolean = false,
        /** True when the UI should offer the all-files-access settings screen. */
        val needsAllFilesAccess: Boolean = false,
        /** Exactly what this scan could not reach. Rendered verbatim in the UI. */
        val limitations: List<String> = emptyList()
    ) {
        val totalBytes: Long get() = results.sumOf { it.sizeBytes }
        val totalItems: Int get() = results.sumOf { it.itemCount }

        /** False when nothing could be read at all — distinct from "read everything, found nothing". */
        val scannedAnything: Boolean get() = scannedSharedStorage || scannedAppData
    }

    /** Guard rails so a pathological tree cannot hang the scan or blow the stack. */
    private const val MAX_DEPTH = 12
    private const val MAX_PATHS_PER_CATEGORY = 2000

    /** The storage root the blacklist patterns below are written against. */
    private const val PRIMARY_ROOT = "/storage/emulated/0"

    /**
     * One filesystem block. `du -sk` reports this for a directory holding nothing but itself, so a
     * hit at or below it has no content to reclaim and is not worth listing as junk.
     */
    private const val ONE_BLOCK_BYTES = 4096L

    /** A `du -sk` output line: a kilobyte count, whitespace, then an absolute path. */
    private val DU_LINE = Regex("(\\d+)\\s+(/.+)")

    /** Well-formed package name, which is also guaranteed free of shell metacharacters. */
    private val PACKAGE_NAME = Regex("[A-Za-z0-9_][A-Za-z0-9_.\\-]*")

    /**
     * Directories we never descend into or delete. Losing any of these is unrecoverable for
     * the user, and no amount of reclaimed space justifies the risk.
     */
    private val protectedNames = setOf(
        "dcim", "pictures", "movies", "music", "documents", "download", "downloads",
        "whatsapp", "telegram", "signal", "recordings", "screenshots", "books",
        "audiobooks", "podcasts", "ringtones", "alarms", "notifications", "backups",
        "backup", "titaniumbackup", "keys", "obb"
    )

    private val blacklistPatterns = listOf(
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

    /**
     * @return true when this app's own file APIs can read shared storage.
     *
     * From API 30 the plain read permission only covers media, so all-files access is the only
     * thing that makes a full storage walk possible — which is exactly the check the reference
     * cleaner performs before scanning.
     */
    fun hasStorageAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    /**
     * Walks external storage and buckets junk by [Category].
     *
     * Two channels are used, because neither alone can see everything:
     *  - file APIs for shared storage, which needs all-files access on API 30+;
     *  - a privileged shell for `Android/data` and `Android/obb`, which no app's file APIs can
     *    reach from API 30 onwards no matter which permissions are held.
     *
     * Whatever a channel cannot cover is recorded in [ScanReport.limitations] instead of quietly
     * contributing 0 B, so the UI never presents "no access" as "nothing to clean".
     *
     * @param shellRunner privileged channel (root first, Shizuku otherwise). Also the source of the
     *   authoritative installed-package list: without it [Category.CORPSES] is skipped entirely,
     *   because package-visibility filtering makes `getInstalledApplications` under-report installed
     *   apps and healthy `Android/data` directories would be misread as leftovers.
     */
    suspend fun scan(context: Context, shellRunner: ShellRunner): ScanReport =
        withContext(Dispatchers.IO) {
            val root = Environment.getExternalStorageDirectory()
            val rootPath = root.absolutePath
            val limitations = mutableListOf<String>()

            val directAccess = hasStorageAccess(context)
            val privileged = shellRunner.hasPrivilege()

            // A granted permission is not proof the volume is readable, so the listing itself is
            // the test. null here is precisely the case that used to empty every bucket silently.
            val rootListing = if (directAccess) root.listFiles() else null

            if (!directAccess) {
                limitations += if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    "All-files access is not granted, so shared storage was not scanned."
                } else {
                    "Storage permission is not granted, so shared storage was not scanned."
                }
            } else if (rootListing == null) {
                limitations += "$rootPath could not be listed even though access is granted — " +
                    "the volume may be unmounted."
            }
            if (!privileged) {
                limitations += "Root/Shizuku unavailable: Android/data and Android/obb are closed " +
                    "to app file APIs on Android 11+, so app caches there were not scanned."
            }

            val installedPackages = resolveInstalledPackages(context, shellRunner)
            if (installedPackages == null) {
                limitations += "Installed-app list unavailable, so leftover app data was not " +
                    "identified (guessing would risk deleting a live app's files)."
            }

            val buckets = Buckets()

            if (rootListing != null) {
                walkSharedStorage(root, rootPath, installedPackages, buckets)
            }

            val scannedAppData = if (privileged) {
                scanAppDataViaShell(shellRunner, rootPath, installedPackages, buckets, limitations)
            } else {
                false
            }

            ScanReport(
                results = buckets.toResults(),
                scannedSharedStorage = rootListing != null,
                scannedAppData = scannedAppData,
                needsAllFilesAccess = !directAccess,
                limitations = limitations
            )
        }

    /** Iterative walk: recursion overflows on deep trees, and an explicit stack lets us cap depth. */
    private suspend fun walkSharedStorage(
        root: File,
        rootPath: String,
        installedPackages: Set<String>?,
        buckets: Buckets
    ) {
        val stack = ArrayDeque<Pair<File, Int>>()
        stack.addLast(root to 0)
        var visited = 0

        while (stack.isNotEmpty()) {
            if (++visited % 256 == 0) coroutineContext.ensureActive()
            val (dir, depth) = stack.removeLast()
            val children = dir.listFiles() ?: continue

            for (child in children) {
                if (isProtected(child) || isSymlink(child)) continue

                if (child.isDirectory) {
                    val category = classifyDirectory(child, rootPath, installedPackages)
                    if (category != null) {
                        // Claim the whole directory and stop — descending would double-count
                        // its contents against a second category.
                        buckets.add(category, child.absolutePath, sizeOf(child))
                        continue
                    }
                    if (depth < MAX_DEPTH) stack.addLast(child to depth + 1)
                } else if (matchesBlacklist(child.absolutePath, rootPath)) {
                    buckets.add(Category.LOGS, child.absolutePath, child.length())
                }
            }
        }
    }

    /**
     * Reads the junk that file APIs cannot see.
     *
     * `Android/data` and `Android/obb` are closed to every app's file APIs from API 30 onwards, and
     * all-files access does not lift that, so a privileged shell is the only honest way to find,
     * size or remove app caches there. `du -sk` supplies the sizes because `File.length()` returns
     * 0 on those paths for the same reason.
     *
     * @return true when the directory was reachable and therefore actually scanned.
     */
    private suspend fun scanAppDataViaShell(
        shellRunner: ShellRunner,
        rootPath: String,
        installedPackages: Set<String>?,
        buckets: Buckets,
        limitations: MutableList<String>
    ): Boolean {
        val dataDir = "$rootPath/Android/data"
        if (!shellRunner.execSafeResult("test", "-d", dataDir).isSuccess) {
            limitations += "$dataDir is not present, so app caches there were not scanned."
            return false
        }

        val globsByCategory = linkedMapOf(
            Category.CACHE to listOf(
                "$dataDir/*/cache",
                "$dataDir/*/code_cache",
                "$dataDir/*/files/cache",
                "$dataDir/*/files/.cache"
            ),
            Category.TEMP to listOf(
                "$dataDir/*/files/temp",
                "$dataDir/*/files/tmp",
                "$dataDir/*/cache/tmp"
            ),
            Category.LOGS to listOf(
                "$dataDir/*/files/log",
                "$dataDir/*/files/logs",
                "$dataDir/*/files/Logs",
                "$dataDir/*/files/tombstone_*",
                "$dataDir/*/files/crashlytics"
            )
        )

        for ((category, globs) in globsByCategory) {
            coroutineContext.ensureActive()
            for ((path, size) in duSizes(shellRunner, globs)) {
                if (size > ONE_BLOCK_BYTES) buckets.add(category, path, size)
            }
        }

        if (installedPackages != null) {
            coroutineContext.ensureActive()
            for ((path, size) in shellCorpses(shellRunner, rootPath, installedPackages)) {
                buckets.add(Category.CORPSES, path, size)
            }
        }
        return true
    }

    /**
     * Measures each match of [globs] with `du -sk`.
     *
     * Both privileged channels run the command through `sh -c`, so the shell expands the globs and
     * `du -sk` prints one line per match. A pattern matching nothing is passed through literally,
     * `du` fails on it, and its error goes to /dev/null — so unmatched patterns simply produce no
     * line. A non-zero exit is therefore normal here and only the absence of output means failure.
     */
    private suspend fun duSizes(shellRunner: ShellRunner, globs: List<String>): List<Pair<String, Long>> {
        if (globs.isEmpty()) return emptyList()
        val result = shellRunner.execResult("du -sk ${globs.joinToString(" ")} 2>/dev/null")
        return result.stdout.lineSequence().mapNotNull { line ->
            val match = DU_LINE.matchEntire(line.trim()) ?: return@mapNotNull null
            val kb = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            match.groupValues[2] to kb * 1024L
        }.toList()
    }

    /**
     * Finds `Android/data` and `Android/obb` directories belonging to packages that are no longer
     * installed. Only well-formed package names qualify: a stray folder is not proof of an
     * uninstalled app, and the name restriction also keeps every path shell-safe.
     */
    private suspend fun shellCorpses(
        shellRunner: ShellRunner,
        rootPath: String,
        installed: Set<String>
    ): List<Pair<String, Long>> {
        val orphans = mutableListOf<String>()
        for (dirName in listOf("data", "obb")) {
            val dir = "$rootPath/Android/$dirName"
            val listing = shellRunner.execSafeResult("ls", "-1", dir)
            if (!listing.isSuccess) continue
            listing.stdout.lineSequence()
                .map { it.trim() }
                .filter { it.contains('.') && PACKAGE_NAME.matches(it) && it !in installed }
                .forEach { orphans += "$dir/$it" }
        }
        // Exact paths, so every one of them produces a du line.
        return duSizes(shellRunner, orphans)
    }

    /** Collects claimed paths per category, with the size measured by whichever channel found them. */
    private class Buckets {
        private val entries = linkedMapOf<Category, MutableList<Pair<String, Long>>>()
        private val claimed = mutableSetOf<String>()

        fun add(category: Category, path: String, sizeBytes: Long) {
            val list = entries.getOrPut(category) { mutableListOf() }
            if (list.size >= MAX_PATHS_PER_CATEGORY) return
            // Both channels can reach the same path on API 27-29; counting it twice would inflate
            // the total, and so would counting a directory already covered by a claimed parent.
            if (path in claimed || isCoveredByAncestor(path)) return
            claimed.add(path)
            list.add(path to sizeBytes)
        }

        private fun isCoveredByAncestor(path: String): Boolean {
            var separator = path.lastIndexOf('/')
            while (separator > 0) {
                if (path.substring(0, separator) in claimed) return true
                separator = path.lastIndexOf('/', separator - 1)
            }
            return false
        }

        fun toResults(): List<ScanResult> = entries
            .filterValues { it.isNotEmpty() }
            .map { (category, items) ->
                ScanResult(
                    category = category,
                    sizeBytes = items.sumOf { it.second },
                    itemCount = items.size,
                    affectedPaths = items.map { it.first }
                )
            }
    }

    private fun classifyDirectory(
        dir: File,
        rootPath: String,
        installedPackages: Set<String>?
    ): Category? = when {
        dir.name.equals("cache", ignoreCase = true) -> Category.CACHE
        dir.name.equals("thumbnails", ignoreCase = true) ||
            dir.name.equals(".thumbnails", ignoreCase = true) -> Category.THUMBNAILS
        dir.name.equals("temp", ignoreCase = true) ||
            dir.name.equals("tmp", ignoreCase = true) -> Category.TEMP
        installedPackages != null && isCorpse(dir, installedPackages) -> Category.CORPSES
        matchesBlacklist(dir.absolutePath, rootPath) -> Category.LOGS
        isEmptyDir(dir) -> Category.EMPTY_DIRS
        else -> null
    }

    /**
     * @return every installed package name, or null when we cannot enumerate them reliably.
     *   `pm list packages` runs with shell/root identity and is not subject to the calling
     *   app's package-visibility filter, unlike PackageManager.
     */
    private suspend fun resolveInstalledPackages(context: Context, shellRunner: ShellRunner): Set<String>? {
        if (!shellRunner.hasPrivilege()) return null
        val result = shellRunner.execResult("pm list packages")
        if (!result.isSuccess) return null
        val packages = result.stdout.lineSequence()
            .mapNotNull { line -> line.trim().removePrefix("package:").takeIf { it.isNotEmpty() } }
            .toSet()
        if (packages.isEmpty()) return null

        // Union with whatever PackageManager can see, so a truncated shell listing can never
        // cause a live app's data directory to be flagged as a corpse.
        val visible = runCatching {
            context.packageManager.getInstalledApplications(0).map { it.packageName }
        }.getOrDefault(emptyList())
        return packages + visible
    }

    private fun matchesBlacklist(path: String, rootPath: String): Boolean {
        // The patterns are written against the primary user's root. Normalising means they also
        // match on a secondary user or work profile, where the root is /storage/emulated/<userId>.
        val normalized = if (rootPath != PRIMARY_ROOT && path.startsWith(rootPath)) {
            PRIMARY_ROOT + path.removePrefix(rootPath)
        } else {
            path
        }
        return blacklistPatterns.any { it.matches(normalized) }
    }

    private fun isProtected(file: File): Boolean = file.name.lowercase() in protectedNames

    /** Symlinks can point back up the tree (or off it), so never follow or delete them. */
    private fun isSymlink(file: File): Boolean = try {
        file.canonicalPath != file.absolutePath
    } catch (_: Exception) {
        true // Unreadable link target — treat as unsafe.
    }

    private fun isCorpse(file: File, installed: Set<String>): Boolean {
        val parent = file.parentFile?.name ?: return false
        val grandParent = file.parentFile?.parentFile?.name ?: return false
        if (parent != "data" && parent != "obb") return false
        if (grandParent != "Android") return false
        // Only well-formed package names; a stray folder is not proof of an uninstalled app.
        if (!file.name.contains('.')) return false
        return file.name !in installed
    }

    private fun isEmptyDir(file: File): Boolean = file.list()?.isEmpty() == true

    /** Recursive byte total. `File.length()` on a directory reports the inode, not its contents. */
    private fun sizeOf(file: File): Long {
        if (isSymlink(file)) return 0L
        if (!file.isDirectory) return file.length()
        var total = 0L
        val stack = ArrayDeque<Pair<File, Int>>()
        stack.addLast(file to 0)
        while (stack.isNotEmpty()) {
            val (dir, depth) = stack.removeLast()
            val children = dir.listFiles() ?: continue
            for (child in children) {
                if (isSymlink(child)) continue
                if (child.isDirectory) {
                    if (depth < MAX_DEPTH) stack.addLast(child to depth + 1)
                } else {
                    total += child.length()
                }
            }
        }
        return total
    }

    /**
     * Deletes the scanned paths and reports what happened.
     *
     * Every figure below comes from a path that was measured immediately before deletion and whose
     * removal was then confirmed: `File.exists()` for paths this app can read, `test -e` through the
     * privileged shell for `Android/data` and `Android/obb`, which file APIs report as absent even
     * when they are there.
     */
    suspend fun clean(shellRunner: ShellRunner, results: List<ScanResult>): List<String> =
        withContext(Dispatchers.IO) {
            val logs = mutableListOf<String>()
            val privileged = shellRunner.hasPrivilege()

            when {
                shellRunner.isRootAvailable() -> logs.add("🚀 Using root access for deep cleaning…")
                privileged -> logs.add("⚡ Using Shizuku for deep cleaning…")
                else -> logs.add("ℹ No root or Shizuku — only paths this app can reach will be removed.")
            }
            if (privileged) {
                // Asks the framework to drop its own internal app caches. That space is not part of
                // the scanned paths, so it is deliberately left out of the freed total below.
                shellRunner.trimCaches()
                logs.add("• Asked the system to trim internal app caches (not counted below)")
            }

            var deleted = 0
            var failed = 0
            var unmeasured = 0
            var alreadyGone = 0
            var freedBytes = 0L

            for (result in results) {
                for (path in result.affectedPaths) {
                    coroutineContext.ensureActive()
                    val file = File(path)
                    if (isProtected(file)) continue

                    val visible = file.exists()
                    if (!visible && !(privileged && shellExists(shellRunner, path))) {
                        alreadyGone++
                        continue
                    }

                    val size = if (visible) sizeOf(file) else shellSize(shellRunner, path)
                    if (deleteWithFallback(file, shellRunner, privileged)) {
                        deleted++
                        if (size != null) freedBytes += size else unmeasured++
                    } else {
                        failed++
                    }
                }
            }

            if (deleted > 0) {
                logs.add(
                    if (unmeasured > 0) {
                        "✓ Removed $deleted items (${formatBytes(freedBytes)} freed; " +
                            "$unmeasured item(s) whose size could not be measured)"
                    } else {
                        "✓ Removed $deleted items (${formatBytes(freedBytes)} freed)"
                    }
                )
            }
            if (failed > 0) {
                logs.add(
                    if (privileged) {
                        "⚠ $failed items could not be removed"
                    } else {
                        "⚠ $failed items need root or Shizuku to remove"
                    }
                )
            }
            if (alreadyGone > 0) logs.add("• $alreadyGone scanned paths were already gone")
            logs.add(if (deleted == 0 && failed == 0) "Nothing was removed." else "✅ Cleaning complete!")
            logs
        }

    private suspend fun deleteWithFallback(file: File, shellRunner: ShellRunner, privileged: Boolean): Boolean {
        val direct = try {
            if (file.isDirectory) file.deleteRecursively() else file.delete()
        } catch (_: Exception) {
            false
        }
        // A partial recursive delete returns true with the directory still standing, so the
        // existence check is what decides, not the return value.
        if (direct && !file.exists()) return true
        if (!privileged) return false

        shellRunner.execSafe("rm", "-rf", file.absolutePath)
        // File.exists() answers "false" for Android/data whether or not the path is really gone,
        // so success has to be confirmed through the same channel that did the deleting.
        return !shellExists(shellRunner, file.absolutePath)
    }

    /** Existence as the privileged shell sees it, which is the only view valid for Android/data. */
    private suspend fun shellExists(shellRunner: ShellRunner, path: String): Boolean =
        shellRunner.execSafeResult("test", "-e", path).isSuccess

    /** @return size in bytes per `du -sk`, or null when it could not be measured. */
    private suspend fun shellSize(shellRunner: ShellRunner, path: String): Long? {
        val result = shellRunner.execSafeResult("du", "-sk", path)
        val line = result.stdout.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
            ?: return null
        val match = DU_LINE.matchEntire(line) ?: return null
        return match.groupValues[1].toLongOrNull()?.times(1024L)
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> String.format(java.util.Locale.US, "%.2f GB", bytes / 1_073_741_824.0)
        bytes >= 1_048_576L -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1_048_576.0)
        bytes >= 1024L -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
