package com.catsmoker.app.features

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.DisplayMetrics
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.catsmoker.app.IFileService
import com.catsmoker.app.R
import com.catsmoker.app.core.createShizukuServiceArgs
import com.catsmoker.app.core.hasShizukuPermission
import com.catsmoker.app.core.requestShizukuPermissionIfNeeded
import com.catsmoker.app.databinding.ActivityResolutionChangerScreenBinding
import com.catsmoker.app.ui.setupScreenHeader
import com.catsmoker.app.ui.showSnackbar
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.abs

class ResolutionChangerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityResolutionChangerScreenBinding
    private var fileService: IFileService? = null

    private var defaultWidth = 0
    private var defaultHeight = 0
    private var defaultDpi = 0
    private var isRootAvailable = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            fileService = IFileService.Stub.asInterface(service)
            log("Shizuku service connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            fileService = null
            log("Shizuku service disconnected")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityResolutionChangerScreenBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupScreenHeader(R.string.resolution_changer_title, R.string.resolution_changer_subtitle)
        binding.methodToggleGroup.check(R.id.btn_method_root)

        initDefaults()
        setupButtons()
        checkAndBindShizuku()
        checkRootAvailability()
    }

    private fun initDefaults() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        defaultWidth = metrics.widthPixels
        defaultHeight = metrics.heightPixels
        defaultDpi = metrics.densityDpi

        binding.widthInput.setText(defaultWidth.toString())
        binding.heightInput.setText(defaultHeight.toString())
        binding.dpiInput.setText(defaultDpi.toString())
        log("Default resolution loaded: ${defaultWidth}x${defaultHeight} @ ${defaultDpi}dpi")
    }

    private fun setupButtons() {
        binding.methodToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            if (checkedId == R.id.btn_method_root && !isRootAvailable) {
                binding.methodToggleGroup.check(R.id.btn_method_shizuku)
                showSnackbar(getString(R.string.root_access_not_detected))
            }
        }

        binding.btnSubmit.setOnClickListener {
            val width = binding.widthInput.text?.toString()?.toIntOrNull()
            val height = binding.heightInput.text?.toString()?.toIntOrNull()
            val dpi = binding.dpiInput.text?.toString()?.toIntOrNull()
            if (width == null || height == null || dpi == null) {
                showSnackbar(getString(R.string.invalid_resolution_input))
                return@setOnClickListener
            }

            if (isDangerous(width, height, dpi)) {
                showWarningDialog(width, height, dpi)
            } else {
                applyResolution(width, height, dpi)
            }
        }

        binding.btnReset.setOnClickListener { resetResolution() }
        binding.exportLogButton.setOnClickListener { exportLogToFile() }
    }

    private fun isDangerous(width: Int, height: Int, dpi: Int): Boolean {
        val widthDiff = abs(width - defaultWidth).toFloat() / defaultWidth
        val heightDiff = abs(height - defaultHeight).toFloat() / defaultHeight
        val dpiDiff = abs(dpi - defaultDpi).toFloat() / defaultDpi
        return widthDiff > 0.5f || heightDiff > 0.5f || dpiDiff > 0.5f
    }

    private fun showWarningDialog(width: Int, height: Int, dpi: Int) {
        AlertDialog.Builder(this)
            .setTitle(R.string.warning)
            .setMessage(
                getString(
                    R.string.string_dialog_warning,
                    defaultWidth.toString(),
                    defaultHeight.toString(),
                    width.toString(),
                    height.toString(),
                    dpi.toString()
                )
            )
            .setPositiveButton(R.string.continue_anyway) { _, _ ->
                applyResolution(width, height, dpi)
            }
            .setNegativeButton(R.string.cancel_button, null)
            .show()
    }

    private fun applyResolution(width: Int, height: Int, dpi: Int) {
        val cmd = "wm size ${width}x${height}; wm density $dpi"
        lifecycleScope.launch(Dispatchers.IO) {
            val ok = if (isRootMode() && isRootAvailable) {
                execRoot(cmd)
            } else {
                execShizuku("sh -c \"$cmd\"") == 0
            }

            withContext(Dispatchers.Main) {
                if (ok) {
                    log("Resolution changed: ${width}x${height} @ ${dpi}dpi")
                    showSnackbar(getString(R.string.resolution_apply_success))
                } else {
                    log("Failed to apply resolution.")
                    showSnackbar(getString(R.string.resolution_apply_failed))
                }
            }
        }
    }

    private fun resetResolution() {
        val cmd = "wm size reset; wm density reset"
        lifecycleScope.launch(Dispatchers.IO) {
            val ok = if (isRootMode() && isRootAvailable) {
                execRoot(cmd)
            } else {
                execShizuku("sh -c \"$cmd\"") == 0
            }
            withContext(Dispatchers.Main) {
                if (ok) {
                    binding.widthInput.setText(defaultWidth.toString())
                    binding.heightInput.setText(defaultHeight.toString())
                    binding.dpiInput.setText(defaultDpi.toString())
                    log("Resolution reset to default.")
                    showSnackbar(getString(R.string.resolution_reset_success))
                } else {
                    log("Failed to reset resolution.")
                    showSnackbar(getString(R.string.resolution_reset_failed))
                }
            }
        }
    }

    private fun isRootMode(): Boolean = binding.methodToggleGroup.checkedButtonId == R.id.btn_method_root

    private fun checkRootAvailability() {
        lifecycleScope.launch(Dispatchers.IO) {
            val rootOk = try {
                Shell.getShell().isRoot || execRoot("id")
            } catch (_: Exception) {
                false
            }
            withContext(Dispatchers.Main) {
                isRootAvailable = rootOk
                binding.btnMethodRoot.isEnabled = rootOk
                if (!rootOk && isRootMode()) {
                    binding.methodToggleGroup.check(R.id.btn_method_shizuku)
                    log("Root unavailable. Switched to Shizuku mode.")
                }
            }
        }
    }

    private fun execRoot(command: String): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            process.waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }

    private fun checkAndBindShizuku() {
        if (!Shizuku.pingBinder()) {
            log("Shizuku not running.")
            return
        }
        if (!requestShizukuPermissionIfNeeded(SHIZUKU_PERMISSION_REQUEST_CODE)) {
            return
        }
        bindShizukuService()
    }

    private fun bindShizukuService() {
        if (fileService != null) return
        try {
            val args = createShizukuServiceArgs(this, processNameSuffix = "resolution_service")
            Shizuku.bindUserService(args, serviceConnection)
        } catch (e: Exception) {
            log("Shizuku bind failed: ${e.message}")
        }
    }

    private suspend fun execShizuku(command: String): Int {
        if (!Shizuku.pingBinder()) return -1
        if (!hasShizukuPermission()) return -1
        if (fileService != null) {
            return try {
                fileService?.executeCommand(arrayOf("sh", "-c", command)) ?: -1
            } catch (_: Exception) {
                -1
            }
        }
        return suspendCancellableCoroutine { cont ->
            val args = createShizukuServiceArgs(this, processNameSuffix = "resolution_service")

            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    try {
                        val remote = IFileService.Stub.asInterface(service)
                        val result = remote.executeCommand(arrayOf("sh", "-c", command))
                        if (cont.isActive) cont.resume(result)
                    } catch (_: Exception) {
                        if (cont.isActive) cont.resume(-1)
                    } finally {
                        try {
                            Shizuku.unbindUserService(args, this, true)
                        } catch (_: Exception) {}
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {}
            }

            Shizuku.bindUserService(args, conn)
        }
    }

    private fun log(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        binding.logTextView.append("[$timestamp] $message\n")
        binding.logScrollView.post { binding.logScrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun exportLogToFile() {
        try {
            val logText = binding.logTextView.text?.toString().orEmpty()
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "resolution_log_$timestamp.txt"
            val file = File(getExternalFilesDir(null), fileName)
            FileOutputStream(file).use { it.write(logText.toByteArray()) }
            Toast.makeText(this, getString(R.string.log_exported, fileName), Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.log_export_failed, e.message ?: "unknown"), Toast.LENGTH_LONG).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE) {
            if (hasShizukuPermission()) {
                bindShizukuService()
            } else {
                log("Shizuku permission denied.")
            }
        }
    }

    companion object {
        private const val SHIZUKU_PERMISSION_REQUEST_CODE = 2001
    }
}






