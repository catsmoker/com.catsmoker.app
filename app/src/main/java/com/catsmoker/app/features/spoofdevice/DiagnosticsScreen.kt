package com.catsmoker.app.features.spoofdevice

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.catsmoker.app.shared.ui.components.ScreenScaffold
import com.catsmoker.app.shared.ui.components.SectionCard

@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    ScreenScaffold(
        title = "Diagnostics",
        subtitle = "Current device identity (spoofed or real).",
        onBack = onBack
    ) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            DiagGroup(title = "Hardware") {
                DiagField("Brand", Build.BRAND)
                DiagField("Manufacturer", Build.MANUFACTURER)
                DiagField("Model", Build.MODEL)
                DiagField("Product", Build.PRODUCT)
                DiagField("Device", Build.DEVICE)
                DiagField("Board", Build.BOARD)
                DiagField("Hardware", Build.HARDWARE)
            }

            DiagGroup(title = "Software") {
                DiagField("Android Release", Build.VERSION.RELEASE)
                DiagField("SDK Level", Build.VERSION.SDK_INT.toString())
                DiagField("Build ID", Build.ID)
                DiagField("Incremental", Build.VERSION.INCREMENTAL)
                DiagField("Fingerprint", Build.FINGERPRINT)
            }
            
            DiagGroup(title = "System") {
                DiagField("Bootloader", Build.BOOTLOADER)
                DiagField("Radio/Modem", Build.getRadioVersion() ?: "Unknown")
                DiagField("Tags", Build.TAGS)
                DiagField("Type", Build.TYPE)
            }
        }
    }
}

@Composable
fun DiagGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.padding(bottom = 8.dp))
        SectionCard(content = content)
    }
}

@Composable
fun DiagField(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Color.Gray, fontSize = 14.sp)
        Text(value, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}
