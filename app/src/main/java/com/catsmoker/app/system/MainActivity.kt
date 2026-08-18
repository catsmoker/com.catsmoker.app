package com.catsmoker.app.system

import android.os.Bundle
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.navigation.compose.rememberNavController
import com.catsmoker.app.shared.ui.theme.CatsmokerTheme
import com.catsmoker.app.system.navigation.AppNavHost
import com.catsmoker.app.system.navigation.Routes
import com.catsmoker.app.system.ui.StartupScreen
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

import android.app.ActivityManager
import android.os.Build
import com.catsmoker.app.R

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Request no title bar before super.onCreate
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        
        super.onCreate(savedInstanceState)
        
        // Fix for icon and label in recent apps overview
        updateTaskDescription()
        
        val startDestination = if (isFirstRun()) Routes.PERMISSION else Routes.MAIN
        
        setContent {
            CatsmokerTheme {
                var showStartup by remember { mutableStateOf(true) }

                LaunchedEffect(showStartup) {
                    if (!showStartup) {
                        // Refresh TaskDescription when UI is ready
                        updateTaskDescription()
                        delay(500.milliseconds)
                        (application as? CatsmokerApp)?.initDeferredTasks()
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (showStartup) {
                        StartupScreen(onFinished = { showStartup = false })
                    } else {
                        val navController = rememberNavController()
                        AppNavHost(navController = navController, startDestination = startDestination)
                    }
                }
            }
        }
    }

    private fun updateTaskDescription() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                @Suppress("DEPRECATION")
                val taskDesc = ActivityManager.TaskDescription(getString(R.string.app_name), R.mipmap.ic_launcher)
                setTaskDescription(taskDesc)
            }
        } catch (_: Exception) {}
    }

    private fun isFirstRun(): Boolean {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        return prefs.getBoolean("is_first_run", true)
    }
}
