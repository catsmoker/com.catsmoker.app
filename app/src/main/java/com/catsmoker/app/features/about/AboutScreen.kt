package com.catsmoker.app.features.about

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.catsmoker.app.BuildConfig
import com.catsmoker.app.R
import com.catsmoker.app.shared.ui.components.QuickActionButton
import com.catsmoker.app.shared.ui.components.ScreenScaffold
import com.catsmoker.app.shared.ui.components.SectionCard
import com.catsmoker.app.shared.ui.theme.CatsmokerTheme

@Composable
fun AboutRoute(onBack: () -> Unit, onOpenPermissions: () -> Unit, onOpenLogs: () -> Unit) {
    val viewModel: AboutViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.toasts.collect { message ->
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    AboutScreen(
        adsEnabled = uiState.adsEnabled,
        autoCheck = uiState.autoCheck,
        isUpdating = uiState.isUpdating,
        updateProgress = uiState.updateProgress,
        onAdsToggled = viewModel::onAdsToggled,
        onAutoCheckToggled = viewModel::onAutoCheckToggled,
        onOpenPermissions = onOpenPermissions,
        onOpenLogs = onOpenLogs,
        onBack = onBack,
        onCheckUpdates = viewModel::onCheckUpdates
    )

    uiState.updateDialog?.let { dialog ->
        AlertDialog(
            onDismissRequest = viewModel::dismissUpdateDialog,
            title = { Text("Update Available: ${dialog.tagName}") },
            text = { Text("A new version is available. Download and install now?") },
            confirmButton = { TextButton(onClick = viewModel::startUpdateDownload) { Text("DOWNLOAD") } },
            dismissButton = { TextButton(onClick = viewModel::dismissUpdateDialog) { Text("LATER") } }
        )
    }
}

@Composable
fun AboutScreen(
    adsEnabled: Boolean, autoCheck: Boolean,
    isUpdating: Boolean, updateProgress: Float,
    onAdsToggled: (Boolean) -> Unit, onAutoCheckToggled: (Boolean) -> Unit,
    onOpenPermissions: () -> Unit, onOpenLogs: () -> Unit, onBack: () -> Unit, onCheckUpdates: () -> Unit
) {
    ScreenScaffold(title = stringResource(R.string.about_header_title), subtitle = "App information and settings.", onBack = onBack) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            // Header
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(modifier = Modifier.size(80.dp).clip(RoundedCornerShape(20.dp)), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Icon(Icons.Default.Info, null, modifier = Modifier.padding(12.dp))
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(text = stringResource(R.string.app_name), fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Color.White)
                Text(text = "v${BuildConfig.VERSION_NAME}", fontSize = 12.sp, color = Color.Gray)
            }

            Text("Settings", style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
            SectionCard {
                SettingsToggle("Enable Ads", adsEnabled, onAdsToggled)
            }

            Spacer(modifier = Modifier.height(20.dp))
            Text("Permissions", style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
            QuickActionButton(
                title = "App Permissions",
                subtitle = "Manage granted permissions.",
                iconContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                iconContentColor = MaterialTheme.colorScheme.primary,
                onClick = onOpenPermissions,
                isFullWidth = true,
                showChevron = true,
                icon = { Icon(Icons.Default.Security, null) }
            )

            Spacer(modifier = Modifier.height(20.dp))
            Text("Diagnostics", style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
            QuickActionButton(
                title = "System Logs",
                subtitle = "View app and system diagnostics.",
                iconContainerColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.1f),
                iconContentColor = MaterialTheme.colorScheme.tertiary,
                onClick = onOpenLogs,
                isFullWidth = true,
                showChevron = true,
                icon = { Icon(Icons.AutoMirrored.Filled.List, null) }
            )

            Spacer(modifier = Modifier.height(20.dp))
            Text("Updates", style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
            SectionCard {
                if (isUpdating) {
                    Column {
                        Text("Downloading update...", fontSize = 14.sp, color = Color.White)
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(progress = { updateProgress }, modifier = Modifier.fillMaxWidth())
                    }
                } else {
                    Button(onClick = onCheckUpdates, modifier = Modifier.fillMaxWidth()) { Text("Check for Updates") }
                    SettingsToggle("Auto Check", autoCheck, onAutoCheckToggled)
                }
            }
        }
    }
}

@Composable
fun SettingsToggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), color = Color.White)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun AboutPreview() {
    CatsmokerTheme {
        AboutScreen(
            adsEnabled = true,
            autoCheck = false,
            isUpdating = false,
            updateProgress = 0f,
            onAdsToggled = {},
            onAutoCheckToggled = {},
            onOpenPermissions = {},
            onOpenLogs = {},
            onBack = {},
            onCheckUpdates = {}
        )
    }
}
