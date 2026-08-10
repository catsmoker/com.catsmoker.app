package com.catsmoker.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
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
import com.catsmoker.app.ui.components.ScreenScaffold
import com.catsmoker.app.ui.components.SectionCard
import com.catsmoker.app.ui.theme.CatsmokerTheme

@Composable
fun SpoofDeviceScreen(
    isRooted: Boolean,
    isModuleActive: Boolean,
    isRefreshing: Boolean,
    lsposedEnabled: Boolean,
    targetPackages: String,
    deviceProps: String,
    magiskProps: String,
    onRefresh: () -> Unit,
    onToggleLsposed: (Boolean) -> Unit,
    onTargetPackagesChange: (String) -> Unit,
    onDevicePropsChange: (String) -> Unit,
    onMagiskPropsChange: (String) -> Unit,
    onSaveLsposed: () -> Unit,
    onRestartApps: () -> Unit,
    onGenerateMagiskZip: () -> Unit,
    onOpenRootManager: () -> Unit,
    onBack: () -> Unit
) {
    ScreenScaffold(
        title = stringResource(R.string.root_amp_lsposed),
        subtitle = "Manage advanced system access",
        onBack = onBack
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp)
        ) {
            // 1. Status Section
            Text("SYSTEM STATUS", style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.padding(bottom = 8.dp))
            SectionCard {
                SpoofStatusItem("Root Access", isRooted, Color(0xFFFF9800))
                Spacer(modifier = Modifier.height(12.dp))
                SpoofStatusItem("LSPosed Module", isModuleActive, Color(0xFF4FDCB8))
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onRefresh,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isRefreshing,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (isRefreshing) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Text("REFRESH STATUS")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 2. LSPosed Config
            Text("LSPOSED CONFIGURATION", style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.padding(bottom = 8.dp))
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Enable Module", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Switch(checked = lsposedEnabled, onCheckedChange = onToggleLsposed)
                }
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = targetPackages,
                    onValueChange = onTargetPackagesChange,
                    label = { Text("Target Packages") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = deviceProps,
                    onValueChange = onDevicePropsChange,
                    label = { Text("Device Properties") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onSaveLsposed, modifier = Modifier.weight(1f)) {
                        Text("SAVE")
                    }
                    OutlinedButton(onClick = onRestartApps, modifier = Modifier.weight(1.2f)) {
                        Text("RESTART APPS", fontSize = 12.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 3. Magisk Tools
            Text("MAGISK TOOLS", style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.padding(bottom = 8.dp))
            SectionCard {
                OutlinedTextField(
                    value = magiskProps,
                    onValueChange = onMagiskPropsChange,
                    label = { Text("Magisk module.prop") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = onGenerateMagiskZip, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Download, null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("GENERATE MAGISK ZIP")
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(onClick = onOpenRootManager, modifier = Modifier.fillMaxWidth()) {
                    Text("OPEN ROOT MANAGER")
                }
            }
        }
    }
}

@Composable
fun SpoofStatusItem(label: String, active: Boolean, activeColor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(if (active) activeColor else Color.Gray))
        Spacer(modifier = Modifier.width(12.dp))
        Text(label, fontWeight = FontWeight.SemiBold, color = Color.White)
        Spacer(modifier = Modifier.weight(1f))
        Text(
            if (active) "ACTIVE" else "NOT ACTIVE",
            color = if (active) activeColor else Color.Gray,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun SpoofPreview() {
    CatsmokerTheme {
        SpoofDeviceScreen(
            isRooted = true,
            isModuleActive = false,
            isRefreshing = false,
            lsposedEnabled = true,
            targetPackages = "com.tencent.ig\ncom.pubg.krmobile",
            deviceProps = "ro.product.model=Pixel 8 Pro",
            magiskProps = "id=catsmoker\nname=Catsmoker Mod",
            onRefresh = {},
            onToggleLsposed = {},
            onTargetPackagesChange = {},
            onDevicePropsChange = {},
            onMagiskPropsChange = {},
            onSaveLsposed = {},
            onRestartApps = {},
            onGenerateMagiskZip = {},
            onOpenRootManager = {},
            onBack = {}
        )
    }
}
