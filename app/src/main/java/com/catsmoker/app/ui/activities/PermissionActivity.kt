package com.catsmoker.app.ui.activities

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
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.catsmoker.app.ui.screens.PermissionScreen
import com.catsmoker.app.ui.theme.CatsmokerTheme
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

class PermissionActivity : ComponentActivity() {

    private var isAgreementStepState by mutableStateOf(true)
    private var isAgreedState by mutableStateOf(false)
    
    private var rootGrantedState by mutableStateOf(false)
    private var notifGrantedState by mutableStateOf(false)
    private var storageGrantedState by mutableStateOf(false)
    private var batteryGrantedState by mutableStateOf(false)
    private var overlayGrantedState by mutableStateOf(false)
    private var usageGrantedState by mutableStateOf(false)
    private var shizukuGrantedState by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            CatsmokerTheme {
                PermissionScreen(
                    isAgreementStep = isAgreementStepState,
                    isAgreed = isAgreedState,
                    rootGranted = rootGrantedState,
                    notifGranted = notifGrantedState,
                    storageGranted = storageGrantedState,
                    batteryGranted = batteryGrantedState,
                    overlayGranted = overlayGrantedState,
                    usageGranted = usageGrantedState,
                    shizukuGranted = shizukuGrantedState,
                    onAgreedChange = { isAgreedState = it },
                    onContinue = {
                        if (isAgreementStepState) {
                            if (isAgreedState) isAgreementStepState = false
                            else showToast("Please agree to the terms first")
                        } else {
                            goToMain(skipped = true)
                        }
                    },
                    onRequestRoot = { requestRootPermission() },
                    onRequestNotif = { requestNotificationPermission() },
                    onRequestStorage = { requestStoragePermission() },
                    onRequestOverlay = { requestOverlayPermission() },
                    onRequestUsage = { requestUsagePermission() },
                    onRequestBattery = { requestBatteryPermission() },
                    onRequestShizuku = { requestShizukuPermission() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStates()
    }

    private fun refreshStates() {
        checkRootPermission { rootGrantedState = it }
        notifGrantedState = checkNotificationPermission()
        storageGrantedState = checkStoragePermission()
        batteryGrantedState = checkBatteryPermission()
        overlayGrantedState = checkOverlayPermission()
        usageGrantedState = checkUsagePermission()
        shizukuGrantedState = checkShizukuPermission()
    }

    private fun checkRootPermission(callback: (Boolean) -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) {
            val isRooted = try { Shell.getShell().isRoot } catch (_: Exception) { false }
            withContext(Dispatchers.Main) { callback(isRooted) }
        }
    }

    private fun requestRootPermission() { checkRootPermission { rootGrantedState = it } }
    private fun checkBatteryPermission(): Boolean = (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
    private fun requestBatteryPermission() { try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) { showToast("Could not open settings") } }
    private fun checkNotificationPermission(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED else true
    private fun requestNotificationPermission() { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101) }
    private fun checkStoragePermission(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    private fun requestStoragePermission() { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { try { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, "package:$packageName".toUri())) } catch (_: Exception) { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } } else { ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 103) } }
    private fun checkOverlayPermission(): Boolean = Settings.canDrawOverlays(this)
    private fun requestOverlayPermission() { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri())) }
    private fun checkUsagePermission(): Boolean = (getSystemService(APP_OPS_SERVICE) as AppOpsManager).checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED
    private fun requestUsagePermission() { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
    private fun checkShizukuPermission(): Boolean = try { !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED } catch (_: Throwable) { false }
    private fun requestShizukuPermission() { try { if (!Shizuku.isPreV11() && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) Shizuku.requestPermission(102) } catch (_: Throwable) { showToast("Shizuku not running") } }

    private fun goToMain(skipped: Boolean) {
        getSharedPreferences("app_prefs", MODE_PRIVATE).edit { putBoolean("permissions_skipped", skipped); putBoolean("is_first_run", false) }
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun showToast(msg: String) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
}
