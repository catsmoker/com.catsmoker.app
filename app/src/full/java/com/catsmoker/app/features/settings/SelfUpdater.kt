package com.catsmoker.app.features.settings

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Full-variant GitHub-release self-updater.
 *
 * Same FQN as the playstore variant's unsupported stub: [SettingsViewModel]
 * delegates here so the APK-download implementation is not compiled into the
 * Play Store variant at all (Play policy forbids self-updating).
 *
 * Release contract (verified against the live API 2026-10-09): tags are
 * `{versionCode}-{versionName}` (`9-2.0.3`), so the authoritative comparison
 * is numeric on the code, never lexicographic on the name; APK assets carry a
 * `digest: "sha256:..."` plus size. Drafts never appear in the listing;
 * prereleases are skipped unless explicitly wanted (an all-prerelease listing
 * means no update, not the first entry). Nothing is ever downgraded.
 *
 * Network work runs on Dispatchers.IO internally — safe to call from any dispatcher.
 * Progress callbacks inherit the IO context; StateFlow.update is thread-safe, but
 * Compose state writes must hop to Main in the caller.
 */
object SelfUpdater {
    const val SUPPORTED = true

    const val API_URL = "https://api.github.com/repos/catsmoker/catsmoker-app/releases"

    /** Parsed `{versionCode}-{versionName}` tag; code is null for legacy unprefixed tags. */
    data class TagVersion(val code: Int?, val name: String)

    data class UpdateInfo(
        val tagName: String,
        val downloadUrl: String?,
        val sha256Hex: String? = null,
        val sizeBytes: Long? = null,
        /** Release body, truncated for dialog display. Null when the release has none. */
        val notes: String? = null
    )

    sealed interface CheckResult {
        data object NoReleases : CheckResult
        data object UpToDate : CheckResult
        data class Available(val info: UpdateInfo) : CheckResult
        data class Failed(val message: String) : CheckResult
    }

    /**
     * Splits a `9-2.0.3` tag into its numeric code and version name. A tag
     * without a numeric `code-` prefix keeps the whole tag as the name with a
     * null code, so legacy tags compare by semver name instead of failing.
     */
    fun parseTagVersion(tag: String): TagVersion {
        val dash = tag.indexOf('-')
        if (dash > 0) {
            val code = tag.substring(0, dash).toIntOrNull()
            if (code != null && dash + 1 < tag.length) {
                return TagVersion(code, tag.substring(dash + 1))
            }
        }
        return TagVersion(null, tag)
    }

    /**
     * Picks the newest releasable update from a releases listing, or null when
     * nothing newer exists. Pure: no network, no clock, fully unit-tested
     * against the real API shape.
     */
    fun selectUpdate(
        releases: JSONArray,
        currentCode: Int?,
        currentName: String,
        wantPreRelease: Boolean
    ): UpdateInfo? {
        for (i in 0 until releases.length()) {
            val release = releases.optJSONObject(i) ?: continue
            if (release.optBoolean("draft", false)) continue
            if (release.optBoolean("prerelease", false) && !wantPreRelease) continue
            val tag = release.optString("tag_name").takeIf { it.isNotBlank() } ?: continue
            if (!isNewer(parseTagVersion(tag), currentCode, currentName)) continue
            return UpdateInfo(
                tagName = tag,
                downloadUrl = pickApkUrl(release),
                sha256Hex = pickApkDigest(release),
                sizeBytes = pickApkSize(release),
                notes = release.optString("body").takeIf { it.isNotBlank() }?.take(NOTES_MAX_CHARS)
            )
        }
        return null
    }

    /**
     * Whether the installed app already satisfies a pending update tag:
     * version code at or past the tag's code, or equal names when the tag
     * carries no code. Used once after the installer returns.
     */
    fun isTagInstalled(tagName: String, currentCode: Int, currentName: String): Boolean {
        val tag = parseTagVersion(tagName)
        if (tag.code != null) return currentCode >= tag.code
        return tag.name == currentName
    }

    private fun isNewer(tag: TagVersion, currentCode: Int?, currentName: String): Boolean {
        if (tag.code != null && currentCode != null) return tag.code > currentCode
        return isNameNewer(tag.name, currentName)
    }

    private fun pickApkUrl(release: JSONObject): String? {
        val assets = release.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            if (asset.optString("name").endsWith(".apk")) {
                return asset.optString("browser_download_url").takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    private fun pickApkDigest(release: JSONObject): String? {
        val assets = release.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            if (!asset.optString("name").endsWith(".apk")) continue
            // API shape is "sha256:<hex>"; anything else is not a checksum we verify.
            return asset.optString("digest").removePrefix("sha256:").takeIf {
                it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' }
            }
        }
        return null
    }

    private fun pickApkSize(release: JSONObject): Long? {
        val assets = release.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            if (!asset.optString("name").endsWith(".apk")) continue
            return asset.optLong("size", -1L).takeIf { it > 0 }
        }
        return null
    }

