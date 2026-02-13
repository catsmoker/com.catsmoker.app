package com.catsmoker.app.main

import android.Manifest
import android.app.AppOpsManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.catsmoker.app.R
import com.catsmoker.app.databinding.ActivityPermissionsScreenBinding
import com.google.android.material.button.MaterialButton
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

class PermissionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPermissionsScreenBinding
    private var isAgreementStep = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPermissionsScreenBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupScreenHeader(
            R.string.permission_activity_title,
            R.string.permissions_header_subtitle,
            showBackButton = false
        )
        setupTexts()
        setupListeners()
        setupAgreementStep()
    }

    override fun onResume() {
        super.onResume()
        refreshStates()
    }

    private fun setupTexts() {
        listOf(
            CardText(binding.permRootTitle, binding.permRootDesc, R.string.perm_root_title, R.string.perm_root_desc),
            CardText(binding.permNotificationTitle, binding.permNotificationDesc, R.string.perm_notification_title, R.string.perm_notification_desc),
            CardText(binding.permStorageTitle, binding.permStorageDesc, R.string.perm_storage_title, R.string.perm_storage_desc),
            CardText(binding.permBatteryTitle, binding.permBatteryDesc, R.string.perm_battery_title, R.string.perm_battery_desc),
            CardText(binding.permOverlayTitle, binding.permOverlayDesc, R.string.perm_overlay_title, R.string.perm_overlay_desc),
            CardText(binding.permUsageTitle, binding.permUsageDesc, R.string.perm_usage_title, R.string.perm_usage_desc),
            CardText(binding.permShizukuTitle, binding.permShizukuDesc, R.string.perm_shizuku_title, R.string.perm_shizuku_desc)
        ).forEach { setCardText(it.titleView, it.descView, it.titleRes, it.descRes) }
    }

    private fun setCardText(titleView: TextView, descView: TextView, titleRes: Int, descRes: Int) {
        titleView.setText(titleRes)
        descView.setText(descRes)
    }

    private fun setupListeners() {
        listOf(
            binding.permRootActionBtn to ::requestRootPermission,
            binding.permNotificationActionBtn to ::requestNotificationPermission,
            binding.permStorageActionBtn to ::requestStoragePermission,
            binding.permBatteryActionBtn to ::requestBatteryPermission,
            binding.permOverlayActionBtn to ::requestOverlayPermission,
            binding.permUsageActionBtn to ::requestUsagePermission,
            binding.permShizukuActionBtn to ::requestShizukuPermission
        ).forEach { (button, action) -> button.setOnClickListener { action() } }

        binding.btnSkip.setOnClickListener {
            if (isAgreementStep) {
                if (!binding.agreementCheckbox.isChecked) {
                    Toast.makeText(this, getString(R.string.agreement_text), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                showPermissionStep()
                return@setOnClickListener
            }
            goToMain(skipped = true)
        }
    }

    private fun refreshStates() {
        checkRootPermission { isGranted -> updateCardState(binding.permRootActionBtn, isGranted) }
        updateCardState(binding.permNotificationActionBtn, checkNotificationPermission())
        updateCardState(binding.permStorageActionBtn, checkStoragePermission())
        updateCardState(binding.permBatteryActionBtn, checkBatteryPermission())
        updateCardState(binding.permOverlayActionBtn, checkOverlayPermission())
        updateCardState(binding.permUsageActionBtn, checkUsagePermission())
        updateCardState(binding.permShizukuActionBtn, checkShizukuPermission())
    }

    private fun updateCardState(btn: MaterialButton, isGranted: Boolean) {
        btn.icon = null
        btn.setText(if (isGranted) R.string.perm_granted else R.string.perm_grant)
        btn.isEnabled = !isGranted
        btn.alpha = if (isGranted) 0.5f else 1f
    }

    private fun setupAgreementStep() {
        isAgreementStep = true
        binding.onboardingSection.visibility = View.VISIBLE
        binding.requiredSection.visibility = View.GONE
        binding.btnSkip.setText(R.string.perm_next)
        binding.btnSkip.isEnabled = binding.agreementCheckbox.isChecked
        binding.agreementCheckbox.setOnCheckedChangeListener { _, checked ->
            if (isAgreementStep) {
                binding.btnSkip.isEnabled = checked
            }
        }
    }

    private fun showPermissionStep() {
        isAgreementStep = false
        binding.onboardingSection.visibility = View.GONE
        binding.requiredSection.visibility = View.VISIBLE
        binding.btnSkip.isEnabled = true
        binding.btnSkip.setText(R.string.perm_skip)
    }

    private fun goToMain(skipped: Boolean) {
        getSharedPreferences(PREFS_APP, MODE_PRIVATE).edit {
            putBoolean(KEY_PERMISSIONS_SKIPPED, skipped)
            putBoolean(KEY_IS_FIRST_RUN, false)
        }
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    // --- Permission Checks & Requests ---

    // 0. Root
    private fun checkRootPermission(callback: (Boolean) -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) {
            val isRooted = try {
                Shell.getShell().isRoot
            } catch (_: Exception) {
                false
            }
            withContext(Dispatchers.Main) {
                callback(isRooted)
            }
        }
    }

    private fun requestRootPermission() {
        // Trigger root request by checking access
        checkRootPermission { isGranted -> updateCardState(binding.permRootActionBtn, isGranted) }
    }

    // Battery
    private fun checkBatteryPermission(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as? PowerManager
        return pm?.isIgnoringBatteryOptimizations(packageName) == true
    }

    private fun requestBatteryPermission() {
        try {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.cannot_open_battery_settings), Toast.LENGTH_SHORT).show()
        }
    }

    // 1. Notification (Android 13+)
    private fun checkNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), RC_NOTIFICATION)
        }
    }

    // 2. Storage
    private fun checkStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    addCategory("android.intent.category.DEFAULT")
                    data = "package:$packageName".toUri()
                }
                startActivity(intent)
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), RC_STORAGE)
        }
    }

    // 3. Overlay
    private fun checkOverlayPermission(): Boolean {
        return Settings.canDrawOverlays(this)
    }

    private fun requestOverlayPermission() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                "package:$packageName".toUri()
            )
        )
    }

    // 4. Usage Access
    private fun checkUsagePermission(): Boolean {
        val appOps = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun requestUsagePermission() {
        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }

    // 5. Shizuku
    private fun checkShizukuPermission(): Boolean {
        try {
            if (Shizuku.isPreV11()) return false
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            return false
        }
    }

    private fun requestShizukuPermission() {
        try {
            if (Shizuku.isPreV11()) return
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(RC_SHIZUKU)
            }
        } catch (_: Throwable) {
            Toast.makeText(this, "Shizuku not running or not installed.", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val PREFS_APP = "app_prefs"
        private const val KEY_PERMISSIONS_SKIPPED = "permissions_skipped"
        private const val KEY_IS_FIRST_RUN = "is_first_run"
        private const val RC_NOTIFICATION = 101
        private const val RC_SHIZUKU = 102
        private const val RC_STORAGE = 103
    }

    private data class CardText(
        val titleView: TextView,
        val descView: TextView,
        val titleRes: Int,
        val descRes: Int
    )
}
