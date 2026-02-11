package com.catsmoker.app

import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.catsmoker.app.databinding.ActivityRootBinding
import com.google.android.material.snackbar.Snackbar
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RootActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRootBinding
    private val prefs by lazy {
        try {
            getSharedPreferences(LSPosedConfig.PREFS_NAME, MODE_WORLD_READABLE)
        } catch (_: SecurityException) {
            getSharedPreferences(LSPosedConfig.PREFS_NAME, MODE_PRIVATE)
        }
    }

    private enum class LsposedStatus {
        NOT_ACTIVE,  // Module is not active
        ACTIVE       // Module is active
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRootBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupListeners()
        bindLsposedConfig()
        refreshStatus()
    }

    private fun setupToolbar() {
        supportActionBar?.apply {
            setTitle(R.string.root_status_title)
            setDisplayHomeAsUpEnabled(true)
        }
    }

    private fun setupListeners() {
        binding.btnRefresh.setOnClickListener { refreshStatus() }
        binding.btnInstallLsposed.setOnClickListener { openUrl() }
        binding.btnOpenManager.setOnClickListener { launchRootManager() }
        binding.btnSaveLsposedConfig.setOnClickListener { saveLsposedConfig() }
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
            prefs.edit().putBoolean(LSPosedConfig.KEY_ENABLED, isChecked).commit()
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

        prefs.edit()
            .putString(LSPosedConfig.KEY_TARGET_PACKAGES, normalizedTargets)
            .putString(LSPosedConfig.KEY_DEVICE_PROPS, normalizedProps)
            .commit()

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

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    companion object {
        // This field will be set to true by the Xposed module if it's active.
        @JvmField
        var isModuleActive: Boolean = false
    }
}
