package com.catsmoker.app.features.gamingtools

import android.content.Intent
import android.graphics.BitmapFactory
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.catsmoker.app.R
import com.catsmoker.app.features.gamingtools.engine.AnimationScaleKind
import com.catsmoker.app.features.gamingtools.engine.BoosterOutcome
import com.catsmoker.app.features.gamingtools.engine.BoosterState
import com.catsmoker.app.features.gamingtools.engine.GamingModeReport
import com.catsmoker.app.features.gamingtools.engine.GamingModeState
import com.catsmoker.app.features.gamingtools.tools.cleaner.CleaningFeature
import com.catsmoker.app.features.gamingtools.tools.dns.DnsFeature
import com.catsmoker.app.features.gamingtools.tools.firewall.BackgroundDataRestrictor
import com.catsmoker.app.features.gamingtools.tools.firewall.VpnFirewall
import com.catsmoker.app.features.gamingtools.tools.graphics.GameDeveloperOptions
import com.catsmoker.app.features.gamingtools.ui.*
import com.catsmoker.app.shared.ui.theme.logLineColor
import com.catsmoker.app.shared.data.model.GameInfo
import com.catsmoker.app.shared.ui.components.*
import com.catsmoker.app.shared.util.DisplayMetricsProvider
import com.catsmoker.app.shared.util.formatBytes
import com.catsmoker.app.shared.ui.theme.CatsmokerTheme

@Composable
fun GamingToolsRoute(onNavigate: (String) -> Unit, onBack: () -> Unit) {
    val viewModel: GamingToolsViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    val gamingState by viewModel.gamingState.collectAsState()
    val gamingReport by viewModel.gamingReport.collectAsState()
    val isFixedPerformanceMode by viewModel.isFixedPerformanceMode.collectAsState()
    val boosterLog by viewModel.boosterLog.collectAsState()
    val boosterState by viewModel.boosterState.collectAsState()
    val animationScales by viewModel.animationScales.collectAsState()
    val alwaysFinishActivities by viewModel.alwaysFinishActivities.collectAsState()
    val backgroundProcessLimit by viewModel.backgroundProcessLimit.collectAsState()
    val gameDevOptions by viewModel.gameDevOptions.collectAsState()

    val overlayLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.syncState()
    }

    // Coming back from the all-files-access screen, re-run the scan so the user sees straight away
    // whether the grant actually took effect instead of having to guess.
    val storageAccessLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.scanForJunk()
    }

    // Developer options owns the same refresh-rate overlay switch this app cannot reach without a
    // privileged channel. Coming back, the whole state is re-read rather than assumed: the user may
    // have turned it on, turned it off, or not found it at all, and only a fresh read knows which.
    val developerOptionsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.syncState()
    }

    // Android's VPN consent dialog. RESULT_OK is the only answer that means the interface may be
    // established, so anything else is reported as "not given" rather than retried silently.
    val vpnConsentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        viewModel.onVpnConsentResult(result.resultCode == android.app.Activity.RESULT_OK)
    }

    // Raised by the ViewModel when the switch was turned on without consent in place. Only an
    // Activity can show the dialog, so the request is handed back here.
    LaunchedEffect(uiState.vpnConsentRequest) {
        if (uiState.vpnConsentRequest) {
            val intent = viewModel.vpnConsentIntent()
            if (intent == null) {
                // Consent arrived between the check and here — nothing to ask for.
                viewModel.onVpnConsentResult(true)
            } else {
                try {
                    vpnConsentLauncher.launch(intent)
                } catch (_: Exception) {
                    viewModel.onVpnConsentResult(false)
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.toasts.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    GamingToolsScreen(
        uiState = uiState,
        gamingState = gamingState,
        gamingReport = gamingReport,
        isFixedPerformanceMode = isFixedPerformanceMode,
        boosterLog = boosterLog,
        boosterState = boosterState,
        animationScales = animationScales,
        alwaysFinishActivities = alwaysFinishActivities,
        backgroundProcessLimit = backgroundProcessLimit,
        gameDevOptions = gameDevOptions,
        onToggleOverlay = { enable ->
            if (enable && !viewModel.canDrawOverlays()) {
                overlayLauncher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri()))
            } else {
                viewModel.toggleOverlay(enable)
            }
        },
        onToggleCrosshair = { enable ->
            if (enable && !viewModel.canDrawOverlays()) {
                overlayLauncher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri()))
            } else {
                viewModel.toggleCrosshair(enable)
            }
        },
        onSelectCrosshair = viewModel::onSelectCrosshair,
        onSetBackgroundDataRestriction = viewModel::setBackgroundDataRestriction,
        onSetVpnFirewall = viewModel::setVpnFirewall,
        onToggleDnd = { enable ->
            if (!viewModel.toggleDnd(enable)) {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            }
        },
        onPerformMaintenance = viewModel::onPerformMaintenance,
        onScanJunk = viewModel::scanForJunk,
        onGrantStorageAccess = {
            val intent = viewModel.allFilesAccessIntent()
            if (intent == null) {
                // Below API 30 the cleaner runs on the runtime storage permission, which lives on
                // the app's own settings page.
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())
                )
            } else {
                try {
                    storageAccessLauncher.launch(intent)
                } catch (_: Exception) {
                    storageAccessLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            }
        },
        onActivateGamingMode = viewModel::activateGamingMode,
        onDeactivateGamingMode = viewModel::deactivateGamingMode,
        onBoostRam = viewModel::boostRam,
        onRunBooster = viewModel::runBooster,
        onStopBooster = viewModel::stopBooster,
        onToggleFixedPerformance = viewModel::toggleFixedPerformance,
        onBoostChange = viewModel::onBoostChange,
        onSetAnimationScale = viewModel::setAnimationScale,
        onToggleAlwaysFinish = viewModel::toggleAlwaysFinish,
        onToggleBackgroundLimit = viewModel::toggleBackgroundLimit,
        onSetShowRefreshRate = viewModel::setShowRefreshRate,
        onSetForcePeakRefreshRate = viewModel::setForcePeakRefreshRate,
        onSetGameDefaultFrameRateDisabled = viewModel::setGameDefaultFrameRateDisabled,
        onOpenDeveloperOptions = {
            // ACTION_APPLICATION_DEVELOPMENT_SETTINGS is the Developer options screen itself. On a
            // device where it has never been unlocked the Activity does not exist, so the fallback is
            // the top-level Settings app — from which the user can reach it.
            try {
                developerOptionsLauncher.launch(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            } catch (_: Exception) {
                try {
                    developerOptionsLauncher.launch(Intent(Settings.ACTION_SETTINGS))
                } catch (_: Exception) {
                    Toast.makeText(context, "This device has no Developer options screen", Toast.LENGTH_LONG).show()
                }
            }
        },
        onRefreshDns = viewModel::refreshDnsStatus,
        onApplyDnsProvider = viewModel::applyDnsProvider,
        onSetDnsAutomatic = viewModel::setDnsAutomatic,
        onDisableDns = viewModel::disableDns,
        onLaunchGame = viewModel::launchGame,
        onRemoveGame = viewModel::removeGameFromLibrary,
        onAddGameClicked = viewModel::onAddGameClicked,

        onToggleAutoForceStop = viewModel::toggleAutoForceStop,
        onToggleAutoForceStopKeepPackage = viewModel::toggleAutoForceStopKeepPackage,
        
        onResWidthChange = viewModel::onResWidthChange,
        onResHeightChange = viewModel::onResHeightChange,
        onResDpiChange = viewModel::onResDpiChange,
        onResOptionSelected = viewModel::onResOptionSelected,
        onApplyResolution = viewModel::applyResolutionChanges,
        onResetResolution = viewModel::resetResolutionChanges,
        
        onBack = onBack,
        onSync = {
            viewModel.refreshPrivilegeState()
            viewModel.syncState()
            viewModel.syncGames()
        }
    )

    if (uiState.isPickingGame) {
        AppPickerDialog(
            apps = uiState.allApps,
            onDismiss = viewModel::dismissGamePicker,
            onAppSelected = viewModel::addGameToLibrary
        )
    }

    if (uiState.showAggressiveCleanWarning) {
        AlertDialog(
            onDismissRequest = viewModel::dismissAggressiveCleanWarning,
            title = { Text("Aggressive Cleaning Warning") },
            text = { Text("You have selected aggressive cleaning categories. Proceed?") },
            confirmButton = { TextButton(onClick = viewModel::confirmAggressiveClean) { Text("CLEAN") } },
            dismissButton = { TextButton(onClick = viewModel::dismissAggressiveCleanWarning) { Text("CANCEL") } }
        )
    }
    
    // The ViewModel decides when a target needs confirming and supplies the concrete reason — an
    // aspect ratio the panel does not have, a size above native, or an unreadable panel — so the
    // dialog states the actual hazard rather than a generic "are you sure".
    uiState.resWarning?.let { warning ->
        AlertDialog(
            onDismissRequest = viewModel::dismissResWarning,
            title = { Text("Check this resolution") },
            text = { Text(warning) },
            confirmButton = { TextButton(onClick = viewModel::confirmResWarning) { Text("APPLY") } },
            dismissButton = { TextButton(onClick = viewModel::dismissResWarning) { Text("CANCEL") } }
        )
    }
}

