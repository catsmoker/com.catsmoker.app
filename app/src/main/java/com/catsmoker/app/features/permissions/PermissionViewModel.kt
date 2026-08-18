package com.catsmoker.app.features.permissions

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.catsmoker.app.system.shell.ShellRunner
import com.topjohnwu.superuser.Shell
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import javax.inject.Inject

@HiltViewModel
class PermissionViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shellRunner: ShellRunner,
) : ViewModel() {

    data class UiState(
        val isAgreementStep: Boolean = true,
        val isAgreed: Boolean = false,
        val rootGranted: Boolean = false,
        val notifGranted: Boolean = false,
        val storageGranted: Boolean = false,
        val batteryGranted: Boolean = false,
        val overlayGranted: Boolean = false,
        val usageGranted: Boolean = false,
        val shizukuGranted: Boolean = false,
        val micGranted: Boolean = false,
        val bluetoothGranted: Boolean = false,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            shellRunner.shizukuHasPermission.collect { granted ->
                _uiState.update { it.copy(shizukuGranted = granted) }
            }
        }
    }

    fun onAgreedChange(agreed: Boolean) {
        _uiState.update { it.copy(isAgreed = agreed) }
    }

    fun onContinue(): Boolean {
        val current = _uiState.value
        if (current.isAgreementStep) {
            if (!current.isAgreed) return false
            _uiState.update { it.copy(isAgreementStep = false) }
            return true
        }
        return true
    }

    fun completeOnboarding() {
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE).edit {
            putBoolean("is_first_run", false)
        }
    }

    fun refreshStates() {
        checkRootPermission { rooted -> _uiState.update { it.copy(rootGranted = rooted) } }
        shellRunner.refreshShizukuPermission()
        _uiState.update {
            it.copy(
                notifGranted = checkNotificationPermission(),
                storageGranted = checkStoragePermission(),
                batteryGranted = checkBatteryPermission(),
                overlayGranted = checkOverlayPermission(),
                usageGranted = checkUsagePermission(),
                shizukuGranted = shellRunner.shizukuHasPermission.value,
                micGranted = checkMicPermission(),
                bluetoothGranted = checkBluetoothPermission()
            )
        }
    }

    private fun checkMicPermission(): Boolean =
        context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun checkBluetoothPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else true

    fun requestRootPermission() {
        viewModelScope.launch(Dispatchers.IO) {
            // Trigger root request
            Shell.cmd("id").exec()
            refreshStates()
        }
    }

    fun requestShizukuPermission() {
        try {
            if (Shizuku.pingBinder()) {
                Shizuku.requestPermission(0)
            }
        } catch (_: Exception) {}
    }

    private fun checkRootPermission(callback: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val isRooted = try { shellRunner.isRootAvailable(force = true) } catch (_: Exception) { false }
            withContext(Dispatchers.Main) { callback(isRooted) }
        }
    }

    private fun checkBatteryPermission(): Boolean =
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName)

    private fun checkNotificationPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true

    private fun checkStoragePermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

    private fun checkOverlayPermission(): Boolean = Settings.canDrawOverlays(context)

    private fun checkUsagePermission(): Boolean = try {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) {
        false
    }

    private fun checkShizukuPermission(): Boolean = shellRunner.shizukuHasPermission.value
}
