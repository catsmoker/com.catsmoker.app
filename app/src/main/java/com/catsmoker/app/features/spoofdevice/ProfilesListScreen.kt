package com.catsmoker.app.features.spoofdevice

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.catsmoker.app.R
import com.catsmoker.app.shared.ui.components.ScreenScaffold
import com.catsmoker.app.shared.ui.components.SectionCard

@Composable
fun ProfilesListScreen(
    uiState: SpoofDeviceViewModel.UiState,
    onNavigateToEditor: (String) -> Unit,
    onCreateProfile: (String) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onBack: () -> Unit
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var newProfileName by remember { mutableStateOf("") }

    ScreenScaffold(
        title = "Spoof Profiles",
        subtitle = "Manage your virtual device identities.",
        onBack = onBack,
        trailingContent = {
            IconButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary)
            }
        }
    ) {
        if (!uiState.storeLoaded) {
            // Saying "no profiles" before the store has been read would be inventing an answer.
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (uiState.profiles.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No profiles found. Create one!", color = Color.Gray)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                itemsIndexed(uiState.profiles) { index, entry ->
                    ProfileItem(
                        name = entry.name,
                        details = "${entry.profile.brand} ${entry.profile.model}",
                        canDelete = index != 0,
                        onEdit = { onNavigateToEditor(entry.id) },
                        onDelete = { onDeleteProfile(entry.id) }
                    )
                }
            }
        }
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("New Profile") },
            text = {
                OutlinedTextField(
                    value = newProfileName,
                    onValueChange = { newProfileName = it },
                    label = { Text("Profile Name") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newProfileName.isNotBlank()) {
                            onCreateProfile(newProfileName)
                            newProfileName = ""
                            showCreateDialog = false
                        }
                    }
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun ProfileItem(
    name: String,
    details: String,
    canDelete: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    SectionCard(
        modifier = Modifier.fillMaxWidth().clickable { onEdit() }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(Icons.Default.PhoneAndroid, null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f)) {
                Text(name, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 16.sp, color = Color.White)
                Text(details, fontSize = 12.sp, color = Color.Gray)
            }
            if (canDelete) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
