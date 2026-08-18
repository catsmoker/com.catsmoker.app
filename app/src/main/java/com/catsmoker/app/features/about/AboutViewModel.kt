package com.catsmoker.app.features.about

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.catsmoker.app.BuildConfig
import com.catsmoker.app.system.ads.AdManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max
import javax.inject.Inject

@HiltViewModel
class AboutViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val adManager: AdManager,
) : ViewModel() {

    data class UiState(
        val adsEnabled: Boolean = true,
        val autoCheck: Boolean = false,
        val isPreRelease: Boolean = false,
        val isUpdating: Boolean = false,
        val updateProgress: Float = 0f,
        val updateDialog: UpdateDialog? = null,
    )

    data class UpdateDialog(val tagName: String, val downloadUrl: String?)

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toasts: SharedFlow<String> = _toasts.asSharedFlow()

    private val prefs by lazy { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }

    init {
        _uiState.update {
            it.copy(
                adsEnabled = adManager.isEnabled(),
                autoCheck = prefs.getBoolean("auto_check_update", false),
                isPreRelease = prefs.getBoolean("use_prerelease", false)
            )
        }
    }

    fun onAdsToggled(enabled: Boolean) {
        adManager.setEnabled(enabled)
        _uiState.update { it.copy(adsEnabled = enabled) }
    }

    fun onAutoCheckToggled(enabled: Boolean) {
        prefs.edit { putBoolean("auto_check_update", enabled) }
        _uiState.update { it.copy(autoCheck = enabled) }
    }

    fun onBuildTypeChanged(isPreRelease: Boolean) {
        prefs.edit { putBoolean("use_prerelease", isPreRelease) }
        _uiState.update { it.copy(isPreRelease = isPreRelease) }
    }

    fun onCheckUpdates() {
        _toasts.tryEmit("Checking for updates...")
        performUpdateCheck(_uiState.value.isPreRelease)
    }

    fun dismissUpdateDialog() {
        _uiState.update { it.copy(updateDialog = null) }
    }

    fun startUpdateDownload() {
        val dialog = _uiState.value.updateDialog ?: return
        val url = dialog.downloadUrl
        if (url == null) {
            dismissUpdateDialog()
            return
        }
        dismissUpdateDialog()
        startUpdateDownload(url)
    }

    private fun performUpdateCheck(isPreRelease: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val releases = fetchReleases()
                if (releases.length() == 0) {
                    withContext(Dispatchers.Main) {
                        _toasts.tryEmit("No releases found on GitHub")
                    }
                    return@launch
                }
                
                val latestRelease = findLatestRelease(releases, isPreRelease)
                withContext(Dispatchers.Main) {
                    if (latestRelease != null) {
                        if (!processReleaseData(latestRelease)) {
                            _toasts.tryEmit("App is up to date (v${BuildConfig.VERSION_NAME})")
                        }
                    } else {
                        _toasts.tryEmit("No suitable release found")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _toasts.tryEmit("Update check failed: ${e.message}")
                }
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
            conn.setRequestProperty("User-Agent", "Catsmoker-App")
            val response = conn.inputStream.bufferedReader().use { it.readText() }
            JSONArray(response)
        } finally {
            conn.disconnect()
        }
    }

    private fun processReleaseData(latestRelease: JSONObject): Boolean {
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
                _uiState.update { it.copy(updateDialog = UpdateDialog(tagName, downloadUrl)) }
                return true
            }
        } catch (_: Exception) {}
        return false
    }

    private fun startUpdateDownload(url: String) {
        _uiState.update { it.copy(isUpdating = true, updateProgress = 0f) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val destination = File(context.getExternalFilesDir(null), "update.apk")
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
                            if (fileLength > 0) {
                                _uiState.update { it.copy(updateProgress = total.toFloat() / fileLength) }
                            }
                            output.write(data, 0, count)
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(isUpdating = false) }
                    installApk(destination)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(isUpdating = false) }
                    _toasts.tryEmit("Download failed: ${e.message}")
                }
            }
        }
    }

    private fun installApk(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
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