@Composable
fun GamingToolsScreen(
    uiState: GamingToolsViewModel.UiState,
    gamingState: GamingModeState,
    gamingReport: GamingModeReport,
    isFixedPerformanceMode: Boolean,
    boosterLog: List<String>,
    boosterState: BoosterState,
    animationScales: Triple<Float, Float, Float>,
    alwaysFinishActivities: Boolean,
    backgroundProcessLimit: Boolean,
    gameDevOptions: GameDeveloperOptions.State,
    onToggleOverlay: (Boolean) -> Unit,
    onToggleCrosshair: (Boolean) -> Unit,
    onSelectCrosshair: (String) -> Unit,
    onSetBackgroundDataRestriction: (Boolean) -> Unit,
    onSetVpnFirewall: (Boolean) -> Unit,
    onToggleDnd: (Boolean) -> Unit,
    onPerformMaintenance: (List<CleaningFeature.Category>) -> Unit,
    onScanJunk: () -> Unit,
    onGrantStorageAccess: () -> Unit,
    onActivateGamingMode: () -> Unit,
    onDeactivateGamingMode: () -> Unit,
    onBoostRam: () -> Unit,
    onRunBooster: (String, Boolean) -> Unit,
    onStopBooster: () -> Unit,
    onBoostChange: (Int) -> Unit,
    onSetAnimationScale: (AnimationScaleKind, Float) -> Unit,
    onToggleAlwaysFinish: (Boolean) -> Unit,
    onToggleBackgroundLimit: (Boolean) -> Unit,
    onSetShowRefreshRate: (Boolean) -> Unit,
    onSetForcePeakRefreshRate: (Boolean) -> Unit,
    onSetGameDefaultFrameRateDisabled: (Boolean) -> Unit,
    /** Opens Android's Developer options screen for the switches this app cannot reach itself. */
    onOpenDeveloperOptions: () -> Unit,
    onRefreshDns: () -> Unit,
    onApplyDnsProvider: (DnsFeature.Provider) -> Unit,
    onSetDnsAutomatic: () -> Unit,
    onDisableDns: () -> Unit,
    onToggleFixedPerformance: (Boolean) -> Unit,
    onLaunchGame: (String) -> Unit,
    onRemoveGame: (String) -> Unit,
    onAddGameClicked: () -> Unit,

    onToggleAutoForceStop: (Boolean) -> Unit,
    onToggleAutoForceStopKeepPackage: (String) -> Unit,
    
    onResWidthChange: (String) -> Unit,
    onResHeightChange: (String) -> Unit,
    onResDpiChange: (String) -> Unit,
    onResOptionSelected: (String) -> Unit,
    onApplyResolution: () -> Unit,
    onResetResolution: () -> Unit,
    
    onBack: () -> Unit,
    onSync: () -> Unit,
) {
    val isActive = gamingState is GamingModeState.Active
    val isBusy = (gamingState is GamingModeState.Enabling) || (gamingState is GamingModeState.Disabling)
    val progressTarget = when (gamingState) {
        is GamingModeState.Enabling -> gamingState.progress
        is GamingModeState.Disabling -> 0.5f
        else -> 0f
    }
    
    LaunchedEffect(Unit) { onSync() }

    val hasPrivilege = uiState.isRooted || uiState.isShizukuActive

    ScreenScaffold(
        title = stringResource(R.string.Gaming_tools_title),
        subtitle = stringResource(R.string.gt_subtitle),
        onBack = onBack
    ) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)
        ) {
            // Library
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.gt_section_your_library), style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                IconButton(onClick = onAddGameClicked, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            if (uiState.games.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.gt_no_games_detected), color = Color.DarkGray, fontSize = 13.sp)
                }
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(uiState.games) { game -> GameLibraryCard(game, onLaunchGame, onRemoveGame) }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            GamingModeCard(
                gamingState = gamingState,
                report = gamingReport,
                animatedProgress = progressTarget,
                // Every optimization behind this switch is a `settings put`, a `cmd`, or `pm suspend`,
                // and Android refuses all of them to an ordinary app. With neither channel the button
                // used to be live and the activation simply failed — so it is disabled instead, and
                // becomes live on its own as soon as root or Shizuku is granted, because `onSync`
                // re-reads both every time this screen is shown.
                canActivate = hasPrivilege,
                isActive = isActive,
                isBusy = isBusy,
                onActivate = onActivateGamingMode,
                onDeactivate = onDeactivateGamingMode
            )
            Spacer(modifier = Modifier.height(24.dp))
            RamBoostCard(uiState.isBoostingRam, uiState.ramResult, onBoostRam)
            Spacer(modifier = Modifier.height(24.dp))
            FixedPerformanceModeCard(isFixedPerformanceMode, onToggleFixedPerformance)
            Spacer(modifier = Modifier.height(24.dp))

            // Tools
            Text(stringResource(R.string.gt_section_performance_boost), style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.padding(bottom = 12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ExpandableToolCard(title = "Louder Sound", subtitle = "Turn the volume up past the limit.", icon = Icons.AutoMirrored.Filled.VolumeUp) {
                    BoostContent(uiState.boostLevel, uiState.audioOutput, onBoostChange)
                }
                ExpandableToolCard(
                    title = "App Booster",
                    subtitle = "Get apps ready so games stutter less.",
                    icon = Icons.Default.RocketLaunch,
                    enabled = hasPrivilege,
                    requirementNote = "Needs root or Shizuku. Android does not let a normal app do this. " +
                        "Shizuku is a free helper app that lends this app that power without root."
                ) {
                    AppBoosterContent(boosterState, boosterLog, onRunBooster, onStopBooster)
                }
                // One card for everything that lives in Android's own Developer Options. These used to
                // be scattered across three sections, which made them look like separate features
                // rather than the one screen's worth of switches they actually are.
                ExpandableToolCard(
                    title = "Developer Options",
                    subtitle = "Speed up menus and background apps.",
                    icon = Icons.Default.DeveloperMode
                ) {
                    DeveloperOptionsContent(
                        animationScales = animationScales,
                        canWriteGlobalSettings = uiState.canWriteGlobalSettings,
                        alwaysFinishActivities = alwaysFinishActivities,
                        backgroundProcessLimit = backgroundProcessLimit,
                        hasPrivilege = hasPrivilege,
                        gameDevOptions = gameDevOptions,
                        onSetAnimationScale = onSetAnimationScale,
                        onToggleAlwaysFinish = onToggleAlwaysFinish,
                        onToggleBackgroundLimit = onToggleBackgroundLimit,
                        onSetShowRefreshRate = onSetShowRefreshRate,
                        onSetForcePeakRefreshRate = onSetForcePeakRefreshRate,
                        onSetGameDefaultFrameRateDisabled = onSetGameDefaultFrameRateDisabled,
                        onOpenDeveloperOptions = onOpenDeveloperOptions
                    )
                }

                ExpandableToolCard(title = "Screen Size", subtitle = "Make the screen easier to draw.", icon = Icons.Default.AspectRatio) {
                    ResolutionChangerContent(
                        native = uiState.nativeResolution,
                        source = uiState.resolutionSource,
                        activeOverride = uiState.activeOverride,
                        options = uiState.resolutionOptions,
                        selectedOptionId = uiState.selectedResolutionId,
                        width = uiState.widthInput, height = uiState.heightInput, dpi = uiState.dpiInput,
                        // Only the Custom entry unlocks the fields; a preset shows what it would apply.
                        editable = uiState.resolutionEditable,
                        validationError = uiState.resValidationError,
                        isApplying = uiState.isApplyingResolution,
                        isRoot = uiState.isRooted, isShizuku = uiState.isShizukuActive,
                        log = uiState.resLog,
                        onOptionSelected = onResOptionSelected,
                        onWidthChange = onResWidthChange, onHeightChange = onResHeightChange, onDpiChange = onResDpiChange,
                        onApply = onApplyResolution, onReset = onResetResolution
                    )
                }

                FeatureToggleCard(title = "FPS Monitor", subtitle = "Show how smooth your game is running.", icon = Icons.Default.BarChart, checked = uiState.isOverlayRunning, onCheckedChange = onToggleOverlay)
                ExpandableToolCard(title = "Crosshair", subtitle = "Put an aiming mark in the middle.", icon = Icons.Default.AddCircleOutline, isToggleable = true, isToggled = uiState.isCrosshairRunning, onToggleChange = onToggleCrosshair, forceExpand = uiState.isCrosshairRunning) {
                    CrosshairPicker(uiState.selectedCrosshair, onSelectCrosshair)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text(stringResource(R.string.gt_section_focus_network), style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.padding(bottom = 12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FeatureToggleCard(title = "Do Not Disturb", subtitle = "Silence notifications while you play.", icon = Icons.Default.NotificationsOff, checked = uiState.isDndEnabled, onCheckedChange = onToggleDnd)
                // Two independent ways to stop other apps using the network, and the card carries no
                // toggle of its own: each switch lives in the body with its own state and its own
                // requirement, because they are different mechanisms and either, both, or neither is a
                // valid choice. The card title used to imply one method with one switch.
                ExpandableToolCard(
                    title = "Stop Other Apps Using Data",
                    subtitle = "Keep other apps off the internet.",
                    icon = Icons.Default.VpnLock
                ) {
                    BackgroundDataContent(
                        status = uiState.backgroundDataStatus,
                        engaged = uiState.backgroundDataEngaged,
                        isChanging = uiState.isChangingBackgroundData,
                        hasPrivilege = hasPrivilege,
                        vpnState = uiState.vpnFirewallState,
                        isChangingVpn = uiState.isChangingVpnFirewall,
                        onSetDataSaver = onSetBackgroundDataRestriction,
                        onSetVpn = onSetVpnFirewall
                    )
                }
                ExpandableToolCard(
                    title = "Faster Address Lookups",
                    subtitle = "Choose a quicker, more private phone book.",
                    icon = Icons.Default.Dns
                ) {
                    DnsContent(
                        status = uiState.dnsStatus,
                        isChanging = uiState.isChangingDns,
                        onRefresh = onRefreshDns,
                        onApplyProvider = onApplyDnsProvider,
                        onSetAutomatic = onSetDnsAutomatic,
                        onDisable = onDisableDns
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text(stringResource(R.string.gt_section_system_advanced), style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.padding(bottom = 12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ExpandableToolCard(
                    title = "Auto Force Stop",
                    subtitle = "Close apps you leave, except your chosen ones.",
                    icon = Icons.Default.Security,
                    isToggleable = true,
                    isToggled = uiState.isAutoForceStopActive,
                    onToggleChange = onToggleAutoForceStop,
                    enabled = hasPrivilege,
                    requirementNote = "Needs root or Shizuku. Android does not let a normal app close " +
                        "other apps. Shizuku is a free helper app that lends this app that power " +
                        "without root."
                ) {
                    AutoForceStopContent(
                        apps = uiState.allApps,
                        kept = uiState.autoForceStopKeepPackages,
                        hasPrivilege = hasPrivilege,
                        onToggle = onToggleAutoForceStopKeepPackage
                    )
                }
                ExpandableToolCard(title = "Cleaner", subtitle = "Free up space by deleting leftovers.", icon = Icons.Default.DeleteSweep) {
                    CleaningContent(uiState.isRooted, uiState.isShizukuActive, uiState.cleanResult, uiState.scanReport, uiState.isScanningJunk, uiState.isCleaningJunk, onScanJunk, onPerformMaintenance, onGrantStorageAccess)
                }
            }
        }
    }
}

@Composable
fun GameLibraryCard(game: GameInfo, onLaunch: (String) -> Unit, onRemove: (String) -> Unit) {
    Surface(
        modifier = Modifier.width(140.dp).clip(RoundedCornerShape(18.dp)),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f))
    ) {
        Box {
            Column(modifier = Modifier.padding(12.dp)) {
                Image(bitmap = game.icon.toBitmap().asImageBitmap(), contentDescription = null, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)))
                Spacer(modifier = Modifier.height(12.dp))
                Text(text = game.appName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(modifier = Modifier.height(12.dp))
                Button(onClick = { onLaunch(game.packageName) }, modifier = Modifier.fillMaxWidth().height(32.dp), contentPadding = PaddingValues(0.dp), shape = RoundedCornerShape(8.dp)) {
                    Text("LAUNCH", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
            IconButton(onClick = { onRemove(game.packageName) }, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(24.dp)) {
                Icon(Icons.Default.Remove, "Remove", tint = Color.Gray.copy(alpha = 0.8f), modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
fun FeatureToggleCard(title: String, subtitle: String, icon: ImageVector, checked: Boolean, enabled: Boolean = true, onCheckedChange: (Boolean) -> Unit) {
    SectionCard(enabled = enabled) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(if (enabled) Color.White.copy(alpha = 0.05f) else Color.Gray.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = if (enabled) Color.White else Color.DarkGray, modifier = Modifier.size(20.dp)) }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, color = if (enabled) Color.White else Color.Gray, fontSize = 15.sp)
                Text(subtitle, color = if (enabled) Color.Gray else Color.DarkGray, fontSize = 12.sp)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        }
    }
}

/**
 * A tool card whose body opens on tap.
 *
 * @param enabled false when the feature cannot run here at all. The card greys out and will not open,
 *   because opening it would show controls that could only fail.
 * @param requirementNote what to say when [enabled] is false. A disabled card that gives no reason is
 *   just a dead card, and its body — where the explanation would normally live — cannot be opened, so
 *   the reason has to sit on the outside of it.
 */
@Composable
fun ExpandableToolCard(title: String, subtitle: String, icon: ImageVector, isToggleable: Boolean = false, isToggled: Boolean = false, enabled: Boolean = true, requirementNote: String? = null, onToggleChange: (Boolean) -> Unit = {}, forceExpand: Boolean = false, content: @Composable () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(forceExpand) { if (forceExpand) expanded = true }
    SectionCard(enabled = enabled) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(if (enabled) Color.White.copy(alpha = 0.05f) else Color.Gray.copy(alpha = 0.1f)).clickable(enabled = enabled) { expanded = !expanded }, contentAlignment = Alignment.Center) { Icon(icon, null, tint = if (enabled) Color.White else Color.DarkGray, modifier = Modifier.size(20.dp)) }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f).clickable(enabled = enabled) { expanded = !expanded }) {
                    Text(title, fontWeight = FontWeight.Bold, color = if (enabled) Color.White else Color.Gray, fontSize = 15.sp)
                    Text(subtitle, color = if (enabled) Color.Gray else Color.DarkGray, fontSize = 12.sp)
                }
                if (isToggleable) {
                    Switch(checked = isToggled, onCheckedChange = onToggleChange, enabled = enabled)
                } else {
                    IconButton(onClick = { expanded = !expanded }, enabled = enabled) { Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, tint = if (enabled) Color.Gray else Color.DarkGray) }
                }
            }
            if (!enabled && requirementNote != null) {
                Spacer(modifier = Modifier.height(12.dp))
                RequirementNotice(requirementNote)
            }
            if (expanded && enabled) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = Color.White.copy(alpha = 0.05f))
                content()
            }
        }
    }
}

