package com.catsmoker.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
fun PermissionScreen(
    isAgreementStep: Boolean,
    isAgreed: Boolean,
    rootGranted: Boolean,
    notifGranted: Boolean,
    storageGranted: Boolean,
    batteryGranted: Boolean,
    overlayGranted: Boolean,
    usageGranted: Boolean,
    shizukuGranted: Boolean,
    onAgreedChange: (Boolean) -> Unit,
    onContinue: () -> Unit,
    onRequestRoot: () -> Unit,
    onRequestNotif: () -> Unit,
    onRequestStorage: () -> Unit,
    onRequestOverlay: () -> Unit,
    onRequestUsage: () -> Unit,
    onRequestBattery: () -> Unit,
    onRequestShizuku: () -> Unit
) {
    ScreenScaffold(
        title = "Setup Catsmoker",
        subtitle = if (isAgreementStep) "Terms & Privacy" else "Grant Permissions"
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp)
        ) {
            if (isAgreementStep) {
                AgreementContent(isAgreed, onAgreedChange)
            } else {
                PermissionListContent(
                    rootGranted, notifGranted, storageGranted, 
                    batteryGranted, overlayGranted, usageGranted, shizukuGranted,
                    onRequestRoot, onRequestNotif, onRequestStorage, 
                    onRequestOverlay, onRequestUsage, onRequestBattery, onRequestShizuku
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth().height(56.dp).padding(top = 16.dp),
                shape = RoundedCornerShape(16.dp),
                enabled = !isAgreementStep || isAgreed
            ) {
                Text(if (isAgreementStep) "CONTINUE" else "SKIP / FINISH", fontWeight = FontWeight.Bold)
                if (isAgreementStep) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(Icons.Default.ChevronRight, null)
                }
            }
        }
    }
}

@Composable
fun AgreementContent(isAgreed: Boolean, onAgreedChange: (Boolean) -> Unit) {
    SectionCard {
        Text(
            stringResource(R.string.welcome_title),
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            stringResource(R.string.welcome_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.LightGray,
            lineHeight = 20.sp
        )
        Spacer(modifier = Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = isAgreed, onCheckedChange = onAgreedChange)
            Text(
                stringResource(R.string.agreement_text),
                fontSize = 13.sp,
                color = Color.White,
                modifier = Modifier.clickable { onAgreedChange(!isAgreed) }
            )
        }
    }
}

@Composable
fun PermissionListContent(
    rootGranted: Boolean, notifGranted: Boolean, storageGranted: Boolean,
    batteryGranted: Boolean, overlayGranted: Boolean, usageGranted: Boolean, shizukuGranted: Boolean,
    onRequestRoot: () -> Unit, onRequestNotif: () -> Unit, onRequestStorage: () -> Unit,
    onRequestOverlay: () -> Unit, onRequestUsage: () -> Unit, onRequestBattery: () -> Unit, onRequestShizuku: () -> Unit
) {
    Text("PERMISSIONS", style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.padding(bottom = 12.dp))
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PermissionRow("Root Access", "Required for advanced optimizations", rootGranted, onRequestRoot)
        PermissionRow("Notifications", "Keep tracker running in background", notifGranted, onRequestNotif)
        PermissionRow("All Files Access", "Needed for game data modification", storageGranted, onRequestStorage)
        PermissionRow("Overlay", "Required for FPS and Crosshair", overlayGranted, onRequestOverlay)
        PermissionRow("Usage Stats", "Track game play time", usageGranted, onRequestUsage)
        PermissionRow("Battery Optimization", "Prevent service from being killed", batteryGranted, onRequestBattery)
        PermissionRow("Shizuku", "Alternative for non-rooted users", shizukuGranted, onRequestShizuku)
    }
}

@Composable
fun PermissionRow(title: String, desc: String, granted: Boolean, onClick: () -> Unit) {
    SectionCard {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, color = Color.White)
                Text(desc, fontSize = 11.sp, color = Color.Gray)
            }
            if (granted) {
                Icon(Icons.Default.Check, null, tint = Color(0xFF22C55E))
            } else {
                Button(
                    onClick = onClick,
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    modifier = Modifier.height(32.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("GRANT", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun PermissionPreview() {
    CatsmokerTheme {
        PermissionScreen(
            isAgreementStep = false,
            isAgreed = true,
            rootGranted = true,
            notifGranted = false,
            storageGranted = true,
            batteryGranted = true,
            overlayGranted = false,
            usageGranted = true,
            shizukuGranted = false,
            onAgreedChange = {},
            onContinue = {},
            onRequestRoot = {},
            onRequestNotif = {},
            onRequestStorage = {},
            onRequestOverlay = {},
            onRequestUsage = {},
            onRequestBattery = {},
            onRequestShizuku = {}
        )
    }
}
