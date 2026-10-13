package com.catsmoker.app.features.editgamefiles.wuwa

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.catsmoker.app.R
import com.catsmoker.app.features.editgamefiles.ConfigBackupStore
import com.catsmoker.app.features.gamingtools.ui.CollapsibleExplainer
import com.catsmoker.app.shared.ui.components.ScreenScaffold
import com.catsmoker.app.shared.ui.components.SectionCard
import kotlinx.coroutines.flow.collectLatest
import java.text.SimpleDateFormat
import java.util.Date
import com.catsmoker.app.shared.ui.components.CatsmokerButton
import com.catsmoker.app.shared.ui.components.CatsmokerOutlinedButton
import com.catsmoker.app.shared.ui.components.ChipFlowRow

/**
 * Wuthering Waves config generator. Cloned from the HSR Graphics screen's shape rather than
 * drawn from blank, per the house convention. Unlike HSR there is no load-failure gate: the
 * generator works from device facts and falls back on its own when a read fails, so the
 * screen always renders — a missing game install or an unreadable GPU is a warning card,
 * not a dead end.
 *
 * Hosted two ways: as a destination of its own (non-null [onBack]) or embedded inside File
 * Engineering's game selector (null — no second header below that screen's).
 *
 * Card rhythm is the host's: cards carry no vertical padding of their own and the content
 * column spaces its children 12 dp apart, so an embedded editor reads as more sections of
 * the File Engineering screen rather than a different screen pasted below the selector —
 * the per-card `vertical = 8.dp` padding used to read exactly that way. Action buttons are
 * uppercase to match the host's APPLY PROFILE / RESTORE BACKUP idiom.
 */
@Composable
fun WuwaConfigRoute(onBack: (() -> Unit)? = null) {
    val viewModel: WuwaConfigViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // SAF folder picker: the tree grant lives under the game's Android/data root. The deploy
    // that asked for it is parked in the ViewModel and re-runs once the grant lands.
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.onSafFolderPicked(uri)
    }

    // One collector for every one-shot event: toasts show inline, the SAF folder request is
    // answered by the picker launcher above. A second collector on the same no-replay flow
    // would race it — a third event kind must extend this `when`, not add a collector.
    LaunchedEffect(Unit) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is WuwaConfigViewModel.WuwaEvent.Toast ->
                    Toast.makeText(context, event.message, if (event.isLong) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
                is WuwaConfigViewModel.WuwaEvent.LaunchFolderPicker ->
                    runCatching { folderPicker.launch(null) }
            }
        }
    }

    // Community pack picker: a separate one-shot tree pick. No persistable grant is taken —
    // the import copies everything it finds into the app's own store.
    val packFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.importCommunityPack(uri)
    }

    WuwaConfigScreen(
        uiState = uiState,
        onSelectPreset = viewModel::selectPreset,
        onUpdateOptions = viewModel::updateOptions,
        onCvarOverrideTextChange = viewModel::onCvarOverrideTextChange,
        onDeploy = viewModel::onDeploy,
        onRestoreBackup = viewModel::onRestoreBackup,
        onApplyRecommendation = viewModel::applyRecommendation,
        onImportPack = { runCatching { packFolderPicker.launch(null) } },
        onDeployPackVariant = viewModel::deployCommunityVariant,
        onDeployPackVariantStripped = viewModel::deployCommunityVariantStripped,
        onDeletePack = viewModel::deleteCommunityPack,
        onVerifyHistory = viewModel::verifyHistoryRecord,
        onDeleteHistory = viewModel::deleteHistoryRecord,
        onClearHistory = viewModel::clearHistory,
        onStartAutoTune = viewModel::startAutoTune,
        onTunerRoundPlayed = viewModel::onTunerRoundPlayed,
        onApplyTunerResult = viewModel::applyTunerResult,
        onStopTuner = viewModel::stopTuner,
        onResetTuner = viewModel::resetTuner,
        onRefresh = viewModel::refresh,
        onShowAnyway = viewModel::onShowEditorAnyway,
        onBack = onBack
    )
}

/** Localized preset names. The keys stay identifiers; only the display text is localized. */
@Composable
private fun presetLabel(preset: String): String = when (preset) {
    "potato" -> stringResource(R.string.gf_preset_potato)
    "endurance" -> stringResource(R.string.gf_preset_endurance)
    "performance" -> stringResource(R.string.gf_preset_performance)
    "competitive" -> stringResource(R.string.gf_preset_competitive)
    "balanced" -> stringResource(R.string.gf_preset_balanced)
    "high" -> stringResource(R.string.gf_preset_high)
    "ultra" -> stringResource(R.string.gf_preset_ultra)
    "cinematic" -> stringResource(R.string.gf_preset_cinematic)
    else -> preset
}