/**
 * Resolution picker.
 *
 * Every fixed entry is a scaling of the panel's real resolution, computed by
 * `DisplayMetricsProvider` — the same reader spoof profiles use — so nothing here is a hardcoded
 * size that the display may not support. When the panel could not be read the picker says so
 * instead of offering entries derived from a guess.
 */
@Composable
fun ResolutionChangerContent(
    native: DisplayMetricsProvider.Snapshot?,
    source: String,
    activeOverride: DisplayMetricsProvider.Snapshot?,
    options: List<GamingToolsViewModel.ResolutionOption>,
    selectedOptionId: String?,
    width: String, height: String, dpi: String,
    validationError: String?,
    isApplying: Boolean,
    isRoot: Boolean,
    isShizuku: Boolean,
    /**
     * Whether the three number fields accept typing.
     *
     * False for every preset: the fields then show what that preset would apply and cannot be edited
     * into something the chip no longer describes. Only the "Custom" chip sets this true. The
     * ViewModel enforces the same rule on its side, so a stray edit cannot slip through the UI.
     */
    editable: Boolean,
    log: List<String>,
    onOptionSelected: (String) -> Unit,
    onWidthChange: (String) -> Unit, onHeightChange: (String) -> Unit, onDpiChange: (String) -> Unit,
    onApply: () -> Unit, onReset: () -> Unit
) {
    // execResult prefers root and only falls back to Shizuku, so this names the channel that will
    // actually run `wm` rather than offering a choice the app does not honour.
    val channel = when {
        isRoot -> "Root"
        isShizuku -> "Shizuku"
        else -> null
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        if (native != null && native.isValid) {
            Text("Your screen: ${native.label}", fontSize = 12.sp, color = Color.White)
        } else {
            Text(
                "Your phone did not report its screen size, so there are no ready-made choices below.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = activeOverride?.let { "Changed to: ${it.label}" } ?: "Not changed — using your screen's own size",
            fontSize = 11.sp,
            color = if (activeOverride != null) MaterialTheme.colorScheme.primary else Color.Gray
        )

        Spacer(modifier = Modifier.height(10.dp))
        ExplainerBox(
            title = "What is this?",
            lines = listOf(
                "It tells your phone to pretend the screen has fewer dots than it really has. " +
                    "Games then have less to draw, so they can run smoother.",
                "Everything looks a little less sharp while it is on. \"Reset\" puts it back.",
                "The third number, DPI, is how big everything looks. Lower makes text and buttons " +
                    "smaller, higher makes them bigger."
            )
        )
        Spacer(modifier = Modifier.height(6.dp))
        ExplainerBox(
            title = "Is it safe?",
            accent = Color(0xFFFFB300),
            lines = listOf(
                "Yes, but odd numbers can make things look stretched or leave black edges. The " +
                    "ready-made choices keep the same shape as your screen, so they are the safe ones.",
                "If the screen ever looks wrong, press \"Reset\". Restarting your phone also undoes it.",
                "Your phone's real screen size was read from " + (native?.let { source } ?: "your phone") +
                    ", so the choices below are made from your actual screen and not guessed."
            )
        )

        if (options.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text("Choose a size", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                options.forEach { option ->
                    FilterChip(
                        selected = option.id == selectedOptionId,
                        onClick = { onOptionSelected(option.id) },
                        label = { Text(option.label, fontSize = 11.sp) }
                    )
                }
            }
            // The numbers behind the chosen preset, so nothing is applied unseen.
            options.firstOrNull { it.id == selectedOptionId }?.target?.let { target ->
                Spacer(modifier = Modifier.height(6.dp))
                Text("This one sets ${target.label}", fontSize = 10.sp, color = Color.Gray)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        // readOnly rather than enabled = false: the numbers stay legible so a preset can be inspected
        // before it is applied, but the keyboard never opens for them.
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = width, onValueChange = onWidthChange, label = { Text("Width") },
                singleLine = true,
                readOnly = !editable,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = validationError != null,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = height, onValueChange = onHeightChange, label = { Text("Height") },
                singleLine = true,
                readOnly = !editable,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = validationError != null,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = dpi, onValueChange = onDpiChange, label = { Text("DPI") },
            singleLine = true,
            readOnly = !editable,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = validationError != null,
            supportingText = validationError?.let { { Text(it, fontSize = 11.sp) } },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = if (editable) "You can type your own numbers."
            else "Locked to the preset above. Pick \"Custom\" to type your own numbers.",
            fontSize = 10.sp,
            color = Color.Gray
        )

        Spacer(modifier = Modifier.height(16.dp))
        if (channel == null) {
            RequirementNotice(
                "Needs root or Shizuku. Android does not let a normal app resize the screen. " +
                    "Shizuku is a free helper app that lends this app that power without root."
            )
        } else {
            Text("Ready — using $channel.", fontSize = 11.sp, color = Color.Gray)
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onApply,
                enabled = channel != null && validationError == null && !isApplying,
                modifier = Modifier.weight(1f)
            ) {
                if (isApplying) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text("Apply")
                }
            }
            OutlinedButton(
                onClick = onReset,
                enabled = channel != null && !isApplying,
                modifier = Modifier.weight(0.6f)
            ) { Text("Reset") }
        }
        if (log.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Box(modifier = Modifier.fillMaxWidth().height(100.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.4f)).padding(8.dp)) {
                val scroll = rememberScrollState()
                LaunchedEffect(log.size) { scroll.scrollTo(scroll.maxValue) }
                Column(modifier = Modifier.verticalScroll(scroll)) {
                    log.forEach { line ->
                        Text(
                            text = line,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = logLineColor(line)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CrosshairPicker(selected: String, onSelect: (String) -> Unit) {
    val context = LocalContext.current
    val scopes = remember { (1..7).map { "scope$it.png" } }
    Column {
        Text("Select Style", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        Spacer(modifier = Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            scopes.forEach { scope ->
                val isSelected = selected == scope
                Surface(
                    modifier = Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).clickable { onSelect(scope) },
                    color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.03f),
                    border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(8.dp)) {
                        val bitmap = remember(scope) { try { context.assets.open("crosshair/$scope").use { BitmapFactory.decodeStream(it) } } catch (_: Exception) { null } }
                        if (bitmap != null) Image(bitmap = bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize())
                        else Icon(Icons.Default.BrokenImage, null, tint = Color.DarkGray)
                    }
                }
            }
        }
    }
}

/**
 * The keep-alive picker for Auto Force Stop.
 *
 * The ticks are the apps to *leave alone*. That is the inverse of what this list used to mean, and the
 * copy says so plainly, because a mis-read here closes the wrong apps.
 */
@Composable
fun AutoForceStopContent(
    apps: List<GameInfo>,
    kept: Set<String>,
    hasPrivilege: Boolean,
    onToggle: (String) -> Unit
) {
    val context = LocalContext.current
    // The service reads foreground app changes from UsageStatsManager, which needs the "Usage access"
    // appop — a grant no permission dialog can ask for. Without it the poll loop runs and finds
    // nothing, so the toggle would sit on "enabled" while doing absolutely nothing. Checked here so
    // the card can say so instead of pretending to work.
    val hasUsageAccess = remember {
        runCatching {
            val appOps = context.getSystemService(android.app.AppOpsManager::class.java)
            val mode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                appOps?.unsafeCheckOpNoThrow(
                    android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps?.checkOpNoThrow(
                    android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    context.packageName
                )
            }
            mode == android.app.AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        ExplainerBox(
            title = "What is this?",
            lines = listOf(
                "When you leave an app, this closes it for you. Closing an app frees up memory and " +
                    "stops it slowing your game down.",
                "Tick an app below to keep it open. Everything you do not tick gets closed when you " +
                    "leave it.",
                "Your home screen, your keyboard, this app, and your phone's built-in apps are never " +
                    "closed, even if you do not tick them."
            )
        )
        Spacer(modifier = Modifier.height(8.dp))
        ExplainerBox(
            title = "What permission does it need?",
            lines = listOf(
                "Two things. First, \"Usage access\" — that is what lets this app see which app you " +
                    "are looking at. You turn it on yourself in Android's settings; there is a button " +
                    "below if it is missing.",
                "Second, root or Shizuku. Android does not let a normal app close another app, so " +
                    "without one of these nothing will be closed. Shizuku is a helper app that lends " +
                    "this app some extra powers without root.",
                "If either one is missing, the switch stays on but the notification will tell you " +
                    "nothing is being closed. It will not pretend to work."
            ),
            accent = Color(0xFFFFB300)
        )
        Spacer(modifier = Modifier.height(8.dp))
        ExplainerBox(
            title = "Should I use it?",
            accent = Color(0xFFFFB300),
            lines = listOf(
                "It helps most if you leave heavy apps open — a browser with lots of tabs, a video " +
                    "app, a social app.",
                "The cost: a closed app takes longer to open next time, and a closed chat app will " +
                    "not show you new messages until you open it again. Tick your chat apps to keep " +
                    "them running.",
                "Gaming Mode already pauses other apps while you play, so you do not need both."
            )
        )
        if (!hasPrivilege) {
            Spacer(modifier = Modifier.height(8.dp))
            RequirementNotice("Needs root or Shizuku. Without one, Android will not let this app close anything.")
        }
        if (!hasUsageAccess) {
            Spacer(modifier = Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFFE53935).copy(alpha = 0.12f))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("Usage access is off", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE53935))
                Text(
                    "This needs to see which app you are using. Until you turn it on, nothing will " +
                        "be closed.",
                    fontSize = 11.sp,
                    color = Color(0xFFE53935),
                    lineHeight = 15.sp
                )
                OutlinedButton(onClick = {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    }.onFailure {
                        context.startActivity(Intent(Settings.ACTION_SETTINGS))
                    }
                }) { Text("Turn on usage access") }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = if (kept.isEmpty()) "Keeping 0 apps open — every app you leave will be closed"
            else "Keeping ${kept.size} app(s) open",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text("Tick an app to keep it open", fontSize = 10.sp, color = Color.Gray)
        Spacer(modifier = Modifier.height(8.dp))
        androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.heightIn(max = 260.dp)) {
            items(apps) { app ->
                Row(modifier = Modifier.fillMaxWidth().clickable { onToggle(app.packageName) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = kept.contains(app.packageName), onCheckedChange = { onToggle(app.packageName) })
                    Image(bitmap = app.icon.toBitmap().asImageBitmap(), contentDescription = null, modifier = Modifier.size(24.dp).clip(RoundedCornerShape(4.dp)))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(app.appName, color = Color.White, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * The one-line "this needs something you do not have" banner.
 *
 * Item 8's rule in a single place: a feature that cannot run says so where the user is looking, in
 * ordinary words, and it is never hidden behind a dropdown.
 */
@Composable
private fun RequirementNotice(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFFFFB300).copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Lock, contentDescription = null, tint = Color(0xFFFFB300), modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(text, fontSize = 11.sp, color = Color(0xFFFFB300), lineHeight = 15.sp)
    }
}

/**
 * A titled explanation, collapsed until tapped.
 *
 * Several cards drive commands whose effect is not guessable from a switch label — closing apps in the
 * background, locking the processor speed, blocking the network. Each of those says what it does and
 * what it costs, and none of it is on screen until the user asks for it. All of them render through
 * [CollapsibleExplainer] so the behaviour is identical everywhere.
 */
@Composable
private fun ExplainerBox(
    title: String,
    lines: List<String>,
    accent: Color = Color(0xFF64B5F6)
) {
    CollapsibleExplainer(title = title, lines = lines, accent = accent)
}

@Composable
fun CleaningContent(
    isRooted: Boolean,
    isShizukuActive: Boolean,
    cleanResult: CleaningFeature.CleanResult?,
    report: CleaningFeature.ScanReport?,
    isScanning: Boolean,
    isCleaning: Boolean,
    onScan: () -> Unit,
    onPerform: (List<CleaningFeature.Category>) -> Unit,
    onGrantStorageAccess: () -> Unit
) {
    var selectedCategories by remember { mutableStateOf(CleaningFeature.Category.entries.filter { !it.isAggressive }.toSet()) }
    val resultsByCategory = remember(report) { report?.results?.associateBy { it.category }.orEmpty() }
    Column(modifier = Modifier.fillMaxWidth()) {
        // Names the channel that will actually do the deleting, because it decides how much of the
        // device can be reached — a normal app cannot see another app's cache at all.
        Text(
            "Can reach: ${
                when {
                    isRooted -> "everything (root)"
                    isShizukuActive -> "more than usual (Shizuku)"
                    else -> "only what Android allows a normal app"
                }
            }",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(8.dp))
        ExplainerBox(
            title = "What is this?",
            lines = listOf(
                "It looks for files your phone no longer needs — leftovers, empty files and empty " +
                    "folders — and can delete them to free up space.",
                "\"Scan\" only looks. Nothing is deleted until you press \"Clean\".",
                "The number next to each row is how many things were found and how much space they " +
                    "take. It is measured, not estimated."
            )
        )
        Spacer(modifier = Modifier.height(4.dp))
        ExplainerBox(
            title = "Is it safe? What are the red ones?",
            accent = Color(0xFFFFB300),
            lines = listOf(
                "The normal rows are safe: your phone rebuilds those files by itself when it needs " +
                    "them again.",
                "The rows written in red are stronger. They can log you out of apps or make an app " +
                    "start slowly the first time after cleaning. They are unticked to begin with.",
                "Your photos, messages, files and apps are never touched."
            )
        )
        Spacer(modifier = Modifier.height(8.dp))
        CleaningFeature.Category.entries.forEach { category ->
            Row(modifier = Modifier.fillMaxWidth().clickable { selectedCategories = if (selectedCategories.contains(category)) selectedCategories - category else selectedCategories + category }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = selectedCategories.contains(category), onCheckedChange = { checked -> selectedCategories = if (checked) selectedCategories + category else selectedCategories - category })
                Text(category.label, modifier = Modifier.weight(1f), color = if (category.isAggressive) Color.Red else Color.White)
                // Only a measured size is shown as a size. "Not scanned" and "none found" are
                // different outcomes and neither of them is 0 B.
                val entry = resultsByCategory[category]
                when {
                    report == null -> Unit
                    // The count leads, because empty files and empty folders are real finds that
                    // reclaim no bytes — a bare "0 B" there reads as "found nothing".
                    entry != null -> Text(
                        "${entry.itemCount} · ${formatBytes(entry.sizeBytes)}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    report.scannedAnything -> Text("none found", fontSize = 11.sp, color = Color.Gray)
                    else -> Text("not scanned", fontSize = 11.sp, color = Color(0xFFFFB300))
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onScan, modifier = Modifier.weight(1f), enabled = !isScanning && !isCleaning) { Text(if (isScanning) "Looking..." else "Scan") }
            Button(onClick = { onPerform(selectedCategories.toList()) }, modifier = Modifier.weight(1f), enabled = !isScanning && !isCleaning && selectedCategories.isNotEmpty()) { Text(if (isCleaning) "Cleaning..." else "Clean") }
        }

        // The outcome of the last clean, in one line. This replaced a scrolling terminal view: the
        // figures are the same measured counts the log lines were built from, so nothing is lost, but
        // the result is readable at a glance instead of needing to be parsed out of a transcript.
        if (cleanResult != null) {
            Spacer(modifier = Modifier.height(12.dp))
            CleanResultRow(cleanResult)
        }

        if (report != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = when {
                    !report.scannedAnything -> "Your storage could not be read, so nothing was measured."
                    report.results.isEmpty() -> "Looked everywhere — there is nothing to clean."
                    report.totalBytes == 0L ->
                        "${report.totalItems} things to remove — all empty, so no space is freed."
                    else -> "Can free up ${formatBytes(report.totalBytes)} from ${report.totalItems} things"
                },
                fontSize = 12.sp,
                color = if (report.scannedAnything) Color.LightGray else Color(0xFFFFB300)
            )

            if (report.limitations.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFFFFB300).copy(alpha = 0.10f))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("What this could not check:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFB300))
                    report.limitations.forEach { limitation ->
                        Text("• $limitation", fontSize = 11.sp, color = Color(0xFFFFB300), lineHeight = 15.sp)
                    }
                }
            }

            if (report.needsAllFilesAccess) {
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = onGrantStorageAccess, modifier = Modifier.fillMaxWidth()) {
                    Text("Let this app see all files")
                }
            }
        }
    }
}

/**
 * States the result of a clean in one line, with a green check when something was actually removed.
 *
 * The headline is deliberately not always "Cleaned N MB". Empty files and empty folders are real junk
 * that frees no bytes, so a clean can legitimately remove hundreds of items and reclaim nothing —
 * printing "Cleaned 0 B" there would read as a failure. Whatever cannot be stated as a measured figure
 * is stated as what it is instead.
 */
@Composable
private fun CleanResultRow(result: CleaningFeature.CleanResult) {
    val success = !result.removedNothing
    val accent = if (success) Color(0xFF4CAF50) else Color(0xFFFFB300)
    val headline = when {
        result.removedNothing && result.failedItems > 0 -> "Nothing could be removed"
        result.removedNothing -> "Nothing was removed"
        // A size of zero after real deletions means everything removed was empty, which is a
        // different fact from having freed nothing measurable.
        result.freedBytes == 0L && result.unmeasuredItems == 0 ->
            "Cleaned ${result.deletedItems} empty things — they took up no space"
        result.unmeasuredItems > 0 ->
            "Freed at least ${formatBytes(result.freedBytes)}"
        else -> "Freed ${formatBytes(result.freedBytes)}"
    }

    // Every count that is not zero gets said out loud. An item that was listed and then not removed
    // has to be accounted for somewhere, or the headline overstates what happened.
    val details = buildList {
        if (!result.removedNothing) add("${result.deletedItems} things deleted")
        if (result.unmeasuredItems > 0) {
            add("${result.unmeasuredItems} of them would not say how big they were, so the real total is a little more")
        }
        if (result.failedItems > 0) {
            add(
                if (result.privileged) {
                    "${result.failedItems} your phone would not let go of"
                } else {
                    "${result.failedItems} need root or Shizuku to delete"
                }
            )
        }
        if (result.alreadyGoneItems > 0) add("${result.alreadyGoneItems} had already gone by themselves")
        if (result.protectedSkips > 0) add("${result.protectedSkips} were left alone on purpose, to be safe")
        if (result.trimmedInternalCaches) {
            add("Your phone also cleared some app leftovers itself — that space is not counted above")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(accent.copy(alpha = 0.10f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (success) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(headline, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = accent)
        }
        details.forEach { line ->
            Text("• $line", fontSize = 11.sp, color = Color.LightGray, lineHeight = 15.sp)
        }
    }
}

/**
 * Two independent ways to stop other apps using the network.
 *
 * They are separate switches because they are separate mechanisms with separate requirements, and
 * either alone is a sensible choice:
 * - **App block (local VPN)** works on any network and needs no root, but only one VPN can be active
 *   on the device at a time.
 * - **Data Saver** is Android's own policy, needs root or Shizuku, and only applies to metered
 *   networks — but it costs nothing and leaves the VPN slot free.
 *
 * Turning both on is allowed and is the strongest setting. Nothing here forces a choice between them.
 *
 * Everything shown is read from the device: [BackgroundDataRestrictor.Status] carries nulls for
 * anything that could not be read, and those render as an explicit unavailable line rather than a
 * default that would look like a reading. The VPN half reports [VpnFirewall.State], whose `running`
 * flag comes from `establish()` returning a real descriptor.
 */
@Composable
fun BackgroundDataContent(
    status: BackgroundDataRestrictor.Status?,
    engaged: Boolean,
    isChanging: Boolean,
    hasPrivilege: Boolean,
    vpnState: VpnFirewall.State,
    isChangingVpn: Boolean,
    onSetDataSaver: (Boolean) -> Unit,
    onSetVpn: (Boolean) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ExplainerBox(
            title = "What is this?",
            lines = listOf(
                "It stops other apps using the internet in the background, so your game gets the whole " +
                    "connection to itself.",
                "There are two ways to do it. You can use one, or both at the same time. Your games are " +
                    "always left alone."
            )
        )

        // ---------- Switch 1: the local VPN ----------
        HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
        NetworkBlockSwitch(
            title = "App block (VPN)",
            // Never called a firewall: it is a local VPN, and saying otherwise would misdescribe what
            // the app does. The subtitle names both the mechanism and the one real trade-off.
            subtitle = "Works on Wi-Fi and mobile data. No root needed. Uses your phone's VPN slot.",
            checked = vpnState.running,
            enabled = !isChangingVpn,
            busy = isChangingVpn,
            onCheckedChange = onSetVpn
        )
        Text(
            text = when {
                vpnState.running && vpnState.blockedCount > 0 ->
                    "On — stopping ${vpnState.blockedCount} apps"
                vpnState.running -> "On"
                else -> "Off"
            },
            fontSize = 11.sp,
            color = if (vpnState.running) MaterialTheme.colorScheme.primary else Color.Gray
        )
        // Kept on screen after a failure so the switch flicking back off is explained rather than
        // looking like the tap was missed.
        vpnState.lastError?.let { error ->
            Text("Could not turn it on: $error", fontSize = 11.sp, color = Color(0xFFEF9A9A), lineHeight = 15.sp)
        }
        ExplainerBox(
            title = "How does the VPN one work?",
            lines = listOf(
                "Android lets an app make a private connection, called a VPN, and choose which apps go " +
                    "through it. This app sends the other apps into it and then drops everything they " +
                    "send. Your games do not go through it at all, so they are untouched.",
                "It is a VPN, not a firewall. Nothing is sent anywhere and nothing leaves your phone — " +
                    "it simply goes nowhere.",
                "Two things to know: a blocked app will look like it is loading forever instead of " +
                    "saying \"no internet\", and your phone only allows one VPN at a time, so this " +
                    "turns off any other VPN app you use.",
                "Android will ask you once to allow it. If you say no, nothing is blocked."
            )
        )

        // ---------- Switch 2: Android's own Data Saver ----------
        HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
        NetworkBlockSwitch(
            title = "Data Saver",
            subtitle = "Android's own setting. Needs root or Shizuku. Mobile data only.",
            checked = status?.dataSaverOn == true,
            enabled = hasPrivilege && !isChanging,
            busy = isChanging,
            onCheckedChange = onSetDataSaver
        )
        if (!hasPrivilege) {
            RequirementNotice(
                "Data Saver needs root or Shizuku. Android does not let a normal app change it. " +
                    "The VPN switch above still works without either."
            )
        }

        // --- Live state, or an honest gap where a reading should be ---
        when {
            status == null -> Text("Checking…", fontSize = 12.sp, color = Color.Gray)
            else -> {
                Text(
                    when (status.dataSaverOn) {
                        true -> "On"
                        false -> "Off"
                        null -> "Your phone would not tell us if this is on or off"
                    },
                    fontSize = 11.sp,
                    color = if (status.dataSaverOn == null) Color(0xFFFFB74D) else MaterialTheme.colorScheme.primary
                )
                if (engaged) {
                    Text(
                        "Turned on by Catsmoker — turning it off puts your phone back how it was.",
                        fontSize = 11.sp, color = Color.Gray
                    )
                }
                Text(
                    when (status.meteredNow) {
                        true -> "This network counts as mobile data, so Data Saver is working right now."
                        false -> "You are on normal Wi-Fi, so Data Saver does nothing here. Use the VPN switch instead."
                        null -> "We could not tell what kind of network you are on."
                    },
                    fontSize = 11.sp,
                    color = if (status.meteredNow == false) Color(0xFFFFB74D) else Color.Gray
                )
                if (status.perAppSupported) {
                    Text(
                        if (status.restrictedPackages.isEmpty()) {
                            "No app is stopped one by one yet."
                        } else {
                            "Stopped one by one (${status.restrictedUids.size}): " +
                                status.restrictedPackages.joinToString()
                        },
                        fontSize = 11.sp,
                        color = if (status.restrictedPackages.isEmpty()) Color.Gray else MaterialTheme.colorScheme.primary
                    )
                } else if (status.privileged) {
                    Text(
                        "Your phone's version cannot stop apps one by one, so only the whole-phone " +
                            "Data Saver setting is available here.",
                        fontSize = 11.sp,
                        color = Color(0xFFFFB74D)
                    )
                }
                if (status.exemptedPackages.isNotEmpty()) {
                    Text(
                        "Always allowed (${status.exemptedPackages.size}): ${status.exemptedPackages.joinToString()}",
                        fontSize = 11.sp, color = Color.Gray
                    )
                }
            }
        }
        ExplainerBox(
            title = "How does Data Saver work?",
            lines = listOf(
                "It uses the same setting you would find in Android's own settings, and asks your phone " +
                    "to turn off background internet for other apps. Your games are added to the " +
                    "\"always allowed\" list first, so they keep working.",
                "Every change is read back from your phone afterwards, so what you see above is your " +
                    "phone's answer, not a guess by this app.",
                "It only works on mobile data, or Wi-Fi you have marked as costing money. On normal " +
                    "Wi-Fi it does nothing — the VPN switch is the one that works everywhere."
            )
        )
        ExplainerBox(
            title = "Which should I use?",
            accent = Color(0xFFFFB300),
            lines = listOf(
                "On mobile data: Data Saver alone is enough, and it costs no battery.",
                "On Wi-Fi: use the VPN switch — Data Saver will not do anything.",
                "Want the strongest setting: turn both on.",
                "Neither one blocks the app you are actually looking at. That is on purpose."
            )
        )
    }
}

/**
 * One labelled on/off row for [BackgroundDataContent].
 *
 * The switch is driven by real state only — `vpnState.running` and the read-back Data Saver value —
 * so it never moves on tap alone. [busy] shows a spinner in place of the switch while the operation
 * is in flight, which is what stops a second tap racing the first.
 */
@Composable
private fun NetworkBlockSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    busy: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = if (enabled) Color.White else Color.Gray
            )
            Text(subtitle, fontSize = 10.sp, color = Color.Gray, lineHeight = 14.sp)
        }
        Spacer(modifier = Modifier.width(8.dp))
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        }
    }
}

