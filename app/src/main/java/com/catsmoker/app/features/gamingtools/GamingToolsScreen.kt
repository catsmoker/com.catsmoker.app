package com.catsmoker.app.features.gamingtools

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
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
import com.catsmoker.app.features.gamingtools.engine.BoosterRun
import com.catsmoker.app.features.gamingtools.engine.BoosterState
import com.catsmoker.app.features.gamingtools.engine.GamingModeReport
import com.catsmoker.app.features.gamingtools.engine.GamingModeState
import com.catsmoker.app.features.gamingtools.tools.cleaner.CleaningFeature
import com.catsmoker.app.features.gamingtools.tools.booster.DexoptScheduleStore
import com.catsmoker.app.features.gamingtools.tools.cleaner.CleanerScheduleStore
import com.catsmoker.app.features.gamingtools.tools.permops.ToggleablePerm
import com.catsmoker.app.features.gamingtools.tools.dns.DnsFeature
import com.catsmoker.app.features.gamingtools.tools.firewall.BackgroundDataRestrictor
import com.catsmoker.app.features.gamingtools.tools.firewall.VpnFirewall
import com.catsmoker.app.features.gamingtools.tools.graphics.GameDeveloperOptions
import com.catsmoker.app.features.gamingtools.tools.graphics.AngleDriverOptions
import com.catsmoker.app.features.gamingtools.ui.*
import com.catsmoker.app.shared.ui.theme.LogTerminalBackground
import com.catsmoker.app.shared.ui.theme.logLineColor
import com.catsmoker.app.shared.data.model.GameInfo
import com.catsmoker.app.shared.ui.components.*
import com.catsmoker.app.shared.util.DisplayMetricsProvider
import com.catsmoker.app.shared.util.formatBytes
import com.catsmoker.app.shared.ui.theme.CatsmokerTheme

