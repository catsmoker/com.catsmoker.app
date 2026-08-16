package com.catsmoker.app.features.spoofdevice

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
        title = "App Assignments",
        subtitle = "Link apps to virtual identities.",
        onBack = onBack
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            SectionCard {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Search Apps") },
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
        AlertDialog(
            onDismissRequest = { selectedApp = null },
            title = { Text("Assign Profile to ${app.label}") },
            text = {
                Column {
                    DropdownMenuItem(
                        text = { Text("None (Unassigned)", color = MaterialTheme.colorScheme.error) },
                        onClick = {
                            onAssignProfile(app.packageName, null)
                            selectedApp = null
                        }
                    )
                    HorizontalDivider()
                    uiState.profiles.forEach { profile ->
                        DropdownMenuItem(
                            text = { Text(profile.name) },
                            onClick = {
                                onAssignProfile(app.packageName, profile.id)
                                selectedApp = null
                            }
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { selectedApp = null }) { Text("Cancel") }
            }
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
                Text(app.label, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 16.sp, color = Color.White)
                Text(app.packageName, fontSize = 11.sp, color = Color.Gray)
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