    suspend fun checkForUpdate(isPreRelease: Boolean, versionCode: Int, versionName: String): CheckResult {
        return try {
            val releases = fetchReleases()
            if (releases.length() == 0) return CheckResult.NoReleases
            val info = selectUpdate(releases, versionCode, versionName, isPreRelease)
            if (info != null) CheckResult.Available(info) else CheckResult.UpToDate
        } catch (e: Exception) {
            CheckResult.Failed(e.message ?: "?")
        }
    }

    /**
     * Downloads the APK to external files dir, reporting 0..1 progress.
     * Returns the file, or null when no URL was available. Throws on I/O
     * failure, insufficient storage, or cancellation (callers surface those).
     */
    suspend fun downloadUpdate(
        context: Context,
        url: String?,
        onProgress: (Float) -> Unit
    ): File? {
        if (url == null) return null
        return withContext(Dispatchers.IO) {
            val destination = File(context.getExternalFilesDir(null), "update.apk")
            val u = URL(url)
            val conn = (u.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
            }
            try {
                conn.connect()
                val fileLength = conn.contentLength.takeIf { it > 0 }?.toLong()
                if (fileLength != null) {
                    val free = destination.parentFile?.usableSpace ?: Long.MAX_VALUE
                    if (free < fileLength + DOWNLOAD_HEADROOM_BYTES) {
                        throw java.io.IOException("insufficient storage")
                    }
                }
                u.openStream().use { input ->
                    FileOutputStream(destination).use { output ->
                        val data = ByteArray(8192)
                        var total = 0L
                        var count: Int
                        while (input.read(data).also { count = it } != -1) {
                            ensureActive()
                            total += count
                            if (fileLength != null && fileLength > 0) {
                                onProgress(total.toFloat() / fileLength)
                            }
                            output.write(data, 0, count)
                        }
                    }
                }
                destination
            } finally {
                conn.disconnect()
            }
        }
    }

    /** SHA-256 hex of a file, or null when it cannot be read. */
    fun sha256HexOf(file: File): String? = try {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val data = ByteArray(8192)
            var count: Int
            while (input.read(data).also { count = it } != -1) {
                digest.update(data, 0, count)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) {
        null
    }

    /**
     * Whether [file] is safe to hand to the package installer: same package
     * id as this app plus a signing certificate identical to the installed
     * one. Anything unreadable or mismatched refuses — never installs blind.
     */
    fun isCompatibleApk(context: Context, file: File): Boolean {
        return try {
            val pm = context.packageManager
            val archive = pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNATURES)
                ?: return false
            if (archive.packageName != context.packageName) return false
            @Suppress("DEPRECATION")
            val archiveSigs = archive.signatures?.map { it.toByteArray() }?.toSet() ?: return false
            @Suppress("DEPRECATION")
            val installedSigs = pm.getPackageInfo(
                context.packageName, PackageManager.GET_SIGNATURES
            ).signatures?.map { it.toByteArray() }?.toSet() ?: return false
            archiveSigs.isNotEmpty() && archiveSigs == installedSigs
        } catch (_: Exception) {
            false
        }
    }

    fun installUpdate(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private suspend fun fetchReleases(): JSONArray = withContext(Dispatchers.IO) {
        val url = URL(API_URL)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 15000
        }
        try {
            conn.setRequestProperty("User-Agent", "Catsmoker-App")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            val code = conn.responseCode
            if (code == 403 || code == 429) {
                throw java.io.IOException("rate limited (HTTP $code)")
            }
            if (code !in 200..299) throw java.io.IOException("HTTP $code")
            val response = conn.inputStream.bufferedReader().use { it.readText() }
            JSONArray(response)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Semver-name fallback for legacy tags without a numeric code prefix:
     * strictly newer names only, so equal versions (and anything unparseable)
     * never offer a downgrade-shaped "update".
     */
    private fun isNameNewer(githubVersion: String, currentVersion: String): Boolean {
        try {
            val v1 = githubVersion.trimStart('v').split(".").map { it.toInt() }
            val v2 = currentVersion.trimStart('v').split(".").map { it.toInt() }
            for (i in 0 until max(v1.size, v2.size)) {
                val n1 = v1.getOrElse(i) { 0 }
                val n2 = v2.getOrElse(i) { 0 }
                if (n1 > n2) return true
                if (n1 < n2) return false
            }
        } catch (_: Exception) {}
        return false
    }

    /** Headroom above the APK size demanded before downloading. */
    private const val DOWNLOAD_HEADROOM_BYTES = 20L * 1024 * 1024

    /** Release notes kept for the dialog; bodies run to kilobytes. */
    private const val NOTES_MAX_CHARS = 2000
}