@Composable
fun GamingToolsRoute(onBack: () -> Unit) {
    val viewModel: GamingToolsViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val noDevOptions = stringResource(R.string.gt_dialog_no_dev_options)

    val gamingState by viewModel.gamingState.collectAsState()
    val gamingReport by viewModel.gamingReport.collectAsState()
    val isFixedPerformanceMode by viewModel.isFixedPerformanceMode.collectAsState()
    val boosterLog by viewModel.boosterLog.collectAsState()
    val boosterState by viewModel.boosterState.collectAsState()
    val boosterHistory by viewModel.boosterHistory.collectAsState()
    val animationScales by viewModel.animationScales.collectAsState()
    val alwaysFinishActivities by viewModel.alwaysFinishActivities.collectAsState()
    val backgroundProcessLimit by viewModel.backgroundProcessLimit.collectAsState()
    val gameDevOptions by viewModel.gameDevOptions.collectAsState()
    val interventionDownscale by viewModel.interventionDownscale.collectAsState()
    val pointerSpeedChoice by viewModel.pointerSpeedChoice.collectAsState()

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
                // Nothing to ask for: consent arrived between the check and here, or the check itself
                // failed. Either way start() re-checks and reports the device's own answer.
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
        boosterHistory = boosterHistory,
        animationScales = animationScales,
        alwaysFinishActivities = alwaysFinishActivities,
        backgroundProcessLimit = backgroundProcessLimit,
        gameDevOptions = gameDevOptions,
        interventionDownscale = interventionDownscale,
        pointerSpeedChoice = pointerSpeedChoice,
        onToggleOverlay = { enable ->
            if (enable && !viewModel.canDrawOverlays()) {
                overlayLauncher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri()))
            } else {
                viewModel.toggleOverlay(enable)
            }
        },
        onSetOverlayMoveMode = viewModel::setOverlayMoveMode,
        onResetOverlayPosition = viewModel::resetOverlayPosition,
        onToggleCrosshair = { enable ->
            if (enable && !viewModel.canDrawOverlays()) {
                overlayLauncher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri()))
            } else {
                viewModel.toggleCrosshair(enable)
            }
        },
        onSelectCrosshair = viewModel::onSelectCrosshair,
        onSetCrosshairMoveMode = viewModel::setCrosshairMoveMode,
        onRecentreCrosshair = viewModel::recentreCrosshair,
        onSetBackgroundDataRestriction = viewModel::setBackgroundDataRestriction,
        onSetVpnFirewall = viewModel::setVpnFirewall,
        onToggleDnd = { enable ->
            if (!viewModel.toggleDnd(enable)) {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            }
        },
        onPerformMaintenance = viewModel::onPerformMaintenance,
        onScanJunk = viewModel::scanForJunk,
        onSetCleanSchedule = viewModel::setCleanSchedule,
        onSetCleanInterval = viewModel::setCleanInterval,
        onShowPermPicker = viewModel::showPermPicker,
        onRequestPermRevoke = viewModel::requestPermRevoke,
        onRegrantPerm = viewModel::regrantPerm,
        onRestorePerms = viewModel::restorePerms,
        onAddCleanerKeepEntry = viewModel::addCleanerKeepEntry,
        onRemoveCleanerKeepEntry = viewModel::removeCleanerKeepEntry,
        onAddCleanerCleanPattern = viewModel::addCleanerCleanPattern,
        onRemoveCleanerCleanPattern = viewModel::removeCleanerCleanPattern,
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
                    // API 30+ only; allFilesAccessIntent() is null below R so this is unreachable there.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        storageAccessLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    } else {
                        context.startActivity(Intent(Settings.ACTION_SETTINGS))
                    }
                }
            }
        },
        onActivateGamingMode = viewModel::activateGamingMode,
        onDeactivateGamingMode = viewModel::deactivateGamingMode,
        onBoostRam = viewModel::boostRam,
        onTrimStorage = viewModel::trimStorage,
        onRunBooster = viewModel::runBooster,
        onStopBooster = viewModel::stopBooster,
        onSetDexoptSchedule = viewModel::setDexoptSchedule,
        onSetDexoptInterval = viewModel::setDexoptInterval,
        onToggleFixedPerformance = viewModel::toggleFixedPerformance,
        onSetInterventionDownscale = viewModel::setInterventionDownscale,
        onSetPointerSpeedChoice = viewModel::setPointerSpeedChoice,
        onBoostChange = viewModel::onBoostChange,
        onSetAnimationScale = viewModel::setAnimationScale,
        onAnimPresetNameChange = viewModel::onAnimPresetNameChange,
        onSaveAnimPreset = viewModel::saveAnimPreset,
        onApplyAnimPreset = viewModel::applyAnimPreset,
        onDeleteAnimPreset = viewModel::deleteAnimPreset,
        onToggleAlwaysFinish = viewModel::toggleAlwaysFinish,
        onToggleBackgroundLimit = viewModel::toggleBackgroundLimit,
        onSetForcePeakRefreshRate = viewModel::setForcePeakRefreshRate,
        onSetGameDefaultFrameRateDisabled = viewModel::setGameDefaultFrameRateDisabled,
        onSelectAnglePackage = viewModel::selectAnglePackage,
        onSetAngleDriver = viewModel::setAngleDriver,
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
                    Toast.makeText(context, noDevOptions, Toast.LENGTH_LONG).show()
                }
            }
        },
        onRefreshDns = viewModel::refreshDnsStatus,
        onApplyDnsProvider = viewModel::applyDnsProvider,
        onSetDnsAutomatic = viewModel::setDnsAutomatic,
        onDisableDns = viewModel::disableDns,
        onMeasureDnsPing = viewModel::measureDnsPing,
        onLaunchGame = viewModel::launchGame,
        onOptimizeGame = viewModel::optimizeGame,
        onSelectOptimizeScope = viewModel::selectOptimizeScope,
        onRemoveGame = viewModel::removeGameFromLibrary,
        onAddGameClicked = viewModel::onAddGameClicked,

        onToggleAutoForceStop = viewModel::toggleAutoForceStop,
        onToggleAutoForceStopKeepPackage = viewModel::toggleAutoForceStopKeepPackage,
        onShowSuspendPicker = viewModel::showSuspendPicker,
        onRemoveExtraSuspendPackage = viewModel::removeExtraSuspendPackage,
        onShowSuspendExemptPicker = viewModel::showSuspendExemptPicker,
        onRemoveSuspendExemptPackage = viewModel::removeSuspendExemptPackage,
        onToggleGameSession = viewModel::toggleGameSession,
        
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
            onAppSelected = viewModel::addGameToLibrary,
            titleRes = R.string.gt_picker_add_game
        )
    }

    if (uiState.isPickingSuspendPackage) {
        AppPickerDialog(
            apps = uiState.allApps,
            onDismiss = viewModel::dismissSuspendPicker,
            onAppSelected = viewModel::toggleExtraSuspendPackage,
            titleRes = R.string.gt_picker_add_freeze
        )
    }

    if (uiState.isPickingSuspendExemptPackage) {
        AppPickerDialog(
            // Unlike the freeze picker (user-installed apps only), this one lists the sweep's
            // full reach: curated preinstalled apps may need keeping too, and a list that hides
            // them would leave exactly the reported YouTube-in-the-library case unsolved.
            apps = uiState.suspendExemptCandidateApps,
            onDismiss = viewModel::dismissSuspendExemptPicker,
            onAppSelected = viewModel::toggleSuspendExemptPackage,
            titleRes = R.string.gt_picker_add_keep
        )
    }

    if (uiState.isPickingPermTarget) {
        AppPickerDialog(
            apps = uiState.allApps,
            onDismiss = viewModel::dismissPermPicker,
            onAppSelected = viewModel::selectPermTarget,
            titleRes = R.string.gt_perm_pick_title
        )
    }

    // Revoke names the app, the permission and the consequence up front; nothing is taken on
    // dismiss — only the explicit confirm button runs it.
    uiState.permConfirm?.let { confirm ->
        AlertDialog(
            onDismissRequest = viewModel::cancelPermRevoke,
            title = { Text(stringResource(R.string.gt_perm_confirm_title)) },
            text = { Text(stringResource(R.string.gt_perm_confirm_body, confirm.label, confirm.permission)) },
            confirmButton = { TextButton(onClick = viewModel::confirmPermRevoke) { Text(stringResource(R.string.gt_perm_confirm_revoke)) } },
            dismissButton = { TextButton(onClick = viewModel::cancelPermRevoke) { Text(stringResource(R.string.gt_dialog_cancel)) } }
        )
    }

    if (uiState.showAggressiveCleanWarning) {
        AlertDialog(
            onDismissRequest = viewModel::dismissAggressiveCleanWarning,
            title = { Text(stringResource(R.string.gt_dialog_aggressive_title)) },
            text = { Text(stringResource(R.string.gt_dialog_aggressive_text)) },
            confirmButton = { TextButton(onClick = viewModel::confirmAggressiveClean) { Text(stringResource(R.string.gt_dialog_clean)) } },
            dismissButton = { TextButton(onClick = viewModel::dismissAggressiveCleanWarning) { Text(stringResource(R.string.gt_dialog_cancel)) } }
        )
    }
    
    // The ViewModel decides when a target needs confirming and supplies the concrete reason — an
    // aspect ratio the panel does not have, a size above native, or an unreadable panel — so the
    // dialog states the actual hazard rather than a generic "are you sure".
    uiState.resWarning?.let { warning ->
        AlertDialog(
            onDismissRequest = viewModel::dismissResWarning,
            title = { Text(stringResource(R.string.gt_dialog_res_title)) },
            text = { Text(warning) },
            confirmButton = { TextButton(onClick = viewModel::confirmResWarning) { Text(stringResource(R.string.gt_dialog_apply)) } },
            dismissButton = { TextButton(onClick = viewModel::dismissResWarning) { Text(stringResource(R.string.gt_dialog_cancel)) } }
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
    boosterHistory: List<BoosterRun>,
    animationScales: Triple<Float, Float, Float>,
    alwaysFinishActivities: Boolean,
    backgroundProcessLimit: Boolean,
    gameDevOptions: GameDeveloperOptions.State,
    /** Render scale for the next Gaming Mode activation, or null for full resolution. */
    interventionDownscale: Float?,
    /** Touch speed for the next activation, or null for stock (untouched). */
    pointerSpeedChoice: Int?,
    onToggleOverlay: (Boolean) -> Unit,
    onSetOverlayMoveMode: (Boolean) -> Unit,
    onResetOverlayPosition: () -> Unit,
    onToggleCrosshair: (Boolean) -> Unit,
    onSelectCrosshair: (String) -> Unit,
    onSetCrosshairMoveMode: (Boolean) -> Unit,
    onRecentreCrosshair: () -> Unit,
    onSetBackgroundDataRestriction: (Boolean) -> Unit,
    onSetVpnFirewall: (Boolean) -> Unit,
    onToggleDnd: (Boolean) -> Unit,
    onPerformMaintenance: (List<CleaningFeature.Category>) -> Unit,
    onScanJunk: () -> Unit,
    onSetCleanSchedule: (Boolean) -> Unit,
    /** Retunes the interval of the recurring sweep (hours, one of CleanerScheduleStore.INTERVAL_CHOICES). */
    onSetCleanInterval: (Int) -> Unit,
    onShowPermPicker: () -> Unit,
    onRequestPermRevoke: (String, String) -> Unit,
    onRegrantPerm: (String, String) -> Unit,
    onRestorePerms: (String) -> Unit,
    onAddCleanerKeepEntry: (String) -> Unit,
    onRemoveCleanerKeepEntry: (String) -> Unit,
    onAddCleanerCleanPattern: (String) -> Unit,
    onRemoveCleanerCleanPattern: (String) -> Unit,
    onGrantStorageAccess: () -> Unit,
    onActivateGamingMode: () -> Unit,
    onDeactivateGamingMode: () -> Unit,
    onBoostRam: () -> Unit,
    onTrimStorage: () -> Unit,
    onRunBooster: (String, Boolean) -> Unit,
    onStopBooster: () -> Unit,
    /** Enrolls or removes the recurring dexopt sweep in WorkManager. */
    onSetDexoptSchedule: (Boolean) -> Unit,
    /** Retunes the interval of the recurring sweep (hours, one of DexoptScheduleStore.INTERVAL_CHOICES). */
    onSetDexoptInterval: (Int) -> Unit,
    onBoostChange: (Int) -> Unit,
    onSetAnimationScale: (AnimationScaleKind, Float) -> Unit,
    onToggleAlwaysFinish: (Boolean) -> Unit,
    onToggleBackgroundLimit: (Boolean) -> Unit,
    onSetForcePeakRefreshRate: (Boolean) -> Unit,
    onSetGameDefaultFrameRateDisabled: (Boolean) -> Unit,
    onAnimPresetNameChange: (String) -> Unit,
    onSaveAnimPreset: () -> Unit,
    onApplyAnimPreset: (AnimationPresetStore.AnimationPreset) -> Unit,
    onDeleteAnimPreset: (String) -> Unit,
    onSelectAnglePackage: (String?) -> Unit,
    onSetAngleDriver: (AngleDriverOptions.Driver) -> Unit,
    /** Opens Android's Developer options screen for the switches this app cannot reach itself. */
    onOpenDeveloperOptions: () -> Unit,
    onRefreshDns: () -> Unit,
    onApplyDnsProvider: (DnsFeature.Provider) -> Unit,
    onSetDnsAutomatic: () -> Unit,
    onDisableDns: () -> Unit,
    onMeasureDnsPing: () -> Unit,
    onToggleFixedPerformance: (Boolean) -> Unit,
    /** Chooses the render scale (or null for full resolution) for the next activation. */
    onSetInterventionDownscale: (Float?) -> Unit,
    /** Chooses the touch speed (or null for stock) for the next activation. */
    onSetPointerSpeedChoice: (Int?) -> Unit,
    onLaunchGame: (String) -> Unit,
    onRemoveGame: (String) -> Unit,
    onOptimizeGame: (String) -> Unit,
    /** Selects the single-game scope in the unified optimisation card; null = all games. */
    onSelectOptimizeScope: (String?) -> Unit,
    onAddGameClicked: () -> Unit,

    onToggleAutoForceStop: (Boolean) -> Unit,
    onToggleAutoForceStopKeepPackage: (String) -> Unit,
    onShowSuspendPicker: () -> Unit,
    onRemoveExtraSuspendPackage: (String) -> Unit,
    onShowSuspendExemptPicker: () -> Unit,
    onRemoveSuspendExemptPackage: (String) -> Unit,
    onToggleGameSession: (Boolean) -> Unit,
    
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
            // Compact automatic-gaming-mode row directly above the library, so the
            // relationship reads top-down: Automatic Gaming Mode -> Your Games.
            AutoGameSessionRow(
                active = uiState.isGameSessionActive,
                enabled = uiState.games.isNotEmpty(),
                onToggle = onToggleGameSession
            )
            Spacer(modifier = Modifier.height(16.dp))
            // Library: vertical full-width cards. A LazyRow clipped the first/last card
            // edges (no content padding, fixed 140dp widths, horizontal scroll bounds),
            // so every card now fills the available width with its shape drawn by the
            // Surface itself — fully visible on phones, tablets, portrait and landscape
            // with no horizontal scrolling.
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.gt_section_your_library), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onClick = onAddGameClicked, modifier = Modifier.size(24.dp)) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = stringResource(R.string.core_desc_add),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            if (uiState.games.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.gt_no_games_detected), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    uiState.games.forEach { game ->
                        GameLibraryCard(
                            game,
                            onLaunchGame,
                            onRemoveGame,
                            onOptimizeGame,
                            isOptimizing = uiState.optimizingGamePkg == game.packageName
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text(stringResource(R.string.gt_section_gaming_mode), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 12.dp))
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
            Spacer(modifier = Modifier.height(12.dp))
            FixedPerformanceModeCard(
                enabled = isFixedPerformanceMode,
                onToggle = onToggleFixedPerformance,
                isGamingModeActive = isActive
            )
            Spacer(modifier = Modifier.height(24.dp))

            Text(stringResource(R.string.gt_section_performance), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ExpandableToolCard(title = stringResource(R.string.gt_tool_sound_title), subtitle = stringResource(R.string.gt_tool_sound_sub), icon = Icons.AutoMirrored.Filled.VolumeUp) {
                    BoostContent(uiState.boostLevel, uiState.audioOutput, onBoostChange)
                }
                ExpandableToolCard(
                    title = stringResource(R.string.gt_tool_booster_title),
                    subtitle = stringResource(R.string.gt_tool_booster_sub),
                    icon = Icons.Default.RocketLaunch,
                    enabled = hasPrivilege,
                    requirementNote = stringResource(R.string.gt_tool_booster_need)
                ) {
                    AppBoosterContent(
                        state = boosterState,
                        log = boosterLog,
                        history = boosterHistory,
                        games = uiState.games,
                        scopePkg = uiState.optimizeScopePkg,
                        optimizingSinglePkg = uiState.optimizingGamePkg,
                        onScopeChange = onSelectOptimizeScope,
                        onOptimizeSingle = onOptimizeGame,
                        scheduleEnabled = uiState.dexoptScheduleEnabled,
                        scheduleIntervalHours = uiState.dexoptIntervalHours,
                        scheduleNextRunAt = uiState.dexoptNextRunAt,
                        onScheduleEnabledChange = onSetDexoptSchedule,
                        onScheduleIntervalChange = onSetDexoptInterval,
                        onRun = onRunBooster,
                        onStop = onStopBooster
                    )
                }
                // One card for everything that lives in Android's own Developer Options. These used to
                // be scattered across three sections, which made them look like separate features
                // rather than the one screen's worth of switches they actually are.
                ExpandableToolCard(
                    title = stringResource(R.string.gt_tool_devopts_title),
                    subtitle = stringResource(R.string.gt_tool_devopts_sub),
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
                        animPresets = uiState.animPresets,
                        animPresetName = uiState.animPresetName,
                        onAnimPresetNameChange = onAnimPresetNameChange,
                        onSaveAnimPreset = onSaveAnimPreset,
                        onApplyAnimPreset = onApplyAnimPreset,
                        onDeleteAnimPreset = onDeleteAnimPreset,
                        onToggleAlwaysFinish = onToggleAlwaysFinish,
                        onToggleBackgroundLimit = onToggleBackgroundLimit,
                        onSetForcePeakRefreshRate = onSetForcePeakRefreshRate,
                        onSetGameDefaultFrameRateDisabled = onSetGameDefaultFrameRateDisabled,
                        games = uiState.games,
                        anglePkg = uiState.anglePkg,
                        angleDriver = uiState.angleDriver,
                        isChangingAngleDriver = uiState.isChangingAngleDriver,
                        onSelectAnglePackage = onSelectAnglePackage,
                        onSetAngleDriver = onSetAngleDriver,
                        onOpenDeveloperOptions = onOpenDeveloperOptions
                    )
                }

                // Kernel Insights lived here as a read-only dump with no controls and no
                // other consumer — removed from Gaming Tools (§6). The parsers stay tested
                // in KernelInfo/ThermalZoneReader for any future diagnostics use.
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text(stringResource(R.string.gt_section_graphics), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ExpandableToolCard(title = stringResource(R.string.gt_tool_screen_title), subtitle = stringResource(R.string.gt_tool_screen_sub), icon = Icons.Default.AspectRatio) {
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
                        onApply = onApplyResolution, onReset = onResetResolution,
                        downscale = interventionDownscale,
                        onDownscaleChange = onSetInterventionDownscale,
                        pointerSpeed = pointerSpeedChoice,
                        onPointerSpeedChange = onSetPointerSpeedChoice,
                        tuningEnabled = hasPrivilege && !isBusy && !isActive
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text(stringResource(R.string.gt_section_overlays), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ExpandableToolCard(
                    title = stringResource(R.string.gt_tool_fps_title),
                    subtitle = stringResource(R.string.gt_tool_fps_sub),
                    icon = Icons.Default.BarChart,
                    isToggleable = true,
                    isToggled = uiState.isOverlayRunning,
                    onToggleChange = onToggleOverlay,
                    forceExpand = uiState.isOverlayRunning
                ) {
                    OverlayPositionContent(
                        isRunning = uiState.isOverlayRunning,
                        isMoveMode = uiState.isOverlayMoveMode,
                        isMoved = uiState.isOverlayMoved,
                        onSetMoveMode = onSetOverlayMoveMode,
                        onReset = onResetOverlayPosition
                    )
                }
                ExpandableToolCard(title = stringResource(R.string.gt_tool_crosshair_title), subtitle = stringResource(R.string.gt_tool_crosshair_sub), icon = Icons.Default.AddCircleOutline, isToggleable = true, isToggled = uiState.isCrosshairRunning, onToggleChange = onToggleCrosshair, forceExpand = uiState.isCrosshairRunning) {
                    CrosshairPicker(
                        selected = uiState.selectedCrosshair,
                        onSelect = onSelectCrosshair,
                        isRunning = uiState.isCrosshairRunning,
                        isMoveMode = uiState.isCrosshairMoveMode,
                        isOffCentre = uiState.isCrosshairOffCentre,
                        onSetMoveMode = onSetCrosshairMoveMode,
                        onRecentre = onRecentreCrosshair
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text(stringResource(R.string.gt_section_focus_network), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FeatureToggleCard(title = stringResource(R.string.gt_tool_dnd_title), subtitle = stringResource(R.string.gt_tool_dnd_sub), icon = Icons.Default.NotificationsOff, checked = uiState.isDndEnabled, onCheckedChange = onToggleDnd)
                // Two independent ways to stop other apps using the network, and the card carries no
                // toggle of its own: each switch lives in the body with its own state and its own
                // requirement, because they are different mechanisms and either, both, or neither is a
                // valid choice. The card title used to imply one method with one switch.
                ExpandableToolCard(
                    title = stringResource(R.string.gt_tool_net_title),
                    subtitle = stringResource(R.string.gt_tool_net_sub),
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
                    title = stringResource(R.string.gt_tool_dns_title),
                    subtitle = stringResource(R.string.gt_tool_dns_sub),
                    icon = Icons.Default.Dns
                ) {
                    DnsContent(
                        status = uiState.dnsStatus,
                        isChanging = uiState.isChangingDns,
                        onRefresh = onRefreshDns,
                        onApplyProvider = onApplyDnsProvider,
                        onSetAutomatic = onSetDnsAutomatic,
                        onDisable = onDisableDns,
                        pingMs = uiState.dnsPingMs,
                        isMeasuringPing = uiState.isMeasuringDnsPing,
                        onMeasurePing = onMeasureDnsPing
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text(stringResource(R.string.gt_section_bg_apps), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ExpandableToolCard(
                    title = stringResource(R.string.gt_tool_afs_title),
                    subtitle = stringResource(R.string.gt_tool_afs_sub),
                    icon = Icons.Default.Security,
                    isToggleable = true,
                    isToggled = uiState.isAutoForceStopActive,
                    onToggleChange = onToggleAutoForceStop,
                    enabled = hasPrivilege,
                    requirementNote = stringResource(R.string.gt_tool_afs_need)
                ) {
                    AutoForceStopContent(
                        apps = uiState.allApps,
                        kept = uiState.autoForceStopKeepPackages,
                        hasPrivilege = hasPrivilege,
                        onToggle = onToggleAutoForceStopKeepPackage
                    )
                }
                // Extra suspend list: user-picked packages frozen at Gaming Mode activation on
                // top of the automatic sweep, woken with everything else on deactivation.
                ExpandableToolCard(
                    title = stringResource(R.string.gt_suspend_title),
                    subtitle = stringResource(R.string.gt_suspend_sub),
                    icon = Icons.Default.Pause
                ) {
                    SuspendListContent(
                        picked = uiState.extraSuspendPackages,
                        apps = uiState.allApps,
                        hasPrivilege = hasPrivilege,
                        onAdd = onShowSuspendPicker,
                        onRemove = onRemoveExtraSuspendPackage
                    )
                }
                // Keep list: the exception to the sweep above — packages it must leave running.
                ExpandableToolCard(
                    title = stringResource(R.string.gt_keep_title),
                    subtitle = stringResource(R.string.gt_keep_sub),
                    icon = Icons.Default.PlayArrow
                ) {
                    KeepListContent(
                        picked = uiState.suspendExemptPackages,
                        apps = uiState.suspendExemptCandidateApps,
                        hasPrivilege = hasPrivilege,
                        onAdd = onShowSuspendExemptPicker,
                        onRemove = onRemoveSuspendExemptPackage
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text(stringResource(R.string.gt_section_memory), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                RamBoostCard(
                    uiState.isBoostingRam,
                    uiState.ramResult,
                    onBoostRam,
                    uiState.isTrimmingStorage,
                    uiState.storageTrimResult,
                    hasPrivilege,
                    onTrimStorage
                )
                ExpandableToolCard(title = stringResource(R.string.gt_tool_cleaner_title), subtitle = stringResource(R.string.gt_tool_cleaner_sub), icon = Icons.Default.DeleteSweep) {
                    CleaningContent(
                        isRooted = uiState.isRooted,
                        isShizukuActive = uiState.isShizukuActive,
                        cleanResult = uiState.cleanResult,
                        report = uiState.scanReport,
                        isScanning = uiState.isScanningJunk,
                        isCleaning = uiState.isCleaningJunk,
                        keepEntries = uiState.cleanerKeepEntries,
                        cleanPatterns = uiState.cleanerCleanPatterns,
                        onAddKeepEntry = onAddCleanerKeepEntry,
                        onRemoveKeepEntry = onRemoveCleanerKeepEntry,
                        onAddCleanPattern = onAddCleanerCleanPattern,
                        onRemoveCleanPattern = onRemoveCleanerCleanPattern,
                        onScan = onScanJunk,
                        onPerform = onPerformMaintenance,
                        onGrantStorageAccess = onGrantStorageAccess,
                        scheduleEnabled = uiState.cleanScheduleEnabled,
                        scheduleIntervalHours = uiState.cleanIntervalHours,
                        scheduleNextRunAt = uiState.cleanNextRunAt,
                        onScheduleEnabledChange = onSetCleanSchedule,
                        onScheduleIntervalChange = onSetCleanInterval
                    )
                }
                // Manual dangerous-permission toggle: per-app, per-permission, explicitly
                // confirmed. Revokes the platform may refuse are reported, not recorded, and
                // everything taken can be regranted from the restore set.
                ExpandableToolCard(title = stringResource(R.string.gt_perm_title), subtitle = stringResource(R.string.gt_perm_sub), icon = Icons.Default.AdminPanelSettings) {
                    PermToggleContent(
                        targetLabel = uiState.permTargetLabel,
                        targetPkg = uiState.permTargetPkg,
                        entries = uiState.permEntries,
                        isBusy = uiState.isPermBusy,
                        hasPrivilege = hasPrivilege,
                        onPickTarget = onShowPermPicker,
                        onRevoke = onRequestPermRevoke,
                        onRegrant = onRegrantPerm,
                        onRestoreAll = onRestorePerms
                    )
                }
            }
        }
    }
}

@Composable
fun GameLibraryCard(
    game: GameInfo,
    onLaunch: (String) -> Unit,
    onRemove: (String) -> Unit,
    onOptimize: (String) -> Unit,
    isOptimizing: Boolean
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Cached per game: toBitmap() allocates on every recomposition otherwise.
            val icon = remember(game.packageName, game.icon) { game.icon.toBitmap().asImageBitmap() }
            Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)))
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = game.appName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    CatsmokerButton(onClick = { onLaunch(game.packageName) }, modifier = Modifier.weight(1f).height(32.dp), contentPadding = PaddingValues(0.dp), shape = RoundedCornerShape(8.dp)) {
                        Text(stringResource(R.string.gt_library_launch), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    // Pre-launch speed-profile compile for exactly this game. Runs through the same
                    // sweep lock as the full booster, so it can never race one — the button spins
                    // while the platform works and the toast reports what it said.
                    CatsmokerOutlinedButton(
                        onClick = { onOptimize(game.packageName) },
                        modifier = Modifier.weight(1f).height(32.dp),
                        contentPadding = PaddingValues(0.dp),
                        shape = RoundedCornerShape(8.dp),
                        enabled = !isOptimizing
                    ) {
                        if (isOptimizing) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        } else {
                            Text(stringResource(R.string.gt_library_optimize), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            IconButton(onClick = { onRemove(game.packageName) }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Remove, stringResource(R.string.gt_library_remove), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f), modifier = Modifier.size(16.dp))
            }
        }
    }
}

/**
 * Compact automatic-gaming-mode row shown directly above Your Games.
 *
 * No card background and no description: the name plus the existing toggle only.
 * The text takes the available width ([weight]) so the switch is never pushed
 * off-screen or clipped on narrow phones; no fixed widths are used.
 */
@Composable
private fun AutoGameSessionRow(
    active: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.gt_session_title),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).padding(end = 8.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Switch(checked = active, onCheckedChange = onToggle, enabled = enabled)
    }
}

@Composable
fun FeatureToggleCard(title: String, subtitle: String, icon: ImageVector, checked: Boolean, enabled: Boolean = true, onCheckedChange: (Boolean) -> Unit) {
    SectionCard(enabled = enabled) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(if (enabled) MaterialTheme.colorScheme.surfaceVariant else Color.Gray.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp)) }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
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
                Box(modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(if (enabled) MaterialTheme.colorScheme.surfaceVariant else Color.Gray.copy(alpha = 0.1f)).clickable(enabled = enabled) { expanded = !expanded }, contentAlignment = Alignment.Center) { Icon(icon, null, tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp)) }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f).clickable(enabled = enabled) { expanded = !expanded }) {
                    Text(title, fontWeight = FontWeight.Bold, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
                    Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
                if (isToggleable) {
                    Switch(checked = isToggled, onCheckedChange = onToggleChange, enabled = enabled)
                } else {
                    IconButton(onClick = { expanded = !expanded }, enabled = enabled) { Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            if (!enabled && requirementNote != null) {
                Spacer(modifier = Modifier.height(12.dp))
                RequirementNotice(requirementNote)
            }
            if (expanded && enabled) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
                content()
            }
        }
    }
}

