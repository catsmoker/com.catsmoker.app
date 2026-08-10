package com.catsmoker.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.catsmoker.app.R
import com.catsmoker.app.ui.activities.EditGameFilesActivity
import com.catsmoker.app.ui.components.InfoCard
import com.catsmoker.app.ui.components.ScreenScaffold
import com.catsmoker.app.ui.components.SectionCard
import com.catsmoker.app.ui.theme.CatsmokerTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditGameFilesScreen(
    isLoading: Boolean,
    selectedGame: EditGameFilesActivity.GameType,
    selectedProfile: Int,
    selectedItemText: String,
    onGameSelected: (EditGameFilesActivity.GameType) -> Unit,
    onProfileSelected: (Int) -> Unit,
    onApplyProfile: () -> Unit,
    onSelectFile: () -> Unit,
    onSelectFolder: () -> Unit,
    onUploadFile: () -> Unit,
    onUploadFolder: () -> Unit,
    onClearSelection: () -> Unit,
    onLaunchGame: () -> Unit,
    onBack: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ScreenScaffold(
        title = stringResource(R.string.non_root_file_manager_title),
        subtitle = stringResource(R.string.non_root_header_subtitle),
        onBack = onBack
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp)
        ) {
            InfoCard(
                title = "INSTRUCTIONS",
                content = stringResource(R.string.non_root_instructions),
                color = Color(0xFF3B82F6)
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Game Selection
            Text("GAME SELECTION", style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.padding(bottom = 8.dp))
            SectionCard {
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded }
                ) {
                    OutlinedTextField(
                        value = selectedGame.displayName,
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        EditGameFilesActivity.GameType.entries.forEach { game ->
                            DropdownMenuItem(
                                text = { Text(game.displayName) },
                                onClick = {
                                    onGameSelected(game)
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }

            if (selectedGame != EditGameFilesActivity.GameType.NONE) {
                Spacer(modifier = Modifier.height(24.dp))

                // Profile Section
                Text("CHOOSE PROFILE", style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.padding(bottom = 8.dp))
                SectionCard {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EditProfileButton("Max FPS", selectedProfile == 0, Modifier.weight(1f)) { onProfileSelected(0) }
                        EditProfileButton("iPad View", selectedProfile == 1, Modifier.weight(1f)) { onProfileSelected(1) }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = onApplyProfile,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("APPLY PROFILE")
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Custom Content
                Text("CUSTOM CONTENT", style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.padding(bottom = 8.dp))
                SectionCard {
                    Text(selectedItemText, fontSize = 13.sp, color = Color.LightGray)
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onSelectFile, modifier = Modifier.weight(1f)) {
                            Text("Select File", fontSize = 12.sp)
                        }
                        OutlinedButton(onClick = onSelectFolder, modifier = Modifier.weight(1f)) {
                            Text("Select Folder", fontSize = 12.sp)
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onUploadFile, modifier = Modifier.weight(1f)) {
                            Text("Upload File", fontSize = 12.sp)
                        }
                        Button(onClick = onUploadFolder, modifier = Modifier.weight(1f)) {
                            Text("Upload Folder", fontSize = 12.sp)
                        }
                    }
                    TextButton(onClick = onClearSelection, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text("Clear Selection", color = Color.Gray)
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))

                Button(
                    onClick = onLaunchGame,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22C55E)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("LAUNCH GAME", fontWeight = FontWeight.Bold)
                }
            }

            if (isLoading) {
                Spacer(modifier = Modifier.height(24.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
fun EditProfileButton(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val color = if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.05f)
    val textColor = if (selected) Color.White else Color.Gray
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(color)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = textColor, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun EditPreview() {
    CatsmokerTheme {
        EditGameFilesScreen(
            isLoading = false,
            selectedGame = EditGameFilesActivity.GameType.PUBG_GLOBAL,
            selectedProfile = 0,
            selectedItemText = "No file selected",
            onGameSelected = {},
            onProfileSelected = {},
            onApplyProfile = {},
            onSelectFile = {},
            onSelectFolder = {},
            onUploadFile = {},
            onUploadFolder = {},
            onClearSelection = {},
            onLaunchGame = {},
            onBack = {}
        )
    }
}
