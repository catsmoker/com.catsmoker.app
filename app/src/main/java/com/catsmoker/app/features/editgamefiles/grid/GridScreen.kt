package com.catsmoker.app.features.editgamefiles.grid

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.catsmoker.app.R
import com.catsmoker.app.shared.ui.components.ScreenScaffold
import com.catsmoker.app.shared.ui.components.SectionCard
import kotlinx.coroutines.flow.collectLatest
import com.catsmoker.app.shared.ui.components.CatsmokerButton
import com.catsmoker.app.shared.ui.components.CatsmokerOutlinedButton
import com.catsmoker.app.shared.ui.components.ChipFlowRow

/**
 * GRID Autosport graphics editor. Cloned from the HSR Graphics screen's shape, per the house
 * convention, with one structural difference: the SAF folder picker is launched from here
 * (not from a parked pending-action), because the pick is only ever a *grant* — the apply
 * that needs it re-runs from the button like any other apply.
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
fun GridRoute(onBack: (() -> Unit)? = null) {
    val viewModel: GridViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is GridViewModel.GridEvent.Toast ->
                    Toast.makeText(context, event.message, if (event.isLong) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
                is GridViewModel.GridEvent.LaunchFolderPicker -> Unit // handled by the launcher below
            }
        }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.onSafFolderPicked(uri)
    }
    LaunchedEffect(Unit) {
        viewModel.events.collectLatest { event ->
            if (event is GridViewModel.GridEvent.LaunchFolderPicker) {
                runCatching { folderPicker.launch(null) }
            }
        }
    }

    GridScreen(
        uiState = uiState,
        onSetResolution = viewModel::setResolution,
        onSetFps = viewModel::setFps,
        onSetFxaa = viewModel::setFxaa,
        onSetWindowScale = viewModel::setWindowScale,
        onSetLadder = viewModel::setLadder,
        onSetAnisotropic = viewModel::setAnisotropic,
        onSetSwitch = viewModel::setSwitch,
        onApply = viewModel::onApply,
        onRestoreBackup = viewModel::onRestoreBackup,
        onPickFolder = viewModel::requestFolderPicker,
        onRefresh = viewModel::refresh,
        onShowAnyway = viewModel::onShowEditorAnyway,
        onBack = onBack
    )
}

@Composable
fun GridScreen(
    uiState: GridUiState,
    onSetResolution: (Int) -> Unit,
    onSetFps: (Int) -> Unit,
    onSetFxaa: (Boolean) -> Unit,
    onSetWindowScale: (Int) -> Unit,
    onSetLadder: (String, String) -> Unit,
    onSetAnisotropic: (String) -> Unit,
    onSetSwitch: (String, Boolean) -> Unit,
    onApply: () -> Unit,
    onRestoreBackup: () -> Unit,
    onPickFolder: () -> Unit,
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
            // The install gate, mirroring the File Engineering screen's own pattern: a
            // confirmed-missing install shows the GAME NOT FOUND failure card with a small
            // secondary "Show anyway" button, and tapping it reveals the existing editor
            // below the card (the ViewModel seeds an empty read — see onShowEditorAnyway).
            // Other failure stages keep their card with no button, and installed games
            // render the editor exactly as before. Missing is the probe's answer, not the
            // stage: a channel-less device reports NO_CHANNEL for a game that is not there.
            val missingInstall = uiState.gameMissing
            when {
                uiState.loading -> Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 96.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }

                uiState.loadFailure != null && (!missingInstall || !uiState.showEditorAnyway) ->
                    GridLoadFailureCard(
                        uiState.loadFailure,
                        onPickFolder,
                        onRefresh,
                        onShowAnyway = if (missingInstall) onShowAnyway else null
                    )

                uiState.read != null -> {
                    val read = uiState.read

                    if (missingInstall && uiState.loadFailure != null) {
                        GridLoadFailureCard(uiState.loadFailure, onPickFolder, onRefresh, onShowAnyway = null)
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    SectionCard {
                        Text(stringResource(R.string.gf_frame_rate), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        // All five FPS options visible at once — a wrapping flow, never sideways scroll.
                        ChipFlowRow {
                            listOf(30, 60, 90, 120, 144).forEach { fps ->
                                FilterChip(
                                    selected = uiState.edits.fps == fps,
                                    onClick = { onSetFps(fps) },
                                    label = { Text("$fps") }
                                )
                            }
                        }
                        if (read.maxFps != null && uiState.edits.fps == null) {
                            Text(
                                stringResource(
                                    R.string.gf_grid_device_holds_fps,
                                    read.maxFps,
                                    read.highMaxFps ?: read.maxFps
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            stringResource(R.string.gf_grid_fps_caption),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    SectionCard {
                        Text(stringResource(R.string.gf_resolution_section), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        ChipFlowRow {
                            listOf(480, 600, 720, 1080, 1440).forEach { height ->
                                FilterChip(
                                    selected = uiState.edits.screenHeight == height,
                                    onClick = { onSetResolution(height) },
                                    label = { Text("${height}p") }
                                )
                            }
                        }
                        Text(
                            currentResolutionLine(read, uiState),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    SectionCard {
                        Text(stringResource(R.string.gf_quality), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        read.ladder.forEach { (name, current) ->
                            val enabled = current != null
                            LadderRow(
                                label = ladderLabel(name),
                                enabled = enabled,
                                options = GridPreferences.QUALITY_TIERS,
                                selected = uiState.edits.ladder[name],
                                current = current,
                                onSelect = { onSetLadder(name, it) }
                            )
                        }
                        LadderRow(
                            label = stringResource(R.string.gf_grid_aniso),
                            enabled = read.anisotropic != null,
                            options = listOf("off") + GridPreferences.QUALITY_TIERS,
                            selected = uiState.edits.anisotropic,
                            current = read.anisotropic,
                            onSelect = onSetAnisotropic
                        )
                        Text(
                            stringResource(R.string.gf_grid_greyed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    SectionCard {
                        Text(stringResource(R.string.gf_perf_switches), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        GridPreferences.SWITCH_TARGETS.forEach { (name, _) ->
                            val current = read.switches[name]
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(switchLabel(name), color = if (current == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
                                    Text(
                                        when {
                                            current == null -> stringResource(R.string.gf_grid_not_in_file)
                                            else -> stringResource(R.string.gf_grid_device_holds, if (current) "on" else "off")
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = uiState.edits.switches[name] ?: current ?: false,
                                    enabled = current != null,
                                    onCheckedChange = { checked -> onSetSwitch(name, checked) }
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                        }

                        // The shipped reference file's two non-gfxconfig_* keys, absent from
                        // its README list: FXAA and the high-preset window scale. FXAA is an
                        // integer 0/1 switch; the scale is the file's own 0-100 integer
                        // (verified file ships 80).
                        val fxaaCurrent = read.fxaa
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(switchLabel("fxaa"), color = if (fxaaCurrent == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
                                Text(
                                    when {
                                        fxaaCurrent == null -> stringResource(R.string.gf_grid_not_in_file)
                                        else -> stringResource(R.string.gf_grid_device_holds, if (fxaaCurrent) "on" else "off")
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = uiState.edits.fxaa ?: fxaaCurrent ?: false,
                                enabled = fxaaCurrent != null,
                                onCheckedChange = { checked -> onSetFxaa(checked) }
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.gf_grid_window_scale),
                            color = if (read.windowScale == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                        )
                        ChipFlowRow {
                            listOf(50, 60, 70, 80, 90, 100).forEach { scale ->
                                FilterChip(
                                    selected = uiState.edits.windowScale == scale,
                                    enabled = read.windowScale != null,
                                    onClick = { onSetWindowScale(scale) },
                                    label = { Text("$scale%") }
                                )
                            }
                        }
                        Text(
                            when {
                                read.windowScale == null -> stringResource(R.string.gf_grid_not_in_file)
                                else -> stringResource(R.string.gf_grid_device_holds, "${read.windowScale}%")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            stringResource(R.string.gf_grid_window_scale_caption),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.gf_grid_switch_caption),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    CatsmokerButton(
                        onClick = onApply,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !uiState.applying
                    ) {
                        if (uiState.applying) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.Black)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.gf_applying))
                        } else {
                            Text(stringResource(R.string.gf_apply_to_game))
                        }
                    }
                    if (uiState.hasBackup) {
                        Spacer(modifier = Modifier.height(8.dp))
                        CatsmokerOutlinedButton(onClick = onRestoreBackup, modifier = Modifier.fillMaxWidth(), enabled = !uiState.applying) {
                            Text(stringResource(R.string.gf_restore_last_backup))
                        }
                    }
                    uiState.lastApply?.let { report ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(stringResource(R.string.gf_last_apply, report), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Defensive: override active but no read (seeding is the ViewModel's job and
                // always runs first) — keep the indicator rather than showing nothing.
                uiState.loadFailure != null ->
                    GridLoadFailureCard(uiState.loadFailure, onPickFolder, onRefresh, onShowAnyway = null)
            }
        }
    }

    if (onBack == null) {
        content()
    } else {
        ScreenScaffold(
            title = "GRID Autosport",
            subtitle = uiState.gameVersion ?: stringResource(R.string.gf_grid_graphics_editor),
            onBack = onBack
        ) {
            content()
        }
    }
}

@Composable
private fun currentResolutionLine(read: GridPreferences.ReadResult, uiState: GridUiState): String {
    val current = if (read.screenWidth != null && read.screenHeight != null) {
        stringResource(R.string.gf_grid_res_holds, read.screenWidth, read.screenHeight)
    } else {
        stringResource(R.string.gf_grid_res_unknown)
    }
    return if (uiState.edits.screenHeight != null) {
        stringResource(
            R.string.gf_grid_res_will_write,
            current,
            uiState.edits.screenWidth?.toString() ?: stringResource(R.string.gf_derived),
            uiState.edits.screenHeight
        )
    } else {
        stringResource(R.string.gf_grid_res_keys, current)
    }
}

@Composable
private fun ladderLabel(key: String): String = when (key) {
    "car_reflection" -> stringResource(R.string.gf_grid_ladder_car)
    "dynamic_ambient_occ" -> stringResource(R.string.gf_grid_ladder_ao)
    "night_lighting" -> stringResource(R.string.gf_grid_ladder_night)
    "voice_count" -> stringResource(R.string.gf_grid_ladder_voice)
    else -> key.replaceFirstChar { it.uppercase() }
}

@Composable
private fun switchLabel(key: String): String = when (key) {
    "advanced_fog" -> stringResource(R.string.gf_grid_sw_fog)
    "advanced_lighting" -> stringResource(R.string.gf_grid_sw_lighting)
    "dynamic_ambient_occ_soft" -> stringResource(R.string.gf_grid_sw_soft_ao)
    "fxaa" -> stringResource(R.string.gf_grid_sw_fxaa)
    "global_illumination" -> stringResource(R.string.gf_grid_sw_gi)
    "groundCover" -> stringResource(R.string.gf_grid_sw_ground)
    "multisampling" -> stringResource(R.string.gf_grid_sw_msaa)
    "skidmarks" -> stringResource(R.string.gf_grid_sw_skid)
    else -> key.replaceFirstChar { it.uppercase() }
}

@Composable
private fun LadderRow(
    label: String,
    enabled: Boolean,
    options: List<String>,
    selected: String?,
    current: String?,
    onSelect: (String) -> Unit
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                modifier = Modifier.weight(1f),
                color = if (enabled) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (current != null) {
                Text(stringResource(R.string.gf_grid_device_value, current), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // Every tier visible at once — a wrapping flow, never sideways scroll.
        ChipFlowRow {
            options.forEach { tier ->
                FilterChip(
                    selected = selected == tier,
                    enabled = enabled,
                    onClick = { onSelect(tier) },
                    label = { Text(tier) }
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
    }
}

@Composable
private fun GridLoadFailureCard(
    failure: GridPreferencesManager.ReadResult.Failure,
    onPickFolder: () -> Unit,
    onRefresh: () -> Unit,
    onShowAnyway: (() -> Unit)? = null
) {
    // Host-styled failure surface: the File Engineering screen's GAME NOT FOUND card is a
    // SectionCard with a labelSmall title (error red for a missing game, primary otherwise),
    // so a load failure here reads as the same kind of thing, not a different component.
    // The small secondary "Show anyway" button is offered only when the caller passes it —
    // callers do so solely for a confirmed-missing install, so other stages keep no button.
    SectionCard {
        Column {
            when (failure.stage) {
                GridPreferencesManager.ReadResult.Stage.NO_CHANNEL -> {
                    Text(stringResource(R.string.gf_grid_no_channel_title), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.gf_grid_no_channel_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    CatsmokerButton(onClick = onPickFolder, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.gf_grid_pick_folder))
                    }
                }
                GridPreferencesManager.ReadResult.Stage.GAME_NOT_INSTALLED -> {
                    Text(stringResource(R.string.gf_game_not_found), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, letterSpacing = 1.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.gf_grid_not_installed_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )
                }
                GridPreferencesManager.ReadResult.Stage.FILE_NOT_FOUND -> {
                    Text(stringResource(R.string.gf_grid_prefs_title), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.gf_grid_prefs_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )
                }
                GridPreferencesManager.ReadResult.Stage.READ_FAILED -> {
                    Text(stringResource(R.string.gf_could_not_read_title), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.gf_read_failed_detail, failure.detail),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )
                }
            }
            if (failure.stage != GridPreferencesManager.ReadResult.Stage.NO_CHANNEL) {
                Spacer(modifier = Modifier.height(12.dp))
                CatsmokerOutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.gf_retry))
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