/**
 * Resolution picker, plus the two next-activation tuning rows that belong with it.
 *
 * Every fixed entry is a scaling of the panel's real resolution, computed by
 * `DisplayMetricsProvider` — the same reader spoof profiles use — so nothing here is a hardcoded
 * size that the display may not support. When the panel could not be read the picker says so
 * instead of offering entries derived from a guess.
 *
 * Render scale lowers the game's *internal* render resolution (`game_overlay`
 * downscaleFactor, per game, at the next Gaming Mode activation) while the override
 * above changes the device display itself — related in purpose, separate channels,
 * so they share this card as clearly labeled subsections rather than one control.
 * Touch speed only changes the pointer speed (`Settings.System.pointer_speed`,
 * same next-activation timing) and is labeled as such; it never touches resolution.
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
    onApply: () -> Unit, onReset: () -> Unit,
    /** Render scale for the next Gaming Mode activation, or null for full resolution. */
    downscale: Float?,
    /** Chooses the render scale; takes effect on the next activation, never mid-session. */
    onDownscaleChange: (Float?) -> Unit,
    /** Touch speed for the next activation, or null for stock (untouched). */
    pointerSpeed: Int?,
    /** Chooses the touch speed; takes effect on the next activation, never mid-session. */
    onPointerSpeedChange: (Int?) -> Unit,
    /**
     * Whether the two tuning rows accept input. Locked while Gaming Mode is active or
     * busy, or without root/Shizuku — the same rule they always had.
     */
    tuningEnabled: Boolean,
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
            Text(stringResource(R.string.gt_res_your_screen, native.label), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
        } else {
            Text(
                stringResource(R.string.gt_res_unknown_screen),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = activeOverride?.let { stringResource(R.string.gt_res_changed_to, it.label) } ?: stringResource(R.string.gt_res_not_changed),
            fontSize = 11.sp,
            color = if (activeOverride != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(10.dp))
        ExplainerBox(
            title = stringResource(R.string.gt_explainer_what),
            lines = listOf(
                stringResource(R.string.gt_res_what_1),
                stringResource(R.string.gt_res_what_2),
                stringResource(R.string.gt_res_what_3)
            )
        )
        Spacer(modifier = Modifier.height(6.dp))
        ExplainerBox(
            title = stringResource(R.string.gt_res_safe_title),
            accent = Color(0xFFFFB300),
            lines = listOf(
                stringResource(R.string.gt_res_safe_1),
                stringResource(R.string.gt_res_safe_2),
                stringResource(R.string.gt_res_safe_3, native?.let { source } ?: stringResource(R.string.gt_res_safe_source_phone))
            )
        )
        Spacer(modifier = Modifier.height(6.dp))
        // Graphics is three independent layers (F11): this card's display override,
        // the render-scale subsection below (per-game internal rendering), and the
        // ANGLE driver table. Stating the stack here keeps a lower resolution from
        // reading as a device-wide performance cap.
        ExplainerBox(
            title = stringResource(R.string.gt_graphics_layers_title),
            lines = listOf(stringResource(R.string.gt_graphics_layers))
        )

        if (options.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(stringResource(R.string.gt_res_choose_size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(8.dp))
            // Resolution presets wrap instead of scrolling sideways: every size stays
            // visible on a narrow phone, one line on a tablet.
            ChipFlowRow {
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
                Text(stringResource(R.string.gt_res_preset_sets, target.label), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        // readOnly rather than enabled = false: the numbers stay legible so a preset can be inspected
        // before it is applied, but the keyboard never opens for them.
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = width, onValueChange = onWidthChange, label = { Text(stringResource(R.string.gt_res_width)) },
                singleLine = true,
                readOnly = !editable,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = validationError != null,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = height, onValueChange = onHeightChange, label = { Text(stringResource(R.string.gt_res_height)) },
                singleLine = true,
                readOnly = !editable,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = validationError != null,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = dpi, onValueChange = onDpiChange, label = { Text(stringResource(R.string.gt_res_dpi)) },
            singleLine = true,
            readOnly = !editable,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = validationError != null,
            supportingText = validationError?.let { { Text(it, fontSize = 11.sp) } },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = if (editable) stringResource(R.string.gt_res_editable_hint)
            else stringResource(R.string.gt_res_locked_hint),
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))
        if (channel == null) {
            RequirementNotice(
                stringResource(R.string.gt_res_need)
            )
        } else {
            Text(
                stringResource(
                    if (channel == "Root") R.string.gt_res_ready_root else R.string.gt_res_ready_shizuku
                ),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CatsmokerButton(
                onClick = onApply,
                enabled = channel != null && validationError == null && !isApplying,
                modifier = Modifier.weight(1f)
            ) {
                if (isApplying) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.gt_res_apply))
                }
            }
            CatsmokerOutlinedButton(
                onClick = onReset,
                enabled = channel != null && !isApplying,
                modifier = Modifier.weight(0.6f)
            ) { Text(stringResource(R.string.gt_res_reset)) }
        }
        // Width presets and the panel-refresh readout used to live here. They were
        // removed from this card; the preset store, the refresh-rate provider and
        // their tests are untouched for any other use.
        //
        // Next-activation tuning, kept with the resolution controls it relates to.
        // Render scale lowers the game's internal render resolution (the display
        // override above is untouched); touch speed only changes the pointer speed.
        // Both wait for the next Gaming Mode run and restore afterwards, so the rows
        // lock under the same rule Gaming Mode itself uses.
        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(modifier = Modifier.height(16.dp))
        DownscaleRow(
            current = downscale,
            enabled = tuningEnabled,
            onSelect = onDownscaleChange
        )
        Spacer(modifier = Modifier.height(16.dp))
        PointerSpeedRow(
            current = pointerSpeed,
            enabled = tuningEnabled,
            onSelect = onPointerSpeedChange
        )
        if (log.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Box(modifier = Modifier.fillMaxWidth().height(100.dp).clip(RoundedCornerShape(8.dp)).background(LogTerminalBackground).padding(8.dp)) {
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

/**
 * Style picker plus the reposition controls.
 *
 * "Move" is a mode with a visible on state rather than a fire-and-forget button, because while it is
 * on the overlay is taking the taps that would otherwise reach the game. The copy says so, and the
 * same control turns it off — the overlay's own banner and its notification are the other two exits.
 */
@Composable
fun CrosshairPicker(
    selected: String,
    onSelect: (String) -> Unit,
    isRunning: Boolean,
    isMoveMode: Boolean,
    isOffCentre: Boolean,
    onSetMoveMode: (Boolean) -> Unit,
    onRecentre: () -> Unit
) {
    val context = LocalContext.current
    val scopes = remember { (1..7).map { "scope$it.png" } }
    Column {
        Text(stringResource(R.string.gt_crosshair_style), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(12.dp))
        // Crosshair tiles wrap in a grid instead of scrolling sideways: all seven
        // visible at once on a phone (roughly 4+3), one row on a tablet.
        ChipFlowRow {
            scopes.forEach { scope ->
                val isSelected = selected == scope
                Surface(
                    modifier = Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).clickable { onSelect(scope) },
                    color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant,
                    border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(8.dp)) {
                        val bitmap = remember(scope) { try { context.assets.open("crosshair/$scope").use { BitmapFactory.decodeStream(it) } } catch (_: Exception) { null } }
                        if (bitmap != null) Image(bitmap = bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize())
                        else Icon(Icons.Default.BrokenImage, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
        Text(stringResource(R.string.gt_crosshair_position), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            when {
                !isRunning -> stringResource(R.string.gt_crosshair_off)
                isMoveMode -> stringResource(R.string.gt_crosshair_move)
                isOffCentre -> stringResource(R.string.gt_crosshair_recenter_hint)
                else -> stringResource(R.string.gt_crosshair_centred)
            },
            color = if (isMoveMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // The label names the state it will move to, not the state it is in, so the button never
            // reads as a description of the current mode.
            if (isMoveMode) {
                CatsmokerButton(onClick = { onSetMoveMode(false) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.gt_crosshair_done))
                }
            } else {
                CatsmokerOutlinedButton(onClick = { onSetMoveMode(true) }, enabled = isRunning, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.OpenWith, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.gt_crosshair_move_btn))
                }
            }
            // Offered whenever the crosshair is off centre, including while the overlay is off — the
            // stored offset outlives the overlay and would be reused at the next start.
            CatsmokerOutlinedButton(onClick = onRecentre, enabled = isOffCentre, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.FilterCenterFocus, null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.gt_crosshair_centre))
            }
        }
    }
}

/**
 * The performance readout's position controls.
 *
 * The readout only becomes draggable in move mode: outside it the window is FLAG_NOT_TOUCHABLE so
 * taps in its area still reach the game. Move mode is therefore a state the UI has to announce,
 * not a hidden toggle — the lines below say which mode is on and the buttons carry the way out.
 */
@Composable
fun OverlayPositionContent(
    isRunning: Boolean,
    isMoveMode: Boolean,
    isMoved: Boolean,
    onSetMoveMode: (Boolean) -> Unit,
    onReset: () -> Unit
) {
    Column {
        Text(stringResource(R.string.gt_overlay_position), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            when {
                !isRunning -> stringResource(R.string.gt_overlay_off)
                isMoveMode -> stringResource(R.string.gt_overlay_move)
                isMoved -> stringResource(R.string.gt_overlay_moved_hint)
                else -> stringResource(R.string.gt_overlay_default_hint)
            },
            color = if (isMoveMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // The label names the state it will move to, not the state it is in, so the button never
            // reads as a description of the current mode.
            if (isMoveMode) {
                CatsmokerButton(onClick = { onSetMoveMode(false) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.gt_overlay_done))
                }
            } else {
                CatsmokerOutlinedButton(onClick = { onSetMoveMode(true) }, enabled = isRunning, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.OpenWith, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.gt_overlay_move_btn))
                }
            }
            // Offered whenever the readout sits off the default corner, including while the overlay
            // is off — the stored position outlives the overlay and would be reused at the next start.
            CatsmokerOutlinedButton(onClick = onReset, enabled = isMoved, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.FilterCenterFocus, null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.gt_overlay_reset))
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
            // checkOpNoThrow is the non-deprecated spelling on the compile SDK and has
            // existed since API 19, so no version branch is needed.
            val mode = appOps?.checkOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
            mode == android.app.AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        ExplainerBox(
            title = stringResource(R.string.gt_explainer_what),
            lines = listOf(
                stringResource(R.string.gt_afs_what_1),
                stringResource(R.string.gt_afs_what_2),
                stringResource(R.string.gt_afs_what_3)
            )
        )
        Spacer(modifier = Modifier.height(8.dp))
        ExplainerBox(
            title = stringResource(R.string.gt_afs_perm_title),
            lines = listOf(
                stringResource(R.string.gt_afs_perm_1),
                stringResource(R.string.gt_afs_perm_2),
                stringResource(R.string.gt_afs_perm_3)
            ),
            accent = Color(0xFFFFB300)
        )
        Spacer(modifier = Modifier.height(8.dp))
        ExplainerBox(
            title = stringResource(R.string.gt_afs_should_title),
            accent = Color(0xFFFFB300),
            lines = listOf(
                stringResource(R.string.gt_afs_should_1),
                stringResource(R.string.gt_afs_should_2),
                stringResource(R.string.gt_afs_should_3)
            )
        )
        if (!hasPrivilege) {
            Spacer(modifier = Modifier.height(8.dp))
            RequirementNotice(stringResource(R.string.gt_needs_root_shizuku_close))
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
                Text(stringResource(R.string.gt_afs_usage_off_title), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE53935))
                Text(
                    stringResource(R.string.gt_afs_usage_off_text),
                    fontSize = 11.sp,
                    color = Color(0xFFE53935),
                    lineHeight = 15.sp
                )
                CatsmokerOutlinedButton(onClick = {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    }.onFailure {
                        context.startActivity(Intent(Settings.ACTION_SETTINGS))
                    }
                }) { Text(stringResource(R.string.gt_afs_usage_btn)) }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = if (kept.isEmpty()) stringResource(R.string.gt_afs_keep_none)
            else stringResource(R.string.gt_afs_keep_some, kept.size),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(stringResource(R.string.gt_afs_tick_hint), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(8.dp))
        androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.heightIn(max = 260.dp)) {
            items(apps) { app ->
                Row(modifier = Modifier.fillMaxWidth().clickable { onToggle(app.packageName) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = kept.contains(app.packageName), onCheckedChange = { onToggle(app.packageName) })
                    // Cached per row: decoding inside LazyColumn items re-runs on every scroll recomposition.
                    val appIcon = remember(app.packageName, app.icon) { app.icon.toBitmap().asImageBitmap() }
                    Image(bitmap = appIcon, contentDescription = null, modifier = Modifier.size(24.dp).clip(RoundedCornerShape(4.dp)))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(app.appName, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
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
    keepEntries: Set<String>,
    cleanPatterns: Set<String>,
    onAddKeepEntry: (String) -> Unit,
    onRemoveKeepEntry: (String) -> Unit,
    onAddCleanPattern: (String) -> Unit,
    onRemoveCleanPattern: (String) -> Unit,
    onScan: () -> Unit,
    onPerform: (List<CleaningFeature.Category>) -> Unit,
    onGrantStorageAccess: () -> Unit,
    scheduleEnabled: Boolean,
    scheduleIntervalHours: Int,
    scheduleNextRunAt: Long?,
    onScheduleEnabledChange: (Boolean) -> Unit,
    onScheduleIntervalChange: (Int) -> Unit
) {
    var selectedCategories by remember { mutableStateOf(CleaningFeature.Category.entries.filter { !it.isAggressive }.toSet()) }
    val resultsByCategory = remember(report) { report?.results?.associateBy { it.category }.orEmpty() }
    Column(modifier = Modifier.fillMaxWidth()) {
        // Names the channel that will actually do the deleting, because it decides how much of the
        // device can be reached — a normal app cannot see another app's cache at all.
        Text(
            stringResource(
                R.string.gt_cleaner_reach,
                when {
                    isRooted -> stringResource(R.string.gt_cleaner_reach_root)
                    isShizukuActive -> stringResource(R.string.gt_cleaner_reach_shizuku)
                    else -> stringResource(R.string.gt_cleaner_reach_plain)
                }
            ),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(8.dp))
        ExplainerBox(
            title = stringResource(R.string.gt_explainer_what),
            lines = listOf(
                stringResource(R.string.gt_cleaner_what_1),
                stringResource(R.string.gt_cleaner_what_2),
                stringResource(R.string.gt_cleaner_what_3)
            )
        )
        Spacer(modifier = Modifier.height(4.dp))
        ExplainerBox(
            title = stringResource(R.string.gt_cleaner_safe_title),
            accent = Color(0xFFFFB300),
            lines = listOf(
                stringResource(R.string.gt_cleaner_safe_1),
                stringResource(R.string.gt_cleaner_safe_2),
                stringResource(R.string.gt_cleaner_safe_3)
            )
        )
        Spacer(modifier = Modifier.height(8.dp))
        CleaningFeature.Category.entries.forEach { category ->
            Row(modifier = Modifier.fillMaxWidth().clickable { selectedCategories = if (selectedCategories.contains(category)) selectedCategories - category else selectedCategories + category }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = selectedCategories.contains(category), onCheckedChange = { checked -> selectedCategories = if (checked) selectedCategories + category else selectedCategories - category })
                Text(categoryLabel(category), modifier = Modifier.weight(1f), color = if (category.isAggressive) Color.Red else MaterialTheme.colorScheme.onSurface)
                // Only a measured size is shown as a size. "Not scanned" and "none found" are
                // different outcomes and neither of them is 0 B.
                val entry = resultsByCategory[category]
                when {
                    report == null -> Unit
                    // The count leads, because empty files and empty folders are real finds that
                    // reclaim no bytes — a bare "0 B" there reads as "found nothing".
                    entry != null -> Text(
                        stringResource(R.string.gt_cleaner_found_count, entry.itemCount, formatBytes(entry.sizeBytes)),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    report.scannedAnything -> Text(stringResource(R.string.gt_cleaner_none_found), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> Text(stringResource(R.string.gt_cleaner_not_scanned), fontSize = 11.sp, color = Color(0xFFFFB300))
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CatsmokerButton(onClick = onScan, modifier = Modifier.weight(1f), enabled = !isScanning && !isCleaning) { Text(if (isScanning) stringResource(R.string.gt_cleaner_scanning) else stringResource(R.string.gt_cleaner_scan)) }
            CatsmokerButton(onClick = { onPerform(selectedCategories.toList()) }, modifier = Modifier.weight(1f), enabled = !isScanning && !isCleaning && selectedCategories.isNotEmpty()) { Text(if (isCleaning) stringResource(R.string.gt_cleaner_cleaning) else stringResource(R.string.gt_cleaner_clean)) }
        }

        // The recurring version of the same sweep: safe buckets only, on a schedule, while the
        // app is closed. Aggressive buckets stay manual-only in every run (see
        // CleanerSweepWorker) — the section below states that before the switch.
        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(modifier = Modifier.height(12.dp))
        CleanerScheduleSection(
            enabled = scheduleEnabled,
            intervalHours = scheduleIntervalHours,
            nextRunAt = scheduleNextRunAt,
            onEnabledChange = onScheduleEnabledChange,
            onIntervalChange = onScheduleIntervalChange
        )

        // Rule editing sits between the buttons and the result: it is the one part of the card
        // that takes input rather than reporting it, and it only matters once a scan exists to
        // show its effect on.
        CleanerRulesEditor(
            keepCount = keepEntries.size,
            patternCount = cleanPatterns.size,
            onAddKeepEntry = onAddKeepEntry,
            onAddCleanPattern = onAddCleanPattern
        )

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
                    !report.scannedAnything -> stringResource(R.string.gt_cleaner_unreadable)
                    report.results.isEmpty() -> stringResource(R.string.gt_cleaner_looked_empty)
                    report.totalBytes == 0L ->
                        stringResource(R.string.gt_cleaner_empty_only, report.totalItems)
                    else -> stringResource(R.string.gt_cleaner_can_free, formatBytes(report.totalBytes), report.totalItems)
                },
                fontSize = 12.sp,
                color = if (report.scannedAnything) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f) else Color(0xFFFFB300)
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
                    Text(stringResource(R.string.gt_cleaner_limitations_title), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFB300))
                    report.limitations.forEach { limitation ->
                        Text("• $limitation", fontSize = 11.sp, color = Color(0xFFFFB300), lineHeight = 15.sp)
                    }
                }
            }

            if (report.needsAllFilesAccess) {
                Spacer(modifier = Modifier.height(8.dp))
                CatsmokerButton(onClick = onGrantStorageAccess, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.gt_cleaner_grant))
                }
            }

            if (keepEntries.isNotEmpty() || cleanPatterns.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                CleanerRulesSummary(
                    keepEntries = keepEntries,
                    cleanPatterns = cleanPatterns,
                    onRemoveKeepEntry = onRemoveKeepEntry,
                    onRemoveCleanPattern = onRemoveCleanPattern
                )
            }
        }
    }
}

