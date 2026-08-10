package com.catsmoker.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
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
import com.catsmoker.app.ui.components.InfoCard
import com.catsmoker.app.ui.components.ScreenScaffold
import com.catsmoker.app.ui.components.SectionCard
import com.catsmoker.app.ui.theme.CatsmokerTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResolutionScreen(
    widthInput: String,
    heightInput: String,
    dpiInput: String,
    isRootAvailable: Boolean,
    isShizukuAvailable: Boolean,
    selectedMethod: Int,
    logItems: List<String>,
    onWidthChange: (String) -> Unit,
    onHeightChange: (String) -> Unit,
    onDpiChange: (String) -> Unit,
    onMethodSelected: (Int) -> Unit,
    onApply: () -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit
) {
    ScreenScaffold(
        title = stringResource(R.string.resolution_changer_title),
        subtitle = "Custom display scaling & DPI",
        onBack = onBack
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp)
        ) {
            InfoCard(
                title = "DANGEROUS SETTINGS",
                content = "Changing resolution or DPI incorrectly can make the device unusable. Ensure you know the correct values for your screen aspect ratio.",
                color = Color(0xFFEF4444)
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Method Selection
            Text("APPLY METHOD", style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.padding(bottom = 8.dp))
            SectionCard {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ResMethodButton("ROOT", selectedMethod == 0, isRootAvailable, Modifier.weight(1f)) { onMethodSelected(0) }
                    ResMethodButton("SHIZUKU", selectedMethod == 1, isShizukuAvailable, Modifier.weight(1f)) { onMethodSelected(1) }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Inputs
            Text("DIMENSIONS & DPI", style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.padding(bottom = 8.dp))
            SectionCard {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = widthInput,
                        onValueChange = onWidthChange,
                        label = { Text("Width") },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    )
                    OutlinedTextField(
                        value = heightInput,
                        onValueChange = onHeightChange,
                        label = { Text("Height") },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = dpiInput,
                    onValueChange = onDpiChange,
                    label = { Text("Density (DPI)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(modifier = Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onApply,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("APPLY")
                    }
                    OutlinedButton(
                        onClick = onReset,
                        modifier = Modifier.weight(0.6f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("RESET")
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Console Log
            Text("EXECUTION LOG", style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.padding(bottom = 8.dp))
            SectionCard {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.4f))
                        .padding(12.dp)
                ) {
                    val scroll = rememberScrollState()
                    LaunchedEffect(logItems.size) { scroll.animateScrollTo(scroll.maxValue) }
                    Text(
                        text = logItems.joinToString("\n"),
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = Color(0xFF22C55E),
                        modifier = Modifier.verticalScroll(scroll)
                    )
                }
            }
        }
    }
}

@Composable
fun ResMethodButton(label: String, selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val color = if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.05f)
    val textColor = if (selected) Color.White else if (enabled) Color.LightGray else Color.DarkGray
    Surface(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled) { onClick() },
        color = color,
        border = if (!selected && enabled) androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)) else null
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, color = textColor, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun ResolutionPreview() {
    CatsmokerTheme {
        ResolutionScreen(
            widthInput = "1080",
            heightInput = "2400",
            dpiInput = "480",
            isRootAvailable = true,
            isShizukuAvailable = true,
            selectedMethod = 0,
            logItems = listOf("[12:00:00] Initialized", "[12:00:01] Check root: OK"),
            onWidthChange = {},
            onHeightChange = {},
            onDpiChange = {},
            onMethodSelected = {},
            onApply = {},
            onReset = {},
            onBack = {}
        )
    }
}
