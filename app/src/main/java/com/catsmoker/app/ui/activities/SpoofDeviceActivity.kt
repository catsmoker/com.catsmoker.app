package com.catsmoker.app.ui.activities

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.TextUtils
import android.util.Base64
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.catsmoker.app.R
import com.catsmoker.app.data.model.LSPosedConfig
import com.catsmoker.app.ui.screens.SpoofDeviceScreen
import com.catsmoker.app.ui.theme.CatsmokerTheme
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SpoofDeviceActivity : ComponentActivity() {

    private val deviceContext by lazy { createDeviceProtectedStorageContext() }
    private val userPrefs by lazy { openLsposedPrefs(this) }
    private val devicePrefs by lazy { openLsposedPrefs(deviceContext) }

    private var isRootedState by mutableStateOf(false)
    private var isModuleActiveState by mutableStateOf(false)
    private var isRefreshing by mutableStateOf(false)

    private var lsposedEnabledState by mutableStateOf(false)
    private var targetPackagesState by mutableStateOf("")
    private var devicePropsState by mutableStateOf("")
    private var magiskPropsState by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        ensurePrefsReadable()
        loadMagiskSystemProp()
        bindLsposedConfig()
        refreshStatus()

        setContent {
            CatsmokerTheme {
                SpoofDeviceScreen(
                    isRooted = isRootedState,
                    isModuleActive = isModuleActiveState,
                    isRefreshing = isRefreshing,
                    lsposedEnabled = lsposedEnabledState,
                    targetPackages = targetPackagesState,
                    deviceProps = devicePropsState,
                    magiskProps = magiskPropsState,
                    onRefresh = { refreshStatus() },
                    onToggleLsposed = { toggleLsposed(it) },
                    onTargetPackagesChange = { targetPackagesState = it },
                    onDevicePropsChange = { devicePropsState = it },
                    onMagiskPropsChange = { magiskPropsState = it },
                    onSaveLsposed = { saveLsposedConfig() },
                    onRestartApps = { restartTargetApps() },
                    onGenerateMagiskZip = { installBundledMagiskZip() },
                    onOpenRootManager = { launchRootManager() },
                    onBack = { finish() }
                )
            }
        }
    }

    private fun refreshStatus() {
        isRefreshing = true
        lifecycleScope.launch(Dispatchers.IO) {
            val rooted = try { Shell.getShell().isRoot } catch (_: Exception) { false }
            val active = isModuleActive
            withContext(Dispatchers.Main) {
                isRootedState = rooted
                isModuleActiveState = active
                isRefreshing = false
                showSnackbar("Status Refreshed")
            }
        }
    }

    private fun bindLsposedConfig() {
        lsposedEnabledState = readLsposedEnabledPref()
        targetPackagesState = readStringPref(LSPosedConfig.KEY_TARGET_PACKAGES, LSPosedConfig.DEFAULT_TARGET_PACKAGES.joinToString("\n"))
        devicePropsState = readStringPref(LSPosedConfig.KEY_DEVICE_PROPS, LSPosedConfig.DEFAULT_DEVICE_PROPS.entries.joinToString("\n") { "${it.key}=${it.value}" })
    }

    private fun toggleLsposed(enabled: Boolean) {
        lsposedEnabledState = enabled
        writeBooleanToBoth(LSPosedConfig.KEY_ENABLED, enabled)
        syncGlobalLsposedConfig(enabled, targetPackagesState, devicePropsState)
        ensurePrefsReadable()
    }

    private fun saveLsposedConfig() {
        val targets = LSPosedConfig.parseTargetPackages(targetPackagesState)
        val props = LSPosedConfig.parseDeviceProps(devicePropsState)
        if (targets.isEmpty() || props.isEmpty()) { showSnackbar("Invalid configuration"); return }
        
        val normTargets = TextUtils.join("\n", targets)
        val normProps = props.entries.joinToString("\n") { "${it.key}=${it.value}" }
        
        writeStringToBoth(LSPosedConfig.KEY_TARGET_PACKAGES, normTargets)
        writeStringToBoth(LSPosedConfig.KEY_DEVICE_PROPS, normProps)
        syncGlobalLsposedConfig(lsposedEnabledState, normTargets, normProps)
        ensurePrefsReadable()
        
        targetPackagesState = normTargets
        devicePropsState = normProps
        showSnackbar("LSPosed Configuration Saved")
    }

    private fun restartTargetApps() {
        val targets = LSPosedConfig.parseTargetPackages(targetPackagesState)
        if (targets.isEmpty()) return
        lifecycleScope.launch(Dispatchers.IO) {
            if (!Shell.getShell().isRoot) { withContext(Dispatchers.Main) { showSnackbar("Restarting apps requires Root") }; return@launch }
            for (pkg in targets) { Shell.cmd("am force-stop $pkg").exec() }
            withContext(Dispatchers.Main) { showSnackbar("Target apps restarted") }
        }
    }

    private fun loadMagiskSystemProp() {
        magiskPropsState = runCatching { assets.open("magisk/system.prop").bufferedReader().use { it.readText() } }.getOrDefault("")
    }

    private fun installBundledMagiskZip() {
        try {
            saveBundledZipToDownloads(magiskPropsState)
            showSnackbar("Magisk ZIP saved to Downloads")
        } catch (_: Exception) {
            showSnackbar("Error generating ZIP")
        }
    }

    private fun saveBundledZipToDownloads(modulePropContent: String) {
        val name = "magisk.zip"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cv = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, "application/zip"); put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS) }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: return
            contentResolver.openOutputStream(uri).use { out -> if (out != null) writeZip(out, modulePropContent) }
        } else {
            val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), name)
            FileOutputStream(file).use { out -> writeZip(out, modulePropContent) }
        }
    }

    private fun writeZip(out: OutputStream, props: String) {
        ZipOutputStream(out).use { zip -> addDirToZip("magisk", "", zip, props) }
    }

    private fun addDirToZip(path: String, prefix: String, zip: ZipOutputStream, props: String) {
        val children = assets.list(path) ?: return
        for (child in children) {
            val assetPath = "$path/$child"
            val zipPath = if (prefix.isEmpty()) child else "$prefix/$child"
            val grandchildren = assets.list(assetPath) ?: emptyArray()
            if (grandchildren.isEmpty()) {
                zip.putNextEntry(ZipEntry(zipPath))
                if (zipPath == "system.prop") zip.write(props.toByteArray())
                else assets.open(assetPath).use { it.copyTo(zip) }
                zip.closeEntry()
            } else addDirToZip(assetPath, zipPath, zip, props)
        }
    }

    private fun launchRootManager() {
        val pm = packageManager
        val pkgs = arrayOf("com.topjohnwu.magisk", "me.weishu.kernelsu", "me.bmax.apatch")
        val intent = pkgs.firstNotNullOfOrNull { pm.getLaunchIntentForPackage(it) }
        if (intent != null) startActivity(intent) else showSnackbar("Root manager not found")
    }

    private fun showSnackbar(msg: String) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    private fun readLsposedEnabledPref(): Boolean = devicePrefs.getBoolean(LSPosedConfig.KEY_ENABLED, userPrefs.getBoolean(LSPosedConfig.KEY_ENABLED, true))
    private fun readStringPref(k: String, d: String): String = devicePrefs.getString(k, userPrefs.getString(k, null)) ?: d
    private fun writeBooleanToBoth(k: String, v: Boolean) { userPrefs.edit(true) { putBoolean(k, v) }; devicePrefs.edit(true) { putBoolean(k, v) } }
    private fun writeStringToBoth(k: String, v: String) { userPrefs.edit(true) { putString(k, v) }; devicePrefs.edit(true) { putString(k, v) } }
    private fun ensurePrefsReadable() { userPrefs.all; devicePrefs.all }

    private fun syncGlobalLsposedConfig(enabled: Boolean, targets: String, props: String) {
        if (!Shell.getShell().isRoot) return
        val tB64 = Base64.encodeToString(targets.toByteArray(), Base64.NO_WRAP)
        val pB64 = Base64.encodeToString(props.toByteArray(), Base64.NO_WRAP)
        val cmds = arrayOf("settings put global ${LSPosedConfig.KEY_GLOBAL_ENABLED} ${if (enabled) 1 else 0}", "settings put global ${LSPosedConfig.KEY_GLOBAL_TARGET_PACKAGES_B64} '$tB64'", "settings put global ${LSPosedConfig.KEY_GLOBAL_DEVICE_PROPS_B64} '$pB64'")
        Shell.cmd(*cmds).submit()
    }

    @Suppress("DEPRECATION")
    private fun openLsposedPrefs(context: Context): SharedPreferences = try { context.getSharedPreferences(LSPosedConfig.PREFS_NAME, MODE_WORLD_READABLE) } catch (_: SecurityException) { context.getSharedPreferences(LSPosedConfig.PREFS_NAME, MODE_PRIVATE) }

    companion object {
        var isModuleActive: Boolean = false
    }
}
