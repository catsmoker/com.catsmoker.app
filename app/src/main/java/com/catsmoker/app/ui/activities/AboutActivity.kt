package com.catsmoker.app.ui.activities

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.catsmoker.app.BuildConfig
import com.catsmoker.app.R
import com.catsmoker.app.ui.screens.AboutScreen
import com.catsmoker.app.ui.theme.CatsmokerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max

class AboutActivity : ComponentActivity() {

    private val prefs by lazy { getSharedPreferences("app_prefs", MODE_PRIVATE) }
    private var isUpdating by mutableStateOf(false)
    private var updateProgress by mutableStateOf(0f)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            CatsmokerTheme {
                AboutScreen(
                    adsEnabled = prefs.getBoolean("ads_enabled", true),
                    autoCheck = prefs.getBoolean("auto_check_update", false),
                    isPreRelease = prefs.getBoolean("use_prerelease", false),
                    isUpdating = isUpdating,
                    updateProgress = updateProgress,
                    onAdsToggled = { prefs.edit { putBoolean("ads_enabled", it) } },
                    onAutoCheckToggled = { prefs.edit { putBoolean("auto_check_update", it) } },
                    onBuildTypeChanged = { isPre -> prefs.edit { putBoolean("use_prerelease", isPre) } },
                    onOpenPermissions = { startActivity(Intent(this, PermissionActivity::class.java)) },
                    onBack = { finish() },
                    onCheckUpdates = { performUpdateCheck(prefs.getBoolean("use_prerelease", false)) },
                    onOpenUrl = { openUrl(it) }
                )
            }
        }
        
        if (prefs.getBoolean("auto_check_update", false)) {
            performUpdateCheck(prefs.getBoolean("use_prerelease", false))
        }
    }

    private fun openUrl(url: String) {
        try {
            val browserIntent = Intent(Intent.ACTION_VIEW, url.toUri())
            startActivity(browserIntent)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.could_not_open_browser, Toast.LENGTH_SHORT).show()
        }
    }

    private fun performUpdateCheck(isPreRelease: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val releases = fetchReleases()
                val latestRelease = findLatestRelease(releases, isPreRelease)
                if (latestRelease != null) {
                    withContext(Dispatchers.Main) { processReleaseData(latestRelease) }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(this@AboutActivity, "Update check failed", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun findLatestRelease(releases: JSONArray, isPreRelease: Boolean): JSONObject? {
        if (releases.length() <= 0) return null
        for (i in 0 until releases.length()) {
            val release = releases.getJSONObject(i)
            if (isPreRelease || !release.getBoolean("prerelease")) return release
        }
        return releases.getJSONObject(0)
    }

    private fun fetchReleases(): JSONArray {
        val url = URL("https://api.github.com/repos/catsmoker/com.catsmoker.app/releases")
        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 5000
            val response = conn.inputStream.bufferedReader().use { it.readText() }
            JSONArray(response)
        } finally {
            conn.disconnect()
        }
    }

    private fun processReleaseData(latestRelease: JSONObject) {
        try {
            val tagName = latestRelease.getString("tag_name")
            val githubVersion = tagName.removePrefix("v")
            if (isUpdateAvailable(githubVersion)) {
                val assets = latestRelease.getJSONArray("assets")
                var downloadUrl: String? = null
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    if (asset.getString("name").endsWith(".apk")) {
                        downloadUrl = asset.getString("browser_download_url")
                        break
                    }
                }
                showUpdateConfirmDialog(tagName, downloadUrl)
            }
        } catch (_: Exception) {}
    }

    private fun showUpdateConfirmDialog(tagName: String, downloadUrl: String?) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Update Available: $tagName")
            .setMessage("A new version is available. Would you like to download and install it now?")
            .setPositiveButton("DOWNLOAD") { _, _ ->
                if (downloadUrl != null) startUpdateDownload(downloadUrl)
                else openUrl("https://github.com/catsmoker/com.catsmoker.app/releases")
            }
            .setNegativeButton("LATER", null)
            .show()
    }

    private fun startUpdateDownload(url: String) {
        isUpdating = true
        updateProgress = 0f
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val destination = File(getExternalFilesDir(null), "update.apk")
                val u = URL(url)
                val conn = u.openConnection() as HttpURLConnection
                conn.connect()
                val fileLength = conn.contentLength
                u.openStream().use { input ->
                    FileOutputStream(destination).use { output ->
                        val data = ByteArray(4096)
                        var total = 0L
                        var count: Int
                        while (input.read(data).also { count = it } != -1) {
                            total += count
                            if (fileLength > 0) updateProgress = total.toFloat() / fileLength
                            output.write(data, 0, count)
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    isUpdating = false
                    installApk(destination)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isUpdating = false
                    Toast.makeText(this@AboutActivity, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun installApk(file: File) {
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun isUpdateAvailable(githubVersion: String): Boolean {
        val currentVersion = BuildConfig.VERSION_NAME
        try {
            val v1 = githubVersion.split(".").map { it.toInt() }
            val v2 = currentVersion.split(".").map { it.toInt() }
            for (i in 0 until max(v1.size, v2.size)) {
                val n1 = v1.getOrElse(i) { 0 }
                val n2 = v2.getOrElse(i) { 0 }
                if (n1 > n2) return true
                if (n1 < n2) return false
            }
        } catch (_: Exception) {}
        return false
    }
}