/**
 * Every setting that lives in Android's Developer Options, gathered into one card.
 *
 * These used to be three separate cards in two different sections — "Custom Animator" under
 * performance, "Discard Activities" and "Process Limit" under advanced tools, and a
 * "Developer Options: Games" card in between — which presented one screen's worth of platform
 * switches as unrelated features.
 *
 * Each row shows the state the device reported, the reason a control is unavailable when it is, and
 * what the switch actually does — the mechanism, not a marketing line. A [GameDeveloperOptions
 * .ToggleState] with a null `enabled` is a setting the device would not answer for; it renders as
 * unknown and its switch cannot be moved, because moving it would be guessing.
 */
@Composable
fun DeveloperOptionsContent(
    animationScales: Triple<Float, Float, Float>,
    canWriteGlobalSettings: Boolean,
    alwaysFinishActivities: Boolean,
    backgroundProcessLimit: Boolean,
    hasPrivilege: Boolean,
    gameDevOptions: GameDeveloperOptions.State,
    onSetAnimationScale: (AnimationScaleKind, Float) -> Unit,
    onToggleAlwaysFinish: (Boolean) -> Unit,
    onToggleBackgroundLimit: (Boolean) -> Unit,
    onSetShowRefreshRate: (Boolean) -> Unit,
    onSetForcePeakRefreshRate: (Boolean) -> Unit,
    onSetGameDefaultFrameRateDisabled: (Boolean) -> Unit,
    /**
     * Opens Android's own Developer options screen.
     *
     * The route launches this with a result callback that re-reads every setting on this card, so a
     * change the user makes over there shows up here when they come back.
     */
    onOpenDeveloperOptions: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // --- Animation scales ---
        Text("Animation speed", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
        ExplainerBox(
            title = "What is this?",
            lines = listOf(
                "These control how fast your phone's menus slide and fade. 0.5× makes them twice as " +
                    "fast, and Off removes them completely, so the phone feels snappier.",
                "It does not change anything inside a game — only the screens around it."
            )
        )
        if (!canWriteGlobalSettings) {
            RequirementNotice(
                "Needs root or Shizuku. Android does not let a normal app change these."
            )
        }
        AnimationScaleRow("Windows", AnimationScaleKind.WINDOW, animationScales.first, canWriteGlobalSettings, onSetAnimationScale)
        AnimationScaleRow("Screen changes", AnimationScaleKind.TRANSITION, animationScales.second, canWriteGlobalSettings, onSetAnimationScale)
        AnimationScaleRow("Everything else", AnimationScaleKind.ANIMATOR, animationScales.third, canWriteGlobalSettings, onSetAnimationScale)

        HorizontalDivider(color = Color.White.copy(alpha = 0.05f))

        // --- Process limit and discard activities ---
        DevSwitchRow(
            label = "Keep fewer apps in memory",
            checked = backgroundProcessLimit,
            enabled = hasPrivilege,
            onCheckedChange = onToggleBackgroundLimit,
            explanation = listOf(
                "Normally your phone keeps lots of apps sitting in memory so they reopen quickly. This " +
                    "asks it to keep only one, which leaves more memory free for your game.",
                "The cost: other apps have to start from scratch when you go back to them."
            )
        )

        HorizontalDivider(color = Color.White.copy(alpha = 0.05f))

        DevSwitchRow(
            label = "Close apps as soon as you leave",
            checked = alwaysFinishActivities,
            enabled = hasPrivilege,
            onCheckedChange = onToggleAlwaysFinish,
            explanation = listOf(
                "The stronger version of the setting above. The moment you leave an app's screen, your " +
                    "phone throws it away instead of holding on to it. That frees up the most memory.",
                "The cost: apps you go back to start over from the beginning, and you lose whatever " +
                    "you had on screen. Good before a big game, worth turning off after."
            )
        )

        HorizontalDivider(color = Color.White.copy(alpha = 0.05f))

        DevOptionSwitchRow(
            label = "Show refresh rate",
            state = gameDevOptions.showRefreshRate,
            onCheckedChange = onSetShowRefreshRate,
            onOpenDeveloperOptions = onOpenDeveloperOptions,
            explanation = listOf(
                "Puts a small number in the corner of your screen showing how many times per second " +
                    "the screen is redrawing. Handy for checking your phone really is running at its " +
                    "fastest.",
                "Android keeps this one locked away, so this app can only switch it for you if you " +
                    "have root or Shizuku. If you do not, the button turns on Android's own screen " +
                    "where you can flip it yourself — look for \"Show refresh rate\"."
            )
        )

        HorizontalDivider(color = Color.White.copy(alpha = 0.05f))

        DevOptionSwitchRow(
            label = "Always use the fastest screen speed",
            state = gameDevOptions.forcePeakRefreshRate,
            onCheckedChange = onSetForcePeakRefreshRate,
            onOpenDeveloperOptions = onOpenDeveloperOptions,
            explanation = listOf(
                "Phones slow the screen down to save battery when nothing much is moving. This stops " +
                    "that, so the screen always runs at its fastest and feels smoother.",
                "The cost: it uses more battery while it is on. Turning it off puts back whatever " +
                    "your phone had before."
            )
        )

        HorizontalDivider(color = Color.White.copy(alpha = 0.05f))

        DevOptionSwitchRow(
            label = "Let games run at full speed",
            state = gameDevOptions.gameDefaultFrameRateDisabled,
            onCheckedChange = onSetGameDefaultFrameRateDisabled,
            onOpenDeveloperOptions = onOpenDeveloperOptions,
            explanation = listOf(
                "Android puts a speed limit on games to save battery. This removes it, so a game can " +
                    "use your screen's full speed.",
                "It only affects games you open afterwards. A game already running keeps the old limit " +
                    "until you restart it — that part is Android's doing, not something this app can " +
                    "change."
            )
        )

        HorizontalDivider(color = Color.White.copy(alpha = 0.05f))

        // Always offered, not only when a switch has failed: several of these live on Android's own
        // Developer options screen, and a user who wants to check or change one directly should not
        // have to hunt for it.
        OutlinedButton(onClick = onOpenDeveloperOptions, modifier = Modifier.fillMaxWidth()) {
            Text("Open Android's developer settings")
        }
    }
}