/**
 * The user's own keep/clean rules, listed with a way to remove each.
 *
 * Shown only when at least one rule exists — the empty state is the common case and needs no UI.
 * A rule change applies from the *next* scan, not to the report already on screen, so the row
 * never pretends a rule edited the numbers above it.
 */@Composable
private fun CleanerRulesSummary(
    keepEntries: Set<String>,
    cleanPatterns: Set<String>,
    onRemoveKeepEntry: (String) -> Unit,
    onRemoveCleanPattern: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF64B5F6).copy(alpha = 0.10f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(stringResource(R.string.gt_cleaner_rules_title), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF64B5F6))
        keepEntries.forEach { entry ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.gt_cleaner_rule_keep, entry), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = { onRemoveKeepEntry(entry) }, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.gt_cleaner_remove_keep), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                }
            }
        }
        cleanPatterns.forEach { pattern ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.gt_cleaner_rule_clean, pattern), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = { onRemoveCleanPattern(pattern) }, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.gt_cleaner_remove_clean), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

/**
 * Collapsed entry point for adding keep entries and clean patterns.
 *
 * Both kinds are typed free-form, so the editor stays one expanded section with two fields rather
 * than two dialogs. Keep entries are plain text (a path or a bare file name); clean patterns are
 * regular-expression text matched against the whole path, and an invalid one is rejected by the
 * ViewModel with a toast rather than stored to break every later scan.
 */