/** Localized game-mode names. Mirrors [WuWaConfigGenerator.GameMode.label]. */
@Composable
private fun gameModeLabel(mode: WuWaConfigGenerator.GameMode): String = when (mode) {
    WuWaConfigGenerator.GameMode.Overworld -> stringResource(R.string.gf_mode_overworld)
    WuWaConfigGenerator.GameMode.ToA -> stringResource(R.string.gf_mode_toa)
}

@Composable
fun WuwaConfigScreen(
    uiState: WuwaConfigUiState,
    onSelectPreset: (String) -> Unit,
    onUpdateOptions: ((WuWaConfigGenerator.Options) -> WuWaConfigGenerator.Options) -> Unit,
    onCvarOverrideTextChange: (String) -> Unit,
    onDeploy: (WuwaDeployChannel) -> Unit,
    onRestoreBackup: (ConfigBackupStore.Entry) -> Unit,
    onApplyRecommendation: () -> Unit,
    onImportPack: () -> Unit,
    onDeployPackVariant: (String, String) -> Unit,
    onDeployPackVariantStripped: (String, String) -> Unit,
    onDeletePack: (String) -> Unit,
    onVerifyHistory: (WuwaDeployHistoryStore.Record) -> Unit,
    onDeleteHistory: (WuwaDeployHistoryStore.Record) -> Unit,
    onClearHistory: () -> Unit,
    onStartAutoTune: () -> Unit,
    onTunerRoundPlayed: () -> Unit,
    onApplyTunerResult: () -> Unit,
    onStopTuner: () -> Unit,
    onResetTuner: () -> Unit,
    onRefresh: () -> Unit,
    onShowAnyway: () -> Unit = {},
    onBack: (() -> Unit)? = null
) {
    // One body, two hosts: standalone (own ScreenScaffold header + scroll) or embedded in File
    // Engineering's selector, where the host already scrolled and padded the page — a second
    // header would double it, and a nested verticalScroll measured under the host's infinite
    // height constraints crashes, so embedded renders the Column bare. The 12 dp rhythm
    // matches the host's card spacing (see the class KDoc).
    val content: @Composable () -> Unit = {
        Column(
            modifier = if (onBack == null) Modifier
            else Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
        ) {
            if (uiState.loading) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 96.dp),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }
                return@Column
            }

            // The same gate the File Engineering screen applies to the profile games: when
            // the package probe says the game is absent, an install-first card replaces the
            // whole editor — unless the small secondary "Show anyway" button on it was
            // tapped, which reveals the existing generator below the card (its previews run
            // on device facts and need no install). Unknown (probe could not answer) still
            // shows the editor; the card stays visible while revealed, so the screen never
            // pretends the game is installed.
            if (uiState.gameInstalled == false && !uiState.showEditorAnyway) {
                WuwaNotInstalledCard(onRefresh = onRefresh, onShowAnyway = onShowAnyway)
                return@Column
            }
            if (uiState.gameInstalled == false) {
                WuwaNotInstalledCard(onRefresh = onRefresh, onShowAnyway = null)
                Spacer(modifier = Modifier.height(12.dp))
            }

            uiState.recommendation?.let { rec ->
                RecommendCard(rec, uiState.preset, uiState.chipset, onApplyRecommendation)
                Spacer(modifier = Modifier.height(12.dp))
            }

            // The recommendation's apply target sits directly below it — the preset chips
            // used to live several cards down, past the auto-tune card.
            SectionCard {
                Text(stringResource(R.string.gf_preset_section), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)
                Spacer(modifier = Modifier.height(8.dp))
                // Every preset visible at once — a wrapping flow, never a sideways strip.
                ChipFlowRow {
                    WuWaConfigGenerator.PRESET_ORDER.forEach { preset ->
                        FilterChip(
                            selected = uiState.preset == preset,
                            onClick = { onSelectPreset(preset) },
                            label = { Text(presetLabel(preset)) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(R.string.gf_wuwa_preset_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            SectionCard {
                Text(stringResource(R.string.gf_frame_rate), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)
                Spacer(modifier = Modifier.height(8.dp))
                ChipFlowRow {
                    listOf(30, 60, 90, 120).forEach { fps ->
                        FilterChip(
                            selected = uiState.options.fps == fps,
                            onClick = { onUpdateOptions { it.copy(fps = fps) } },
                            label = { Text("$fps") }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                WuwaSwitchRow(
                    stringResource(R.string.gf_wuwa_unlock120_t),
                    stringResource(R.string.gf_wuwa_unlock120_c),
                    uiState.options.unlock120
                ) { v -> onUpdateOptions { it.copy(unlock120 = v) } }
                WuwaSwitchRow(
                    stringResource(R.string.gf_wuwa_ultra_t),
                    stringResource(R.string.gf_wuwa_ultra_c),
                    uiState.options.unlockUltra
                ) { v -> onUpdateOptions { it.copy(unlockUltra = v) } }
                WuwaSwitchRow(
                    stringResource(R.string.gf_wuwa_vsync_t),
                    stringResource(R.string.gf_wuwa_vsync_c),
                    uiState.options.vsync
                ) { v -> onUpdateOptions { it.copy(vsync = v) } }
            }

            Spacer(modifier = Modifier.height(12.dp))

            SectionCard {
                Text(stringResource(R.string.gf_game_mode), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Row {
                    WuWaConfigGenerator.GameMode.entries.forEach { mode ->
                        FilterChip(
                            selected = uiState.options.mode == mode,
                            onClick = { onUpdateOptions { it.copy(mode = mode) } },
                            label = { Text(gameModeLabel(mode)) },
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(R.string.gf_wuwa_mode_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            SectionCard {
                Text(stringResource(R.string.gf_effects), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)
                Spacer(modifier = Modifier.height(8.dp))
                WuwaSwitchRow(stringResource(R.string.gf_fx_outline_t), stringResource(R.string.gf_fx_outline_c), !uiState.options.disableOutline) { v ->
                    onUpdateOptions { it.copy(disableOutline = !v) }
                }
                WuwaSwitchRow(stringResource(R.string.gf_fx_radial_t), stringResource(R.string.gf_fx_radial_c), !uiState.options.disableRadialBlur) { v ->
                    onUpdateOptions { it.copy(disableRadialBlur = !v) }
                }
                WuwaSwitchRow(stringResource(R.string.gf_fx_bloom_t), stringResource(R.string.gf_fx_bloom_c), !uiState.options.disableBloom) { v ->
                    onUpdateOptions { it.copy(disableBloom = !v) }
                }
                WuwaSwitchRow(stringResource(R.string.gf_fx_exposure_t), stringResource(R.string.gf_fx_exposure_c), !uiState.options.disableAutoExposure) { v ->
                    onUpdateOptions { it.copy(disableAutoExposure = !v) }
                }
                WuwaSwitchRow(stringResource(R.string.gf_fx_ssr_t), stringResource(R.string.gf_fx_ssr_c), !uiState.options.disableSSR) { v ->
                    onUpdateOptions { it.copy(disableSSR = !v) }
                }
                WuwaSwitchRow(stringResource(R.string.gf_fx_ca_t), stringResource(R.string.gf_fx_ca_c), uiState.options.ca) { v ->
                    onUpdateOptions { it.copy(ca = v) }
                }
                WuwaSwitchRow(stringResource(R.string.gf_fx_hzb_t), stringResource(R.string.gf_fx_hzb_c), uiState.options.hzb) { v ->
                    onUpdateOptions { it.copy(hzb = v) }
                }
                WuwaSwitchRow(stringResource(R.string.gf_fx_cool_t), stringResource(R.string.gf_fx_cool_c), uiState.options.cool) { v ->
                    onUpdateOptions { it.copy(cool = v) }
                }
                WuwaSwitchRow(
                    stringResource(R.string.gf_fx_vulkan_t),
                    stringResource(R.string.gf_fx_vulkan_c),
                    uiState.options.vulkan
                ) { v -> onUpdateOptions { it.copy(vulkan = v) } }
                WuwaSwitchRow(
                    stringResource(R.string.gf_fx_noauto_t),
                    stringResource(R.string.gf_fx_noauto_c),
                    uiState.options.disableAutoAdjust
                ) { v -> onUpdateOptions { it.copy(disableAutoAdjust = v) } }
                WuwaSwitchRow(
                    stringResource(R.string.gf_fx_fog_t),
                    stringResource(R.string.gf_fx_fog_c),
                    !uiState.options.fog
                ) { v -> onUpdateOptions { it.copy(fog = !v) } }
            }

            Spacer(modifier = Modifier.height(12.dp))

            SectionCard {
                Text(stringResource(R.string.gf_files_section), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)
                Spacer(modifier = Modifier.height(8.dp))
                WuwaSwitchRow(
                    stringResource(R.string.gf_files_scal_t),
                    stringResource(R.string.gf_files_scal_c),
                    uiState.options.generateScalability
                ) { v -> onUpdateOptions { it.copy(generateScalability = v) } }
                WuwaSwitchRow(
                    stringResource(R.string.gf_files_hw_t),
                    stringResource(R.string.gf_files_hw_c),
                    uiState.options.generateHardware
                ) { v -> onUpdateOptions { it.copy(generateHardware = v) } }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(R.string.gf_files_always),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            SectionCard {
                Text(stringResource(R.string.gf_advanced), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)
                Spacer(modifier = Modifier.height(8.dp))
                WuwaSwitchRow(
                    stringResource(R.string.gf_adv_restricted_t),
                    stringResource(R.string.gf_adv_restricted_c),
                    uiState.options.allowRestrictedCvars
                ) { v -> onUpdateOptions { it.copy(allowRestrictedCvars = v) } }
                WuwaSwitchRow(
                    stringResource(R.string.gf_adv_exp_t),
                    stringResource(R.string.gf_adv_exp_c),
                    uiState.options.experimentalCvars
                ) { v -> onUpdateOptions { it.copy(experimentalCvars = v) } }
                WuwaSwitchRow(
                    stringResource(R.string.gf_adv_gsr_t),
                    stringResource(R.string.gf_adv_gsr_c),
                    uiState.options.enableGSR
                ) { v -> onUpdateOptions { it.copy(enableGSR = v) } }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(R.string.gf_fx_override_t),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    stringResource(R.string.gf_fx_override_c),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = uiState.cvarOverrideText,
                    onValueChange = onCvarOverrideTextChange,
                    label = { Text(stringResource(R.string.gf_fx_override_hint)) },
                    singleLine = false,
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    stringResource(R.string.gf_fx_override_applied, uiState.options.cvarOverrides.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            SectionCard {
                Text(stringResource(R.string.gf_deploy), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(R.string.gf_deploy_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // Generation report (M100): accepted / stripped / render-pipeline
                // counts for the previewed Engine.ini, computed inside generation
                // from its pre/post-strip snapshots. Null before the first
                // generation runs — then there is honestly nothing to report.
                uiState.engineReport?.let { report ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.gf_preview_report, report.accepted, report.rejected.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (report.rejected.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            stringResource(
                                R.string.gf_preview_report_rejected,
                                report.rejected.joinToString(", ")
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (report.rtFlagged.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            stringResource(
                                R.string.gf_preview_report_rt,
                                report.rtFlagged.joinToString(", ")
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                CatsmokerButton(
                    onClick = { onDeploy(WuwaDeployChannel.SHELL) },
                    enabled = !uiState.applying && uiState.previews.isNotEmpty() && uiState.canShell,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (uiState.applying) stringResource(R.string.gf_deploying) else stringResource(R.string.gf_deploy_shell))
                }
                Spacer(modifier = Modifier.height(8.dp))
                CatsmokerOutlinedButton(
                    onClick = { onDeploy(WuwaDeployChannel.SAF) },
                    enabled = !uiState.applying && uiState.previews.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (uiState.hasSafUri) stringResource(R.string.gf_deploy_saf) else stringResource(R.string.gf_deploy_saf_pick))
                }
                if (!uiState.canShell) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.gf_deploy_noshell),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                uiState.deployFailure?.let { failure ->
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(failure, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }

                uiState.deployReport?.let { report -> DeployReportCard(report) }
            }

            CommunityPacksCard(
                uiState = uiState,
                onImportPack = onImportPack,
                onDeployPackVariant = onDeployPackVariant,
                onDeployPackVariantStripped = onDeployPackVariantStripped,
                onDeletePack = onDeletePack
            )

            if (uiState.backups.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                SectionCard {
                    Text(stringResource(R.string.gf_backups), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.gf_backups_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // Backup-vs-generated diff (M100): the ViewModel pairs the
                    // generated Engine.ini with the newest device backup, so the
                    // user sees what a deploy would change before touching Deploy.
                    // Null when there is no backup or it does not decode — then
                    // there is honestly nothing to compare, so no row at all.
                    uiState.engineBackupDiff?.let { diff ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            if (diff.added == 0 && diff.removed == 0)
                                stringResource(R.string.gf_backup_diff_same)
                            else
                                stringResource(R.string.gf_backup_diff_detail, diff.added, diff.removed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    uiState.backups.forEach { entry ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    entry.file.name.substringAfter('_'),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    "${entry.formattedTimestamp()} · ${entry.formattedSize()}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(onClick = { onRestoreBackup(entry) }) { Text(stringResource(R.string.gf_restore)) }
                        }
                    }
                }
            }

            // Auto-tune sits with the history: the loop is the measured before/after FPS
            // comparison the history card's explainer points at, one Client.log read per round.
            Spacer(modifier = Modifier.height(12.dp))
            AutoTuneCard(
                uiState = uiState,
                onStart = onStartAutoTune,
                onPlayed = onTunerRoundPlayed,
                onApplyResult = onApplyTunerResult,
                onStop = onStopTuner,
                onReset = onResetTuner
            )

            if (uiState.history.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.gf_history), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)
                        Spacer(modifier = Modifier.weight(1f))
                        TextButton(onClick = onClearHistory) { Text(stringResource(R.string.gf_clear_all)) }
                    }
                    Text(
                        stringResource(R.string.gf_history_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    uiState.history.forEach { record ->
                        HistoryRecordRow(
                            record = record,
                            verifying = uiState.verifyingHistoryId == record.id,
                            onVerify = { onVerifyHistory(record) },
                            onDelete = { onDeleteHistory(record) }
                        )
                    }
                }
            }
        }
    }

    if (onBack == null) {
        content()
    } else {
        ScreenScaffold(
            title = stringResource(R.string.gf_wuwa_standalone_title),
            subtitle = "Wuthering Waves",
            onBack = onBack
        ) {
            content()
        }
    }
}

/**
 * The install-first card, shown in place of the generator when the package probe says the
 * game is absent. The small secondary "Show anyway" button (offered only when [onShowAnyway]
 * is non-null, i.e. the generator is still hidden) reveals the existing generator below
 * this card without pretending the game is installed — the card stays visible either way.
 */
@Composable
private fun WuwaNotInstalledCard(
    onRefresh: () -> Unit,
    onShowAnyway: (() -> Unit)? = null
) {
    SectionCard {
        Column {
            Text(stringResource(R.string.gf_game_not_found), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, letterSpacing = 1.sp)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                stringResource(R.string.gf_wuwa_not_installed_body, WuwaConfigManager.PACKAGE),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CatsmokerOutlinedButton(onClick = onRefresh, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.gf_recheck))
                }
            }
            if (onShowAnyway != null) {
                Spacer(modifier = Modifier.height(4.dp))
                TextButton(
                    onClick = onShowAnyway,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text(stringResource(R.string.gf_show_anyway))
                }
            }
        }
    }
}

@Composable
private fun CommunityPacksCard(
    uiState: WuwaConfigUiState,
    onImportPack: () -> Unit,
    onDeployPackVariant: (String, String) -> Unit,
    onDeployPackVariantStripped: (String, String) -> Unit,
    onDeletePack: (String) -> Unit
) {
    SectionCard {
        Text(
            stringResource(R.string.gf_packs),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = 1.sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            stringResource(R.string.gf_packs_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))
        CatsmokerButton(onClick = onImportPack, enabled = !uiState.importingPack, modifier = Modifier.fillMaxWidth()) {
            Text(if (uiState.importingPack) stringResource(R.string.gf_importing) else stringResource(R.string.gf_import_pack))
        }
        uiState.packFailure?.let { failure ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(failure, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        uiState.communityPacks.forEach { pack ->
            Spacer(modifier = Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    pack.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { onDeletePack(pack.id) }) { Text(stringResource(R.string.gf_delete)) }
            }
            Text(
                stringResource(R.string.gf_pack_meta, pack.formattedImportedAt(), pack.variants.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // The root README is the pack's own voice; nested ones (variant notes, "Extra")
            // stay in the verbatim block below.
            val rootReadme = pack.readmes.entries
                .firstOrNull { !it.key.contains('/') }?.value
                ?: pack.readmes.values.firstOrNull()
            rootReadme?.let { readme ->
                Spacer(modifier = Modifier.height(8.dp))
                WuwaCommunityPack.readmeFacts(readme).forEach { fact ->
                    Text(
                        "${fact.label}: ${fact.value}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (fact.warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                        fontWeight = if (fact.warning) FontWeight.Bold else null
                    )
                }
            }
            if (pack.readmes.isNotEmpty()) {
                CollapsibleExplainer(
                    title = stringResource(R.string.gf_pack_readme),
                    lines = pack.readmes.entries.flatMap { (path, text) ->
                        listOf("── $path ──") + text.lines()
                    },
                    initiallyExpanded = false
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            pack.variants.forEach { variant ->
                val busy = uiState.deployingPackKey == "${pack.id}/${variant.name}"
                Surface(
                    color = Color.Black.copy(alpha = 0.25f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            variant.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            stringResource(R.string.gf_files_list, variant.files.keys.joinToString(", ")),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (variant.forbiddenCount == 0) {
                            Spacer(modifier = Modifier.height(8.dp))
                            CatsmokerButton(
                                onClick = { onDeployPackVariant(pack.id, variant.name) },
                                enabled = !uiState.applying && !busy,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (busy) stringResource(R.string.gf_deploying) else stringResource(R.string.gf_deploy_variant))
                            }
                        } else {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                stringResource(R.string.gf_pack_refused, variant.forbiddenCount),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                            Text(
                                variant.forbidden.entries.joinToString { (file, keys) ->
                                    "$file: ${keys.joinToString()}"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            CatsmokerOutlinedButton(
                                onClick = { onDeployPackVariantStripped(pack.id, variant.name) },
                                enabled = !uiState.applying && !busy,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (busy) stringResource(R.string.gf_deploying) else stringResource(R.string.gf_deploy_stripped))
                            }
                        }
                    }
                }
            }
            if (pack.unknownFiles.isNotEmpty()) {
                Text(
                    stringResource(R.string.gf_pack_unknown, pack.unknownFiles.joinToString(", ")),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * The auto-tune benchmark loop's card. The reference drove this with modal dialogs; this
 * screen is card-based, so the loop lives inline — which also means its state is still on
 * screen when the user comes back from the game, which is exactly when they need it.
 */
@Composable
private fun AutoTuneCard(
    uiState: WuwaConfigUiState,
    onStart: () -> Unit,
    onPlayed: () -> Unit,
    onApplyResult: () -> Unit,
    onStop: () -> Unit,
    onReset: () -> Unit
) {
    SectionCard {
        val tuner = uiState.tuner
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.gf_autotune), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)
            Spacer(modifier = Modifier.weight(1f))
            Text(
                stringResource(
                    when (tuner?.stage) {
                        null, WuwaBenchmarkTuner.TunerStage.IDLE -> R.string.gf_tuner_idle
                        WuwaBenchmarkTuner.TunerStage.DEPLOYING -> R.string.gf_tuner_deploying
                        WuwaBenchmarkTuner.TunerStage.WAITING_FOR_PLAY -> R.string.gf_tuner_goplay
                        WuwaBenchmarkTuner.TunerStage.CAPTURING -> R.string.gf_tuner_measuring
                        WuwaBenchmarkTuner.TunerStage.COMPLETE -> if (tuner.error != null) R.string.gf_tuner_stopped else R.string.gf_tuner_done
                    }
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(8.dp))

        if (tuner == null || tuner.stage == WuwaBenchmarkTuner.TunerStage.IDLE) {
            Text(
                stringResource(R.string.gf_tuner_intro, WuwaBenchmarkTuner.MAX_ROUNDS),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            val canTune = uiState.canShell || uiState.hasSafUri
            CatsmokerButton(onClick = onStart, enabled = !uiState.tunerBusy && canTune, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.gf_start_tune))
            }
            if (!canTune) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    stringResource(R.string.gf_tuner_need_channel),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@SectionCard
        }

        StatusLine(stringResource(R.string.gf_sl_round), stringResource(R.string.gf_tuner_round_of, tuner.round, WuwaBenchmarkTuner.MAX_ROUNDS))
        StatusLine(stringResource(R.string.gf_sl_preset), presetLabel(tuner.preset))
        StatusLine(stringResource(R.string.gf_sl_target), stringResource(R.string.gf_tuner_target_fps, tuner.targetFps))

        when (tuner.stage) {
            WuwaBenchmarkTuner.TunerStage.DEPLOYING -> {
                Spacer(modifier = Modifier.height(8.dp))
                Text(stringResource(R.string.gf_tuner_deploying_round, tuner.round), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
            WuwaBenchmarkTuner.TunerStage.WAITING_FOR_PLAY -> {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(R.string.gf_tuner_play_then_measure, WuwaBenchmarkTuner.LINE_WINDOW),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                CatsmokerButton(onClick = onPlayed, enabled = !uiState.tunerBusy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.gf_measure_now))
                }
                TextButton(onClick = onStop, enabled = !uiState.tunerBusy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.gf_stop_tuning))
                }
            }
            WuwaBenchmarkTuner.TunerStage.CAPTURING -> {
                Spacer(modifier = Modifier.height(8.dp))
                Text(stringResource(R.string.gf_measuring_log), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
            WuwaBenchmarkTuner.TunerStage.COMPLETE -> {
                Spacer(modifier = Modifier.height(8.dp))
                if (tuner.error != null) {
                    Text(tuner.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                } else {
                    val last = tuner.results.lastOrNull()
                    val met = last != null && last.avgFps >= tuner.targetFps
                    Text(
                        stringResource(
                            if (met) R.string.gf_tuner_best_met else R.string.gf_tuner_best_close,
                            presetLabel(tuner.finalPreset ?: tuner.preset)
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (tuner.finalPreset != null) {
                        CatsmokerButton(onClick = onApplyResult, enabled = !uiState.tunerBusy, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.gf_apply_best))
                        }
                    }
                    CatsmokerOutlinedButton(onClick = onReset, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.gf_dismiss))
                    }
                }
            }
        }

        if (tuner.results.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            CollapsibleExplainer(
                title = stringResource(R.string.gf_measured_rounds, tuner.results.size),
                lines = tuner.results.map { r ->
                    stringResource(
                        R.string.gf_tuner_round_line,
                        r.round,
                        presetLabel(r.preset),
                        "%.1f".format(r.avgFps),
                        "%.1f".format(r.minFps),
                        "%.0f".format(r.stabilityPct)
                    )
                },
                initiallyExpanded = true
            )
        }
    }
}

@Composable
private fun RecommendCard(
    rec: WuwaSmartBrain.Recommendation,
    currentPreset: String,
    chipset: WuwaChipset.ChipsetInfo?,
    onApply: () -> Unit
) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.gf_smart_recommend), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)
            Spacer(modifier = Modifier.weight(1f))
            Text(
                stringResource(R.string.gf_wuwa_score, rec.score, rec.tier),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    presetLabel(rec.preset),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    if (currentPreset == rec.preset) stringResource(R.string.gf_wuwa_already_selected) else stringResource(R.string.gf_wuwa_rec_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            CatsmokerButton(onClick = onApply, enabled = currentPreset != rec.preset) {
                Text(stringResource(R.string.gf_apply))
            }
        }
        // Unscored silicon note: what the chipset suggests, weighed at zero — the scored GPU
        // tier comes from the log's measured renderer string, and a guess never outranks it.
        chipset?.let {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                stringResource(R.string.gf_chipset, it.socName.ifBlank { it.family }, it.family),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        CollapsibleExplainer(
            title = stringResource(R.string.gf_wuwa_why_score, rec.signals.size, rec.warnings.size),
            lines = buildList {
                rec.signals.forEach { add(it) }
                if (rec.warnings.isNotEmpty()) {
                    add("")
                    add(stringResource(R.string.gf_wuwa_warnings))
                    rec.warnings.forEach { add("! $it") }
                }
                add("")
                if (rec.hadLogEvidence) {
                    add(stringResource(R.string.gf_wuwa_scored_log_1))
                    add(stringResource(R.string.gf_wuwa_scored_log_2))
                } else {
                    add(stringResource(R.string.gf_wuwa_no_log_1))
                    add(stringResource(R.string.gf_wuwa_no_log_2))
                }
                rec.logOnlyAxes.forEach { add("· $it") }
                if (!rec.hadLogEvidence) {
                    add("")
                    add(stringResource(R.string.gf_wuwa_no_evidence_1))
                    add(stringResource(R.string.gf_wuwa_no_evidence_2))
                    add(stringResource(R.string.gf_wuwa_no_evidence_3))
                }
            },
            initiallyExpanded = false
        )
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(110.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f), fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun WuwaSwitchRow(title: String, caption: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Channel label for a stored record's channel string ([WuwaConfigManager.Channel.name]). */
@Composable
private fun historyChannelLabel(channel: String): String = when (channel) {
    WuwaConfigManager.Channel.ROOT_OR_SHIZUKU.name -> stringResource(R.string.gf_hist_channel_shell)
    WuwaConfigManager.Channel.SAF.name -> "SAF"
    // Old records from before wireless ADB was removed keep their label.
    "ADB" -> stringResource(R.string.gf_hist_channel_adb)
    else -> channel
}

@Composable
private fun historyStatusLabel(status: WuwaDeployHistoryStore.Status): String = when (status) {
    WuwaDeployHistoryStore.Status.MATCH -> stringResource(R.string.gf_hist_status_match)
    WuwaDeployHistoryStore.Status.CHANGED -> stringResource(R.string.gf_hist_status_changed)
    WuwaDeployHistoryStore.Status.MISSING -> stringResource(R.string.gf_hist_status_missing)
    WuwaDeployHistoryStore.Status.UNREADABLE -> stringResource(R.string.gf_hist_status_unreadable)
    WuwaDeployHistoryStore.Status.NOT_RECORDED -> stringResource(R.string.gf_hist_status_norecord)
}

@Composable
private fun formattedHistoryTime(timestamp: Long): String {
    val locale = LocalConfiguration.current.locales[0]
    return SimpleDateFormat("d MMM yyyy, HH:mm", locale).format(Date(timestamp))
}

@Composable
private fun HistoryRecordRow(
    record: WuwaDeployHistoryStore.Record,
    verifying: Boolean,
    onVerify: () -> Unit,
    onDelete: () -> Unit
) {
    val presetName = if (record.preset == "restore") stringResource(R.string.gf_restore_label) else presetLabel(record.preset)
    val verifiedCount = record.files.count { it.verified }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.gf_history_title, presetName, historyChannelLabel(record.channel)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    stringResource(
                        R.string.gf_history_subtitle,
                        record.formattedTimestamp(),
                        record.files.size,
                        verifiedCount
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onVerify, enabled = !verifying) {
                Text(if (verifying) stringResource(R.string.gf_verifying) else stringResource(R.string.gf_verify))
            }
            TextButton(onClick = onDelete) { Text(stringResource(R.string.gf_delete)) }
        }
        record.verification?.let { v ->
            val mismatched = v.files.count {
                it.status != WuwaDeployHistoryStore.Status.MATCH && it.status != WuwaDeployHistoryStore.Status.NOT_RECORDED
            }
            Text(
                if (mismatched > 0) stringResource(
                    R.string.gf_history_checked_diff,
                    formattedHistoryTime(v.timestamp),
                    v.channelUsed,
                    v.files.count { it.status == WuwaDeployHistoryStore.Status.MATCH },
                    mismatched
                ) else stringResource(
                    R.string.gf_history_checked_ok,
                    formattedHistoryTime(v.timestamp),
                    v.channelUsed,
                    v.files.count { it.status == WuwaDeployHistoryStore.Status.MATCH }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (mismatched > 0) MaterialTheme.colorScheme.error else Color(0xFF81C784)
            )
        }
        CollapsibleExplainer(
            title = stringResource(R.string.gf_history_what),
            lines = buildList {
                add(
                    when (record.gameStopped) {
                        true -> stringResource(R.string.gf_hs_stopped)
                        false -> stringResource(R.string.gf_hs_stop_refused)
                        null -> stringResource(R.string.gf_hs_not_stopped)
                    }
                )
                record.files.forEach { f ->
                    val backupNote = when {
                        f.backupTaken -> stringResource(R.string.gf_hist_backup_taken)
                        !f.pushed -> ""
                        else -> stringResource(R.string.gf_file_no_backup)
                    }
                    val md5Note = if (f.md5.isNotBlank()) stringResource(R.string.gf_hist_md5, f.md5.take(8)) else ""
                    add(
                        (if (!f.pushed) stringResource(R.string.gf_file_refused, f.name)
                        else if (f.verified) stringResource(R.string.gf_file_pushed_ok, f.name)
                        else stringResource(R.string.gf_file_pushed_unver, f.name)) + backupNote + md5Note
                    )
                }
                add(
                    when (record.hashSynced) {
                        true -> stringResource(R.string.gf_hash_ok)
                        false -> stringResource(R.string.gf_hash_fail, record.hashDetail)
                        null -> stringResource(R.string.gf_hash_na, record.hashDetail)
                    }
                )
                record.verification?.let { v ->
                    add("")
                    add(stringResource(R.string.gf_recheck_line, v.channelUsed))
                    v.files.forEach { add(stringResource(R.string.gf_verify_file_line, it.name, historyStatusLabel(it.status), it.detail)) }
                }
            },
            initiallyExpanded = false
        )
    }
}

@Composable
private fun DeployReportCard(report: WuwaConfigManager.DeploySuccess) {    Spacer(modifier = Modifier.height(12.dp))
    CollapsibleExplainer(
        title = stringResource(R.string.gf_deploy_report_title),
        lines = buildList {
            add(stringResource(R.string.gf_channel_line, when (report.channel) {
                WuwaConfigManager.Channel.ROOT_OR_SHIZUKU -> stringResource(R.string.gf_hist_channel_shell)
                WuwaConfigManager.Channel.SAF -> "SAF"
            }))
            add(
                when (report.gameStopped) {
                    true -> stringResource(R.string.gf_hs_stopped)
                    false -> stringResource(R.string.gf_hs_stop_refused)
                    null -> stringResource(R.string.gf_hs_not_stopped)
                }
            )
            report.files.forEach { f ->
                val backupNote = when {
                    f.backup != null -> stringResource(R.string.gf_file_backup_taken, f.backup.formattedTimestamp())
                    f.backupFailed -> stringResource(R.string.gf_file_backup_failed)
                    else -> stringResource(R.string.gf_file_no_prior)
                }
                add(
                    (if (!f.pushed) stringResource(R.string.gf_file_refused_detail, f.name, f.detail)
                    else if (f.verified) stringResource(R.string.gf_file_pushed_ok, f.name)
                    else stringResource(R.string.gf_file_pushed_unver, f.name)) + backupNote
                )
            }
            add(
                when (report.hashSynced) {
                    true -> stringResource(R.string.gf_hash_ok)
                    false -> stringResource(R.string.gf_hash_fail, report.hashDetail)
                    null -> stringResource(R.string.gf_hash_na, report.hashDetail)
                }
            )
        },
        initiallyExpanded = true
    )
}
