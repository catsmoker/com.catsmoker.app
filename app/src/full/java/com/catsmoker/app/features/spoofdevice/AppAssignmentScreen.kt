package com.catsmoker.app.features.spoofdevice

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.catsmoker.app.R
import com.catsmoker.app.shared.ui.components.ScreenScaffold
import com.catsmoker.app.shared.ui.components.SectionCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppAssignmentScreen(
    uiState: SpoofDeviceViewModel.UiState,
    onLoadApps: () -> Unit,
    onAssignProfile: (String, String?) -> Unit,
    onBack: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedApp by remember { mutableStateOf<SpoofDeviceViewModel.AppEntry?>(null) }
    // An assignment held for exclusivity confirmation: non-null only between
    // the picker's Assign tap and the confirm dialog's verdict.
    var pendingAssign by remember { mutableStateOf<PendingAssign?>(null) }

    LaunchedEffect(Unit) {
        onLoadApps()
    }

    val filteredApps = remember(uiState.apps, searchQuery) {
        uiState.apps.filter {
            it.label.contains(searchQuery, ignoreCase = true) || it.packageName.contains(searchQuery, ignoreCase = true)
        }.sortedWith(
            compareByDescending<SpoofDeviceViewModel.AppEntry> { it.assignedProfileName != null }
                .thenBy { it.label.lowercase() }
        )
    }

    ScreenScaffold(
        title = stringResource(R.string.spoof_apps_title),
        subtitle = stringResource(R.string.spoof_apps_subtitle),
        onBack = onBack
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            SectionCard {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text(stringResource(R.string.spoof_search_apps)) },
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Default.Search, null) }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (uiState.isLoadingApps) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredApps) { app ->
                        AppItem(
                            app = app,
                            onClick = { selectedApp = app }
                        )
                    }
                }
            }
        }
    }

    selectedApp?.let { app ->
        AssignProfileDialog(
            app = app,
            uiState = uiState,
            onAssignProfile = { profileId ->
                // The exclusivity gate for the Xposed direction lives one level
                // out: the picker dialog stays dumb, and a confirm wraps the
                // commit only when a Magisk module is installed.
                if (
                    SpoofMethodConflict.needsAssignConfirm(
                        profileId = profileId,
                        magisk = uiState.magiskModuleStatus
                    )
                ) {
                    pendingAssign = PendingAssign(app.packageName, profileId)
                } else {
                    onAssignProfile(app.packageName, profileId)
                    selectedApp = null
                }
            },
            onDismiss = { selectedApp = null }
        )
    }
    // Confirming commits the pending assignment; anything else drops it and
    // closes the picker too, so no half-chosen state lingers.
    pendingAssign?.let { pending ->
        AlertDialog(
            onDismissRequest = {
                pendingAssign = null
                selectedApp = null
            },
            title = { Text(stringResource(R.string.spoof_method_confirm_assign_title)) },
            text = { Text(stringResource(R.string.spoof_method_confirm_assign_body)) },
            confirmButton = {
                TextButton(onClick = {
                    onAssignProfile(pending.packageName, pending.profileId)
                    pendingAssign = null
                    selectedApp = null
                }) { Text(stringResource(R.string.spoof_method_use_anyway)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingAssign = null
                    selectedApp = null
                }) { Text(stringResource(R.string.spoof_action_cancel)) }
            }
        )
    }
}

/**
 * An assignment waiting on the method-exclusivity confirm: null means nothing
 * is pending. Kept outside the ViewModel because it is pure UI transit —
 * process death drops an unconfirmed tap rather than committing it later.
 */
private data class PendingAssign(val packageName: String, val profileId: String?)

/**
 * The per-app assignment dialog answers exactly one question: which spoofing
 * PROFILE should this app use. A direct single-choice list with an explicit
 * Assign confirmation — no nested pickers, no tier controls.
 *
 * A package may still carry a frame-rate ladder built by an older version (see
 * `SpoofRepository.rateAssignments`): such a ladder keeps resolving through the
 * normal publish path and is named as the current state, but this dialog neither
 * builds nor edits ladders — assigning a profile here replaces the ladder, and
 * clearing the assignment leaves the ladder alone.
 */
@Composable
private fun AssignProfileDialog(
    app: SpoofDeviceViewModel.AppEntry,
    uiState: SpoofDeviceViewModel.UiState,
    onAssignProfile: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    val hasLadder = uiState.rateAssignments[app.packageName].orEmpty().isNotEmpty()
    val currentId = uiState.assignments[app.packageName]

    var selectedId by remember(app.packageName) { mutableStateOf<String?>(currentId) }

    val currentLabel = when {
        hasLadder -> stringResource(R.string.spoof_ladder_label)
        currentId != null -> uiState.profiles.firstOrNull { it.id == currentId }?.name
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.spoof_assign_title, app.label)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(
                        R.string.spoof_assign_current,
                        currentLabel ?: stringResource(R.string.spoof_assign_none)
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                // Direct profile choice: tapping a row selects, Assign commits.
                ProfileRadioRow(
                    selected = selectedId == null,
                    label = stringResource(R.string.spoof_assign_none),
                    onSelect = { selectedId = null }
                )
                uiState.profiles.forEach { profile ->
                    ProfileRadioRow(
                        selected = selectedId == profile.id,
                        label = profile.name,
                        onSelect = { selectedId = profile.id }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onAssignProfile(selectedId)
            }) { Text(stringResource(R.string.spoof_action_assign)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.spoof_action_cancel)) }
        }
    )
}

@Composable
private fun ProfileRadioRow(selected: Boolean, label: String, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { onSelect() }.padding(vertical = 4.dp)
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}


@Composable
fun AppItem(app: SpoofDeviceViewModel.AppEntry, onClick: () -> Unit) {
    SectionCard(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Image(
                bitmap = app.icon.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(40.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(app.label, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                Text(app.packageName, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (app.assignedProfileName != null) {
                Text(
                    text = app.assignedProfileName,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 12.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                )
            }
        }
    }
}
