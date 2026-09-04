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
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.catsmoker.app.R
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Request no title bar before super.onCreate
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        
        super.onCreate(savedInstanceState)
        
        incrementLaunchCount()
        
        // Fix for icon and label in recent apps overview
        updateTaskDescription()
        
        val startDestination = if (isFirstRun()) Routes.PERMISSION else Routes.MAIN
        
        setContent {
            CatsmokerTheme {
                var showStartup by remember { mutableStateOf(true) }
                var showSupportDialog by remember { mutableStateOf(shouldShowSupportDialog()) }

                if (!showStartup && showSupportDialog) {
                    val githubUrl = stringResource(R.string.url_github)
                    AlertDialog(
                        onDismissRequest = { showSupportDialog = false },
                        title = { Text("Support the Developer", color = Color.White) },
                        text = {
                            Text(
                                "pleas star the project on github and donate to help the dev on paypal.",
                                color = Color.White.copy(alpha = 0.7f)
                            )
                        },
                        confirmButton = {
                            Row {
                                TextButton(onClick = {
                                    try {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(githubUrl))
                                        startActivity(intent)
                                    } catch (_: Exception) {}
                                    showSupportDialog = false
                                }) { Text("Star", color = Color.White) }
                                TextButton(onClick = {
                                    try {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.paypal.com/paypalme/catsmoker"))
                                        startActivity(intent)
                                    } catch (_: Exception) {}
                                    showSupportDialog = false
                                }) { Text("Donate", color = Color.White) }
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showSupportDialog = false }) { 
                                Text("LATER", color = Color.White.copy(alpha = 0.5f)) 
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = Color.White,
                        textContentColor = Color.White
                    )
                }

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

    private fun incrementLaunchCount() {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val count = prefs.getInt("app_launch_count", 0) + 1
        prefs.edit().putInt("app_launch_count", count).apply()
    }

    private fun shouldShowSupportDialog(): Boolean {
        val count = getSharedPreferences("app_prefs", MODE_PRIVATE).getInt("app_launch_count", 0)
        return count > 0 && count % 5 == 0
    }
}
