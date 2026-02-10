package com.catsmoker.app

import android.content.Intent
import android.os.Bundle
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