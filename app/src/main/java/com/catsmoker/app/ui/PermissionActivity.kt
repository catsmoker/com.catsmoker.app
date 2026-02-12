package com.catsmoker.app.ui

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
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.catsmoker.app.R
import com.catsmoker.app.databinding.ActivityPermissionsScreenBinding
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

class PermissionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPermissionsScreenBinding
    private var showingOptional = false
    private var isFirstRun = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPermissionsScreenBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnContinue.setText(R.string.perm_next_optional)
        binding.btnSkip.visibility = View.GONE

        setupScreenHeader(
            R.string.permission_activity_title,
            R.string.permissions_header_subtitle,
            showBackButton = false
        )
        setupOnboarding()
        setupTexts()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        refreshStates()
    }

    private fun setupTexts() {
        setCardText(binding.permRootTitle, binding.permRootDesc, R.string.perm_root_title, R.string.perm_root_desc)
        setCardText(binding.permNotificationTitle, binding.permNotificationDesc, R.string.perm_notification_title, R.string.perm_notification_desc)
        setCardText(binding.permStorageTitle, binding.permStorageDesc, R.string.perm_storage_title, R.string.perm_storage_desc)
        setCardText(binding.permBatteryTitle, binding.permBatteryDesc, R.string.perm_battery_title, R.string.perm_battery_desc)
        setCardText(binding.permOverlayTitle, binding.permOverlayDesc, R.string.perm_overlay_title, R.string.perm_overlay_desc)
        setCardText(binding.permUsageTitle, binding.permUsageDesc, R.string.perm_usage_title, R.string.perm_usage_desc)
        setCardText(binding.permShizukuTitle, binding.permShizukuDesc, R.string.perm_shizuku_title, R.string.perm_shizuku_desc)
    }

    private fun setCardText(titleView: android.widget.TextView, descView: android.widget.TextView, titleRes: Int, descRes: Int) {
        titleView.setText(titleRes)
        descView.setText(descRes)
    }

    private fun setupListeners() {
        binding.permRootActionBtn.setOnClickListener { requestRootPermission() }
        binding.permNotificationActionBtn.setOnClickListener { requestNotificationPermission() }
        binding.permStorageActionBtn.setOnClickListener { requestStoragePermission() }
        binding.permBatteryActionBtn.setOnClickListener { requestBatteryPermission() }
        binding.permOverlayActionBtn.setOnClickListener { requestOverlayPermission() }
        binding.permUsageActionBtn.setOnClickListener { requestUsagePermission() }
        binding.permShizukuActionBtn.setOnClickListener { requestShizukuPermission() }

        binding.btnContinue.setOnClickListener {
            if (isFirstRun && !binding.agreementCheckbox.isChecked) {
                Toast.makeText(this, getString(R.string.agreement_text), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!showingOptional) {
                showingOptional = true
                binding.requiredSection.visibility = View.GONE
                binding.optionalSection.visibility = View.VISIBLE
                binding.btnContinue.setText(R.string.perm_continue)
                binding.btnSkip.visibility = View.VISIBLE
                return@setOnClickListener
            }
            goToMain(skipped = false)
        }

        binding.btnSkip.setOnClickListener {
            goToMain(skipped = true)
        }
    }

    private fun refreshStates() {
        checkRootPermission { isGranted ->
             updateCardState(binding.permRootActionBtn, isGranted)
        }
        updateCardState(binding.permNotificationActionBtn, checkNotificationPermission())
        updateCardState(binding.permStorageActionBtn, checkStoragePermission())
        updateCardState(binding.permBatteryActionBtn, checkBatteryPermission())
        updateCardState(binding.permOverlayActionBtn, checkOverlayPermission())
        updateCardState(binding.permUsageActionBtn, checkUsagePermission())
        updateCardState(binding.permShizukuActionBtn, checkShizukuPermission())
    }

    private fun updateCardState(btn: com.google.android.material.button.MaterialButton, isGranted: Boolean) {
        if (isGranted) {
            btn.setText(R.string.perm_granted)
            btn.setIconResource(android.R.drawable.checkbox_on_background)
            btn.isEnabled = false
            btn.alpha = 0.5f
        } else {
            btn.setText(R.string.perm_grant)
            btn.icon = null
            btn.isEnabled = true
            btn.alpha = 1.0f
        }
    }

    private fun setupOnboarding() {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        isFirstRun = prefs.getBoolean("is_first_run", true)
        if (isFirstRun) {
            binding.onboardingSection.visibility = View.VISIBLE
            binding.btnContinue.isEnabled = binding.agreementCheckbox.isChecked
            binding.agreementCheckbox.setOnCheckedChangeListener { _, checked ->
                binding.btnContinue.isEnabled = checked
            }
        } else {
            binding.onboardingSection.visibility = View.GONE
        }
    }

    private fun goToMain(skipped: Boolean) {
        getSharedPreferences("app_prefs", MODE_PRIVATE).edit {
            putBoolean("permissions_skipped", skipped)
            putBoolean("is_first_run", false)
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
        checkRootPermission { isGranted ->
             updateCardState(binding.permRootActionBtn, isGranted)
        }
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
        if (Build.VERSION.SDK_INT >= 33) {
            return ContextCompat.checkSelfPermission(
            this, 
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
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
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 103)
        }
    }

    // 3. Overlay
    private fun checkOverlayPermission(): Boolean {
        return Settings.canDrawOverlays(this)
    }

    private fun requestOverlayPermission() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri()))
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
        private const val RC_NOTIFICATION = 101
        private const val RC_SHIZUKU = 102
    }
}