@Composable
private fun CleanerRulesEditor(
    keepCount: Int,
    patternCount: Int,
    onAddKeepEntry: (String) -> Unit,
    onAddCleanPattern: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var keepInput by remember { mutableStateOf("") }
    var patternInput by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = { expanded = !expanded }) {
            val ruleCount = keepCount + patternCount
            Text(
                if (expanded) stringResource(R.string.gt_cleaner_rules_hide)
                else if (ruleCount > 0) stringResource(R.string.gt_cleaner_rules_add_count, ruleCount)
                else stringResource(R.string.gt_cleaner_rules_add),
                fontSize = 12.sp
            )
        }
        if (expanded) {
            Text(
                stringResource(R.string.gt_cleaner_rules_help),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 15.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = keepInput,
                    onValueChange = { keepInput = it },
                    placeholder = { Text(stringResource(R.string.gt_cleaner_keep_hint), fontSize = 11.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
                )
                CatsmokerButton(
                    onClick = {
                        onAddKeepEntry(keepInput)
                        keepInput = ""
                    },
                    enabled = keepInput.isNotBlank()
                ) { Text(stringResource(R.string.gt_cleaner_add)) }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = patternInput,
                    onValueChange = { patternInput = it },
                    placeholder = { Text(stringResource(R.string.gt_cleaner_pattern_hint), fontSize = 11.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
                )
                CatsmokerButton(
                    onClick = {
                        onAddCleanPattern(patternInput)
                        patternInput = ""
                    },
                    enabled = patternInput.isNotBlank()
                ) { Text(stringResource(R.string.gt_cleaner_add)) }
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
        result.removedNothing && result.failedItems > 0 -> stringResource(R.string.gt_cleaner_result_nothing_failed)
        result.removedNothing -> stringResource(R.string.gt_cleaner_result_nothing)
        // A size of zero after real deletions means everything removed was empty, which is a
        // different fact from having freed nothing measurable.
        result.freedBytes == 0L && result.unmeasuredItems == 0 ->
            stringResource(R.string.gt_cleaner_result_empty, result.deletedItems)
        result.unmeasuredItems > 0 ->
            stringResource(R.string.gt_cleaner_result_at_least, formatBytes(result.freedBytes))
        else -> stringResource(R.string.gt_cleaner_result_freed, formatBytes(result.freedBytes))
    }

    // Every count that is not zero gets said out loud. An item that was listed and then not removed
    // has to be accounted for somewhere, or the headline overstates what happened.
    val details = buildList {
        if (!result.removedNothing) add(Pair(R.string.gt_cleaner_detail_deleted, result.deletedItems))
        if (result.unmeasuredItems > 0) {
            add(Pair(R.string.gt_cleaner_detail_unmeasured, result.unmeasuredItems))
        }
        if (result.failedItems > 0) {
            add(
                Pair(
                    if (result.privileged) R.string.gt_cleaner_detail_failed_priv
                    else R.string.gt_cleaner_detail_failed_nopriv,
                    result.failedItems
                )
            )
        }
        if (result.alreadyGoneItems > 0) add(Pair(R.string.gt_cleaner_detail_gone, result.alreadyGoneItems))
        if (result.protectedSkips > 0) add(Pair(R.string.gt_cleaner_detail_protected, result.protectedSkips))
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
        details.forEach { (res, count) ->
            Text("• " + stringResource(res, count), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f), lineHeight = 15.sp)
        }
        if (result.trimmedInternalCaches) {
            Text("• " + stringResource(R.string.gt_cleaner_detail_trim), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f), lineHeight = 15.sp)
        }
    }
}

/**
 * Localized label for a cleaner bucket. The enum itself carries the English words (it has no
 * Context), so the mapping lives here at the call site inside the composable.
 */
