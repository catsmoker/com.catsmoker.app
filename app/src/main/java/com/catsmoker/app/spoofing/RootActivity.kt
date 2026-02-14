package com.catsmoker.app.spoofing

import android.content.Context
import android.content.ContentValues
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.TextUtils
import android.util.Base64
import android.view.MotionEvent
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.catsmoker.app.R
import com.catsmoker.app.databinding.ActivityRootScreenBinding
import com.catsmoker.app.main.setupScreenHeader
import com.google.android.material.snackbar.Snackbar
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RootActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRootScreenBinding
    private val deviceContext by lazy { createDeviceProtectedStorageContext() }
    private val userPrefs by lazy { openLsposedPrefs(this) }
    private val devicePrefs by lazy { openLsposedPrefs(deviceContext) }

    private enum class LsposedStatus {
        NOT_ACTIVE,  // Module is not active
        ACTIVE       // Module is active
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRootScreenBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ensurePrefsReadable()
        setupScreenHeader(R.string.root_amp_lsposed, R.string.manage_system_access)
        setupEditorScrolling()
        loadMagiskSystemProp()
        setupListeners()
        bindLsposedConfig()
        refreshStatus()
    }

    private fun setupEditorScrolling() {
        val editors = listOf(binding.etTargetPackages, binding.etDeviceProps, binding.etMagiskModuleProp)
        editors.forEach { editor ->
            editor.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                }
                false
            }
        }
    }

    private fun setupListeners() {
        listOf(
            binding.btnRefresh to ::refreshStatus,
            binding.btnInstallLsposed to ::openUrl,
            binding.btnOpenManager to ::launchRootManager,
            binding.btnInstallMagiskZip to ::installBundledMagiskZip,
            binding.btnSaveLsposedConfig to ::saveLsposedConfig,
            binding.btnRestartTargetApps to ::restartTargetApps
        ).forEach { (button, action) -> button.setOnClickListener { action() } }
    }

    private fun loadMagiskSystemProp() {
        val moduleProp = runCatching {
            assets.open("$MAGISK_MODULE_ASSET_DIR/$MAGISK_PROP_FILE")
                .bufferedReader()
                .use { it.readText() }
        }.getOrDefault("")
        binding.etMagiskModuleProp.setText(moduleProp)
    }

    private fun installBundledMagiskZip() {
        val modulePropText = binding.etMagiskModuleProp.text?.toString().orEmpty()
        try {
            saveBundledZipToDownloads(modulePropText)
            showSnackbar(getString(R.string.magisk_manual_install_hint))
        } catch (_: Exception) {
            showSnackbar(getString(R.string.magisk_zip_asset_missing))
        }
    }

    private fun saveBundledZipToDownloads(modulePropContent: String) {
        val moduleChildren = assets.list(MAGISK_MODULE_ASSET_DIR)
        if (moduleChildren.isNullOrEmpty()) {
            throw IllegalStateException("Missing module assets directory")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, MAGISK_ZIP_ASSET_NAME)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                ?: throw IllegalStateException("MediaStore insert failed")

            contentResolver.openOutputStream(uri).use { output ->
                if (output == null) throw IllegalStateException("Output stream unavailable")
                writeModuleZipToStream(output, modulePropContent)
            }
            return
        }

        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloadsDir.exists()) downloadsDir.mkdirs()
        val outFile = File(downloadsDir, MAGISK_ZIP_ASSET_NAME)
        FileOutputStream(outFile).use { output ->
            writeModuleZipToStream(output, modulePropContent)
        }
    }

    private fun writeModuleZipToStream(output: OutputStream, modulePropContent: String) {
        ZipOutputStream(output).use { zipOut ->
            addAssetDirToZip(
                assetDirPath = MAGISK_MODULE_ASSET_DIR,
                zipPrefix = "",
                zipOut = zipOut,
                modulePropContent = modulePropContent
            )
        }
    }

    private fun addAssetDirToZip(
        assetDirPath: String,
        zipPrefix: String,
        zipOut: ZipOutputStream,
        modulePropContent: String
    ) {
        val children = assets.list(assetDirPath) ?: emptyArray()
        for (child in children) {
            val childAssetPath = "$assetDirPath/$child"
            val childZipPath = if (zipPrefix.isEmpty()) child else "$zipPrefix/$child"
            val grandChildren = assets.list(childAssetPath) ?: emptyArray()
            if (grandChildren.isEmpty()) {
                zipOut.putNextEntry(ZipEntry(childZipPath))
                if (childZipPath == MAGISK_PROP_FILE) {
                    zipOut.write(modulePropContent.toByteArray(Charsets.UTF_8))
                } else {
                    assets.open(childAssetPath).use { input ->
                        input.copyTo(zipOut)
                    }
                }
                zipOut.closeEntry()
            } else {
                addAssetDirToZip(childAssetPath, childZipPath, zipOut, modulePropContent)
            }
        }
    }

    private fun bindLsposedConfig() {
        val defaultTargets = LSPosedConfig.DEFAULT_TARGET_PACKAGES.joinToString("\n")
        val defaultProps = LSPosedConfig.DEFAULT_DEVICE_PROPS.entries.joinToString("\n") {
            "${it.key}=${it.value}"
        }

        binding.switchLsposedEnabled.setOnCheckedChangeListener(null)
        binding.switchLsposedEnabled.isChecked =
            readLsposedEnabledPref()
        binding.etTargetPackages.setText(
            readStringPref(LSPosedConfig.KEY_TARGET_PACKAGES, defaultTargets)
        )
        binding.etDeviceProps.setText(
            readStringPref(LSPosedConfig.KEY_DEVICE_PROPS, defaultProps)
        )

        binding.switchLsposedEnabled.setOnCheckedChangeListener { _, isChecked ->
            writeBooleanToBoth(LSPosedConfig.KEY_ENABLED, isChecked)
            syncGlobalLsposedConfig(
                enabled = isChecked,
                targetPackagesRaw = binding.etTargetPackages.text?.toString().orEmpty(),
                devicePropsRaw = binding.etDeviceProps.text?.toString().orEmpty()
            )
            ensurePrefsReadable()
            showSnackbar(getString(R.string.lsposed_config_hint))
        }
    }

    private fun saveLsposedConfig() {
        val targetRaw = binding.etTargetPackages.text?.toString().orEmpty()
        val propsRaw = binding.etDeviceProps.text?.toString().orEmpty()

        val targets = LSPosedConfig.parseTargetPackages(targetRaw)
        val props = LSPosedConfig.parseDeviceProps(propsRaw)

        if (targets.isEmpty() || props.isEmpty()) {
            showSnackbar(getString(R.string.lsposed_config_invalid))
            return
        }

        val normalizedTargets = TextUtils.join("\n", targets)
        val normalizedProps = props.entries.joinToString("\n") { "${it.key}=${it.value}" }

        writeStringToBoth(LSPosedConfig.KEY_TARGET_PACKAGES, normalizedTargets)
        writeStringToBoth(LSPosedConfig.KEY_DEVICE_PROPS, normalizedProps)
        syncGlobalLsposedConfig(
            enabled = binding.switchLsposedEnabled.isChecked,
            targetPackagesRaw = normalizedTargets,
            devicePropsRaw = normalizedProps
        )
        ensurePrefsReadable()

        binding.etTargetPackages.setText(normalizedTargets)
        binding.etDeviceProps.setText(normalizedProps)

        showSnackbar(
            "${getString(R.string.lsposed_config_saved)} ${getString(R.string.lsposed_config_hint)}"
        )
    }

    private fun restartTargetApps() {
        val raw = binding.etTargetPackages.text?.toString().orEmpty()
        val targets = LSPosedConfig.parseTargetPackages(raw)
        if (targets.isEmpty()) {
//            showSnackbar(getString(R.string.restart_targets_empty))
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                binding.btnRestartTargetApps.isEnabled = false
                showSnackbar(getString(R.string.restart_targets_running))
            }

            val rooted = try { Shell.getShell().isRoot } catch (_: Exception) { false }
            if (!rooted) {
                withContext(Dispatchers.Main) {
                    if (!isFinishing && !isDestroyed) {
                        binding.btnRestartTargetApps.isEnabled = true
                        showSnackbar(getString(R.string.restart_targets_need_root))
                    }
                }
                return@launch
            }

            var ok = 0
            var fail = 0
            for (pkg in targets) {
                val result = runCatching { Shell.cmd("am force-stop $pkg").exec() }.getOrNull()
                if (result?.isSuccess == true) ok++ else fail++
            }

            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                binding.btnRestartTargetApps.isEnabled = true
                if (fail == 0) {
                    showSnackbar(getString(R.string.restart_targets_ok, ok))
                } else {
                    showSnackbar(getString(R.string.restart_targets_partial, ok, fail))
                }
            }
        }
    }


    /**
     * Attempts to find and launch Magisk, KernelSU, or APatch.
     */
    private fun launchRootManager() {
        rootManagerIntent?.let(::startActivity)
            ?: showSnackbar(getString(R.string.root_manager_not_found))
    }

    private val rootManagerIntent: Intent?
        get() {
            val pm = packageManager
            val packages = arrayOf(
                "com.topjohnwu.magisk",  // Magisk
                "me.weishu.kernelsu",    // KernelSU
                "me.bmax.apatch"         // APatch
            )
            return packages.firstNotNullOfOrNull { pkg -> pm.getLaunchIntentForPackage(pkg) }
        }

    private fun refreshStatus() {
        binding.btnRefresh.isEnabled = false
        binding.btnRefresh.setText(R.string.status_checking)
        setStatus(binding.tvRootStatus, R.string.root_access_checking, android.R.color.darker_gray, 0)
        setStatus(binding.tvLsposedStatus, R.string.checking_lsposed, android.R.color.darker_gray, 0)

        lifecycleScope.launch(Dispatchers.IO) {
            // Check Root Access
            val isRooted = try {
                Shell.getShell().isRoot
            } catch (_: Exception) {
                false
            }

            // Check LSPosed Module status
            val lsposedModuleStatus = if (isModuleActive) LsposedStatus.ACTIVE else LsposedStatus.NOT_ACTIVE

            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext

                updateUi(isRooted, lsposedModuleStatus)
                binding.btnRefresh.isEnabled = true
                binding.btnRefresh.setText(R.string.refresh_status)
                showSnackbar(getString(R.string.status_refreshed))
            }
        }
    }

    private fun updateUi(isRooted: Boolean, lsposedModuleStatus: LsposedStatus) {
        setStatus(
            view = binding.tvRootStatus,
            textRes = if (isRooted) R.string.root_access_granted else R.string.root_access_denied,
            colorRes = if (isRooted) android.R.color.holo_green_dark else android.R.color.holo_red_dark,
            iconRes = if (isRooted) android.R.drawable.checkbox_on_background else android.R.drawable.ic_dialog_alert
        )
        val moduleActive = lsposedModuleStatus == LsposedStatus.ACTIVE
        setStatus(
            view = binding.tvLsposedStatus,
            textRes = if (moduleActive) R.string.lsposed_module_enabled else R.string.lsposed_module_disabled,
            colorRes = if (moduleActive) android.R.color.holo_green_dark else android.R.color.holo_red_dark,
            iconRes = if (moduleActive) android.R.drawable.checkbox_on_background else android.R.drawable.ic_dialog_alert
        )
    }

    private fun setStatus(view: TextView, textRes: Int, colorRes: Int, iconRes: Int) {
        view.setText(textRes)
        view.setTextColor(ContextCompat.getColor(this, colorRes))
        view.setCompoundDrawablesWithIntrinsicBounds(iconRes, 0, 0, 0)
    }

    private fun openUrl() {
        try {
            val browserIntent =
                Intent(Intent.ACTION_VIEW, "https://github.com/LSPosed/LSPosed/releases".toUri())
            startActivity(browserIntent)
        } catch (_: Exception) {
            showSnackbar(getString(R.string.could_not_open_browser))
        }
    }

    private fun showSnackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
    }

    private fun readLsposedEnabledPref(): Boolean {
        if (devicePrefs.contains(LSPosedConfig.KEY_ENABLED)) {
            return devicePrefs.getBoolean(LSPosedConfig.KEY_ENABLED, true)
        }
        if (userPrefs.contains(LSPosedConfig.KEY_ENABLED)) {
            return userPrefs.getBoolean(LSPosedConfig.KEY_ENABLED, true)
        }
        return true
    }

    private fun readStringPref(key: String, defaultValue: String): String {
        val fromDevice = devicePrefs.getString(key, null)
        if (!fromDevice.isNullOrBlank()) return fromDevice
        val fromUser = userPrefs.getString(key, null)
        if (!fromUser.isNullOrBlank()) return fromUser
        return defaultValue
    }

    private fun writeBooleanToBoth(key: String, value: Boolean) {
        userPrefs.edit(commit = true) { putBoolean(key, value) }
        devicePrefs.edit(commit = true) { putBoolean(key, value) }
    }

    private fun writeStringToBoth(key: String, value: String) {
        userPrefs.edit(commit = true) { putString(key, value) }
        devicePrefs.edit(commit = true) { putString(key, value) }
    }

    private fun ensurePrefsReadable() {
        // Touch preferences to ensure LSPosed's new XSharedPreferences storage is initialized.
        userPrefs.all
        devicePrefs.all
    }

    private fun syncGlobalLsposedConfig(
        enabled: Boolean,
        targetPackagesRaw: String,
        devicePropsRaw: String
    ) {
        if (!Shell.getShell().isRoot) return
        val targetB64 = Base64.encodeToString(targetPackagesRaw.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val propsB64 = Base64.encodeToString(devicePropsRaw.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val commands = arrayOf(
            "settings put global ${LSPosedConfig.KEY_GLOBAL_ENABLED} ${if (enabled) 1 else 0}",
            "settings put global ${LSPosedConfig.KEY_GLOBAL_TARGET_PACKAGES_B64} ${shQuote(targetB64)}",
            "settings put global ${LSPosedConfig.KEY_GLOBAL_DEVICE_PROPS_B64} ${shQuote(propsB64)}"
        )
        runCatching { Shell.cmd(*commands).exec() }
    }

    private fun shQuote(value: String): String {
        return "'" + value.replace("'", "'\\''") + "'"
    }

    @Suppress("DEPRECATION")
    private fun openLsposedPrefs(context: Context): SharedPreferences {
        return try {
            context.getSharedPreferences(LSPosedConfig.PREFS_NAME, MODE_WORLD_READABLE)
        } catch (_: SecurityException) {
            context.getSharedPreferences(LSPosedConfig.PREFS_NAME, MODE_PRIVATE)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    companion object {
        private const val MAGISK_ZIP_ASSET_NAME = "magisk.zip"
        private const val MAGISK_MODULE_ASSET_DIR = "magisk"
        private const val MAGISK_PROP_FILE = "system.prop"
        // This field will be set to true by the Xposed module if it's active.
        @JvmField
        var isModuleActive: Boolean = false
    }
}
