package com.catsmoker.app.features.spoofdevice

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.catsmoker.app.R
import com.catsmoker.app.shared.ui.components.ScreenScaffold
import com.catsmoker.app.shared.ui.components.SectionCard

@Composable
fun SafeModeScreen(
    uiState: SpoofDeviceViewModel.UiState,
    onLoadApps: () -> Unit,
    onToggleSafeMode: (String, Boolean) -> Unit,
    onToggleVersionBypass: (String, Boolean) -> Unit,
    onBack: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        onLoadApps()
    }

    // Either list floats the app to the top: both are "configured here".
    val filteredApps = remember(uiState.apps, uiState.safeModePackages, uiState.versionBypassPackages, searchQuery) {
        uiState.apps.filter {
            it.label.contains(searchQuery, ignoreCase = true) || it.packageName.contains(searchQuery, ignoreCase = true)
        }.sortedWith(
            compareByDescending<SpoofDeviceViewModel.AppEntry> {
                uiState.safeModePackages.contains(it.packageName) ||
                    uiState.versionBypassPackages.contains(it.packageName)
            }
                .thenBy { it.label.lowercase() }
        )
    }

    ScreenScaffold(
        title = stringResource(R.string.spoof_menu_safe),
        subtitle = stringResource(R.string.spoof_safe_subtitle),
        onBack = onBack
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                stringResource(R.string.spoof_safe_desc),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Text(
                stringResource(R.string.spoof_safe_version_note),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp)
            )

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
                        SafeModeItem(
                            app = app,
                            isSafe = uiState.safeModePackages.contains(app.packageName),
                            isVersionOnly = uiState.versionBypassPackages.contains(app.packageName),
                            onToggleSafe = { onToggleSafeMode(app.packageName, it) },
                            onToggleVersion = { onToggleVersionBypass(app.packageName, it) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SafeModeItem(
    app: SpoofDeviceViewModel.AppEntry,
    isSafe: Boolean,
    isVersionOnly: Boolean,
    onToggleSafe: (Boolean) -> Unit,
    onToggleVersion: (Boolean) -> Unit
) {
    val switchColors = SwitchDefaults.colors(
        checkedThumbColor = MaterialTheme.colorScheme.primary,
        checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
        uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
        uncheckedTrackColor = MaterialTheme.colorScheme.outlineVariant
    )
    SectionCard(
        modifier = Modifier.fillMaxWidth(),
        enabled = true // Ensure it looks active
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, androidx.compose.foundation.shape.CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = app.icon.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.size(32.dp).clip(androidx.compose.foundation.shape.CircleShape)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = app.label,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    Text(
                        text = app.packageName,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }

            // The two independent lists; this row only writes them. When an app is in both,
            // the module resolves full skip first — the screen's section text states that.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.spoof_safe_full),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Switch(checked = isSafe, onCheckedChange = onToggleSafe, colors = switchColors)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.spoof_safe_version),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Switch(checked = isVersionOnly, onCheckedChange = onToggleVersion, colors = switchColors)
            }
        }
    }
}
