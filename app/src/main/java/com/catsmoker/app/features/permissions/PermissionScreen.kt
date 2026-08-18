package com.catsmoker.app.features.permissions

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.catsmoker.app.R
import com.catsmoker.app.shared.ui.components.ScreenScaffold
import com.catsmoker.app.shared.ui.components.SectionCard
import com.catsmoker.app.shared.ui.theme.CatsmokerTheme

@Composable
fun PermissionRoute(onDone: () -> Unit) {
    val viewModel: PermissionViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshStates()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    if (uiState.isAgreementStep) {
        AgreementScreen(
            agreed = uiState.isAgreed,
            onAgreedChange = viewModel::onAgreedChange,
            onContinue = { if (viewModel.onContinue()) {} }
        )
    } else {
        PermissionScreen(
            uiState = uiState,
            onRefresh = viewModel::refreshStates,
            onRequestRoot = viewModel::requestRootPermission,
            onRequestShizuku = viewModel::requestShizukuPermission,
            onDone = {
                viewModel.completeOnboarding()
                onDone()
            }
        )
    }
}

@Composable
fun AgreementScreen(agreed: Boolean, onAgreedChange: (Boolean) -> Unit, onContinue: () -> Unit) {
    val annotatedLinkString = buildAnnotatedString {
        append("Please read our ")
        withLink(
            LinkAnnotation.Url(
                url = "https://catsmoker.vercel.app/legal",
                styles = TextLinkStyles(style = SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline))
            )
        ) {
            append("Terms and Conditions")
        }
        append(" before using the app.")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Gavel,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(64.dp)
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text("Terms of Service", style = MaterialTheme.typography.titleLarge, color = Color.White)
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = annotatedLinkString,
            style = MaterialTheme.typography.bodyMedium.copy(color = Color.Gray),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable { onAgreedChange(!agreed) }
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Checkbox(
                checked = agreed,
                onCheckedChange = null,
                colors = CheckboxDefaults.colors(
                    uncheckedColor = Color.Gray,
                    checkedColor = MaterialTheme.colorScheme.primary
                )
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("I agree to the terms.", color = Color.White)
        }
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onContinue,
            enabled = agreed,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text("CONTINUE", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun PermissionScreen(
    uiState: PermissionViewModel.UiState,
    onRefresh: () -> Unit,
    onRequestRoot: () -> Unit,
    onRequestShizuku: () -> Unit,
    onDone: () -> Unit
) {
    val context = LocalContext.current
    
    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { onRefresh() }

    val storageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { onRefresh() }

    val micLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { onRefresh() }

    val bluetoothLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { onRefresh() }

    ScreenScaffold(
        title = "Permissions",
        subtitle = "Grant permissions to enable all features.",
        trailingContent = {
            IconButton(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = MaterialTheme.colorScheme.primary)
            }
        }
    ) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            PermissionItem("Root Access", "Required for advanced optimizations.", uiState.rootGranted) { onRequestRoot() }
            
            PermissionItem("Shizuku Access", "Alternative to Root for system tweaks.", uiState.shizukuGranted) { onRequestShizuku() }

            PermissionItem("Storage Access", "To read/modify game configuration files.", uiState.storageGranted) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = "package:${context.packageName}".toUri()
                    })
                } else {
                    storageLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }

            PermissionItem("Battery Optimization", "Allows the app to run smoothly in background.", uiState.batteryGranted) {
                try {
                    context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply { data = "package:${context.packageName}".toUri() })
                } catch (e: Exception) {
                    context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }

            PermissionItem("Notifications", "To show service status.", uiState.notifGranted) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            PermissionItem("Overlay", "To show performance monitors.", uiState.overlayGranted) {
                context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri()))
            }
            PermissionItem("Usage Stats", "To track game playtime.", uiState.usageGranted) {
                context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }

            PermissionItem("Microphone", "Required for Audio Boost features.", uiState.micGranted) {
                micLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }

            PermissionItem("Bluetooth", "Required for Gamepad and headset optimizations.", uiState.bluetoothGranted) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    bluetoothLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("DONE") }
        }
    }
}

@Composable
fun PermissionItem(title: String, subtitle: String, granted: Boolean, onClick: () -> Unit) {
    SectionCard(modifier = Modifier.padding(vertical = 8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, color = Color.White)
                Text(subtitle, fontSize = 12.sp, color = Color.Gray)
            }
            if (granted) {
                Icon(Icons.Default.CheckCircle, null, tint = Color.Green)
            } else {
                Button(onClick = onClick, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                    Text("GRANT", fontSize = 10.sp)
                }
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun AgreementPreview() {
    CatsmokerTheme {
        AgreementScreen(
            agreed = true,
            onAgreedChange = {},
            onContinue = {}
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun PermissionPreview() {
    CatsmokerTheme {
        PermissionScreen(
            uiState = PermissionViewModel.UiState(
                isAgreementStep = false,
                rootGranted = true,
                notifGranted = false,
                overlayGranted = true
            ),
            onRefresh = {},
            onRequestRoot = {},
            onRequestShizuku = {},
            onDone = {}
        )
    }
}