/**
 * A plain switch for a setting this app writes and reads back itself, with its explanation tucked away.
 *
 * Distinct from [DevOptionSwitchRow], which renders a [GameDeveloperOptions.ToggleState] and therefore
 * has an availability and an unknown state to show. These two settings are simple booleans held in
 * `Settings.Global`, so there is nothing extra to report beyond whether a write channel exists.
 */
@Composable
private fun DevSwitchRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    explanation: List<String>,
    onCheckedChange: (Boolean) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    label,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (enabled) Color.White else Color.Gray
                )
                Text(
                    if (checked) "On" else "Off",
                    fontSize = 11.sp,
                    color = if (checked) MaterialTheme.colorScheme.primary else Color.Gray
                )
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        }
        if (!enabled) {
            RequirementNotice("Needs root or Shizuku. Android does not let a normal app change this.")
        }
        ExplainerBox(title = "What is this?", lines = explanation)
    }
}

/**
 * One Developer Options switch, rendering the device's answer rather than a local guess.
 *
 * The switch is disabled when the setting is unavailable or its state is unknown, and the reason is
 * printed underneath — an unavailable setting is reported, never silently accepted. It also never moves
 * on tap: the ViewModel only publishes a new state after the write was read back, so a rejected write
 * leaves the switch exactly where it was.
 *
 * When [GameDeveloperOptions.ToggleState.openDeveloperOptions] is set, the setting is one Android will
 * not let this app write but the user can set themselves, so the row offers the way there instead of
 * only refusing.
 */
