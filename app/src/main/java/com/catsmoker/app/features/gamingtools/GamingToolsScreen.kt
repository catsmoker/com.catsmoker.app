package com.catsmoker.app.features.gamingtools

import android.content.Intent
import android.graphics.BitmapFactory
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.catsmoker.app.R
import com.catsmoker.app.features.gamingtools.engine.GamingModeState
import com.catsmoker.app.features.gamingtools.tools.cleaner.CleaningFeature
import com.catsmoker.app.features.gamingtools.tools.dns.DnsFeature
import com.catsmoker.app.features.gamingtools.ui.*
import com.catsmoker.app.shared.data.model.GameInfo
import com.catsmoker.app.shared.ui.components.*
import com.catsmoker.app.shared.util.StorageUtils
import com.catsmoker.app.shared.ui.theme.CatsmokerTheme

@Composable
fun GamingToolsRoute(onNavigate: (String) -> Unit, onBack: () -> Unit) {
    val viewModel: GamingToolsViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    val gamingState by viewModel.gamingState.collectAsState()
    val isFixedPerformanceMode by viewModel.isFixedPerformanceMode.collectAsState()
    val boosterLog by viewModel.boosterLog.collectAsState()
    val boosterProgress by viewModel.boosterProgress.collectAsState()
    val animationScales by viewModel.animationScales.collectAsState()
    val alwaysFinishActivities by viewModel.alwaysFinishActivities.collectAsState()
    val backgroundProcessLimit by viewModel.backgroundProcessLimit.collectAsState()
    val refreshRateLock by viewModel.refreshRateLock.collectAsState()

    val overlayLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.syncState()
    }

    LaunchedEffect(Unit) {
        viewModel.toasts.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    GamingToolsScreen(
        uiState = uiState,
        gamingState = gamingState,
        isFixedPerformanceMode = isFixedPerformanceMode,
        boosterLog = boosterLog,
        boosterProgress = boosterProgress,
        animationScales = animationScales,
        alwaysFinishActivities = alwaysFinishActivities,
        backgroundProcessLimit = backgroundProcessLimit,
        refreshRateLock = refreshRateLock,
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
        onToggleVpn = { enable -> if (enable) viewModel.startVpnServiceInternal() else viewModel.stopVpn() },
        onToggleDnd = { enable ->
            if (!viewModel.toggleDnd(enable)) {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            }
        },
        onPerformMaintenance = viewModel::onPerformMaintenance,
        onScanJunk = viewModel::scanForJunk,
        onActivateGamingMode = viewModel::activateGamingMode,
        onDeactivateGamingMode = viewModel::deactivateGamingMode,
        onBoostRam = viewModel::boostRam,
        onResetDefaults = viewModel::resetDefaults,
        onRunBooster = viewModel::runBooster,
        onStopBooster = viewModel::stopBooster,
        onToggleFixedPerformance = viewModel::toggleFixedPerformance,
        onBoostChange = viewModel::onBoostChange,
        onSetAnimationScales = viewModel::setAnimationScales,
        onToggleAlwaysFinish = viewModel::toggleAlwaysFinish,
        onToggleBackgroundLimit = viewModel::toggleBackgroundLimit,
        onToggleRefreshRateLock = viewModel::toggleRefreshRateLock,
        onSetAngleDriver = viewModel::setAngleDriver,
        onLaunchGame = viewModel::launchGame,
        onRemoveGame = viewModel::removeGameFromLibrary,
        onAddGameClicked = viewModel::onAddGameClicked,
        
        onToggleFancyIme = viewModel::toggleFancyIme,
        onToggleClockSeconds = viewModel::toggleClockSeconds,
        onToggleAutoForceStop = viewModel::toggleAutoForceStop,
        onToggleAutoForceStopPackage = viewModel::toggleAutoForceStopPackage,
        
        onResWidthChange = viewModel::onResWidthChange,
        onResHeightChange = viewModel::onResHeightChange,
        onResDpiChange = viewModel::onResDpiChange,
        onResMethodSelected = viewModel::onResMethodSelected,
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
    
    if (uiState.showResWarning) {
        AlertDialog(
            onDismissRequest = viewModel::dismissResWarning,
            title = { Text("Extreme Resolution!") },
            text = { Text("The values you entered are significantly different from defaults. Proceed?") },
            confirmButton = { TextButton(onClick = viewModel::confirmResWarning) { Text("CONTINUE") } },
            dismissButton = { TextButton(onClick = viewModel::dismissResWarning) { Text("CANCEL") } }
        )
    }
}

@Composable
fun GamingToolsScreen(
    uiState: GamingToolsViewModel.UiState,
    gamingState: GamingModeState,
    isFixedPerformanceMode: Boolean,
    boosterLog: List<String>,
    boosterProgress: Float,
    animationScales: Triple<Float, Float, Float>,
    alwaysFinishActivities: Boolean,
    backgroundProcessLimit: Boolean,
    refreshRateLock: Boolean,
    onToggleOverlay: (Boolean) -> Unit,
    onToggleCrosshair: (Boolean) -> Unit,
    onSelectCrosshair: (String) -> Unit,
    onToggleVpn: (Boolean) -> Unit,
    onToggleDnd: (Boolean) -> Unit,
    onPerformMaintenance: (List<CleaningFeature.Category>) -> Unit,
    onScanJunk: () -> Unit,
    onActivateGamingMode: () -> Unit,
    onDeactivateGamingMode: () -> Unit,
    onBoostRam: () -> Unit,
    onResetDefaults: () -> Unit,
    onRunBooster: (String, Boolean) -> Unit,
    onStopBooster: () -> Unit,
    onBoostChange: (Int) -> Unit,
    onSetAnimationScales: (Float, Float, Float) -> Unit,
    onToggleAlwaysFinish: (Boolean) -> Unit,
    onToggleBackgroundLimit: (Boolean) -> Unit,
    onToggleRefreshRateLock: (Boolean) -> Unit,
    onSetAngleDriver: (String, String?) -> Unit,
    onToggleFixedPerformance: (Boolean) -> Unit,
    onLaunchGame: (String) -> Unit,
    onRemoveGame: (String) -> Unit,
    onAddGameClicked: () -> Unit,

    onToggleFancyIme: (Boolean) -> Unit,
    onToggleClockSeconds: (Boolean) -> Unit,
    onToggleAutoForceStop: (Boolean) -> Unit,
    onToggleAutoForceStopPackage: (String) -> Unit,
    
    onResWidthChange: (String) -> Unit,
    onResHeightChange: (String) -> Unit,
    onResDpiChange: (String) -> Unit,
    onResMethodSelected: (Int) -> Unit,
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
    val animatedProgress by animateFloatAsState(targetValue = progressTarget, animationSpec = tween(300))
    
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
            HeroGamingCard(gamingState, animatedProgress, true, isActive, isBusy, onActivateGamingMode, onDeactivateGamingMode)
            Spacer(modifier = Modifier.height(24.dp))
            OptimizationSlidersSection(uiState.isBoostingRam, uiState.showRamResult, uiState.isResettingDefaults, uiState.showResetResult, onBoostRam, onResetDefaults)
            Spacer(modifier = Modifier.height(24.dp))
            FixedPerformanceModeCard(isFixedPerformanceMode, onToggleFixedPerformance)
            Spacer(modifier = Modifier.height(24.dp))

            // Tools
            Text(stringResource(R.string.gt_section_performance_boost), style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.padding(bottom = 12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ExpandableToolCard(title = "Boost Audio", subtitle = "Amplify system volume.", icon = Icons.AutoMirrored.Filled.VolumeUp) {
                    BoostContent(uiState.boostLevel, onBoostChange)
                }
                ExpandableToolCard(title = "App Booster", subtitle = "Trigger ART compilation.", icon = Icons.Default.RocketLaunch, enabled = hasPrivilege) {
                    AppBoosterContent(boosterLog, boosterProgress, onRunBooster, onStopBooster)
                }
                ExpandableToolCard(title = "Custom Animator", subtitle = "Control animation scales.", icon = Icons.Default.AutoFixHigh, enabled = hasPrivilege) {
                    CustomAnimatorContent(animationScales, onSetAnimationScales)
                }
                FeatureToggleCard(title = "Refresh Rate Lock", subtitle = "Force max display Hz.", icon = Icons.Default.HdrStrong, checked = refreshRateLock, onCheckedChange = onToggleRefreshRateLock, enabled = hasPrivilege)
                ExpandableToolCard(title = "Graphics API", subtitle = "Force specific drivers.", icon = Icons.Default.SettingsInputComponent, enabled = hasPrivilege) {
                    GraphicsDriverContent(uiState.games, uiState.angleSelections, onSetAngleDriver)
                }
                
                ExpandableToolCard(title = "Resolution Changer", subtitle = "Adjust display scaling & DPI.", icon = Icons.Default.AspectRatio) {
                    ResolutionChangerContent(
                        width = uiState.widthInput, height = uiState.heightInput, dpi = uiState.dpiInput,
                        method = uiState.resMethod, log = uiState.resLog,
                        isRoot = uiState.isRooted, isShizuku = uiState.isShizukuActive,
                        onWidthChange = onResWidthChange, onHeightChange = onResHeightChange, onDpiChange = onResDpiChange,
                        onMethodSelected = onResMethodSelected, onApply = onApplyResolution, onReset = onResetResolution
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
                FeatureToggleCard(title = "Network Firewall", subtitle = "Blocks background traffic.", icon = Icons.Default.VpnLock, checked = uiState.isVpnRunning, onCheckedChange = onToggleVpn)
                ExpandableToolCard(title = "DNS Optimization", subtitle = "Apply low-latency DNS.", icon = Icons.Default.Dns) { DnsContent() }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text(stringResource(R.string.gt_section_system_advanced), style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.padding(bottom = 12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FeatureToggleCard(title = "Discard Activities", subtitle = "Immediately destroy activities.", icon = Icons.Default.HistoryToggleOff, checked = alwaysFinishActivities, onCheckedChange = onToggleAlwaysFinish, enabled = hasPrivilege)
                FeatureToggleCard(title = "Process Limit", subtitle = "Restrict background processes.", icon = Icons.Default.DataThresholding, checked = backgroundProcessLimit, onCheckedChange = onToggleBackgroundLimit, enabled = hasPrivilege)
                FeatureToggleCard(title = "Fancy IME", subtitle = "Smooth keyboard animations.", icon = Icons.Default.Keyboard, checked = uiState.fancyImeAnimations, onCheckedChange = { onToggleFancyIme(!it) }, enabled = hasPrivilege)
                FeatureToggleCard(title = "Clock Seconds", subtitle = "Show seconds in status bar.", icon = Icons.Default.AccessTime, checked = uiState.clockSeconds, onCheckedChange = onToggleClockSeconds, enabled = hasPrivilege)
                ExpandableToolCard(title = "Auto Force Stop", subtitle = "Hunt background beasts.", icon = Icons.Default.Security, isToggleable = true, isToggled = uiState.isAutoForceStopActive, onToggleChange = onToggleAutoForceStop, enabled = hasPrivilege) {
                    AutoForceStopContent(uiState.allApps, uiState.autoForceStopPackages, onToggleAutoForceStopPackage)
                }
                ExpandableToolCard(title = "System Cleaner", subtitle = "Clear cache & temp files.", icon = Icons.Default.DeleteSweep) {
                    CleaningContent(uiState.isRooted, uiState.isShizukuActive, uiState.maintenanceLog, uiState.scanResults, uiState.isScanningJunk, uiState.isCleaningJunk, onScanJunk, onPerformMaintenance)
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
                Text(text = game.playTime ?: "Optimized", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontSize = 10.sp)
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
        Column(modifier = Modifier.fillMaxWidth().animateContentSize()) {
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

@Composable
fun ResolutionChangerContent(
    width: String, height: String, dpi: String,
    method: Int, log: List<String>,
    isRoot: Boolean, isShizuku: Boolean,
    onWidthChange: (String) -> Unit, onHeightChange: (String) -> Unit, onDpiChange: (String) -> Unit,
    onMethodSelected: (Int) -> Unit, onApply: () -> Unit, onReset: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Apply Method", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ResMethodButton("ROOT", method == 0, isRoot, Modifier.weight(1f)) { onMethodSelected(0) }
            ResMethodButton("SHIZUKU", method == 1, isShizuku, Modifier.weight(1f)) { onMethodSelected(1) }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(value = width, onValueChange = onWidthChange, label = { Text("Width") }, modifier = Modifier.weight(1f))
            OutlinedTextField(value = height, onValueChange = onHeightChange, label = { Text("Height") }, modifier = Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(value = dpi, onValueChange = onDpiChange, label = { Text("DPI") }, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onApply, modifier = Modifier.weight(1f)) { Text("Apply") }
            OutlinedButton(onClick = onReset, modifier = Modifier.weight(0.6f)) { Text("Reset") }
        }
        if (log.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Box(modifier = Modifier.fillMaxWidth().height(100.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.4f)).padding(8.dp)) {
                val scroll = rememberScrollState()
                LaunchedEffect(log.size) { scroll.animateScrollTo(scroll.maxValue) }
                Text(log.joinToString("\n"), fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 10.sp, color = Color(0xFF22C55E), modifier = Modifier.verticalScroll(scroll))
            }
        }
    }
}

@Composable
fun ResMethodButton(label: String, selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.height(40.dp).clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled) { onClick() },
        color = if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.05f),
        border = if (!selected && enabled) BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)) else null
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, color = if (selected) Color.White else if (enabled) Color.LightGray else Color.DarkGray, fontWeight = FontWeight.Bold, fontSize = 11.sp)
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
fun CleaningContent(isRooted: Boolean, isShizukuActive: Boolean, log: List<String>, scanResults: Map<CleaningFeature.Category, CleaningFeature.ScanResult>, isScanning: Boolean, isCleaning: Boolean, onScan: () -> Unit, onPerform: (List<CleaningFeature.Category>) -> Unit) {
    var selectedCategories by remember { mutableStateOf(CleaningFeature.Category.entries.filter { !it.isAggressive }.toSet()) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Cleaning Mode: ${if (isRooted) "Root" else if (isShizukuActive) "Shizuku" else "Normal"}", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(8.dp))
        CleaningFeature.Category.entries.forEach { category ->
            Row(modifier = Modifier.fillMaxWidth().clickable { selectedCategories = if (selectedCategories.contains(category)) selectedCategories - category else selectedCategories + category }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = selectedCategories.contains(category), onCheckedChange = { checked -> selectedCategories = if (checked) selectedCategories + category else selectedCategories - category })
                Text(category.label, modifier = Modifier.weight(1f), color = if (category.isAggressive) Color.Red else Color.White)
                scanResults[category]?.let { Text(StorageUtils.convertSize(it.sizeBytes), fontSize = 11.sp, color = MaterialTheme.colorScheme.primary) }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onScan, modifier = Modifier.weight(1f), enabled = !isScanning && !isCleaning) { Text(if (isScanning) "Scanning..." else "Scan Junk") }
            Button(onClick = { onPerform(selectedCategories.toList()) }, modifier = Modifier.weight(1f), enabled = !isScanning && !isCleaning && selectedCategories.isNotEmpty()) { Text(if (isCleaning) "Cleaning..." else "Clean") }
        }
        if (log.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Box(modifier = Modifier.fillMaxWidth().height(100.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.4f)).padding(10.dp)) {
                val scroll = rememberScrollState()
                LaunchedEffect(log.size) { scroll.animateScrollTo(scroll.maxValue) }
                Text(log.joinToString("\n"), fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 10.sp, color = Color(0xFF22C55E), modifier = Modifier.verticalScroll(scroll))
            }
        }
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
fun BoostContent(level: Int, onLevelChange: (Int) -> Unit) {
    Column {
        Text("Boost Level: $level%", color = MaterialTheme.colorScheme.primary)
        Slider(value = level.toFloat(), onValueChange = { onLevelChange(it.toInt()) }, valueRange = 0f..100f)
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

@Composable
fun CustomAnimatorContent(scales: Triple<Float, Float, Float>, onApply: (Float, Float, Float) -> Unit) {
    var w by remember(scales) { mutableStateOf(scales.first) }
    var t by remember(scales) { mutableStateOf(scales.second) }
    var a by remember(scales) { mutableStateOf(scales.third) }
    Column {
        Slider(value = w, onValueChange = { w = it }, valueRange = 0f..2f)
        Slider(value = t, onValueChange = { t = it }, valueRange = 0f..2f)
        Slider(value = a, onValueChange = { a = it }, valueRange = 0f..2f)
        Button(onClick = { onApply(w, t, a) }, modifier = Modifier.fillMaxWidth()) { Text("Apply") }
    }
}

@Composable
fun AppBoosterContent(log: List<String>, progress: Float, onRun: (String, Boolean) -> Unit, onStop: () -> Unit) {
    var mode by remember { mutableStateOf("speed-profile") }
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("speed-profile", "speed", "everything").forEach { m ->
                Button(onClick = { mode = m }, colors = ButtonDefaults.buttonColors(containerColor = if (mode == m) MaterialTheme.colorScheme.primary else Color.Gray)) { Text(m) }
            }
        }
        Button(onClick = { onRun(mode, false) }, modifier = Modifier.fillMaxWidth()) { Text("Start") }
        if (progress > 0f) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
    }
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
            isFixedPerformanceMode = false,
            boosterLog = listOf("System optimized", "Cache cleared"),
            boosterProgress = 0f,
            animationScales = Triple(1f, 1f, 1f),
            alwaysFinishActivities = false,
            backgroundProcessLimit = false,
            refreshRateLock = false,
            onToggleOverlay = {},
            onToggleCrosshair = {},
            onSelectCrosshair = {},
            onToggleVpn = {},
            onToggleDnd = {},
            onPerformMaintenance = {},
            onScanJunk = {},
            onActivateGamingMode = {},
            onDeactivateGamingMode = {},
            onBoostRam = {},
            onResetDefaults = {},
            onRunBooster = { _, _ -> },
            onStopBooster = {},
            onToggleFixedPerformance = {},
            onBoostChange = {},
            onSetAnimationScales = { _, _, _ -> },
            onToggleAlwaysFinish = {},
            onToggleBackgroundLimit = {},
            onToggleRefreshRateLock = {},
            onSetAngleDriver = { _, _ -> },
            onLaunchGame = {},
            onRemoveGame = {},
            onAddGameClicked = {},
            
            onToggleFancyIme = {},
            onToggleClockSeconds = {},
            onToggleAutoForceStop = {},
            onToggleAutoForceStopPackage = {},
            
            onResWidthChange = {},
            onResHeightChange = {},
            onResDpiChange = {},
            onResMethodSelected = {},
            onApplyResolution = {},
            onResetResolution = {},
            onBack = {},
            onSync = {}
        )
    }
}
