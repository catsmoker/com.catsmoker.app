package com.catsmoker.app.features.spoofdevice

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.catsmoker.app.shared.data.model.DevicePreset
import com.catsmoker.app.shared.data.model.DeviceProfile
import com.catsmoker.app.shared.ui.components.ScreenScaffold
import com.catsmoker.app.shared.ui.components.SectionCard
import com.catsmoker.app.shared.util.RandomGenerator

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditorScreen(
    profileId: String,
    uiState: SpoofDeviceViewModel.UiState,
    presets: List<DevicePreset>,
    onSave: (String, String, DeviceProfile) -> Unit,
    onBack: () -> Unit
) {
    val entry = uiState.profiles.find { it.id == profileId }
    if (entry == null) {
        onBack()
        return
    }

    var profileName by remember { mutableStateOf(entry.name) }
    var profile by remember { mutableStateOf(entry.profile.copy()) }
    var expandedPresets by remember { mutableStateOf(false) }
    var selectedPresetLabel by remember { mutableStateOf("Select a template...") }

    val isDefaultProfile = uiState.profiles.firstOrNull()?.id == profileId

    ScreenScaffold(
        title = "Edit Profile",
        subtitle = "Forge your device identity.",
        onBack = onBack,
        trailingContent = {
            IconButton(onClick = { onSave(profileId, profileName, profile) }) {
                Icon(Icons.Default.Save, null, tint = MaterialTheme.colorScheme.primary)
            }
        }
    ) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SectionCard {
                OutlinedTextField(
                    value = profileName,
                    onValueChange = { if (!isDefaultProfile) profileName = it },
                    label = { Text("Profile Name") },
                    modifier = Modifier.fillMaxWidth(),
                    readOnly = isDefaultProfile,
                    supportingText = {
                        if (isDefaultProfile) {
                            Text("The default profile name cannot be changed.")
                        }
                    }
                )
            }

            Text("Quick Presets", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            SectionCard {
                ExposedDropdownMenuBox(
                    expanded = expandedPresets,
                    onExpandedChange = { expandedPresets = it }
                ) {
                    OutlinedTextField(
                        value = selectedPresetLabel,
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expandedPresets) },
                        modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = expandedPresets,
                        onDismissRequest = { expandedPresets = false }
                    ) {
                        presets.forEach { preset ->
                            DropdownMenuItem(
                                text = { Text(preset.displayName) },
                                onClick = {
                                    profile = preset.profile.copy()
                                    selectedPresetLabel = preset.displayName
                                    expandedPresets = false
                                }
                            )
                        }
                    }
                }
            }

            EditorGroup(title = "Hardware Identity") {
                EditorField("Brand", profile.brand) { profile = profile.copy(brand = it) }
                EditorField("Manufacturer", profile.manufacturer) { profile = profile.copy(manufacturer = it) }
                EditorField("Model", profile.model) { profile = profile.copy(model = it) }
                EditorField("Product Name", profile.productName) { profile = profile.copy(productName = it) }
                EditorField("Device Code", profile.deviceCode) { profile = profile.copy(deviceCode = it) }
                EditorField("Board", profile.board) { profile = profile.copy(board = it) }
                EditorField("Hardware", profile.hardware) { profile = profile.copy(hardware = it) }
                EditorField("Platform", profile.boardPlatform) { profile = profile.copy(boardPlatform = it) }
            }

            EditorGroup(title = "Build Info") {
                Button(
                    onClick = {
                        val buildId = RandomGenerator.generateBuildId()
                        val incremental = RandomGenerator.generateIncremental()
                        profile = profile.copy(
                            buildId = buildId,
                            buildDisplayId = buildId,
                            buildIncremental = incremental,
                            securityPatch = RandomGenerator.generateSecurityPatch(),
                            buildFingerprint = RandomGenerator.generateFingerprint(
                                profile.brand, profile.productName, profile.deviceCode, profile.buildRelease, buildId, incremental
                            ),
                            bootloader = RandomGenerator.generateBootloader(profile.deviceCode)
                        )
                    },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    colors = ButtonDefaults.filledTonalButtonColors()
                ) {
                    Icon(Icons.Default.Casino, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Roll Build Info")
                }
                EditorField("Android Version", profile.buildRelease) { profile = profile.copy(buildRelease = it) }
                EditorField("SDK Level", profile.buildSdk.toString(), numeric = true) { profile = profile.copy(buildSdk = it.toIntOrNull() ?: 0) }
                AdvancedField("Build ID", profile.buildId, { profile = profile.copy(buildId = it, buildDisplayId = it) }) { RandomGenerator.generateBuildId() }
                AdvancedField("Incremental", profile.buildIncremental, { profile = profile.copy(buildIncremental = it) }) { RandomGenerator.generateIncremental() }
                AdvancedField("Security Patch", profile.securityPatch, { profile = profile.copy(securityPatch = it) }) { RandomGenerator.generateSecurityPatch() }
                AdvancedField("Fingerprint", profile.buildFingerprint, { profile = profile.copy(buildFingerprint = it) }) {
                    RandomGenerator.generateFingerprint(profile.brand, profile.productName, profile.deviceCode, profile.buildRelease, profile.buildId, profile.buildIncremental)
                }
                AdvancedField("Bootloader", profile.bootloader, { profile = profile.copy(bootloader = it) }) { RandomGenerator.generateBootloader(profile.deviceCode) }
            }

            EditorGroup(title = "Display Metrics") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) { EditorField("Width", profile.screenWidth.toString(), numeric = true) { profile = profile.copy(screenWidth = it.toIntOrNull() ?: 0) } }
                    Box(Modifier.weight(1f)) { EditorField("Height", profile.screenHeight.toString(), numeric = true) { profile = profile.copy(screenHeight = it.toIntOrNull() ?: 0) } }
                    Box(Modifier.weight(1f)) { EditorField("Density", profile.screenDensity.toString(), numeric = true) { profile = profile.copy(screenDensity = it.toIntOrNull() ?: 0) } }
                }
            }

            EditorGroup(title = "Network & Region") {
                Button(
                    onClick = {
                        val op = RandomGenerator.randomOperator()
                        profile = profile.copy(
                            operatorAlpha = op.first,
                            operatorNumeric = op.second,
                            simOperatorAlpha = op.first,
                            simOperatorNumeric = op.second,
                            simCountryIso = op.third,
                            timezone = RandomGenerator.randomTimezone(),
                            locale = RandomGenerator.randomLocale()
                        )
                    },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    colors = ButtonDefaults.filledTonalButtonColors()
                ) {
                    Icon(Icons.Default.Casino, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Roll Network & Region")
                }
                AdvancedField("Operator Name", profile.operatorAlpha, { profile = profile.copy(operatorAlpha = it) }) {
                    val op = RandomGenerator.randomOperator()
                    profile = profile.copy(operatorNumeric = op.second, simOperatorAlpha = op.first, simOperatorNumeric = op.second, simCountryIso = op.third)
                    op.first
                }
                EditorField("Operator Numeric", profile.operatorNumeric, numeric = true) { profile = profile.copy(operatorNumeric = it) }
                EditorField("SIM Operator Name", profile.simOperatorAlpha) { profile = profile.copy(simOperatorAlpha = it) }
                EditorField("SIM Operator Numeric", profile.simOperatorNumeric, numeric = true) { profile = profile.copy(simOperatorNumeric = it) }
                EditorField("SIM Country ISO", profile.simCountryIso) { profile = profile.copy(simCountryIso = it) }
                AdvancedField("Timezone", profile.timezone, { profile = profile.copy(timezone = it) }) { RandomGenerator.randomTimezone() }
                AdvancedField("Locale", profile.locale, { profile = profile.copy(locale = it) }) { RandomGenerator.randomLocale() }
            }

            EditorGroup(title = "Advanced Identifiers") {
                Button(
                    onClick = {
                        profile = profile.copy(
                            androidId = RandomGenerator.generateAndroidId(),
                            imei = RandomGenerator.generateIMEI(),
                            meid = RandomGenerator.generateMEID(),
                            subscriberId = RandomGenerator.generateIMSI(),
                            simSerialNumber = RandomGenerator.generateICCID(),
                            phoneNumber = RandomGenerator.generatePhoneNumber(),
                            gaid = RandomGenerator.generateGAID(),
                            gsfId = RandomGenerator.generateGSFId(),
                            mediaDrmId = RandomGenerator.generateMediaDrmId(),
                            appSetId = RandomGenerator.generateAppSetId(),
                            serialNumber = RandomGenerator.generateAndroidId().take(12).uppercase()
                        )
                    },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    colors = ButtonDefaults.filledTonalButtonColors()
                ) {
                    Icon(Icons.Default.Casino, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Roll Bones for All IDs")
                }
                AdvancedField("Android ID", profile.androidId, { profile = profile.copy(androidId = it) }) { RandomGenerator.generateAndroidId() }
                AdvancedField("IMEI", profile.imei, { profile = profile.copy(imei = it) }) { RandomGenerator.generateIMEI() }
                AdvancedField("MEID", profile.meid, { profile = profile.copy(meid = it) }) { RandomGenerator.generateMEID() }
                AdvancedField("IMSI (Subscriber ID)", profile.subscriberId, { profile = profile.copy(subscriberId = it) }) { RandomGenerator.generateIMSI() }
                AdvancedField("ICCID (SIM Serial)", profile.simSerialNumber, { profile = profile.copy(simSerialNumber = it) }) { RandomGenerator.generateICCID() }
                AdvancedField("Phone Number", profile.phoneNumber, { profile = profile.copy(phoneNumber = it) }) { RandomGenerator.generatePhoneNumber() }
                AdvancedField("GAID", profile.gaid, { profile = profile.copy(gaid = it) }) { RandomGenerator.generateGAID() }
                AdvancedField("GSF ID", profile.gsfId, { profile = profile.copy(gsfId = it) }) { RandomGenerator.generateGSFId() }
                AdvancedField("Media DRM ID", profile.mediaDrmId, { profile = profile.copy(mediaDrmId = it) }) { RandomGenerator.generateMediaDrmId() }
                AdvancedField("App Set ID", profile.appSetId, { profile = profile.copy(appSetId = it) }) { RandomGenerator.generateAppSetId() }
                AdvancedField("Serial Number", profile.serialNumber, { profile = profile.copy(serialNumber = it) }) { RandomGenerator.generateAndroidId().take(12).uppercase() }
            }
        }
    }
}

@Composable
fun EditorGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.padding(bottom = 8.dp))
        SectionCard(content = content)
    }
}

@Composable
fun EditorField(label: String, value: String, numeric: Boolean = false, multiline: Boolean = false, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        singleLine = !multiline,
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text),
        placeholder = { Text("Leave blank to disable", color = Color.LightGray, fontSize = 12.sp) }
    )
}

@Composable
fun AdvancedField(label: String, value: String, onValueChange: (String) -> Unit, onRandomize: () -> String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            modifier = Modifier.weight(1f),
            placeholder = { Text("Leave blank to disable", color = Color.LightGray, fontSize = 12.sp) }
        )
        IconButton(onClick = { onValueChange(onRandomize()) }) {
            Icon(Icons.Default.Casino, null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}
