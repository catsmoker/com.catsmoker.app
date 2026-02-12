package com.catsmoker.app.features

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.TextUtils
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.catsmoker.app.R
import com.catsmoker.app.databinding.ActivityRootScreenBinding
import com.catsmoker.app.core.LSPosedConfig
import com.catsmoker.app.ui.setupScreenHeader
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
    private val prefs by lazy {
        getSharedPreferences(LSPosedConfig.PREFS_NAME, MODE_PRIVATE)
    }

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

    @SuppressLint("ClickableViewAccessibility")
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
        binding.btnRefresh.setOnClickListener { refreshStatus() }
        binding.btnInstallLsposed.setOnClickListener { openUrl() }
        binding.btnOpenManager.setOnClickListener { launchRootManager() }
        binding.btnInstallMagiskZip.setOnClickListener { installBundledMagiskZip() }
        binding.btnSaveLsposedConfig.setOnClickListener { saveLsposedConfig() }
    }

    private fun loadMagiskSystemProp() {
        try {
            val moduleProp = assets.open("$MAGISK_MODULE_ASSET_DIR/$MAGISK_PROP_FILE")
                .bufferedReader()
                .use { it.readText() }
            binding.etMagiskModuleProp.setText(moduleProp)
        } catch (_: Exception) {
            binding.etMagiskModuleProp.setText("")
        }
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

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
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
            prefs.getBoolean(LSPosedConfig.KEY_ENABLED, true)
        binding.etTargetPackages.setText(
            prefs.getString(LSPosedConfig.KEY_TARGET_PACKAGES, defaultTargets)
        )
        binding.etDeviceProps.setText(
            prefs.getString(LSPosedConfig.KEY_DEVICE_PROPS, defaultProps)
        )

        binding.switchLsposedEnabled.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit(commit = true) { putBoolean(LSPosedConfig.KEY_ENABLED, isChecked) }
            ensurePrefsReadable()
            showSnackbar(getString(R.string.lsposed_config_hint))
        }
    }

    private fun saveLsposedConfig() {
        val targetRaw = binding.etTargetPackages.text?.toString().orEmpty()
        val propsRaw = binding.etDeviceProps.text?.toString().orEmpty()

        val targets = parseTargetPackages(targetRaw)
        val props = parseDeviceProps(propsRaw)

        if (targets.isEmpty() || props.isEmpty()) {
            showSnackbar(getString(R.string.lsposed_config_invalid))
            return
        }

        val normalizedTargets = TextUtils.join("\n", targets)
        val normalizedProps = props.entries.joinToString("\n") { "${it.key}=${it.value}" }

        prefs.edit(commit = true) {
            putString(LSPosedConfig.KEY_TARGET_PACKAGES, normalizedTargets)
                .putString(LSPosedConfig.KEY_DEVICE_PROPS, normalizedProps)
        }
        ensurePrefsReadable()

        binding.etTargetPackages.setText(normalizedTargets)
        binding.etDeviceProps.setText(normalizedProps)

        showSnackbar(
            "${getString(R.string.lsposed_config_saved)} ${getString(R.string.lsposed_config_hint)}"
        )
    }

    private fun parseTargetPackages(raw: String): Set<String> {
        if (raw.isBlank()) return emptySet()
        val result = LinkedHashSet<String>()
        raw
            .split("\n", ",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { result.add(it) }
        return result
    }

    private fun parseDeviceProps(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        raw.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach
            val idx = trimmed.indexOf('=')
            if (idx <= 0 || idx == trimmed.length - 1) return@forEach
            val key = trimmed.substring(0, idx).trim()
            val value = trimmed.substring(idx + 1).trim()
            if (key.isNotEmpty() && value.isNotEmpty()) {
                result[key] = value
            }
        }
        return result
    }


    /**
     * Attempts to find and launch Magisk, KernelSU, or APatch.
     */
    private fun launchRootManager() {
        val intent = rootManagerIntent
        if (intent != null) {
            startActivity(intent)
        } else {
            showSnackbar(getString(R.string.root_manager_not_found))
        }
    }

    private val rootManagerIntent: Intent?
        get() {
            val pm = packageManager
            val packages = arrayOf(
                "com.topjohnwu.magisk",  // Magisk
                "me.weishu.kernelsu",    // KernelSU
                "me.bmax.apatch"         // APatch
            )

            for (pkg in packages) {
                val intent = pm.getLaunchIntentForPackage(pkg)
                if (intent != null) return intent
            }
            return null
        }

    private fun refreshStatus() {
        binding.btnRefresh.isEnabled = false
        binding.btnRefresh.setText(R.string.status_checking)

        binding.tvRootStatus.setText(R.string.root_access_checking)
        binding.tvRootStatus.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
        binding.tvRootStatus.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)

        binding.tvLsposedStatus.setText(R.string.checking_lsposed)
        binding.tvLsposedStatus.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
        binding.tvLsposedStatus.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)

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
        // Update Root Status UI
        if (isRooted) {
            binding.tvRootStatus.setText(R.string.root_access_granted)
            binding.tvRootStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
            binding.tvRootStatus.setCompoundDrawablesWithIntrinsicBounds(
                android.R.drawable.checkbox_on_background, 0, 0, 0
            )
        } else {
            binding.tvRootStatus.setText(R.string.root_access_denied)
            binding.tvRootStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
            binding.tvRootStatus.setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.ic_dialog_alert, 0, 0, 0)
        }

        // Update LSPosed Status UI
        if (lsposedModuleStatus == LsposedStatus.ACTIVE) {
            binding.tvLsposedStatus.setText(R.string.lsposed_module_enabled)
            binding.tvLsposedStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
            binding.tvLsposedStatus.setCompoundDrawablesWithIntrinsicBounds(
                android.R.drawable.checkbox_on_background, 0, 0, 0
            )
        } else {
            binding.tvLsposedStatus.setText(R.string.lsposed_module_disabled)
            binding.tvLsposedStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
            binding.tvLsposedStatus.setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.ic_dialog_alert, 0, 0, 0)
        }
    }

    private fun openUrl() {
        try {
            val browserIntent = Intent(Intent.ACTION_VIEW, "https://github.com/LSPosed/LSPosed/releases".toUri())
            startActivity(browserIntent)
        } catch (_: Exception) {
            showSnackbar(getString(R.string.could_not_open_browser))
        }
    }

    private fun showSnackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
    }

    private fun ensurePrefsReadable() {
        val prefsDir = File(applicationInfo.dataDir, "shared_prefs")
        val prefsFile = File(prefsDir, "${LSPosedConfig.PREFS_NAME}.xml")
        if (prefsDir.exists()) {
            prefsDir.setReadable(true, false)
            prefsDir.setExecutable(true, false)
        }
        if (prefsFile.exists()) {
            prefsFile.setReadable(true, false)
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