@Composable
private fun DevOptionSwitchRow(
    label: String,
    state: GameDeveloperOptions.ToggleState,
    explanation: List<String>,
    onCheckedChange: (Boolean) -> Unit,
    onOpenDeveloperOptions: () -> Unit
) {
    val switchEnabled = state.available && state.enabled != null
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    label,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (switchEnabled) Color.White else Color.Gray
                )
                Text(
                    when {
                        state.enabled == null && !state.available -> "Not available on this phone"
                        state.enabled == null -> "Not sure — your phone would not say"
                        state.enabled == true -> "On"
                        else -> "Off"
                    },
                    fontSize = 11.sp,
                    color = when {
                        state.enabled == null -> Color(0xFFFFB74D)
                        state.enabled == true -> MaterialTheme.colorScheme.primary
                        else -> Color.Gray
                    }
                )
            }
            Switch(
                checked = state.enabled == true,
                onCheckedChange = onCheckedChange,
                // A switch that refuses to move is better than one that moves and changes nothing.
                enabled = switchEnabled
            )
        }
        state.detail?.takeIf { it.isNotBlank() }?.let {
            Text(it, fontSize = 11.sp, color = Color.Gray)
        }
        state.unavailableReason?.let {
            Text(it, fontSize = 11.sp, color = Color(0xFFFFB74D), lineHeight = 15.sp)
        }
        if (state.openDeveloperOptions) {
            OutlinedButton(onClick = onOpenDeveloperOptions, modifier = Modifier.fillMaxWidth()) {
                Text("Turn it on in Android settings")
            }
        }
        if (state.available && state.enabled == null) {
            Text(
                "Your phone would not tell us if this is on or off, so it is being left alone.",
                fontSize = 11.sp, color = Color(0xFFFFB74D)
            )
        }
        ExplainerBox(title = "What is this?", lines = explanation)
    }
}

