package com.catsmoker.app.features.editgamefiles

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.catsmoker.app.R
import com.catsmoker.app.shared.data.model.GameType
import com.catsmoker.app.shared.ui.components.InfoCard
import com.catsmoker.app.shared.ui.components.QuickActionButton
import com.catsmoker.app.shared.ui.components.ScreenScaffold
import com.catsmoker.app.shared.ui.components.SectionCard
import com.catsmoker.app.shared.ui.theme.CatsmokerTheme
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditGameFilesRoute(onBack: () -> Unit) {
    val viewModel: EditGameFilesViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.onCustomFilePicked(it) }
    }
    val safPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { viewModel.onSafPicked(it) }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { viewModel.onCustomFolderPicked(it) }
    }
    val allFilesPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.onStoragePermissionResult()
    }

    LaunchedEffect(Unit) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is EditGameFilesViewModel.EditEvent.Toast -> Toast.makeText(context, event.message, if (event.isLong) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
                EditGameFilesViewModel.EditEvent.LaunchFilePicker -> filePicker.launch("*/*")
                is EditGameFilesViewModel.EditEvent.LaunchSafPicker -> safPicker.launch(null)
                EditGameFilesViewModel.EditEvent.LaunchFolderPicker -> folderPicker.launch(null)
                EditGameFilesViewModel.EditEvent.LaunchAllFilesAccess -> {
                    viewModel.launchAllFilesAccess()?.let { allFilesPicker.launch(it) }
                }
                EditGameFilesViewModel.EditEvent.ShowZArchiverDialog -> {
                    Toast.makeText(context, "File copied to Downloads. Open ZArchiver to paste.", Toast.LENGTH_LONG).show()
                    viewModel.launchZArchiver()
                }
            }
        }
    }

    EditGameFilesScreen(
        uiState = uiState,
        onGameSelected = viewModel::onGameSelected,
        onProfileSelected = viewModel::onProfileSelected,
        onApplyProfile = viewModel::onApplyProfile,
        onMethodSelected = viewModel::onMethodSelected,
        onDismissChooser = viewModel::dismissMethodChooser,
        onSelectFile = viewModel::onSelectFile,
        onUploadFile = viewModel::onUploadFile,
        onClearSelection = viewModel::onClearSelection,
        onLaunchGame = viewModel::onLaunchGame,
        onBack = onBack
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameSelector(
    selectedGame: GameType,
    onGameSelected: (GameType) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = if (selectedGame == GameType.NONE) "Select a game" else selectedGame.displayName,
            onValueChange = {},
            readOnly = true,
            label = { Text("Game") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth()
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            GameType.entries.filter { it != GameType.NONE }.forEach { game ->
                DropdownMenuItem(
                    text = { Text(game.displayName) },
                    onClick = {
                        onGameSelected(game)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                )
            }
        }
    }
}

@Composable
fun EditGameFilesScreen(
    uiState: EditGameFilesViewModel.UiState,
    onGameSelected: (GameType) -> Unit,
    onProfileSelected: (Int) -> Unit,
    onApplyProfile: () -> Unit,
    onMethodSelected: (Int) -> Unit,
    onDismissChooser: () -> Unit,
    onSelectFile: () -> Unit,
    onUploadFile: () -> Unit,
    onClearSelection: () -> Unit,
    onLaunchGame: () -> Unit,
    onBack: () -> Unit
) {
    ScreenScaffold(
        title = stringResource(R.string.dash_edit_files_title),
        subtitle = "Modify configuration and save games.",
        onBack = onBack
    ) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)
        ) {
            InfoCard(
                title = "WARNING",
                content = "Modifying game files can lead to account bans. Use at your own risk.",
                color = Color.Red
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text("Select Game", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            SectionCard {
                GameSelector(
                    selectedGame = uiState.selectedGame,
                    onGameSelected = onGameSelected
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            if (uiState.selectedGame != GameType.NONE) {
                Text("Select Profile", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                SectionCard {
                    listOf("Unlock 90 FPS", "iPad View (Wide)").forEachIndexed { index, profile ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { onProfileSelected(index) }.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = uiState.selectedProfile == index, onClick = { onProfileSelected(index) })
                            Text(profile, color = Color.White)
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = onApplyProfile, modifier = Modifier.fillMaxWidth()) {
                        Text("APPLY PROFILE")
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text("Custom File Upload", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                SectionCard {
                    Text(uiState.selectedItemText, fontSize = 13.sp, color = Color.LightGray)
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onSelectFile, modifier = Modifier.weight(1f)) { Text("SELECT") }
                        Button(onClick = onUploadFile, modifier = Modifier.weight(1f)) { Text("UPLOAD") }
                    }
                    if (uiState.selectedItemText != stringResource(R.string.selected_item_placeholder)) {
                        TextButton(onClick = onClearSelection, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                            Text("Clear Selection", color = Color.Red.copy(alpha = 0.7f))
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                Button(
                    onClick = onLaunchGame,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Default.PlayArrow, null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("LAUNCH GAME", fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    if (uiState.showMethodChooser) {
        AlertDialog(
            onDismissRequest = onDismissChooser,
            title = { Text("Choose Method") },
            text = { Text("Select how you want to apply the file.") },
            confirmButton = {
                Column {
                    TextButton(onClick = { onMethodSelected(0) }) { Text("SHIZUKU (ROOTLESS)") }
                    TextButton(onClick = { onMethodSelected(1) }) { Text("SAF (MANUAL)") }
                    TextButton(onClick = { onMethodSelected(2) }) { Text("ZARCHIVER (EXTERNAL)") }
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissChooser) { Text("CANCEL") }
            }
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun EditGameFilesPreview() {
    CatsmokerTheme {
        EditGameFilesScreen(
            uiState = EditGameFilesViewModel.UiState(
                selectedGame = GameType.PUBG_GLOBAL,
                selectedItemText = "Active.sav selected"
            ),
            onGameSelected = {},
            onProfileSelected = {},
            onApplyProfile = {},
            onMethodSelected = {},
            onDismissChooser = {},
            onSelectFile = {},
            onUploadFile = {},
            onClearSelection = {},
            onLaunchGame = {},
            onBack = {}
        )
    }
}