@Composable
private fun categoryLabel(category: CleaningFeature.Category): String = stringResource(
    when (category) {
        CleaningFeature.Category.CACHE -> R.string.gt_cat_cache
        CleaningFeature.Category.TEMP -> R.string.gt_cat_temp
        CleaningFeature.Category.THUMBNAILS -> R.string.gt_cat_thumbs
        CleaningFeature.Category.EMPTY_FILES -> R.string.gt_cat_empty_files
        CleaningFeature.Category.EMPTY_DIRS -> R.string.gt_cat_empty_dirs
        CleaningFeature.Category.LOGS -> R.string.gt_cat_logs
        CleaningFeature.Category.CORPSES -> R.string.gt_cat_corpses
        CleaningFeature.Category.INSTALLERS -> R.string.gt_cat_installers
    }
)

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
            title = stringResource(R.string.gt_explainer_what),
            lines = listOf(
                stringResource(R.string.gt_net_what_1),
                stringResource(R.string.gt_net_what_2)
            )
        )

        // ---------- Switch 1: the local VPN ----------
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        NetworkBlockSwitch(
            title = stringResource(R.string.gt_net_vpn_title),
            // Never called a firewall: it is a local VPN, and saying otherwise would misdescribe what
            // the app does. The subtitle names both the mechanism and the one real trade-off.
            subtitle = stringResource(R.string.gt_net_vpn_sub),
            checked = vpnState.running,
            enabled = !isChangingVpn,
            busy = isChangingVpn,
            onCheckedChange = onSetVpn
        )
        Text(
            text = when {
                vpnState.running && vpnState.blockedCount > 0 ->
                    stringResource(R.string.gt_net_vpn_on_count, vpnState.blockedCount)
                vpnState.running -> stringResource(R.string.gt_net_on)
                else -> stringResource(R.string.gt_net_off)
            },
            fontSize = 11.sp,
            color = if (vpnState.running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
        // Kept on screen after a failure so the switch flicking back off is explained rather than
        // looking like the tap was missed.
        vpnState.lastError?.let { error ->
            Text(stringResource(R.string.gt_net_vpn_error, error), fontSize = 11.sp, color = Color(0xFFEF9A9A), lineHeight = 15.sp)
        }
        ExplainerBox(
            title = stringResource(R.string.gt_net_vpn_how_title),
            lines = listOf(
                stringResource(R.string.gt_net_vpn_how_1),
                stringResource(R.string.gt_net_vpn_how_2),
                stringResource(R.string.gt_net_vpn_how_3),
                stringResource(R.string.gt_net_vpn_how_4)
            )
        )

        // ---------- Switch 2: Android's own Data Saver ----------
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        NetworkBlockSwitch(
            title = stringResource(R.string.gt_net_ds_title),
            subtitle = stringResource(R.string.gt_net_ds_sub),
            checked = status?.dataSaverOn == true,
            enabled = hasPrivilege && !isChanging,
            busy = isChanging,
            onCheckedChange = onSetDataSaver
        )
        if (!hasPrivilege) {
            RequirementNotice(
                stringResource(R.string.gt_net_ds_need)
            )
        }

        // --- Live state, or an honest gap where a reading should be ---
        when {
            status == null -> Text(stringResource(R.string.gt_net_checking), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> {
                // Data Saver's own state and how many apps it is actually stopping, in one line, the
                // same shape as the VPN switch above. This used to be two lines, the second listing
                // every restricted package by name — but with Data Saver on that list is most of the
                // phone, so it pushed everything under it off screen and said nothing the count does
                // not. The number is `restrictedUids`, what `cmd netpolicy` actually reported, not the
                // subset whose names happened to resolve.
                val stoppedCount = status.restrictedUids.size
                Text(
                    when {
                        status.dataSaverOn == null -> stringResource(R.string.gt_net_unknown)
                        status.dataSaverOn == true && stoppedCount > 0 -> stringResource(R.string.gt_net_ds_on_count, stoppedCount)
                        status.dataSaverOn == true -> stringResource(R.string.gt_net_on)
                        // Off with apps still restricted is a real state, not a contradiction: the
                        // per-app blacklist blocks whether or not Data Saver is on, and `disable`
                        // deliberately leaves behind entries this app did not add.
                        stoppedCount > 0 -> stringResource(R.string.gt_net_ds_off_stopped, stoppedCount)
                        else -> stringResource(R.string.gt_net_off)
                    },
                    fontSize = 11.sp,
                    color = if (status.dataSaverOn == null) Color(0xFFFFB74D) else MaterialTheme.colorScheme.primary
                )
                if (engaged) {
                    Text(
                        stringResource(R.string.gt_net_engaged),
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    when (status.meteredNow) {
                        true -> stringResource(R.string.gt_net_metered)
                        false -> stringResource(R.string.gt_net_unmetered)
                        null -> stringResource(R.string.gt_net_unknown_net)
                    },
                    fontSize = 11.sp,
                    color = if (status.meteredNow == false) Color(0xFFFFB74D) else MaterialTheme.colorScheme.onSurfaceVariant
                )
                // The per-app list is gone, but this stays: a build whose `cmd netpolicy` cannot list
                // the blacklist is a real limitation the count line above cannot express, since an
                // unreadable list and an empty one both leave `restrictedUids` empty.
                if (!status.perAppSupported && status.privileged) {
                    Text(
                        stringResource(R.string.gt_net_no_perapp),
                        fontSize = 11.sp,
                        color = Color(0xFFFFB74D)
                    )
                }
                if (status.exemptedPackages.isNotEmpty()) {
                    Text(
                        stringResource(
                            R.string.gt_net_exempt,
                            status.exemptedPackages.size,
                            status.exemptedPackages.joinToString()
                        ),
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        ExplainerBox(
            title = stringResource(R.string.gt_net_ds_how_title),
            lines = listOf(
                stringResource(R.string.gt_net_ds_how_1),
                stringResource(R.string.gt_net_ds_how_2),
                stringResource(R.string.gt_net_ds_how_3)
            )
        )
        ExplainerBox(
            title = stringResource(R.string.gt_net_which_title),
            accent = Color(0xFFFFB300),
            lines = listOf(
                stringResource(R.string.gt_net_which_1),
                stringResource(R.string.gt_net_which_2),
                stringResource(R.string.gt_net_which_3),
                stringResource(R.string.gt_net_which_4)
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
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(subtitle, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 14.sp)
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
    onSetForcePeakRefreshRate: (Boolean) -> Unit,
    onSetGameDefaultFrameRateDisabled: (Boolean) -> Unit,
    /** Named animation-scale presets, in save order. */
    animPresets: List<AnimationPresetStore.AnimationPreset>,
    /** Name typed for the next animation preset. */
    animPresetName: String,
    onAnimPresetNameChange: (String) -> Unit,
    onSaveAnimPreset: () -> Unit,
    onApplyAnimPreset: (AnimationPresetStore.AnimationPreset) -> Unit,
    onDeleteAnimPreset: (String) -> Unit,
    /** Games the ANGLE picker lists; the card picks the first once loaded. */
    games: List<GameInfo>,
    /** Package the ANGLE card acts on, or null before selection. */
    anglePkg: String?,
    /** Driver the table currently holds for [anglePkg], or null when it holds none. */
    angleDriver: AngleDriverOptions.Driver?,
    /** True while an ANGLE write is in flight. */
    isChangingAngleDriver: Boolean,
    onSelectAnglePackage: (String?) -> Unit,
    onSetAngleDriver: (AngleDriverOptions.Driver) -> Unit,
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
        Text(stringResource(R.string.gt_dev_anim), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        ExplainerBox(
            title = stringResource(R.string.gt_explainer_what),
            lines = listOf(
                stringResource(R.string.gt_dev_anim_what_1),
                stringResource(R.string.gt_dev_anim_what_2)
            )
        )
        if (!canWriteGlobalSettings) {
            RequirementNotice(
                stringResource(R.string.gt_dev_anim_need)
            )
        }
        AnimationScaleRow(stringResource(R.string.gt_dev_anim_windows), AnimationScaleKind.WINDOW, animationScales.first, canWriteGlobalSettings, onSetAnimationScale)
        AnimationScaleRow(stringResource(R.string.gt_dev_anim_transition), AnimationScaleKind.TRANSITION, animationScales.second, canWriteGlobalSettings, onSetAnimationScale)
        AnimationScaleRow(stringResource(R.string.gt_dev_anim_animator), AnimationScaleKind.ANIMATOR, animationScales.third, canWriteGlobalSettings, onSetAnimationScale)

        AnimPresetLibrary(
            presets = animPresets,
            name = animPresetName,
            canWrite = canWriteGlobalSettings,
            onNameChange = onAnimPresetNameChange,
            onSave = onSaveAnimPreset,
            onApply = onApplyAnimPreset,
            onDelete = onDeleteAnimPreset
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // --- Process limit and discard activities ---
        DevSwitchRow(
            label = stringResource(R.string.gt_dev_keep_title),
            checked = backgroundProcessLimit,
            enabled = hasPrivilege,
            onCheckedChange = onToggleBackgroundLimit,
            explanation = listOf(
                stringResource(R.string.gt_dev_keep_1),
                stringResource(R.string.gt_dev_keep_2)
            )
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        DevSwitchRow(
            label = stringResource(R.string.gt_dev_close_title),
            checked = alwaysFinishActivities,
            enabled = hasPrivilege,
            onCheckedChange = onToggleAlwaysFinish,
            explanation = listOf(
                stringResource(R.string.gt_dev_close_1),
                stringResource(R.string.gt_dev_close_2)
            )
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        DevOptionSwitchRow(
            label = stringResource(R.string.gt_dev_peak),
            state = gameDevOptions.forcePeakRefreshRate,
            onCheckedChange = onSetForcePeakRefreshRate,
            onOpenDeveloperOptions = onOpenDeveloperOptions,
            explanation = listOf(
                stringResource(R.string.gt_dev_peak_1),
                stringResource(R.string.gt_dev_peak_2)
            )
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        DevOptionSwitchRow(
            label = stringResource(R.string.gt_dev_game_speed),
            state = gameDevOptions.gameDefaultFrameRateDisabled,
            onCheckedChange = onSetGameDefaultFrameRateDisabled,
            onOpenDeveloperOptions = onOpenDeveloperOptions,
            explanation = listOf(
                stringResource(R.string.gt_dev_game_speed_1),
                stringResource(R.string.gt_dev_game_speed_2)
            )
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        AngleDriverSection(
            games = games,
            anglePkg = anglePkg,
            angleDriver = angleDriver,
            isChanging = isChangingAngleDriver,
            canChange = hasPrivilege,
            onSelectPackage = onSelectAnglePackage,
            onSetDriver = onSetAngleDriver
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // Always offered, not only when a switch has failed: several of these live on Android's own
        // Developer options screen, and a user who wants to check or change one directly should not
        // have to hunt for it.
        CatsmokerOutlinedButton(onClick = onOpenDeveloperOptions, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.gt_dev_open_developer))
        }
    }
}

/**
 * Per-game graphics driver choice, on the same table Developer Options edits.
 *
 * The game picker reuses the library's app dialog; the driver chips write through
 * [AngleDriverOptions] with a read-back, so a refused write leaves the chips where they were.
 * The current choice reads through the provider and needs no privilege, which is why the
 * picker stays live while only the chips lock without root/Shizuku.
 */
@Composable
private fun AngleDriverSection(
    games: List<GameInfo>,
    anglePkg: String?,
    angleDriver: AngleDriverOptions.Driver?,
    isChanging: Boolean,
    canChange: Boolean,
    onSelectPackage: (String?) -> Unit,
    onSetDriver: (AngleDriverOptions.Driver) -> Unit
) {
    var picking by remember { mutableStateOf(false) }
    val pickedName = games.firstOrNull { it.packageName == anglePkg }?.appName

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.gt_angle_title), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        ExplainerBox(
            title = stringResource(R.string.gt_explainer_what),
            lines = listOf(
                stringResource(R.string.gt_graphics_layers),
                stringResource(R.string.gt_angle_1),
                stringResource(R.string.gt_angle_2)
            )
        )
        if (games.isEmpty()) {
            RequirementNotice(stringResource(R.string.gt_no_games_detected))
        } else {
            CatsmokerOutlinedButton(
                onClick = { picking = true },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isChanging
            ) {
                Text(pickedName ?: stringResource(R.string.gt_angle_pick))
            }
            // Three driver chips wrap instead of squeezing on a narrow phone.
            ChipFlowRow {
                AngleDriverOptions.Driver.entries.forEach { driver ->
                    FilterChip(
                        selected = angleDriver == driver,
                        onClick = { onSetDriver(driver) },
                        enabled = canChange && !isChanging && anglePkg != null,
                        label = {
                            Text(
                                when (driver) {
                                    AngleDriverOptions.Driver.ANGLE -> stringResource(R.string.gt_angle_angle)
                                    AngleDriverOptions.Driver.NATIVE -> stringResource(R.string.gt_angle_native)
                                    AngleDriverOptions.Driver.DEFAULT -> stringResource(R.string.gt_angle_default)
                                },
                                fontSize = 11.sp
                            )
                        }
                    )
                }
            }
            // The table's own answer for the picked package — a missing entry is "no choice
            // stored", never a failure, and a 0 here would be a value the device never gave.
            Text(
                text = angleDriver?.let {
                    stringResource(
                        R.string.gt_angle_now,
                        when (it) {
                            AngleDriverOptions.Driver.ANGLE -> stringResource(R.string.gt_angle_angle)
                            AngleDriverOptions.Driver.NATIVE -> stringResource(R.string.gt_angle_native)
                            AngleDriverOptions.Driver.DEFAULT -> stringResource(R.string.gt_angle_default)
                        }
                    )
                } ?: stringResource(R.string.gt_angle_none),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!canChange) {
                RequirementNotice(stringResource(R.string.gt_angle_need))
            }
        }
    }

    if (picking) {
        AppPickerDialog(
            apps = games,
            onDismiss = { picking = false },
            onAppSelected = {
                onSelectPackage(it)
                picking = false
            }
        )
    }
}

/**
 * The extra suspend list: picked packages shown with a remove button, plus an add button that
 * opens the app picker. The active game and Catsmoker itself can never be honored however
 * picked (the store filters them at activation), which the note states rather than hiding.
 */
@Composable
private fun SuspendListContent(
    picked: Set<String>,
    apps: List<GameInfo>,
    hasPrivilege: Boolean,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit
) = PickedAppsContent(
    picked = picked,
    apps = apps,
    hasPrivilege = hasPrivilege,
    what1 = R.string.gt_suspend_what_1,
    what2 = R.string.gt_suspend_what_2,
    none = R.string.gt_suspend_none,
    some = R.string.gt_suspend_some,
    add = R.string.gt_suspend_add,
    remove = R.string.gt_suspend_remove,
    onAdd = onAdd,
    onRemove = onRemove
)

/**
 * The keep list: packages the Gaming Mode suspend sweep must leave running, shown with a remove
 * button plus an add button that opens the app picker. Its copy states the two rules the engine
 * enforces — an app is either kept or frozen, and the active game and Catsmoker itself are
 * always kept — rather than hiding the overlap cases.
 */
@Composable
private fun KeepListContent(
    picked: Set<String>,
    apps: List<GameInfo>,
    hasPrivilege: Boolean,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit
) = PickedAppsContent(
    picked = picked,
    apps = apps,
    hasPrivilege = hasPrivilege,
    what1 = R.string.gt_keep_what_1,
    what2 = R.string.gt_keep_what_2,
    none = R.string.gt_keep_none,
    some = R.string.gt_keep_some,
    add = R.string.gt_keep_add,
    remove = R.string.gt_keep_remove,
    onAdd = onAdd,
    onRemove = onRemove
)

/**
 * Shared body of the two user-picked package lists (freeze and keep): an explainer, the count
 * line, one removable row per pick, and the add button. Only the copy differs, so the layout,
 * privilege gate and name-resolution rule stay in one place — a picked package renders as its
 * app name when the picker source has it, and as the package name otherwise (never hidden).
 */
@Composable
private fun PickedAppsContent(
    picked: Set<String>,
    apps: List<GameInfo>,
    hasPrivilege: Boolean,
    @StringRes what1: Int,
    @StringRes what2: Int,
    @StringRes none: Int,
    @StringRes some: Int,
    @StringRes add: Int,
    @StringRes remove: Int,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit
) {
    val names = remember(picked, apps) {
        val byPkg = apps.associateBy { it.packageName }
        picked.sorted().map { pkg -> byPkg[pkg]?.appName ?: pkg }
            .zip(picked.sorted())
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        ExplainerBox(
            title = stringResource(R.string.gt_explainer_what),
            lines = listOf(stringResource(what1), stringResource(what2))
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (picked.isEmpty()) stringResource(none)
            else stringResource(some, picked.size),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(8.dp))
        names.forEach { (label, pkg) ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(label, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f), fontSize = 13.sp)
                TextButton(onClick = { onRemove(pkg) }) { Text(stringResource(remove)) }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        CatsmokerOutlinedButton(
            onClick = onAdd,
            modifier = Modifier.fillMaxWidth(),
            enabled = hasPrivilege
        ) {
            Text(stringResource(add))
        }
        if (!hasPrivilege) {
            Spacer(modifier = Modifier.height(8.dp))
            RequirementNotice(stringResource(R.string.gt_needs_root_shizuku_close))
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
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    if (checked) stringResource(R.string.gt_dev_on) else stringResource(R.string.gt_dev_off),
                    fontSize = 11.sp,
                    color = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        }
        if (!enabled) {
            RequirementNotice(stringResource(R.string.gt_needs_root_shizuku_change))
        }
        ExplainerBox(title = stringResource(R.string.gt_explainer_what), lines = explanation)
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
                    color = if (switchEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    when {
                        state.enabled == null && !state.available -> stringResource(R.string.gt_dev_na)
                        state.enabled == null -> stringResource(R.string.gt_dev_unknown)
                        state.enabled == true -> stringResource(R.string.gt_dev_on)
                        else -> stringResource(R.string.gt_dev_off)
                    },
                    fontSize = 11.sp,
                    color = when {
                        state.enabled == null -> Color(0xFFFFB74D)
                        state.enabled == true -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
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
            Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.unavailableReason?.let {
            Text(it, fontSize = 11.sp, color = Color(0xFFFFB74D), lineHeight = 15.sp)
        }
        if (state.openDeveloperOptions) {
            CatsmokerOutlinedButton(onClick = onOpenDeveloperOptions, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.gt_dev_open_android))
            }
        }
        if (state.available && state.enabled == null) {
            Text(
                stringResource(R.string.gt_dev_unknown_long),
                fontSize = 11.sp, color = Color(0xFFFFB74D)
            )
        }
        ExplainerBox(title = stringResource(R.string.gt_explainer_what), lines = explanation)
    }
}

/**
 * The Private DNS provider chips, shared by the supported and unsupported branches.
 *
 * Rendered disabled (never hidden) where Private DNS does not exist, so the options
 * stay visible with the reason above them. A disabled chip cannot be tapped, so the
 * write callbacks can never fire from the unsupported branch.
 */
@Composable
private fun DnsProviderChips(
    selectedHostname: String?,
    selectedMode: DnsFeature.Mode?,
    chipsEnabled: Boolean,
    onApplyProvider: (DnsFeature.Provider) -> Unit,
    onSetAutomatic: () -> Unit,
    onDisable: () -> Unit
) {
    // Chips rather than buttons: one of these is the current state, and a chip can show
    // which without a separate label.
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        DnsFeature.PROVIDERS.forEach { provider ->
            FilterChip(
                selected = selectedHostname == provider.hostname &&
                    selectedMode == DnsFeature.Mode.PROVIDER,
                onClick = { onApplyProvider(provider) },
                enabled = chipsEnabled,
                label = { Text(provider.label, fontSize = 12.sp) }
            )
        }
        FilterChip(
            selected = selectedMode == DnsFeature.Mode.AUTOMATIC,
            onClick = onSetAutomatic,
            enabled = chipsEnabled,
            label = { Text(stringResource(R.string.gt_dns_auto), fontSize = 12.sp) }
        )
        FilterChip(
            selected = selectedMode == DnsFeature.Mode.OFF,
            onClick = onDisable,
            enabled = chipsEnabled,
            label = { Text(stringResource(R.string.gt_dns_off), fontSize = 12.sp) }
        )
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
    onDisable: () -> Unit,
    /** Measured reply ms per provider address (null per address = never replied); null = never measured. */
    pingMs: Map<String, Int?>?,
    isMeasuringPing: Boolean,
    onMeasurePing: () -> Unit
) {
    // Private DNS is equally settable from Settings, so the reading is refreshed on open rather than
    // trusted from whenever this app last wrote it.
    LaunchedEffect(Unit) { onRefresh() }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (isChanging) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.gt_dns_applying), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        when {
            status == null -> Text(stringResource(R.string.gt_dns_checking), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            !status.supported -> {
                Text(
                    status.unsupportedReason ?: stringResource(R.string.gt_dns_unsupported_generic),
                    fontSize = 12.sp, color = Color(0xFFEF9A9A)
                )
                // Global rule: the provider options stay visible but disabled, so
                // the user sees what exists on newer phones instead of nothing.
                // Disabled chips cannot be tapped, so nothing is written.
                Spacer(modifier = Modifier.height(8.dp))
                DnsProviderChips(
                    selectedHostname = null,
                    selectedMode = null,
                    chipsEnabled = false,
                    onApplyProvider = onApplyProvider,
                    onSetAutomatic = onSetAutomatic,
                    onDisable = onDisable
                )
            }
            else -> {
                Text(
                    stringResource(
                        R.string.gt_dns_now_using,
                        status.mode?.let { dnsModeLabel(it) }
                            ?: status.rawMode
                            ?: stringResource(R.string.gt_dns_could_not_read)
                    ),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (status.mode == null) Color(0xFFFFB74D) else MaterialTheme.colorScheme.primary
                )
                status.hostname?.let {
                    Text(stringResource(R.string.gt_dns_set_to, it), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // The one line that proves anything happened. If it does not change after applying a
                // provider, the change did not take, whatever the toast said.
                Text(
                    if (status.activeServers.isEmpty()) {
                        stringResource(R.string.gt_dns_no_servers)
                    } else {
                        stringResource(R.string.gt_dns_in_use, status.activeServers.joinToString())
                    },
                    fontSize = 11.sp,
                    color = if (status.activeServers.isEmpty()) Color(0xFFFFB74D) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                )
                status.validatedPrivateDns?.let {
                    Text(stringResource(R.string.gt_dns_validated, it), fontSize = 11.sp, color = Color(0xFF81C784))
                }
                if (status.vpnActive) {
                    Text(
                        stringResource(R.string.gt_dns_vpn_note),
                        fontSize = 11.sp, color = Color(0xFFFFB74D)
                    )
                }
                if (!status.canWrite) {
                    RequirementNotice(
                        stringResource(R.string.gt_dns_need)
                    )
                }

                val writable = status.canWrite && !isChanging
                Spacer(modifier = Modifier.height(2.dp))
                // Chips rather than buttons: one of these is the current state, and a chip can show
                // which without a separate label.
                DnsProviderChips(
                    selectedHostname = status.hostname,
                    selectedMode = status.mode,
                    chipsEnabled = writable,
                    onApplyProvider = onApplyProvider,
                    onSetAutomatic = onSetAutomatic,
                    onDisable = onDisable
                )

                val selected = DnsFeature.PROVIDERS.firstOrNull { it.hostname == status.hostname }
                if (selected != null && status.mode == DnsFeature.Mode.PROVIDER) {
                    ExplainerBox(
                        title = stringResource(R.string.gt_dns_about, selected.label),
                        lines = listOf(
                            dnsProviderNote(selected),
                            stringResource(
                                R.string.gt_dns_about_proof,
                                selected.addresses.joinToString()
                            )
                        )
                    )
                }

                // Measured reply times, one line per provider — the numbers behind "usually
                // the fastest". Never measured reads as no rows at all; a provider whose
                // address never replied reads as "no reply", never 0 ms.
                CatsmokerOutlinedButton(
                    onClick = onMeasurePing,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isMeasuringPing && !isChanging
                ) {
                    if (isMeasuringPing) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.gt_dns_ping_measure), fontSize = 12.sp)
                    }
                }
                pingMs?.forEach { (host, ms) ->
                    val label = DnsFeature.PROVIDERS.firstOrNull { host in it.addresses }?.label
                    Text(
                        text = "${label ?: host} · $host · " +
                            (ms?.let { "$it ms" } ?: stringResource(R.string.gt_dns_ping_none)),
                        fontSize = 11.sp,
                        color = if (ms != null) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        ExplainerBox(
            title = stringResource(R.string.gt_explainer_what),
            lines = listOf(
                stringResource(R.string.gt_dns_what_1),
                stringResource(R.string.gt_dns_what_2),
                stringResource(R.string.gt_dns_what_3)
            )
        )

        ExplainerBox(
            title = stringResource(R.string.gt_dns_ping_title),
            accent = Color(0xFFFFB300),
            lines = listOf(
                stringResource(R.string.gt_dns_ping_1),
                stringResource(R.string.gt_dns_ping_2),
                stringResource(R.string.gt_dns_ping_3)
            )
        )
    }
}

/**
 * Localized DNS mode name. The enum itself keeps the `Settings.Global` values (never translated),
 * so the mapping lives here at the call site inside the composable.
 */
@Composable
private fun dnsModeLabel(mode: DnsFeature.Mode): String = stringResource(
    when (mode) {
        DnsFeature.Mode.OFF -> R.string.gt_dns_off
        DnsFeature.Mode.AUTOMATIC -> R.string.gt_dns_auto
        DnsFeature.Mode.PROVIDER -> R.string.gt_dns_mode_provider
    }
)

/**
 * Localized provider note, keyed by provider id. Hostnames, addresses and operator names stay
 * exactly as published (Private DNS validates the certificate against the hostname).
 */
@Composable
private fun dnsProviderNote(provider: DnsFeature.Provider): String = stringResource(
    when (provider.id) {
        "google" -> R.string.gt_dns_note_google
        "quad9" -> R.string.gt_dns_note_quad9
        "adguard" -> R.string.gt_dns_note_adguard
        else -> R.string.gt_dns_note_cloudflare
    }
)

@Composable
fun BoostContent(level: Int, outputDevice: String?, onLevelChange: (Int) -> Unit) {
    // Local drag state: committing on every pixel would rebuild the audio effect chain and
    // write SharedPreferences dozens of times per gesture.
    var draft by remember(level) { mutableFloatStateOf(level.toFloat()) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.gt_boost_extra, draft.toInt()), color = MaterialTheme.colorScheme.primary)
        Slider(
            value = draft,
            onValueChange = { draft = it },
            onValueChangeFinished = { onLevelChange(draft.toInt()) },
            valueRange = 0f..100f
        )
        // Read from the audio system, not guessed, so it names whatever is actually playing.
        if (outputDevice != null) {
            Text(
                stringResource(R.string.gt_boost_output, outputDevice),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        ExplainerBox(
            title = stringResource(R.string.gt_explainer_what),
            lines = listOf(
                stringResource(R.string.gt_boost_what_1),
                stringResource(R.string.gt_boost_what_2)
            )
        )
        ExplainerBox(
            title = stringResource(R.string.gt_boost_safe_title),
            accent = Color(0xFFFFB300),
            lines = listOf(
                stringResource(R.string.gt_boost_safe_1),
                stringResource(R.string.gt_boost_safe_2)
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
            Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
            // A value another app set that is not one of the three is shown as it is, not snapped onto
            // the nearest chip — the chips would otherwise misreport what the system currently holds.
            if (ANIMATION_SCALE_VALUES.none { kotlin.math.abs(it - current) < 0.005f }) {
                Text(formatAnimationScale(current), fontSize = 10.sp, color = Color(0xFFFFB74D))
            }
        }
        // Three compact chips wrap instead of squeezing next to the fixed label.
        ChipFlowRow {
            ANIMATION_SCALE_VALUES.forEach { value ->
                FilterChip(
                    selected = kotlin.math.abs(current - value) < 0.005f,
                    onClick = { onSetScale(kind, value) },
                    enabled = canWrite,
                    label = { Text(if (value == 0f) stringResource(R.string.gt_dev_anim_off) else formatAnimationScale(value), fontSize = 11.sp) }
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

/**
 * Named animation-scale preset library (M67).
 *
 * "Save current" snapshots the live triple the rows above show — never a typed number — so a
 * preset is always a configuration the device actually held. Applying runs each scale through
 * the verified single-scale path; the toast (not this list) reports the honest count.
 */
@Composable
private fun AnimPresetLibrary(
    presets: List<AnimationPresetStore.AnimationPreset>,
    name: String,
    canWrite: Boolean,
    onNameChange: (String) -> Unit,
    onSave: () -> Unit,
    onApply: (AnimationPresetStore.AnimationPreset) -> Unit,
    onDelete: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            stringResource(R.string.gt_preset_anim_title),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = onNameChange,
                label = { Text(stringResource(R.string.gt_preset_name)) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            CatsmokerOutlinedButton(onClick = onSave) { Text(stringResource(R.string.gt_preset_anim_save)) }
        }
        if (presets.isEmpty()) {
            Text(
                stringResource(R.string.gt_preset_empty),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }
        presets.forEach { preset ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(preset.name, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
                    Text(
                        "${formatAnimationScale(preset.window)} · ${formatAnimationScale(preset.transition)} · ${formatAnimationScale(preset.animator)}",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = { onApply(preset) }, enabled = canWrite) {
                    Text(stringResource(R.string.gt_preset_apply))
                }
                TextButton(onClick = { onDelete(preset.id) }) {
                    Text(stringResource(R.string.gt_preset_delete))
                }
            }
        }
    }
}

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
    history: List<BoosterRun>,
    games: List<GameInfo>,
    /** Single-game scope, or null for the all-games sweep. Shared with the library button. */
    scopePkg: String?,
    /** Package with a single-game pre-compile in flight, or null. */
    optimizingSinglePkg: String?,
    onScopeChange: (String?) -> Unit,
    onOptimizeSingle: (String) -> Unit,
    scheduleEnabled: Boolean,
    scheduleIntervalHours: Int,
    scheduleNextRunAt: Long?,
    onScheduleEnabledChange: (Boolean) -> Unit,
    onScheduleIntervalChange: (Int) -> Unit,
    onRun: (String, Boolean) -> Unit,
    onStop: () -> Unit
) {
    var force by remember { mutableStateOf(false) }
    Column {
        ExplainerBox(
            title = stringResource(R.string.gt_explainer_what),
            lines = listOf(
                stringResource(R.string.gt_booster_what_1),
                stringResource(R.string.gt_booster_what_2),
                stringResource(R.string.gt_booster_what_3)
            )
        )
        Spacer(modifier = Modifier.height(6.dp))
        ExplainerBox(
            title = stringResource(R.string.gt_booster_cost_title),
            accent = Color(0xFFFFB300),
            lines = listOf(
                stringResource(R.string.gt_booster_cost_1),
                stringResource(R.string.gt_booster_cost_2),
                stringResource(R.string.gt_booster_cost_3)
            )
        )
        Spacer(modifier = Modifier.height(6.dp))
        ExplainerBox(
            title = stringResource(R.string.gt_booster_why_title),
            lines = listOf(
                stringResource(R.string.gt_booster_why_1),
                stringResource(R.string.gt_booster_why_2),
                stringResource(R.string.gt_booster_why_3)
            )
        )
        Spacer(modifier = Modifier.height(8.dp))

        // One backend, two scopes (F8): the all-games sweep (`speed`, minutes) and the
        // single-game pre-compile (`speed-profile`, seconds). The library PRE-COMPILE
        // button deep-links here through the same scope state, so both entries run one
        // system instead of looking like two features.
        Text(
            text = stringResource(R.string.gt_booster_scope_title),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = scopePkg == null,
                onClick = { onScopeChange(null) },
                label = { Text(stringResource(R.string.gt_booster_scope_all)) }
            )
            FilterChip(
                selected = scopePkg != null,
                onClick = {
                    if (games.isNotEmpty()) {
                        onScopeChange(
                            games.firstOrNull { it.packageName == scopePkg }?.packageName
                                ?: games.first().packageName
                        )
                    }
                },
                enabled = games.isNotEmpty(),
                label = { Text(stringResource(R.string.gt_booster_scope_single)) }
            )
        }
        Text(
            text = if (scopePkg == null) stringResource(R.string.gt_booster_scope_hint_all)
            else stringResource(R.string.gt_booster_scope_hint_single),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (scopePkg != null) {
            Spacer(modifier = Modifier.height(4.dp))
            if (games.isEmpty()) {
                Text(
                    text = stringResource(R.string.gt_booster_no_games_scope),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    games.forEach { game ->
                        FilterChip(
                            selected = game.packageName == scopePkg,
                            onClick = { onScopeChange(game.packageName) },
                            label = {
                                Text(
                                    game.appName,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                CatsmokerButton(
                    onClick = { onOptimizeSingle(scopePkg) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.isRunning && optimizingSinglePkg == null
                ) {
                    Text(stringResource(R.string.gt_booster_precompile))
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        Spacer(modifier = Modifier.height(8.dp))

        // Without -f the platform skips any app already in the requested filter, so a re-run would
        // report success having compiled nothing. Kept as the reference project's "force optimize".
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = force, onCheckedChange = { force = it }, enabled = !state.isRunning)
            Text(stringResource(R.string.booster_force_label), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(modifier = Modifier.height(8.dp))

        if (state.isRunning) {
            CatsmokerButton(
                onClick = onStop,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) { Text(stringResource(R.string.booster_stop)) }
        } else {
            CatsmokerButton(onClick = { onRun("speed", force) }, modifier = Modifier.fillMaxWidth()) {
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
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )

        if (state.isRunning) {
            Spacer(modifier = Modifier.height(8.dp))
            val progress = state.progress
            if (progress == null) {
                // Still querying the app list: there is no percentage yet, so a
                // travelling wave shows work without pretending a percentage.
                SquigglyProgressBar(progress = null, animate = true)
            } else {
                SquigglyProgressBar(progress = progress, animate = true)
            }
        }

        if (log.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Box(modifier = Modifier.fillMaxWidth().height(100.dp).clip(RoundedCornerShape(8.dp)).background(LogTerminalBackground).padding(8.dp)) {
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

        // The log above dies with the app; this is every finished sweep the device actually
        // reported, newest first, so "what did the last boost do" has an answer tomorrow too.
        if (history.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            CollapsibleExplainer(
                title = stringResource(R.string.gt_booster_history),
                lines = history.asReversed().take(10).map { run -> boosterHistoryLine(run) }
            )
        }

        // The recurring version of the same sweep, driven by WorkManager while the app is closed.
        // Its stated limits are in the section's own explainer — rough intervals, skipped (not
        // failed) runs without privilege, and WorkManager's estimate rather than a promised time.
        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(modifier = Modifier.height(12.dp))
        DexoptScheduleSection(
            enabled = scheduleEnabled,
            intervalHours = scheduleIntervalHours,
            nextRunAt = scheduleNextRunAt,
            onEnabledChange = onScheduleEnabledChange,
            onIntervalChange = onScheduleIntervalChange
        )
    }
}

/**
 * Manual dangerous-permission toggle for one target app. Every destructive step is explicit:
 * picking names the app, each row names the permission and its live state, revoke asks first
 * (the dialog, not the row button, runs it), and restore-all regrants exactly what this tool
 * took. A refused revoke records nothing; a refused regrant stays recorded and reported.
 */
@Composable
private fun PermToggleContent(
    targetLabel: String?,
    targetPkg: String?,
    entries: List<ToggleablePerm>,
    isBusy: Boolean,
    hasPrivilege: Boolean,
    onPickTarget: () -> Unit,
    onRevoke: (String, String) -> Unit,
    onRegrant: (String, String) -> Unit,
    onRestoreAll: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ExplainerBox(
            title = stringResource(R.string.gt_perm_what_title),
            lines = listOf(
                stringResource(R.string.gt_perm_what_1),
                stringResource(R.string.gt_perm_what_2)
            )
        )
        CatsmokerOutlinedButton(onClick = onPickTarget, modifier = Modifier.fillMaxWidth()) {
            Text(targetLabel ?: stringResource(R.string.gt_perm_pick))
        }
        if (targetPkg != null) {
            if (entries.isEmpty()) {
                Text(
                    stringResource(R.string.gt_perm_none_toggleable, targetLabel ?: targetPkg),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            entries.forEach { entry ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            entry.permission.substringAfterLast('.'),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            if (entry.granted) stringResource(R.string.gt_perm_granted_state)
                            else stringResource(R.string.gt_perm_revoked_state),
                            fontSize = 11.sp,
                            color = if (entry.granted) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (entry.granted) {
                        CatsmokerOutlinedButton(
                            onClick = { onRevoke(targetPkg, entry.permission) },
                            enabled = !isBusy && hasPrivilege
                        ) { Text(stringResource(R.string.gt_perm_revoke), fontSize = 12.sp) }
                    } else {
                        CatsmokerButton(
                            onClick = { onRegrant(targetPkg, entry.permission) },
                            enabled = !isBusy && hasPrivilege
                        ) { Text(stringResource(R.string.gt_perm_grant), fontSize = 12.sp) }
                    }
                }
            }
            if (entries.any { it.wasRevokedByUs }) {
                CatsmokerOutlinedButton(
                    onClick = { onRestoreAll(targetPkg) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isBusy && hasPrivilege
                ) { Text(stringResource(R.string.gt_perm_restore_all)) }
            }
            if (!hasPrivilege) {
                Text(
                    stringResource(R.string.gt_perm_needs_priv),
                    fontSize = 11.sp,
                    color = Color(0xFFFFB300)
                )
            }
        }
    }
}

/**
 * The recurring junk sweep's controls. Same contract as the dexopt schedule: the switch state
 * is the persisted setting *and* the enrollment, the next-run line is WorkManager's own
 * estimate, and the interval chips retune in place. The explainer states the unattended
 * boundary up front — safe buckets only, aggressive ones stay manual in every run.
 */
@Composable
private fun CleanerScheduleSection(
    enabled: Boolean,
    intervalHours: Int,
    nextRunAt: Long?,
    onEnabledChange: (Boolean) -> Unit,
    onIntervalChange: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.gt_booster_schedule),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    when {
                        enabled && nextRunAt != null -> stringResource(R.string.gt_booster_next, formatScheduleTime(nextRunAt))
                        enabled -> stringResource(R.string.gt_booster_next_unknown)
                        else -> stringResource(R.string.gt_booster_sched_off)
                    },
                    fontSize = 11.sp,
                    color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = enabled, onCheckedChange = onEnabledChange)
        }

        if (enabled) {
            ChipFlowRow {
                CleanerScheduleStore.INTERVAL_CHOICES.forEach { hours ->
                    FilterChip(
                        selected = intervalHours == hours,
                        onClick = { onIntervalChange(hours) },
                        label = { Text(intervalLabel(hours)) }
                    )
                }
            }
        }

        ExplainerBox(
            title = stringResource(R.string.gt_booster_sched_what_title),
            lines = listOf(
                stringResource(R.string.gt_cleaner_sched_1),
                stringResource(R.string.gt_cleaner_sched_2),
                stringResource(R.string.gt_cleaner_sched_3)
            )
        )
    }
}

/**
 * The recurring dexopt sweep's controls. Every claim on the row is something the device or
 * WorkManager reported: the switch state is the persisted setting *and* the enrollment, and the
 * next-run line is WorkManager's own current estimate — "unknown" when it will not say, never a
 * time nobody promised.
 */
@Composable
private fun DexoptScheduleSection(
    enabled: Boolean,
    intervalHours: Int,
    nextRunAt: Long?,
    onEnabledChange: (Boolean) -> Unit,
    onIntervalChange: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.gt_booster_schedule),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    when {
                        enabled && nextRunAt != null -> stringResource(R.string.gt_booster_next, formatScheduleTime(nextRunAt))
                        enabled -> stringResource(R.string.gt_booster_next_unknown)
                        else -> stringResource(R.string.gt_booster_sched_off)
                    },
                    fontSize = 11.sp,
                    color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = enabled, onCheckedChange = onEnabledChange)
        }

        if (enabled) {
            // Changing the interval retunes the schedule in place (UPDATE, not REPLACE), so the
            // countdown is not restarted from zero on every tap.
            // Four chips wrap instead of scrolling: always fully visible.
            ChipFlowRow {
                DexoptScheduleStore.INTERVAL_CHOICES.forEach { hours ->
                    FilterChip(
                        selected = intervalHours == hours,
                        onClick = { onIntervalChange(hours) },
                        label = { Text(intervalLabel(hours)) }
                    )
                }
            }
        }

        ExplainerBox(
            title = stringResource(R.string.gt_booster_sched_what_title),
            lines = listOf(
                stringResource(R.string.gt_booster_sched_1),
                stringResource(R.string.gt_booster_sched_2),
                stringResource(R.string.gt_booster_sched_3),
                stringResource(R.string.gt_booster_sched_4)
            )
        )
    }
}

@Composable
private fun intervalLabel(hours: Int): String = when (hours) {    24 -> stringResource(R.string.gt_booster_every_day)
    72 -> stringResource(R.string.gt_booster_every_3)
    168 -> stringResource(R.string.gt_booster_every_week)
    else -> stringResource(R.string.gt_booster_every_h, hours)
}

@Composable
private fun formatScheduleTime(at: Long): String =
    java.text.SimpleDateFormat("d MMM, HH:mm", LocalConfiguration.current.locales[0]).format(java.util.Date(at))

/** One history row: when, what it achieved, how long it took, and how it ended. */
@Composable
private fun boosterHistoryLine(run: BoosterRun): String {
    val locale = LocalConfiguration.current.locales[0]
    val when_ = java.text.SimpleDateFormat("d MMM, HH:mm", locale)
        .format(java.util.Date(run.startedAt))
    val minutes = run.durationMs / 60000
    val seconds = (run.durationMs % 60000) / 1000
    val duration = stringResource(R.string.gt_booster_hist_dur, minutes, seconds)
    val tail = when (run.outcome) {
        "cancelled" -> stringResource(R.string.gt_booster_hist_cancelled)
        "failed" -> stringResource(R.string.gt_booster_hist_failed)
        else -> ""
    }
    return stringResource(
        R.string.gt_booster_hist,
        when_,
        run.optimized,
        run.skipped,
        run.failed,
        duration,
        tail
    )
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
fun AppPickerDialog(
    apps: List<GameInfo>,
    onDismiss: () -> Unit,
    onAppSelected: (String) -> Unit,
    /** Dialog title: the game library and the freeze list share this picker (§23). */
    @StringRes titleRes: Int = R.string.gt_picker_add_app
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }

    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(titleRes)) }, text = {
        Column {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text(stringResource(R.string.gt_picker_search)) },
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Default.Search, null) }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // The list is filtered by label and package name: a package name is often the only
            // way to tell two apps with the same display name apart, so both must match.
            val query = searchQuery.trim()
            val filtered = if (query.isEmpty()) apps else apps.filter {
                it.appName.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true)
            }

            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if (apps.isEmpty()) stringResource(R.string.gt_picker_loading) else stringResource(R.string.gt_picker_none),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp
                    )
                }
            } else {
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(filtered, key = { it.packageName }) { app ->
                        // Cached per row (key already exists above): same scroll-churn reason as the other lists.
                        val pickerIcon = remember(app.packageName, app.icon) { app.icon.toBitmap().asImageBitmap() }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onAppSelected(app.packageName) }
                                .padding(8.dp)
                        ) {
                            Image(
                                bitmap = pickerIcon,
                                contentDescription = null,
                                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp))
                            )
                            Column {
                                Text(
                                    app.appName,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    app.packageName,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.gt_picker_cancel)) } })
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
            boosterHistory = emptyList(),
            animationScales = Triple(1f, 1f, 1f),
            alwaysFinishActivities = false,
            backgroundProcessLimit = false,
            gameDevOptions = GameDeveloperOptions.State(),
            interventionDownscale = null,
            pointerSpeedChoice = null,
            onToggleOverlay = {},
            onSetOverlayMoveMode = {},
            onResetOverlayPosition = {},
            onToggleCrosshair = {},
            onSelectCrosshair = {},
            onSetCrosshairMoveMode = {},
            onRecentreCrosshair = {},
            onSetBackgroundDataRestriction = {},
            onToggleDnd = {},
            onPerformMaintenance = {},
            onScanJunk = {},
            onSetCleanSchedule = {},
            onSetCleanInterval = {},
            onShowPermPicker = {},
            onRequestPermRevoke = { _, _ -> },
            onRegrantPerm = { _, _ -> },
            onRestorePerms = {},
            onAddCleanerKeepEntry = {},
            onRemoveCleanerKeepEntry = {},
            onAddCleanerCleanPattern = {},
            onRemoveCleanerCleanPattern = {},
            onGrantStorageAccess = {},
            onActivateGamingMode = {},
            onDeactivateGamingMode = {},
            onBoostRam = {},
            onTrimStorage = {},
            onRunBooster = { _, _ -> },
            onStopBooster = {},
            onSetDexoptSchedule = {},
            onSetDexoptInterval = {},
            onToggleFixedPerformance = {},
            onSetInterventionDownscale = {},
            onSetPointerSpeedChoice = {},
            onBoostChange = {},
            onSetAnimationScale = { _, _ -> },
            onAnimPresetNameChange = {},
            onSaveAnimPreset = {},
            onApplyAnimPreset = {},
            onDeleteAnimPreset = {},
            onToggleAlwaysFinish = {},
            onToggleBackgroundLimit = {},
            onSetForcePeakRefreshRate = {},
            onSetGameDefaultFrameRateDisabled = {},
            onSelectAnglePackage = {},
            onSetAngleDriver = {},
            onOpenDeveloperOptions = {},
            onSetVpnFirewall = {},
            onRefreshDns = {},
            onApplyDnsProvider = {},
            onSetDnsAutomatic = {},
            onDisableDns = {},
            onMeasureDnsPing = {},
            onLaunchGame = {},
            onOptimizeGame = {},
            onSelectOptimizeScope = {},
            onRemoveGame = {},
            onAddGameClicked = {},
            onToggleAutoForceStop = {},
            onToggleAutoForceStopKeepPackage = {},
            onShowSuspendPicker = {},
            onRemoveExtraSuspendPackage = {},
            onShowSuspendExemptPicker = {},
            onRemoveSuspendExemptPackage = {},
            onToggleGameSession = {},
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