/**
 * The Private DNS card.
 *
 * It reports the resolvers the active network is *using*, not the ones this app asked for, because the
 * first is evidence and the second is only an intention. The previous version of this card wrote
 * `net.dns1`/`net.dns2` — properties nothing on Android has read since version 5 — so both buttons
 * reported success and changed nothing at all; see [DnsFeature] for the full account.
 */
@Composable
fun DnsContent(
    status: DnsFeature.Status?,
    isChanging: Boolean,
    onRefresh: () -> Unit,
    onApplyProvider: (DnsFeature.Provider) -> Unit,
    onSetAutomatic: () -> Unit,
    onDisable: () -> Unit
) {
    // Private DNS is equally settable from Settings, so the reading is refreshed on open rather than
    // trusted from whenever this app last wrote it.
    LaunchedEffect(Unit) { onRefresh() }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (isChanging) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Applying…", fontSize = 12.sp, color = Color.Gray)
            }
        }

        when {
            status == null -> Text("Checking what your phone is using…", fontSize = 12.sp, color = Color.Gray)
            !status.supported -> Text(
                status.unsupportedReason ?: "Your phone does not have this setting",
                fontSize = 12.sp, color = Color(0xFFEF9A9A)
            )
            else -> {
                Text(
                    "Now using: ${status.mode?.label ?: status.rawMode ?: "could not be read"}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (status.mode == null) Color(0xFFFFB74D) else MaterialTheme.colorScheme.primary
                )
                status.hostname?.let {
                    Text("Set to: $it", fontSize = 11.sp, color = Color.Gray)
                }
                // The one line that proves anything happened. If it does not change after applying a
                // provider, the change did not take, whatever the toast said.
                Text(
                    if (status.activeServers.isEmpty()) {
                        "Your phone did not report which one it is using"
                    } else {
                        "Really in use right now: ${status.activeServers.joinToString()}"
                    },
                    fontSize = 11.sp,
                    color = if (status.activeServers.isEmpty()) Color(0xFFFFB74D) else Color.LightGray
                )
                status.validatedPrivateDns?.let {
                    Text("Scrambled and confirmed with: $it", fontSize = 11.sp, color = Color(0xFF81C784))
                }
                if (status.vpnActive) {
                    Text(
                        "A VPN is on. While it is on, the VPN chooses this instead, so changes here " +
                            "will not do anything until you turn it off.",
                        fontSize = 11.sp, color = Color(0xFFFFB74D)
                    )
                }
                if (!status.canWrite) {
                    RequirementNotice(
                        "Needs root or Shizuku. Android does not let a normal app change this. " +
                            "You can still change it yourself in Settings → Network & internet → " +
                            "Private DNS."
                    )
                }

                val writable = status.canWrite && !isChanging
                Spacer(modifier = Modifier.height(2.dp))
                // Chips rather than buttons: one of these is the current state, and a chip can show
                // which without a separate label.
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DnsFeature.PROVIDERS.forEach { provider ->
                        FilterChip(
                            selected = status.hostname == provider.hostname &&
                                status.mode == DnsFeature.Mode.PROVIDER,
                            onClick = { onApplyProvider(provider) },
                            enabled = writable,
                            label = { Text(provider.label, fontSize = 12.sp) }
                        )
                    }
                    FilterChip(
                        selected = status.mode == DnsFeature.Mode.AUTOMATIC,
                        onClick = onSetAutomatic,
                        enabled = writable,
                        label = { Text("Automatic", fontSize = 12.sp) }
                    )
                    FilterChip(
                        selected = status.mode == DnsFeature.Mode.OFF,
                        onClick = onDisable,
                        enabled = writable,
                        label = { Text("Off", fontSize = 12.sp) }
                    )
                }

                val selected = DnsFeature.PROVIDERS.firstOrNull { it.hostname == status.hostname }
                if (selected != null && status.mode == DnsFeature.Mode.PROVIDER) {
                    ExplainerBox(
                        title = "About ${selected.label}",
                        lines = listOf(
                            selected.note,
                            "Its addresses are ${selected.addresses.joinToString()}. If you see those " +
                                "on the \"really in use\" line above, the change worked."
                        )
                    )
                }
            }
        }

        HorizontalDivider(color = Color.White.copy(alpha = 0.05f))

        ExplainerBox(
            title = "What is this?",
            lines = listOf(
                "When your phone opens a website or a game server, it first has to look up its address, " +
                    "a bit like looking up a name in a phone book. This picks which phone book your " +
                    "phone uses.",
                "The ones listed here are fast, free and private, and they scramble the lookup so the " +
                    "Wi-Fi you are on cannot read it or send you somewhere else.",
                "\"Automatic\" lets your phone decide. \"Off\" goes back to whatever your Wi-Fi or SIM " +
                    "hands out."
            )
        )

        ExplainerBox(
            title = "Will this lower my ping?",
            accent = Color(0xFFFFB300),
            lines = listOf(
                "No, and nothing that changes this can. Ping is how long your game's messages take " +
                    "once it is already connected, and the phone book is not used any more by then.",
                "What it does make faster is the waiting *before* something connects — a game " +
                    "starting, a match being found, a page loading.",
                "An older version of this app promised lower ping here and actually changed nothing " +
                    "at all. This one says what it really does."
            )
        )
    }
}

@Composable
fun BoostContent(level: Int, outputDevice: String?, onLevelChange: (Int) -> Unit) {
    // Local drag state: committing on every pixel would rebuild the audio effect chain and
    // write SharedPreferences dozens of times per gesture.
    var draft by remember(level) { mutableFloatStateOf(level.toFloat()) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Extra volume: ${draft.toInt()}%", color = MaterialTheme.colorScheme.primary)
        Slider(
            value = draft,
            onValueChange = { draft = it },
            onValueChangeFinished = { onLevelChange(draft.toInt()) },
            valueRange = 0f..100f
        )
        // Read from the audio system, not guessed, so it names whatever is actually playing.
        if (outputDevice != null) {
            Text(
                "Playing through: $outputDevice",
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray
            )
        }
        ExplainerBox(
            title = "What is this?",
            lines = listOf(
                "It makes sound louder than your phone's own maximum, so quiet footsteps in a game " +
                    "are easier to hear.",
                "It works while this app is running. Set it back to 0% to turn it off."
            )
        )
        ExplainerBox(
            title = "Is it safe?",
            accent = Color(0xFFFFB300),
            lines = listOf(
                "Loud sound in headphones can hurt your ears, so start low and only turn it up as " +
                    "much as you need.",
                "Very high settings can also make the sound crackle, because there is only so much " +
                    "room before it distorts."
            )
        )
    }
}

/**
 * One animation scale, as a label and three chips on a single line.
 *
 * The values offered are off, 0.5× and 1×. Developer Options itself also offers 1.5×, 2×, 5× and 10×,
 * but every one of those makes the UI slower than stock, which is the opposite of what anyone opens
 * this card for — so they are not listed. They are written straight to
 * `Settings.Global.WINDOW_ANIMATION_SCALE` / `TRANSITION_ANIMATION_SCALE` / `ANIMATOR_DURATION_SCALE`,
 * so a change here is the same change the system screen makes, applied immediately, and the engine
 * verifies each write by reading the setting back — a refused write is reported, not assumed to work.
 */
