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
import com.catsmoker.app.features.gamingtools.tools.graphics.GameDeveloperOptions
import com.catsmoker.app.features.gamingtools.ui.*
import com.catsmoker.app.shared.util.LogUtils
import com.catsmoker.app.shared.data.model.GameInfo
import com.catsmoker.app.shared.ui.components.*
import com.catsmoker.app.shared.util.DisplayMetricsProvider
import com.catsmoker.app.shared.util.StorageUtils
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
        onResetDefaults = viewModel::resetDefaults,
        onRunBooster = viewModel::runBooster,
        onStopBooster = viewModel::stopBooster,
        onToggleFixedPerformance = viewModel::toggleFixedPerformance,
        onBoostChange = viewModel::onBoostChange,
        onSetAnimationScale = viewModel::setAnimationScale,
        onSetAllAnimationScales = viewModel::setAllAnimationScales,
        onToggleAlwaysFinish = viewModel::toggleAlwaysFinish,
        onToggleBackgroundLimit = viewModel::toggleBackgroundLimit,
        onSetShowRefreshRate = viewModel::setShowRefreshRate,
        onSetForcePeakRefreshRate = viewModel::setForcePeakRefreshRate,
        onSetGameDefaultFrameRateDisabled = viewModel::setGameDefaultFrameRateDisabled,
        onSetAngleDriver = viewModel::setAngleDriver,
        onLaunchGame = viewModel::launchGame,
        onRemoveGame = viewModel::removeGameFromLibrary,
        onAddGameClicked = viewModel::onAddGameClicked,

        onToggleAutoForceStop = viewModel::toggleAutoForceStop,
        onToggleAutoForceStopPackage = viewModel::toggleAutoForceStopPackage,
        
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
    onToggleDnd: (Boolean) -> Unit,
    onPerformMaintenance: (List<CleaningFeature.Category>) -> Unit,
    onScanJunk: () -> Unit,
    onGrantStorageAccess: () -> Unit,
    onActivateGamingMode: () -> Unit,
    onDeactivateGamingMode: () -> Unit,
    onBoostRam: () -> Unit,
    onResetDefaults: () -> Unit,
    onRunBooster: (String, Boolean) -> Unit,
    onStopBooster: () -> Unit,
    onBoostChange: (Int) -> Unit,
    onSetAnimationScale: (AnimationScaleKind, Float) -> Unit,
    onSetAllAnimationScales: (Float) -> Unit,
    onToggleAlwaysFinish: (Boolean) -> Unit,
    onToggleBackgroundLimit: (Boolean) -> Unit,
    onSetShowRefreshRate: (Boolean) -> Unit,
    onSetForcePeakRefreshRate: (Boolean) -> Unit,
    onSetGameDefaultFrameRateDisabled: (Boolean) -> Unit,
    onSetAngleDriver: (String, String?) -> Unit,
    onToggleFixedPerformance: (Boolean) -> Unit,
    onLaunchGame: (String) -> Unit,
    onRemoveGame: (String) -> Unit,
    onAddGameClicked: () -> Unit,

    onToggleAutoForceStop: (Boolean) -> Unit,
    onToggleAutoForceStopPackage: (String) -> Unit,
    
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
            HeroGamingCard(
                gamingState = gamingState,
                report = gamingReport,
                animatedProgress = progressTarget,
                canActivate = true,
                isActive = isActive,
                isBusy = isBusy,
                onActivate = onActivateGamingMode,
                onDeactivate = onDeactivateGamingMode
            )
            Spacer(modifier = Modifier.height(24.dp))
            OptimizationSlidersSection(uiState.isBoostingRam, uiState.ramResult, uiState.isResettingDefaults, uiState.resetResult, onBoostRam, onResetDefaults)
            Spacer(modifier = Modifier.height(24.dp))
            FixedPerformanceModeCard(isFixedPerformanceMode, onToggleFixedPerformance)
            Spacer(modifier = Modifier.height(24.dp))

            // Tools
            Text(stringResource(R.string.gt_section_performance_boost), style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.padding(bottom = 12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ExpandableToolCard(title = "Boost Audio", subtitle = "Amplify system volume.", icon = Icons.AutoMirrored.Filled.VolumeUp) {
                    BoostContent(uiState.boostLevel, uiState.audioOutput, onBoostChange)
                }
                ExpandableToolCard(title = "App Booster", subtitle = "Trigger ART compilation.", icon = Icons.Default.RocketLaunch, enabled = hasPrivilege) {
                    AppBoosterContent(boosterState, boosterLog, onRunBooster, onStopBooster)
                }
                // WRITE_SECURE_SETTINGS granted over adb is enough for these, so this card is not
                // gated on root/Shizuku like the ones that need a shell.
                ExpandableToolCard(title = "Custom Animator", subtitle = "Control animation scales.", icon = Icons.Default.AutoFixHigh, enabled = uiState.canWriteGlobalSettings) {
                    CustomAnimatorContent(
                        scales = animationScales,
                        canWrite = uiState.canWriteGlobalSettings,
                        onSetScale = onSetAnimationScale,
                        onSetAll = onSetAllAnimationScales
                    )
                }
                // Android's own Developer Options gaming switches, driven through the same mechanisms
                // the platform screen uses. Each renders its real availability rather than a switch
                // that moves and does nothing.
                ExpandableToolCard(
                    title = "Developer Options: Games",
                    subtitle = "Frame rate & refresh rate switches.",
                    icon = Icons.Default.HdrStrong
                ) {
                    GameDeveloperOptionsContent(
                        state = gameDevOptions,
                        onSetShowRefreshRate = onSetShowRefreshRate,
                        onSetForcePeakRefreshRate = onSetForcePeakRefreshRate,
                        onSetGameDefaultFrameRateDisabled = onSetGameDefaultFrameRateDisabled
                    )
                }
                ExpandableToolCard(title = "Graphics API", subtitle = "Force specific drivers.", icon = Icons.Default.SettingsInputComponent, enabled = hasPrivilege) {
                    GraphicsDriverContent(uiState.games, uiState.angleSelections, onSetAngleDriver)
                }
                
                ExpandableToolCard(title = "Resolution Changer", subtitle = "Adjust display scaling & DPI.", icon = Icons.Default.AspectRatio) {
                    ResolutionChangerContent(
                        native = uiState.nativeResolution,
                        source = uiState.resolutionSource,
                        activeOverride = uiState.activeOverride,
                        options = uiState.resolutionOptions,
                        selectedOptionId = uiState.selectedResolutionId,
                        width = uiState.widthInput, height = uiState.heightInput, dpi = uiState.dpiInput,
                        validationError = uiState.resValidationError,
                        isApplying = uiState.isApplyingResolution,
                        isRoot = uiState.isRooted, isShizuku = uiState.isShizukuActive,
                        log = uiState.resLog,
                        onOptionSelected = onResOptionSelected,
                        onWidthChange = onResWidthChange, onHeightChange = onResHeightChange, onDpiChange = onResDpiChange,
                        onApply = onApplyResolution, onReset = onResetResolution
                    )
                }

                FeatureToggleCard(title = "FPS Monitor", subtitle = "Real-time overlay.", icon = Icons.Default.BarChart, checked = uiState.isOverlayRunning, onCheckedChange = onToggleOverlay)
                ExpandableToolCard(title = "Crosshair", subtitle = "Precision aim overlay.", icon = Icons.Default.AddCircleOutline, isToggleable = true, isToggled = uiState.isCrosshairRunning, onToggleChange = onToggleCrosshair, forceExpand = uiState.isCrosshairRunning) {
                    CrosshairPicker(uiState.selectedCrosshair, onSelectCrosshair)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text(stringResource(R.string.gt_section_focus_network), style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.padding(bottom = 12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FeatureToggleCard(title = "Gaming DND", subtitle = "Block notifications.", icon = Icons.Default.NotificationsOff, checked = uiState.isDndEnabled, onCheckedChange = onToggleDnd)
                // Named for the mechanism it actually drives. The previous "Network Firewall" card
                // started a VpnService that blackholed packets and reported success either way; this
                // one changes Android's own Data Saver policy, and its body says so.
                ExpandableToolCard(
                    title = "Background Data Restriction",
                    subtitle = "Android Data Saver, with games exempt.",
                    icon = Icons.Default.VpnLock,
                    isToggleable = true,
                    isToggled = uiState.backgroundDataStatus?.dataSaverOn == true,
                    onToggleChange = onSetBackgroundDataRestriction,
                    enabled = hasPrivilege
                ) {
                    BackgroundDataContent(
                        status = uiState.backgroundDataStatus,
                        engaged = uiState.backgroundDataEngaged,
                        isChanging = uiState.isChangingBackgroundData
                    )
                }
                ExpandableToolCard(title = "DNS Optimization", subtitle = "Apply low-latency DNS.", icon = Icons.Default.Dns) { DnsContent() }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text(stringResource(R.string.gt_section_system_advanced), style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.padding(bottom = 12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FeatureToggleCard(title = "Discard Activities", subtitle = "Immediately destroy activities.", icon = Icons.Default.HistoryToggleOff, checked = alwaysFinishActivities, onCheckedChange = onToggleAlwaysFinish, enabled = hasPrivilege)
                FeatureToggleCard(title = "Process Limit", subtitle = "Restrict background processes.", icon = Icons.Default.DataThresholding, checked = backgroundProcessLimit, onCheckedChange = onToggleBackgroundLimit, enabled = hasPrivilege)
                ExpandableToolCard(title = "Auto Force Stop", subtitle = "Hunt background beasts.", icon = Icons.Default.Security, isToggleable = true, isToggled = uiState.isAutoForceStopActive, onToggleChange = onToggleAutoForceStop, enabled = hasPrivilege) {
                    AutoForceStopContent(uiState.allApps, uiState.autoForceStopPackages, onToggleAutoForceStopPackage)
                }
                ExpandableToolCard(title = "System Cleaner", subtitle = "Clear cache & temp files.", icon = Icons.Default.DeleteSweep) {
                    CleaningContent(uiState.isRooted, uiState.isShizukuActive, uiState.maintenanceLog, uiState.scanReport, uiState.isScanningJunk, uiState.isCleaningJunk, onScanJunk, onPerformMaintenance, onGrantStorageAccess)
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

@Composable
fun ExpandableToolCard(title: String, subtitle: String, icon: ImageVector, isToggleable: Boolean = false, isToggled: Boolean = false, enabled: Boolean = true, onToggleChange: (Boolean) -> Unit = {}, forceExpand: Boolean = false, content: @Composable () -> Unit) {
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
            Text("Panel: ${native.label} (sw${native.smallestWidthDp}dp)", fontSize = 12.sp, color = Color.White)
            Text("Read from $source", fontSize = 10.sp, color = Color.Gray)
        } else {
            Text(
                "This device did not report its display size, so no presets can be derived from it.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = activeOverride?.let { "Override active: ${it.label}" } ?: "No override active",
            fontSize = 11.sp,
            color = if (activeOverride != null) MaterialTheme.colorScheme.primary else Color.Gray
        )

        if (options.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text("Preset", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
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
                Text("Applies ${target.label} (sw${target.smallestWidthDp}dp)", fontSize = 10.sp, color = Color.Gray)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = width, onValueChange = onWidthChange, label = { Text("Width") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = validationError != null,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = height, onValueChange = onHeightChange, label = { Text("Height") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = validationError != null,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = dpi, onValueChange = onDpiChange, label = { Text("DPI") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = validationError != null,
            supportingText = validationError?.let { { Text(it, fontSize = 11.sp) } },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = channel?.let { "Applied with $it via `wm size` and `wm density`" }
                ?: "Needs root or Shizuku: `wm` cannot run without one",
            fontSize = 11.sp,
            color = if (channel != null) Color.Gray else MaterialTheme.colorScheme.error
        )
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
                            color = LogUtils.getLogColor(line)
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

@Composable
fun AutoForceStopContent(apps: List<GameInfo>, selected: Set<String>, onToggle: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
        Text("Selected Apps: ${selected.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(8.dp))
        androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.weight(1f)) {
            items(apps) { app ->
                Row(modifier = Modifier.fillMaxWidth().clickable { onToggle(app.packageName) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = selected.contains(app.packageName), onCheckedChange = { onToggle(app.packageName) })
                    Image(bitmap = app.icon.toBitmap().asImageBitmap(), contentDescription = null, modifier = Modifier.size(24.dp).clip(RoundedCornerShape(4.dp)))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(app.appName, color = Color.White, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
fun CleaningContent(
    isRooted: Boolean,
    isShizukuActive: Boolean,
    log: List<String>,
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
        Text("Cleaning Mode: ${if (isRooted) "Root" else if (isShizukuActive) "Shizuku" else "Normal"}", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
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
                    entry != null -> Text(StorageUtils.convertSize(entry.sizeBytes), fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                    report.scannedAnything -> Text("none found", fontSize = 11.sp, color = Color.Gray)
                    else -> Text("not scanned", fontSize = 11.sp, color = Color(0xFFFFB300))
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onScan, modifier = Modifier.weight(1f), enabled = !isScanning && !isCleaning) { Text(if (isScanning) "Scanning..." else "Scan Junk") }
            Button(onClick = { onPerform(selectedCategories.toList()) }, modifier = Modifier.weight(1f), enabled = !isScanning && !isCleaning && selectedCategories.isNotEmpty()) { Text(if (isCleaning) "Cleaning..." else "Clean") }
        }

        if (report != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = when {
                    !report.scannedAnything -> "Storage could not be read — nothing was measured."
                    report.results.isEmpty() -> "Scanned, nothing to clean."
                    else -> "Reclaimable: ${StorageUtils.convertSize(report.totalBytes)} across ${report.totalItems} items"
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
                    Text("What this scan could not reach:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFB300))
                    report.limitations.forEach { limitation ->
                        Text("• $limitation", fontSize = 11.sp, color = Color(0xFFFFB300), lineHeight = 15.sp)
                    }
                }
            }

            if (report.needsAllFilesAccess) {
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = onGrantStorageAccess, modifier = Modifier.fillMaxWidth()) {
                    Text("Grant all-files access")
                }
            }
        }

        if (log.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Box(modifier = Modifier.fillMaxWidth().height(100.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.4f)).padding(10.dp)) {
                val scroll = rememberScrollState()
                LaunchedEffect(log.size) { scroll.scrollTo(scroll.maxValue) }
                Column(modifier = Modifier.verticalScroll(scroll)) {
                    log.forEach { line ->
                        Text(
                            text = line,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = LogUtils.getLogColor(line)
                        )
                    }
                }
            }
        }
    }
}

/**
 * The card body for background data restriction — and the place item 5's questions are answered.
 *
 * Everything here is read from the device: [BackgroundDataRestrictor.Status] carries nulls for
 * anything that could not be read, and those render as an explicit unavailable line rather than a
 * default that would look like a reading.
 */
@Composable
fun BackgroundDataContent(
    status: BackgroundDataRestrictor.Status?,
    engaged: Boolean,
    isChanging: Boolean
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (isChanging) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Changing the network policy…", fontSize = 12.sp, color = Color.Gray)
            }
        }

        // --- Live state, or an honest gap where a reading should be ---
        when {
            status == null -> Text("Reading the current policy…", fontSize = 12.sp, color = Color.Gray)
            else -> {
                Text(
                    when (status.dataSaverOn) {
                        true -> "Data Saver is ON"
                        false -> "Data Saver is OFF"
                        null -> "Data Saver state could not be read on this device"
                    },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (status.dataSaverOn == null) Color(0xFFFFB74D) else MaterialTheme.colorScheme.primary
                )
                if (engaged) {
                    Text(
                        "Turned on by Catsmoker — turning this off restores what the device had before.",
                        fontSize = 11.sp, color = Color.Gray
                    )
                }
                Text(
                    when (status.meteredNow) {
                        true -> "This network is metered, so the restriction applies right now."
                        false -> "This network is NOT metered, so the restriction has no effect on it."
                        null -> "Whether this network is metered could not be read."
                    },
                    fontSize = 11.sp,
                    color = if (status.meteredNow == false) Color(0xFFFFB74D) else Color.Gray
                )
                if (status.exemptedPackages.isNotEmpty()) {
                    Text(
                        "Exempt (${status.exemptedPackages.size}): ${status.exemptedPackages.joinToString()}",
                        fontSize = 11.sp, color = Color.Gray
                    )
                } else if (status.privileged) {
                    Text("Nothing is exempt yet.", fontSize = 11.sp, color = Color.Gray)
                }
                if (!status.privileged) {
                    Text(
                        "Needs root or Shizuku: `cmd netpolicy` is a privileged command, and no public API " +
                            "lets an app change another app's network policy.",
                        fontSize = 11.sp, color = Color(0xFFEF9A9A)
                    )
                }
            }
        }

        HorizontalDivider(color = Color.White.copy(alpha = 0.05f))

        Text("How this works", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text(
            "It drives Android's own Data Saver through `cmd netpolicy` — the shell interface to " +
                "NetworkPolicyManagerService, the same service the Settings app talks to. Enabling it " +
                "runs `cmd netpolicy set restrict-background true`, then " +
                "`cmd netpolicy add restrict-background-whitelist <uid>` for each game in your library so " +
                "the game keeps its network while other apps lose theirs in the background. Both steps " +
                "are read back afterwards, so the state above is the system's answer, not this app's.",
            fontSize = 11.sp, color = Color.Gray
        )

        Text("Why this is not a firewall, and not a VPN", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text(
            "No packet passes through Catsmoker. Nothing is intercepted, inspected, proxied or tunnelled — " +
                "the kernel enforces the policy, and the app only asks the OS to set it. This replaced an " +
                "earlier \"Network Firewall\" that ran a VpnService: it routed 0.0.0.0/0 into a tun " +
                "interface and then never read a packet from it, so traffic was silently blackholed rather " +
                "than filtered, IPv6 leaked past it entirely, and it reported itself active even when " +
                "Android had never granted VPN consent. A local VPN is also the wrong shape for a gaming " +
                "app: copying every packet through a userspace process adds the very latency the app " +
                "exists to reduce.",
            fontSize = 11.sp, color = Color.Gray
        )

        Text("Limits compared with a real VPN firewall", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text(
            "• Metered networks only — mobile data, and Wi-Fi you have marked metered. Ordinary Wi-Fi is " +
                "untouched.\n" +
                "• Background only — an app you are looking at keeps its network.\n" +
                "• Needs root or Shizuku.\n" +
                "• A VPN-based firewall (NetGuard and similar) can block any app on any network because it " +
                "holds the packets itself. It pays the latency cost above, and only one VPN can be active " +
                "on a device at a time.",
            fontSize = 11.sp, color = Color.Gray
        )
    }
}

/**
 * The card body for Android's three Developer Options gaming switches.
 *
 * Each row shows the state the device reported, the reason a control is unavailable when it is, and
 * what the switch actually does — the mechanism, not a marketing line. A [GameDeveloperOptions
 * .ToggleState] with a null `enabled` is a setting the device would not answer for; it renders as
 * unknown and its switch cannot be moved, because moving it would be guessing.
 */
@Composable
fun GameDeveloperOptionsContent(
    state: GameDeveloperOptions.State,
    onSetShowRefreshRate: (Boolean) -> Unit,
    onSetForcePeakRefreshRate: (Boolean) -> Unit,
    onSetGameDefaultFrameRateDisabled: (Boolean) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        DevOptionSwitchRow(
            label = "Show refresh rate",
            state = state.showRefreshRate,
            onCheckedChange = onSetShowRefreshRate,
            explanation = "Draws SurfaceFlinger's own refresh-rate overlay in the corner of the screen, " +
                "so you can see the rate the display is actually running at. It goes through " +
                "SurfaceFlinger's debug transaction 1034 — the same call the platform's Developer " +
                "Options screen makes — and that transaction only answers a privileged shell, which is " +
                "why it needs root or Shizuku."
        )

        HorizontalDivider(color = Color.White.copy(alpha = 0.05f))

        DevOptionSwitchRow(
            label = "Force peak refresh rate",
            state = state.forcePeakRefreshRate,
            onCheckedChange = onSetForcePeakRefreshRate,
            explanation = "Raises the display's minimum refresh rate to the panel's peak, so the screen " +
                "stops dropping to a lower rate between frames. This is Android's own " +
                "`min_refresh_rate` setting, written as a float exactly as Developer Options writes it: " +
                "the display service votes the ceiling as max(min, peak), which is why raising the " +
                "minimum is what forces the rate up. Turning it off puts back whatever the setting held " +
                "before. It costs battery while on."
        )

        HorizontalDivider(color = Color.White.copy(alpha = 0.05f))

        DevOptionSwitchRow(
            label = "Disable default frame rate for games",
            state = state.gameDefaultFrameRateDisabled,
            onCheckedChange = onSetGameDefaultFrameRateDisabled,
            explanation = "Lifts the default frame-rate cap Android applies to games so a game can run at " +
                "the panel's full rate. Android keeps this in the system property " +
                "`debug.graphics.game_default_frame_rate.disabled`; there is no Settings key for it, so " +
                "the property is set directly and read back. Developer Options additionally notifies " +
                "SurfaceFlinger through IGameManagerService in the same step, and no app can call that " +
                "binder interface — so the change here applies to games started afterwards, not to one " +
                "already running."
        )
    }
}

/**
 * One Developer Options switch, rendering the device's answer rather than a local guess.
 *
 * The switch is disabled when the setting is unavailable or its state is unknown, and the reason is
 * printed underneath — an unavailable setting is reported, never silently accepted.
 */
@Composable
private fun DevOptionSwitchRow(
    label: String,
    state: GameDeveloperOptions.ToggleState,
    explanation: String,
    onCheckedChange: (Boolean) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text(
                    when {
                        state.enabled == null && !state.available -> "Unavailable on this device"
                        state.enabled == null -> "State unknown"
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
                enabled = state.available && state.enabled != null
            )
        }
        state.detail?.takeIf { it.isNotBlank() }?.let {
            Text(it, fontSize = 11.sp, color = Color.Gray)
        }
        state.unavailableReason?.let {
            Text(it, fontSize = 11.sp, color = Color(0xFFEF9A9A))
        }
        if (state.available && state.enabled == null) {
            Text(
                "The device would not report a state for this setting, so it is left alone.",
                fontSize = 11.sp, color = Color(0xFFFFB74D)
            )
        }
        Text(explanation, fontSize = 11.sp, color = Color.Gray)
    }
}

@Composable
fun DnsContent() {
    val context = LocalContext.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { DnsFeature.applyRootDns(context, "1.1.1.1", "1.0.0.1") }, modifier = Modifier.weight(1f)) { Text("Cloudflare") }
        OutlinedButton(onClick = { DnsFeature.applyRootDns(context, "8.8.8.8", "8.8.4.4") }, modifier = Modifier.weight(1f)) { Text("Google") }
    }
}

@Composable
fun BoostContent(level: Int, outputDevice: String?, onLevelChange: (Int) -> Unit) {
    // Local drag state: committing on every pixel would rebuild the audio effect chain and
    // write SharedPreferences dozens of times per gesture.
    var draft by remember(level) { mutableFloatStateOf(level.toFloat()) }
    Column {
        Text("Boost Level: ${draft.toInt()}%", color = MaterialTheme.colorScheme.primary)
        Slider(
            value = draft,
            onValueChange = { draft = it },
            onValueChangeFinished = { onLevelChange(draft.toInt()) },
            valueRange = 0f..100f
        )
        if (outputDevice != null) {
            Text(
                "Output: $outputDevice",
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray
            )
        }
    }
}

@Composable
fun GraphicsDriverContent(games: List<GameInfo>, selections: Map<String, String>, onSet: (String, String?) -> Unit) {
    Column {
        games.forEach { game ->
            Text(game.appName, fontWeight = FontWeight.Bold, color = Color.White)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val current = selections[game.packageName]
                Button(onClick = { onSet(game.packageName, null) }, enabled = current != null) { Text("Default") }
                Button(onClick = { onSet(game.packageName, "native") }, enabled = current != "native") { Text("Native") }
                Button(onClick = { onSet(game.packageName, "angle") }, enabled = current != "angle") { Text("ANGLE") }
            }
        }
    }
}

/**
 * The three animation scales from Android's Developer Options.
 *
 * The values offered are that screen's own seven: off, 0.5x, 1x, 1.5x, 2x, 5x, 10x. They are written
 * straight to `Settings.Global.WINDOW_ANIMATION_SCALE` / `TRANSITION_ANIMATION_SCALE` /
 * `ANIMATOR_DURATION_SCALE`, so a change here is the same change the system screen makes. Selecting
 * applies immediately, also as Developer Options does, and the engine verifies each write by reading
 * the setting back — so a refused write is reported instead of appearing to work.
 */
@Composable
fun CustomAnimatorContent(
    scales: Triple<Float, Float, Float>,
    canWrite: Boolean,
    onSetScale: (AnimationScaleKind, Float) -> Unit,
    onSetAll: (Float) -> Unit
) {
    Column {
        if (!canWrite) {
            Text(
                "These are system settings: changing them needs root, Shizuku, or WRITE_SECURE_SETTINGS " +
                    "granted over adb. Values below are the system's current ones.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        AnimationScaleRow(AnimationScaleKind.WINDOW, scales.first, canWrite) { onSetScale(AnimationScaleKind.WINDOW, it) }
        Spacer(modifier = Modifier.height(16.dp))
        AnimationScaleRow(AnimationScaleKind.TRANSITION, scales.second, canWrite) { onSetScale(AnimationScaleKind.TRANSITION, it) }
        Spacer(modifier = Modifier.height(16.dp))
        AnimationScaleRow(AnimationScaleKind.ANIMATOR, scales.third, canWrite) { onSetScale(AnimationScaleKind.ANIMATOR, it) }

        Spacer(modifier = Modifier.height(20.dp))
        Text("Set all three", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // The two people actually reach for: everything off, or everything back to stock.
            OutlinedButton(onClick = { onSetAll(0f) }, enabled = canWrite, modifier = Modifier.weight(1f)) {
                Text("Off (0x)", fontSize = 12.sp)
            }
            OutlinedButton(onClick = { onSetAll(1f) }, enabled = canWrite, modifier = Modifier.weight(1f)) {
                Text("Default (1x)", fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun AnimationScaleRow(
    scale: AnimationScaleKind,
    current: Float,
    canWrite: Boolean,
    onSelect: (Float) -> Unit
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(scale.label, fontSize = 13.sp, color = Color.White, modifier = Modifier.weight(1f))
            Text(formatAnimationScale(current), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
        // A value another app set that is not one of the seven is shown as it is, not snapped onto
        // the nearest chip — the chips would otherwise misreport what the system currently holds.
        if (ANIMATION_SCALE_VALUES.none { kotlin.math.abs(it - current) < 0.005f }) {
            Text(
                "Currently set to a custom value; pick one below to change it.",
                fontSize = 10.sp,
                color = Color.Gray
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ANIMATION_SCALE_VALUES.forEach { value ->
                FilterChip(
                    selected = kotlin.math.abs(current - value) < 0.005f,
                    onClick = { onSelect(value) },
                    enabled = canWrite,
                    label = { Text(if (value == 0f) "Off" else formatAnimationScale(value), fontSize = 11.sp) }
                )
            }
        }
    }
}

/** Developer Options' own set: animation off, then .5x through 10x. */
private val ANIMATION_SCALE_VALUES = listOf(0f, 0.5f, 1f, 1.5f, 2f, 5f, 10f)

private fun formatAnimationScale(value: Float): String =
    if (value % 1f == 0f) "${value.toInt()}x" else "${value}x"

/**
 * The ART compilation card.
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
    var mode by remember { mutableStateOf("speed-profile") }
    var force by remember { mutableStateOf(false) }
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("speed-profile", "speed", "everything").forEach { m ->
                Button(
                    onClick = { mode = m },
                    enabled = !state.isRunning,
                    colors = ButtonDefaults.buttonColors(containerColor = if (mode == m) MaterialTheme.colorScheme.primary else Color.Gray)
                ) { Text(m) }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        // The reference project's "force optimize": recompiles every app instead of letting the
        // platform skip the ones already in the requested filter.
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
            Button(onClick = { onRun(mode, force) }, modifier = Modifier.fillMaxWidth()) {
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
                            color = LogUtils.getLogColor(line)
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
            onResetDefaults = {},
            onRunBooster = { _, _ -> },
            onStopBooster = {},
            onToggleFixedPerformance = {},
            onBoostChange = {},
            onSetAnimationScale = { _, _ -> },
            onSetAllAnimationScales = {},
            onToggleAlwaysFinish = {},
            onToggleBackgroundLimit = {},
            onSetShowRefreshRate = {},
            onSetForcePeakRefreshRate = {},
            onSetGameDefaultFrameRateDisabled = {},
            onSetAngleDriver = { _, _ -> },
            onLaunchGame = {},
            onRemoveGame = {},
            onAddGameClicked = {},

            onToggleAutoForceStop = {},
            onToggleAutoForceStopPackage = {},
            
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
