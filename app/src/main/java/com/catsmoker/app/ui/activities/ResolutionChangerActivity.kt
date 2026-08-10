package com.catsmoker.app.ui.activities

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.util.DisplayMetrics
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import com.catsmoker.app.BuildConfig
import com.catsmoker.app.IFileService
import com.catsmoker.app.util.FileService
import com.catsmoker.app.ui.screens.ResolutionScreen
import com.catsmoker.app.ui.theme.CatsmokerTheme
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.coroutines.resume
import kotlin.math.abs

class ResolutionChangerActivity : ComponentActivity() {

    private var fileService: IFileService? = null
    
    private var defaultWidth by mutableStateOf(0)
    private var defaultHeight by mutableStateOf(0)
    private var defaultDpi by mutableStateOf(0)
    
    private var widthInputState by mutableStateOf("")
    private var heightInputState by mutableStateOf("")
    private var dpiInputState by mutableStateOf("")
    
    private var isRootAvailableState by mutableStateOf(false)
    private var isShizukuAvailableState by mutableStateOf(false)
    private var selectedMethodState by mutableStateOf(0)
    
    private val logTextItems = mutableStateListOf<String>()

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
        enableEdgeToEdge()
        initDefaults()
        checkAndBindShizuku()
        checkRootAvailability()

        setContent {
            CatsmokerTheme {
                ResolutionScreen(
                    widthInput = widthInputState,
                    heightInput = heightInputState,
                    dpiInput = dpiInputState,
                    isRootAvailable = isRootAvailableState,
                    isShizukuAvailable = isShizukuAvailableState,
                    selectedMethod = selectedMethodState,
                    logItems = logTextItems,
                    onWidthChange = { widthInputState = it },
                    onHeightChange = { heightInputState = it },
                    onDpiChange = { dpiInputState = it },
                    onMethodSelected = { selectedMethodState = it },
                    onApply = { 
                        val w = widthInputState.toIntOrNull()
                        val h = heightInputState.toIntOrNull()
                        val d = dpiInputState.toIntOrNull()
                        if (w != null && h != null && d != null) {
                            if (isDangerous(w, h, d)) showWarningDialog(w, h, d)
                            else applyResolution(w, h, d)
                        }
                    },
                    onReset = { resetResolution() },
                    onBack = { finish() }
                )
            }
        }
    }

    private fun initDefaults() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        defaultWidth = metrics.widthPixels
        defaultHeight = metrics.heightPixels
        defaultDpi = metrics.densityDpi
        widthInputState = defaultWidth.toString()
        heightInputState = defaultHeight.toString()
        dpiInputState = defaultDpi.toString()
        log("Default loaded: ${defaultWidth}x${defaultHeight} @ ${defaultDpi}dpi")
    }

    private fun checkRootAvailability() {
        lifecycleScope.launch(Dispatchers.IO) {
            val rootOk = try { Shell.getShell().isRoot } catch (_: Exception) { false }
            withContext(Dispatchers.Main) {
                isRootAvailableState = rootOk
                if (!rootOk && selectedMethodState == 0) selectedMethodState = 1
            }
        }
    }

    private fun checkAndBindShizuku() {
        isShizukuAvailableState = Shizuku.pingBinder()
        if (isShizukuAvailableState && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) bindShizukuService()
    }

    private fun bindShizukuService() {
        if (fileService != null) return
        try {
            val args = Shizuku.UserServiceArgs(ComponentName(this, FileService::class.java))
                .daemon(false).processNameSuffix("resolution_service").version(BuildConfig.VERSION_CODE)
            Shizuku.bindUserService(args, serviceConnection)
        } catch (_: Exception) {}
    }

    private fun isDangerous(w: Int, h: Int, d: Int): Boolean {
        return abs(w - defaultWidth).toFloat() / defaultWidth > 0.5f || abs(h - defaultHeight).toFloat() / defaultHeight > 0.5f || abs(d - defaultDpi).toFloat() / defaultDpi > 0.5f
    }

    private fun showWarningDialog(w: Int, h: Int, d: Int) {
        AlertDialog.Builder(this)
            .setTitle("Extreme Resolution!")
            .setMessage("The values you entered are significantly different from defaults. Proceed?")
            .setPositiveButton("CONTINUE") { _, _ -> applyResolution(w, h, d) }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun applyResolution(w: Int, h: Int, d: Int) {
        val cmd = "wm size ${w}x${h}; wm density $d"
        executeResCommand(cmd, "Resolution: ${w}x${h} @ ${d}dpi")
    }

    private fun resetResolution() {
        executeResCommand("wm size reset; wm density reset", "Resolution reset to default") {
            widthInputState = defaultWidth.toString()
            heightInputState = defaultHeight.toString()
            dpiInputState = defaultDpi.toString()
        }
    }

    private fun executeResCommand(cmd: String, successMsg: String, onSuccess: (() -> Unit)? = null) {
        lifecycleScope.launch(Dispatchers.IO) {
            val ok = if (selectedMethodState == 0) execRoot(cmd) else execShizuku("sh -c \"$cmd\"") == 0
            withContext(Dispatchers.Main) {
                if (ok) { log(successMsg); onSuccess?.invoke() }
                else log("Failed to execute command")
            }
        }
    }

    private fun execRoot(command: String): Boolean = try { Runtime.getRuntime().exec(arrayOf("su", "-c", command)).waitFor() == 0 } catch (_: Exception) { false }

    private suspend fun execShizuku(command: String): Int = suspendCancellableCoroutine { cont ->
        val args = Shizuku.UserServiceArgs(ComponentName(packageName, FileService::class.java.name)).daemon(false).processNameSuffix("resolution_service").version(BuildConfig.VERSION_CODE)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                try {
                    val result = IFileService.Stub.asInterface(service).executeCommand(arrayOf("sh", "-c", command))
                    if (cont.isActive) cont.resume(result)
                } catch (_: Exception) { if (cont.isActive) cont.resume(-1) }
                finally { Shizuku.unbindUserService(args, this, true) }
            }
            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        Shizuku.bindUserService(args, conn)
    }

    private fun log(m: String) { logTextItems.add("[${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())}] $m") }
}
