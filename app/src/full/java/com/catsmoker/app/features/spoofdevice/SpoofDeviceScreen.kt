package com.catsmoker.app.features.spoofdevice

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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.catsmoker.app.R
import com.catsmoker.app.shared.ui.components.QuickActionButton
import com.catsmoker.app.shared.ui.components.ScreenScaffold
import com.catsmoker.app.shared.ui.components.SectionCard

@Composable
fun SpoofRoute(
    onNavigate: (String) -> Unit,
    onBack: () -> Unit
) {
    val viewModel: SpoofDeviceViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.toasts.collect { message ->
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    SpoofDeviceScreen(
        uiState = uiState,
        onNavigate = onNavigate,
        onDownloadMagisk = viewModel::installBundledMagiskZip,
        onOpenRootManager = viewModel::launchRootManager,
        onRefresh = viewModel::refreshStatus,
        onBack = onBack
    )
}

@Composable
fun SpoofDeviceScreen(
    uiState: SpoofDeviceViewModel.UiState,
    onNavigate: (String) -> Unit,
    onDownloadMagisk: () -> Unit,
    onOpenRootManager: () -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit
) {
    // Pending Magisk generation behind the exclusivity confirm: building the
    // ZIP only starts after the user accepts the second method explicitly.
    var confirmGenerate by remember { mutableStateOf(false) }
    ScreenScaffold(
        title = stringResource(R.string.dash_spoof_title),
        subtitle = stringResource(R.string.spoof_home_subtitle),
        onBack = onBack
    ) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)
        ) {
            // Status Card
            SectionCard {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text(stringResource(R.string.spoof_home_status), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusIndicator(active = uiState.isRooted)
                            Text(stringResource(R.string.spoof_home_root), color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val headline = VectorActivation.headline(uiState.xposedEvidence)
                            StatusIndicator(
                                active = headline == VectorActivation.Headline.ACTIVE,
                                unknown = headline == VectorActivation.Headline.UNKNOWN,
                                warn = headline == VectorActivation.Headline.ENABLED_UNVERIFIED
                            )
                            Text(
                                stringResource(
                                    when (headline) {
                                        VectorActivation.Headline.ACTIVE -> R.string.spoof_home_lsposed_on
                                        VectorActivation.Headline.ENABLED_UNVERIFIED ->
                                            R.string.spoof_home_lsposed_unverified
                                        else -> R.string.spoof_home_lsposed_off
                                    }
                                ),
                                color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp
                            )
                        }
                        // What the headline above is built from: framework presence,
                        // manager-enabled state, scope contents, observed loads and
                        // observed spoofing are separate facts. "Enabled" alone never
                        // proves the module loaded (nor does a missing heartbeat prove
                        // it did not — see VectorActivation). Read-only evidence; our
                        // own package needs no scope entry for any of this.
                        XposedEvidenceLine(evidence = uiState.xposedEvidence)
                        Spacer(modifier = Modifier.height(6.dp))
                        // The second spoofing method, stated as plainly as the first:
                        // installed/disabled/absent when the probe could answer,
                        // honestly unknown when it could not (no root to look).
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusIndicator(
                                active = uiState.magiskModuleStatus ==
                                    SpoofMethodConflict.MagiskModuleStatus.INSTALLED,
                                unknown = uiState.magiskModuleStatus ==
                                    SpoofMethodConflict.MagiskModuleStatus.UNKNOWN
                            )
                            Text(
                                stringResource(
                                    when (uiState.magiskModuleStatus) {
                                        SpoofMethodConflict.MagiskModuleStatus.INSTALLED ->
                                            R.string.spoof_method_magisk_installed
                                        SpoofMethodConflict.MagiskModuleStatus.DISABLED ->
                                            R.string.spoof_method_magisk_disabled
                                        SpoofMethodConflict.MagiskModuleStatus.ABSENT ->
                                            R.string.spoof_method_magisk_absent
                                        SpoofMethodConflict.MagiskModuleStatus.UNKNOWN ->
                                            R.string.spoof_method_magisk_unknown
                                    }
                                ),
                                color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp
                            )
                        }
                    }
                    if (uiState.isRefreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        IconButton(onClick = onRefresh) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.logs_refresh),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            if (uiState.isRefreshing) {
                Spacer(modifier = Modifier.height(12.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            }
            // Both methods armed at once rewrite the same identity through
            // different channels — the banner names the conflict instead of
            // letting the two disagree silently. Nothing here removes or
            // disables anything; picking one method is the user's tap.
            if (
                SpoofMethodConflict.isConflict(
                    xposedActive = uiState.moduleActive,
                    magisk = uiState.magiskModuleStatus
                )
            ) {
                Spacer(modifier = Modifier.height(12.dp))
                SectionCard {
                    Text(
                        stringResource(R.string.spoof_method_conflict_title),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.spoof_method_conflict_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(stringResource(R.string.spoof_home_management), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SpoofMenuCard(
                    title = stringResource(R.string.spoof_menu_profiles),
                    subtitle = stringResource(R.string.spoof_menu_identities, uiState.profiles.size),
                    icon = Icons.Default.AccountBox,
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigate("spoof_profiles") }
                )
                SpoofMenuCard(
                    title = stringResource(R.string.spoof_menu_apps),
                    // Ladder packages are hooked too — they just get their identity per tier, so
                    // counting only the plain assignments would under-report.
                    subtitle = stringResource(R.string.spoof_menu_hooked, (uiState.assignments.keys + uiState.rateAssignments.keys).size),
                    icon = Icons.Default.Apps,
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigate("spoof_apps") }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SpoofMenuCard(
                    title = stringResource(R.string.spoof_menu_safe),
                    subtitle = stringResource(R.string.spoof_menu_apps_count, uiState.safeModePackages.size),
                    icon = Icons.Default.Security,
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigate("spoof_safe_mode") }
                )
                SpoofMenuCard(
                    title = stringResource(R.string.spoof_menu_diag),
                    subtitle = stringResource(R.string.spoof_menu_verify),
                    icon = Icons.Default.Troubleshoot,
                    modifier = Modifier.weight(1f),
                    onClick = { onNavigate("spoof_diagnostics") }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(stringResource(R.string.spoof_home_advanced), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SectionCard {
                QuickActionButton(
                    title = stringResource(R.string.spoof_magisk_title),
                    subtitle = if (uiState.isGeneratingMagisk) stringResource(R.string.spoof_magisk_building) else stringResource(R.string.spoof_magisk_desc),
                    iconContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    iconContentColor = MaterialTheme.colorScheme.onSurface,
                    icon = { Icon(Icons.Default.FileDownload, null) },
                    onClick = {
                        // Unloaded store means the assignment lists are unknown,
                        // not empty — treat as potentially armed, never as clear.
                        if (
                            !uiState.storeLoaded ||
                            SpoofMethodConflict.needsGenerateConfirm(
                                moduleActive = uiState.moduleActive,
                                hasAssignments = uiState.assignments.isNotEmpty(),
                                hasLadders = uiState.rateAssignments.isNotEmpty()
                            )
                        ) {
                            confirmGenerate = true
                        } else {
                            onDownloadMagisk()
                        }
                    },
                    isFullWidth = true,
                    isLoading = uiState.isGeneratingMagisk
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                // Global rule: always show, gray when unsupported. Opening a root
                // manager without root can do nothing, so the tile stays visible
                // but disabled with the reason instead of disappearing.
                QuickActionButton(
                    title = stringResource(R.string.spoof_root_manager_title),
                    subtitle = if (uiState.isRooted) stringResource(R.string.spoof_root_manager_desc)
                    else stringResource(R.string.spoof_root_manager_need),
                    iconContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    iconContentColor = MaterialTheme.colorScheme.onSurface,
                    icon = { Icon(Icons.Default.Settings, null) },
                    onClick = onOpenRootManager,
                    isFullWidth = true,
                    enabled = uiState.isRooted,
                    modifier = Modifier.alpha(if (uiState.isRooted) 1f else 0.6f)
                )
            }
        }
    }
    // The exclusivity gate for the Magisk direction: Confirm starts the
    // generation the tap asked for; anything else drops it silently.
    if (confirmGenerate) {
        AlertDialog(
            onDismissRequest = { confirmGenerate = false },
            title = { Text(stringResource(R.string.spoof_method_confirm_generate_title)) },
            text = { Text(stringResource(R.string.spoof_method_confirm_generate_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmGenerate = false
                    onDownloadMagisk()
                }) { Text(stringResource(R.string.spoof_method_use_anyway)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmGenerate = false }) {
                    Text(stringResource(R.string.spoof_action_cancel))
                }
            }
        )
    }
}

@Composable
fun SpoofMenuCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    SectionCard(
        modifier = modifier.clickable { onClick() }
    ) {
        Column(horizontalAlignment = Alignment.Start) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Spacer(modifier = Modifier.height(12.dp))
            Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Gray detail lines under the Xposed status row naming the evidence behind it.
 *
 * Framework presence, manager-enabled state, scope contents, observed loads and
 * observed spoofing are told apart here instead of collapsing into one dot: the
 * row above answers with the headline, these lines answer "how do you know, and
 * what is missing". Unknown stays unknown (no privilege to check reads as
 * unchecked, never as absent). Our own package being out of scope is the normal
 * state and gets no complaint line.
 */
@Composable
private fun XposedEvidenceLine(evidence: VectorActivation.Evidence) {
    val detail = when (evidence.framework) {
        VectorActivation.Framework.UNKNOWN ->
            stringResource(R.string.spoof_home_xposed_nopriv)
        VectorActivation.Framework.ABSENT ->
            stringResource(R.string.spoof_home_xposed_nofw)
        VectorActivation.Framework.VECTOR -> {
            val framework = evidence.frameworkVersion?.let { "Vector $it" } ?: "Vector"
            val enabled = when (evidence.moduleEnabled) {
                true -> stringResource(R.string.spoof_home_xposed_enabled)
                false -> stringResource(R.string.spoof_home_xposed_disabled)
                null -> stringResource(R.string.spoof_home_xposed_unknown)
            }
            val scope = evidence.scopePackages?.size?.let {
                stringResource(R.string.spoof_home_xposed_scope, it)
            } ?: stringResource(R.string.spoof_home_xposed_unknown)
            stringResource(R.string.spoof_home_xposed_detail, framework, enabled, scope)
        }
    }
    Text(
        text = detail,
        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp,
        modifier = Modifier.padding(start = 16.dp, top = 2.dp)
    )
    // Second line: what execution was actually observed. Spoof proof names the
    // target and the model it received; plain loads name the target packages;
    // enabled-without-any-load says exactly that instead of claiming success.
    // Map to strings before joining (no @Composable inside joinToString).
    val observed: String? = when {
        evidence.logSpoofTargets.isNotEmpty() -> {
            val pairs = evidence.logSpoofTargets.map { (pkg, model) -> "$pkg → $model" }
            stringResource(R.string.spoof_home_xposed_spoofed, pairs.joinToString(", "))
        }
        evidence.loadedTargets.isNotEmpty() ->
            stringResource(
                R.string.spoof_home_xposed_loaded,
                evidence.loadedTargets.sorted().joinToString(", ")
            )
        evidence.framework == VectorActivation.Framework.VECTOR &&
            evidence.moduleEnabled == true ->
            stringResource(R.string.spoof_home_xposed_load_pending)
        else -> null
    }
    observed?.let {
        Text(
            text = it,
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp,
            modifier = Modifier.padding(start = 16.dp, top = 2.dp)
        )
    }
}

@Composable
fun StatusIndicator(active: Boolean, unknown: Boolean = false, warn: Boolean = false) {
    Box(
        modifier = Modifier.padding(end = 8.dp).size(8.dp).clip(RoundedCornerShape(4.dp))
            .background(
                when {
                    unknown -> Color.Gray
                    warn -> Color(0xFFFFB300)
                    active -> Color.Green
                    else -> Color.Red
                }
            )
    )
}
