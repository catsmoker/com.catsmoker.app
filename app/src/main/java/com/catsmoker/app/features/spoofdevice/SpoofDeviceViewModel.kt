package com.catsmoker.app.features.spoofdevice

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import androidx.core.content.edit
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.catsmoker.app.shared.data.model.DevicePreset
import com.catsmoker.app.shared.data.model.DeviceProfile
import com.catsmoker.app.shared.data.model.LSPosedConfig
import com.catsmoker.app.shared.data.repository.SpoofRepository
import com.catsmoker.app.shared.util.RandomGenerator
import com.catsmoker.app.system.shell.ShellRunner
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
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject

@HiltViewModel
class SpoofDeviceViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shellRunner: ShellRunner,
    val repository: SpoofRepository
) : ViewModel() {

    data class AppEntry(
        val label: String,
        val packageName: String,
        val icon: Bitmap,
        val systemApp: Boolean,
        val assignedProfileName: String? = null
    )

    data class UiState(
        val isRooted: Boolean = false,
        val isRefreshing: Boolean = false,
        val profiles: List<SpoofRepository.ProfileEntry> = emptyList(),
        val assignments: Map<String, String> = emptyMap(),
        val apps: List<AppEntry> = emptyList(),
        val isLoadingApps: Boolean = false,
        val safeModePackages: Set<String> = emptySet(),
        val applyScreenMetrics: Boolean = false
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toasts: SharedFlow<String> = _toasts.asSharedFlow()

    private val deviceContext by lazy { context.createDeviceProtectedStorageContext() }
    private val userPrefs by lazy { openLsposedPrefs(context) }
    private val devicePrefs by lazy { openLsposedPrefs(deviceContext) }

    init {
        refreshStatus()
        loadInitialData()
    }

    private fun loadInitialData() {
        viewModelScope.launch {
            val data = repository.loadData()
            _uiState.update { state ->
                state.copy(
                    profiles = data.profiles,
                    assignments = data.assignments,
                    safeModePackages = data.globalProperties["safe_mode.packages"]?.split(",")?.toSet() ?: emptySet(),
                    applyScreenMetrics = data.globalProperties["device.apply_screen_metrics"] == "true"
                )
            }
        }
    }

    fun refreshStatus() {
        _uiState.update { it.copy(isRefreshing = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val rooted = shellRunner.isRootAvailable()
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(isRooted = rooted, isRefreshing = false) }
                _toasts.tryEmit("Status Refreshed")
            }
        }
    }

    // --- Profile Management ---

    fun createProfile(name: String, preset: DevicePreset? = null) {
        viewModelScope.launch {
            val data = repository.loadData()
            val newProfile = preset?.profile?.copy() ?: DeviceProfile().apply { applyFallbacks() }
            val entry = SpoofRepository.ProfileEntry(UUID.randomUUID().toString(), name, newProfile)
            data.profiles.add(entry)
            repository.save()
            _uiState.update { it.copy(profiles = data.profiles.toList()) }
            _toasts.tryEmit("Profile '$name' created")
        }
    }

    fun updateProfile(profileId: String, name: String, profile: DeviceProfile) {
        viewModelScope.launch {
            val data = repository.loadData()
            val index = data.profiles.indexOfFirst { it.id == profileId }
            if (index != -1) {
                data.profiles[index].name = name
                data.profiles[index].profile.apply {
                    // Update fields manually or just replace the object
                    // For simplicity, we replace the object if we trust the UI state
                }
                // Actually me just replace the whole entry if me want
                data.profiles[index] = SpoofRepository.ProfileEntry(profileId, name, profile)
                repository.save()
                syncLsposedIfRooted()
                _uiState.update { it.copy(profiles = data.profiles.toList()) }
                _toasts.tryEmit("Profile updated")
            }
        }
    }

    fun deleteProfile(profileId: String) {
        viewModelScope.launch {
            val data = repository.loadData()
            if (data.profiles.size <= 1) {
                _toasts.tryEmit("Cannot delete the last profile")
                return@launch
            }
            if (data.profiles.firstOrNull()?.id == profileId) {
                _toasts.tryEmit("Cannot delete the default profile")
                return@launch
            }
            data.profiles.removeAll { it.id == profileId }
            data.assignments.entries.removeAll { it.value == profileId }
            repository.save()
            syncLsposedIfRooted()
            _uiState.update { it.copy(profiles = data.profiles.toList(), assignments = data.assignments.toMap()) }
            _toasts.tryEmit("Profile deleted")
        }
    }

    // --- App Assignments ---

    fun loadApps() {
        if (_uiState.value.apps.isNotEmpty()) return
        _uiState.update { it.copy(isLoadingApps = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val pm = context.packageManager
            val packages = pm.getInstalledPackages(0)
            val apps = packages.mapNotNull { pkg ->
                val appInfo = pkg.applicationInfo ?: return@mapNotNull null
                if (pkg.packageName == context.packageName || pkg.packageName == "android") return@mapNotNull null
                
                AppEntry(
                    label = appInfo.loadLabel(pm).toString(),
                    packageName = pkg.packageName,
                    icon = appInfo.loadIcon(pm).toBitmap(96, 96),
                    systemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    assignedProfileName = _uiState.value.assignments[pkg.packageName]?.let { id ->
                        _uiState.value.profiles.find { it.id == id }?.name
                    }
                )
            }.sortedBy { it.label }
            
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(apps = apps, isLoadingApps = false) }
            }
        }
    }

    fun assignProfile(packageName: String, profileId: String?) {
        viewModelScope.launch {
            val data = repository.loadData()
            if (profileId == null) {
                data.assignments.remove(packageName)
            } else {
                data.assignments[packageName] = profileId
            }
            repository.save()
            syncLsposedIfRooted()
            _uiState.update { state ->
                state.copy(
                    assignments = data.assignments.toMap(),
                    apps = state.apps.map { 
                        if (it.packageName == packageName) {
                            it.copy(assignedProfileName = profileId?.let { id -> data.profiles.find { p -> p.id == id }?.name })
                        } else it
                    }
                )
            }
        }
    }

    // --- Global Config ---

    fun toggleScreenMetrics(enabled: Boolean) {
        viewModelScope.launch {
            val data = repository.loadData()
            data.globalProperties["device.apply_screen_metrics"] = enabled.toString()
            repository.save()
            _uiState.update { it.copy(applyScreenMetrics = enabled) }
        }
    }

    fun toggleSafeMode(packageName: String, enabled: Boolean) {
        viewModelScope.launch {
            val data = repository.loadData()
            val current = data.globalProperties["safe_mode.packages"]?.split(",")?.toMutableSet() ?: mutableSetOf()
            if (enabled) current.add(packageName) else current.remove(packageName)
            data.globalProperties["safe_mode.packages"] = current.joinToString(",")
            repository.save()
            _uiState.update { it.copy(safeModePackages = current) }
        }
    }

    // --- Legacy / Root Support ---

    fun installBundledMagiskZip() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val data = repository.loadData()
                val profile = data.profiles.firstOrNull()?.profile ?: return@launch
                val rendered = repository.renderConfig(profile, data.globalProperties)
                saveBundledZipToDownloads(rendered)
                _toasts.emit("Magisk ZIP saved to Downloads")
            } catch (e: Exception) {
                _toasts.emit("Failed to save ZIP")
            }
        }
    }

    private fun saveBundledZipToDownloads(modulePropContent: String) {
        val name = "catsmoker_spoof_magisk.zip"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: return
            context.contentResolver.openOutputStream(uri).use { out -> out?.let { writeZip(it, modulePropContent) } }
        } else {
            val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), name)
            FileOutputStream(file).use { out -> writeZip(out, modulePropContent) }
        }
    }

    private fun writeZip(out: OutputStream, props: String) {
        ZipOutputStream(out).use { zip -> addDirToZip("magisk", "", zip, props) }
    }

    private fun addDirToZip(path: String, prefix: String, zip: ZipOutputStream, props: String) {
        val children = context.assets.list(path) ?: return
        for (child in children) {
            val assetPath = "$path/$child"
            val zipPath = if (prefix.isEmpty()) child else "$prefix/$child"
            val grandchildren = context.assets.list(assetPath) ?: emptyArray()
            if (grandchildren.isEmpty()) {
                zip.putNextEntry(ZipEntry(zipPath))
                if (zipPath == "system.prop") zip.write(props.toByteArray())
                else context.assets.open(assetPath).use { it.copyTo(zip) }
                zip.closeEntry()
            } else {
                addDirToZip(assetPath, zipPath, zip, props)
            }
        }
    }

    fun launchRootManager() {
        val pm = context.packageManager
        val pkgs = arrayOf("com.topjohnwu.magisk", "me.weishu.kernelsu", "me.bmax.apatch")
        val intent = pkgs.firstNotNullOfOrNull { pm.getLaunchIntentForPackage(it) }
        if (intent != null) {
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } else {
            _toasts.tryEmit("No Root Manager found")
        }
    }

    private fun syncLsposedIfRooted() {
        if (!_uiState.value.isRooted) return
        viewModelScope.launch(Dispatchers.IO) {
            val data = repository.loadData()
            // We use the first profile as the "Global" fallback for root for now
            // In a better version, we'd let user pick which one is global
            val globalProfile = data.profiles.firstOrNull()?.profile ?: return@launch
            val rendered = repository.renderConfig(globalProfile, data.globalProperties)
            
            val pB64 = Base64.encodeToString(rendered.toByteArray(), Base64.NO_WRAP)
            shellRunner.execSafe("settings", "put", "global", LSPosedConfig.KEY_GLOBAL_DEVICE_PROPS_B64, pB64)
            
            // Also write to prefs for world-readable access if possible
            writeStringToBoth(LSPosedConfig.KEY_DEVICE_PROPS, rendered)
        }
    }

    private fun readStringPref(k: String, d: String): String =
        devicePrefs.getString(k, userPrefs.getString(k, null)) ?: d

    private fun writeStringToBoth(k: String, v: String) {
        userPrefs.edit(commit = true) { putString(k, v) }
        devicePrefs.edit(commit = true) { putString(k, v) }
    }

    @Suppress("DEPRECATION")
    private fun openLsposedPrefs(context: Context): SharedPreferences =
        try {
            context.getSharedPreferences(LSPosedConfig.PREFS_NAME, Context.MODE_WORLD_READABLE)
        } catch (_: SecurityException) {
            context.getSharedPreferences(LSPosedConfig.PREFS_NAME, Context.MODE_PRIVATE)
        }
}
