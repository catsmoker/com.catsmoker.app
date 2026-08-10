package com.catsmoker.app.ui.activities

import android.app.AppOpsManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Process
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.catsmoker.app.core.DeviceDiagnosticManager
import com.catsmoker.app.core.GamingEngine
import com.catsmoker.app.core.MetricsEngine
import com.catsmoker.app.core.ShizukuManager
import com.catsmoker.app.ui.screens.DashboardScreen
import com.catsmoker.app.ui.theme.CatsmokerTheme

class MainActivity : ComponentActivity() {
    private lateinit var metricsEngine: MetricsEngine
    private lateinit var shizukuManager: ShizukuManager
    private lateinit var gamingEngine: GamingEngine
    private lateinit var deviceDiagnosticManager: DeviceDiagnosticManager
    private val appPrefs by lazy { getSharedPreferences("app_prefs", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        shizukuManager = ShizukuManager(this)
        shizukuManager.init()
        deviceDiagnosticManager = DeviceDiagnosticManager(this)
        gamingEngine = GamingEngine(this, shizukuManager, deviceDiagnosticManager)

        if (appPrefs.getBoolean("is_first_run", true)) {
            launchPermissionFlow()
            return
        }

        val permissionsSkipped = appPrefs.getBoolean("permissions_skipped", false)
        if (!permissionsSkipped && !arePermissionsGranted()) {
            launchPermissionFlow()
            return
        }

        metricsEngine = MetricsEngine(this, shizukuManager)
        metricsEngine.start()

        setContent {
            CatsmokerTheme {
                val metricsState by metricsEngine.state.collectAsState()
                val fpsHistory by metricsEngine.fpsHistory.collectAsState()
                
                var exitPressCount by remember { mutableStateOf(0) }
                var lastPressTime by remember { mutableStateOf(0L) }

                BackHandler {
                    val now = System.currentTimeMillis()
                    if (now - lastPressTime > 2000) {
                        exitPressCount = 1
                    } else {
                        exitPressCount++
                    }
                    lastPressTime = now

                    if (exitPressCount >= 3) {
                        finish()
                    } else {
                        val remaining = 3 - exitPressCount
                        Toast.makeText(this@MainActivity, "Press back $remaining more times to exit", Toast.LENGTH_SHORT).show()
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    DashboardScreen(
                        state = metricsState,
                        fpsHistory = fpsHistory,
                        onOpenSpoofDevice = { startActivity(Intent(this, SpoofDeviceActivity::class.java)) },
                        onOpenEditGameFiles = { startActivity(Intent(this, EditGameFilesActivity::class.java)) },
                        onOpenGameTools = { startActivity(Intent(this, GamingToolsActivity::class.java)) },
                        onOpenAbout = { startActivity(Intent(this, AboutActivity::class.java)) }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::metricsEngine.isInitialized) {
            metricsEngine.stop()
        }
        if (::shizukuManager.isInitialized) {
            shizukuManager.destroy()
        }
    }

    private fun launchPermissionFlow() {
        startActivity(Intent(this, PermissionActivity::class.java))
        finish()
    }

    private fun arePermissionsGranted(): Boolean {
        if (!Settings.canDrawOverlays(this)) return false
        val usageStatsGranted = try {
            val appOps = getSystemService(APP_OPS_SERVICE) as AppOpsManager
            val mode = appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                packageName
            )
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            false
        }
        if (!usageStatsGranted) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager()
        }
        return true
    }
}