@Composable
private fun AnimationScaleRow(
    label: String,
    kind: AnimationScaleKind,
    current: Float,
    canWrite: Boolean,
    onSetScale: (AnimationScaleKind, Float) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.width(92.dp)) {
            Text(label, fontSize = 12.sp, color = Color.White)
            // A value another app set that is not one of the three is shown as it is, not snapped onto
            // the nearest chip — the chips would otherwise misreport what the system currently holds.
            if (ANIMATION_SCALE_VALUES.none { kotlin.math.abs(it - current) < 0.005f }) {
                Text(formatAnimationScale(current), fontSize = 10.sp, color = Color(0xFFFFB74D))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ANIMATION_SCALE_VALUES.forEach { value ->
                FilterChip(
                    selected = kotlin.math.abs(current - value) < 0.005f,
                    onClick = { onSetScale(kind, value) },
                    enabled = canWrite,
                    label = { Text(if (value == 0f) "Off" else formatAnimationScale(value), fontSize = 11.sp) }
                )
            }
        }
    }
}

/**
 * Off, half, and stock.
 *
 * Developer Options' full set runs to 10×, but everything above 1× only makes the device feel slower;
 * offering those from a card whose purpose is to speed the UI up would be offering a pessimisation.
 */
private val ANIMATION_SCALE_VALUES = listOf(0f, 0.5f, 1f)

private fun formatAnimationScale(value: Float): String =
    if (value % 1f == 0f) "${value.toInt()}x" else "${value}x"

/**
 * The ART compilation card.
 *
 * ## Why there is only one profile now
 *
 * This card used to offer `speed-profile`, `speed` and `everything` as three buttons with no
 * explanation of any of them. Only one has a defensible reason to exist here:
 *
 * - `speed-profile` compiles just the methods ART has already recorded as hot in that app's local
 *   profile. It is what the platform runs by itself, on idle, every night. Choosing it manually
 *   mostly re-does work already done, and on an app you have never opened there is no profile to
 *   compile from, so it does almost nothing.
 * - `speed` compiles every method ahead of time, so nothing has to be JIT-compiled while the app is
 *   running. That removes the compile pauses that show up as stutter in the first minutes of play and
 *   after loading screens. It is the one mode with a real gaming rationale, and it is also the mode
 *   the reference project uses — `cmd package compile -m speed -f <pkg>` — as its only mode.
 * - `everything` compiles every method *including* ones ART deliberately excludes as never-executed
 *   debug and error paths. It takes substantially longer and produces a much larger odex for no
 *   in-game benefit, because those methods do not run.
 *
 * So the picker is gone and the sweep is fixed at `speed`. The force checkbox stays: without `-f` the
 * platform skips any app already in the requested filter, so a second run would report success and
 * compile nothing.
 *
 * Everything shown here comes from [BoosterState], which the engine only advances when a command
 * actually ran and answered: the status line, the counts and the bar all describe the real sweep,
 * and a run that could not start says why instead of showing an empty progress bar.
 */
@Composable
fun AppBoosterContent(
    state: BoosterState,
    log: List<String>,
    onRun: (String, Boolean) -> Unit,
    onStop: () -> Unit
) {
    var force by remember { mutableStateOf(false) }
    Column {
        ExplainerBox(
            title = "What is this?",
            lines = listOf(
                "Apps arrive in a form your phone has to translate as it runs them. This translates " +
                    "them all now instead, so your phone does not have to stop and do it later.",
                "In a game, that is the little stutters in the first few minutes and after each " +
                    "loading screen. This gets them out of the way beforehand.",
                "It does not make your game run faster once it is going. It makes it smoother at the " +
                    "start."
            )
        )
        Spacer(modifier = Modifier.height(6.dp))
        ExplainerBox(
            title = "What does it cost?",
            accent = Color(0xFFFFB300),
            lines = listOf(
                "Space. Translated apps take up more room on your phone than before.",
                "Time and heat. Going through every app takes a while and warms your phone up, so " +
                    "plug it in and leave it. You can press Stop at any point.",
                "Nothing breaks. Your apps keep working exactly the same, and your phone redoes this " +
                    "by itself whenever an app updates."
            )
        )
        Spacer(modifier = Modifier.height(6.dp))
        ExplainerBox(
            title = "Why is there only one setting?",
            lines = listOf(
                "There used to be three. Two of them were not worth offering.",
                "One only redid work your phone already does by itself every night while you sleep.",
                "The other also translated parts of an app that never run, which took much longer and " +
                    "used much more space for no gain. So the useful one is the only one left."
            )
        )
        Spacer(modifier = Modifier.height(8.dp))

        // Without -f the platform skips any app already in the requested filter, so a re-run would
        // report success having compiled nothing. Kept as the reference project's "force optimize".
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = force, onCheckedChange = { force = it }, enabled = !state.isRunning)
            Text(stringResource(R.string.booster_force_label), fontSize = 12.sp, color = Color.Gray)
        }
        Spacer(modifier = Modifier.height(8.dp))

        if (state.isRunning) {
            Button(
                onClick = onStop,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) { Text(stringResource(R.string.booster_stop)) }
        } else {
            Button(onClick = { onRun("speed", force) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.booster_start))
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = boosterStatusText(state),
            fontSize = 12.sp,
            color = if (state.outcome is BoosterOutcome.Unavailable || state.outcome is BoosterOutcome.Failed) {
                MaterialTheme.colorScheme.error
            } else {
                Color.Gray
            }
        )

        if (state.isRunning) {
            Spacer(modifier = Modifier.height(8.dp))
            val progress = state.progress
            if (progress == null) {
                // Still querying the app list: there is no percentage yet, so nothing pretends there is.
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
        }

        if (log.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Box(modifier = Modifier.fillMaxWidth().height(100.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.4f)).padding(8.dp)) {
                val scroll = rememberScrollState()
                LaunchedEffect(log.size) { scroll.scrollTo(scroll.maxValue) }
                Column(modifier = Modifier.verticalScroll(scroll)) {
                    log.forEach { line ->
                        Text(
                            text = line,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = logLineColor(line)
                        )
                    }
                }
            }
        }
    }
}

/** One line saying what the sweep is doing, in counts the engine measured. */
@Composable
private fun boosterStatusText(state: BoosterState): String = when (val outcome = state.outcome) {
    BoosterOutcome.Idle -> stringResource(R.string.booster_status_idle)
    BoosterOutcome.Running -> state.currentPackage?.let { pkg ->
        stringResource(R.string.booster_status_compiling, state.processedCount + 1, state.totalCount, pkg)
    } ?: stringResource(R.string.booster_status_preparing)
    BoosterOutcome.Completed -> stringResource(
        R.string.booster_status_done, state.optimizedCount, state.skippedCount, state.failedCount
    )
    BoosterOutcome.Cancelled -> stringResource(R.string.booster_status_cancelled, state.processedCount)
    // The engine's own words for what the device refused — not a generic failure message.
    is BoosterOutcome.Unavailable -> outcome.reason
    is BoosterOutcome.Failed -> stringResource(R.string.booster_status_failed, outcome.reason)
}

@Composable
fun AppPickerDialog(apps: List<GameInfo>, onDismiss: () -> Unit, onAppSelected: (String) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Add App") }, text = {
        androidx.compose.foundation.lazy.LazyColumn {
            items(apps) { app ->
                Row(modifier = Modifier.fillMaxWidth().clickable { onAppSelected(app.packageName) }.padding(8.dp)) {
                    Text(app.appName, color = Color.White)
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun GamingToolsPreview() {
    CatsmokerTheme {
        GamingToolsScreen(
            uiState = GamingToolsViewModel.UiState(
                isRooted = true,
                isShizukuActive = true,
                games = emptyList()
            ),
            gamingState = GamingModeState.Idle,
            gamingReport = GamingModeReport(),
            isFixedPerformanceMode = false,
            boosterLog = listOf("System optimized", "Cache cleared"),
            boosterState = BoosterState(),
            animationScales = Triple(1f, 1f, 1f),
            alwaysFinishActivities = false,
            backgroundProcessLimit = false,
            gameDevOptions = GameDeveloperOptions.State(),
            onToggleOverlay = {},
            onToggleCrosshair = {},
            onSelectCrosshair = {},
            onSetBackgroundDataRestriction = {},
            onToggleDnd = {},
            onPerformMaintenance = {},
            onScanJunk = {},
            onGrantStorageAccess = {},
            onActivateGamingMode = {},
            onDeactivateGamingMode = {},
            onBoostRam = {},
            onRunBooster = { _, _ -> },
            onStopBooster = {},
            onToggleFixedPerformance = {},
            onBoostChange = {},
            onSetAnimationScale = { _, _ -> },
            onToggleAlwaysFinish = {},
            onToggleBackgroundLimit = {},
            onSetShowRefreshRate = {},
            onSetForcePeakRefreshRate = {},
            onSetGameDefaultFrameRateDisabled = {},
            onOpenDeveloperOptions = {},
            onSetVpnFirewall = {},
            onRefreshDns = {},
            onApplyDnsProvider = {},
            onSetDnsAutomatic = {},
            onDisableDns = {},
            onLaunchGame = {},
            onRemoveGame = {},
            onAddGameClicked = {},
            onToggleAutoForceStop = {},
            onToggleAutoForceStopKeepPackage = {},
            onResWidthChange = {},
            onResHeightChange = {},
            onResDpiChange = {},
            onResOptionSelected = {},
            onApplyResolution = {},
            onResetResolution = {},
            onBack = {},
            onSync = {}
        )
    }
}
