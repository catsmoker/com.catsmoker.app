package com.catsmoker.app.features.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.catsmoker.app.shared.ui.components.QuickActionButton
import com.catsmoker.app.shared.ui.components.ScreenScaffold
import com.catsmoker.app.shared.ui.components.SectionCard
import com.catsmoker.app.shared.ui.theme.CatsmokerTheme

@Composable
fun SettingsRoute(onBack: () -> Unit, onOpenPermissions: () -> Unit, onOpenLogs: () -> Unit) {
    val viewModel: SettingsViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.toasts.collect { message ->
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    SettingsScreen(
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
fun SettingsScreen(
    adsEnabled: Boolean,
    autoCheck: Boolean,
    isUpdating: Boolean,
    updateProgress: Float,
    onAdsToggled: (Boolean) -> Unit,
    onAutoCheckToggled: (Boolean) -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenLogs: () -> Unit,
    onBack: () -> Unit,
    onCheckUpdates: () -> Unit
) {
    ScreenScaffold(
        title = "Settings",
        subtitle = "App configuration and preferences.",
        onBack = onBack
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp)
        ) {
            Spacer(modifier = Modifier.height(16.dp))
            
            Text("General", style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
            SectionCard {
                SettingsToggle("Enable Ads", adsEnabled, onAdsToggled)
            }

            Spacer(modifier = Modifier.height(24.dp))
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
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), thickness = 0.5.dp, color = Color.White.copy(alpha = 0.05f))
                SettingsToggle("Auto Check Updates", autoCheck, onAutoCheckToggled)
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text("Management", style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
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

            Spacer(modifier = Modifier.height(12.dp))
            QuickActionButton(
                title = "System Logs",
                subtitle = "View app and system diagnostics.",
                iconContainerColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.1f),
                iconContentColor = Color.White,
                onClick = onOpenLogs,
                isFullWidth = true,
                showChevron = true,
                icon = { Icon(Icons.Default.Terminal, null) }
            )
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
fun SettingsPreview() {
    CatsmokerTheme {
        SettingsScreen(
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
