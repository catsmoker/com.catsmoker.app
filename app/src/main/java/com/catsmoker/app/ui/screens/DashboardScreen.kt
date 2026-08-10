package com.catsmoker.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.catsmoker.app.data.model.MetricsState
import com.catsmoker.app.ui.components.QuickActionButton
import com.catsmoker.app.ui.components.SectionCard
import com.catsmoker.app.ui.theme.CatsmokerTheme
import java.util.Locale

@Composable
fun DashboardScreen(
    state: MetricsState,
    fpsHistory: List<Int>,
    onOpenSpoofDevice: () -> Unit,
    onOpenEditGameFiles: () -> Unit,
    onOpenGameTools: () -> Unit,
    onOpenAbout: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Catsmoker",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusBadge(label = "ROOT", active = state.hasRoot, activeColor = Color(0xFFFF9800))
                StatusBadge(label = "SHIZUKU", active = state.hasShizuku, activeColor = Color(0xFF4FDCB8))
            }
        }

        // Content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
        ) {
            // Compact Performance Monitor
            SectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "LIVE PERFORMANCE",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray,
                            letterSpacing = 0.08.sp
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${state.fps}",
                                fontSize = 32.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = " FPS",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Gray,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                    
                    // Indicators row
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CompactStat(label = "CPU", value = "${state.cpuPercentage ?: 0}%", color = Color(0xFFF87171))
                        CompactStat(label = "RAM", value = "${String.format(Locale.US, "%.1f", state.ramUsedGb)}G", color = Color(0xFF60A5FA))
                        CompactStat(label = "TEMP", value = "${state.batteryTempC.toInt()}°", color = Color(0xFFFBBF24))
                        CompactStat(label = "PING", value = "${state.pingMs}ms", color = Color(0xFF4FDCB8))
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Chart
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black.copy(alpha = 0.2f))
                        .padding(8.dp)
                ) {
                    val lineColor = MaterialTheme.colorScheme.primary
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val w = size.width
                        val h = size.height
                        if (fpsHistory.size >= 2) {
                            val maxFps = fpsHistory.max().coerceAtLeast(60)
                            val path = androidx.compose.ui.graphics.Path()
                            fpsHistory.forEachIndexed { i, fps ->
                                val x = w * i / (fpsHistory.size - 1).toFloat()
                                val y = h * (1f - fps.toFloat() / maxFps)
                                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                            }
                            drawPath(
                                path = path,
                                color = lineColor,
                                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                            )
                            val fillPath = androidx.compose.ui.graphics.Path().apply {
                                addPath(path)
                                lineTo(w, h)
                                lineTo(0f, h)
                                close()
                            }
                            drawPath(
                                fillPath,
                                brush = Brush.verticalGradient(
                                    colors = listOf(lineColor.copy(alpha = 0.2f), Color.Transparent)
                                )
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            Text(
                text = "QUICK ACTIONS",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.Gray,
                letterSpacing = 0.06.sp,
                modifier = Modifier.padding(start = 4.dp, bottom = 12.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Max),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                QuickActionButton(
                    title = "Spoof Device",
                    subtitle = "Override props & mods",
                    iconContainerColor = Color(0xFFE65100).copy(alpha = 0.14f),
                    iconContentColor = Color(0xFFFF9800),
                    onClick = onOpenSpoofDevice,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    icon = { Icon(Icons.Default.Build, null) }
                )
                QuickActionButton(
                    title = "Edit Game Files",
                    subtitle = "SAF & Shizuku Tools",
                    iconContainerColor = Color(0xFF2FBF9F).copy(alpha = 0.14f),
                    iconContentColor = Color(0xFF4FDCB8),
                    onClick = onOpenEditGameFiles,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    icon = { Icon(Icons.Default.Description, null) }
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))

            QuickActionButton(
                title = "Gaming Tools",
                subtitle = "Overlays, VPN & optimization",
                iconContainerColor = Color(0xFF6C6CE0).copy(alpha = 0.14f),
                iconContentColor = Color(0xFF9494EE),
                onClick = onOpenGameTools,
                isFullWidth = true,
                showChevron = true,
                icon = { Icon(Icons.Default.SportsEsports, null) }
            )

            Spacer(modifier = Modifier.height(12.dp))

            QuickActionButton(
                title = "About",
                subtitle = "Version, Updates & Developer",
                iconContainerColor = Color(0xFF9E9E9E).copy(alpha = 0.14f),
                iconContentColor = Color(0xFFBDBDBD),
                onClick = onOpenAbout,
                isFullWidth = true,
                showChevron = true,
                icon = { Icon(Icons.Default.Info, null) }
            )

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

@Composable
fun StatusBadge(label: String, active: Boolean, activeColor: Color) {
    Box(
        modifier = Modifier
            .background(
                if (active) activeColor.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.05f),
                RoundedCornerShape(8.dp)
            )
            .border(
                1.dp,
                if (active) activeColor.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.1f),
                RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(if (active) activeColor else Color.Gray, CircleShape)
            )
            Text(
                text = label,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = if (active) Color.White else Color.Gray
            )
        }
    }
}

@Composable
fun CompactStat(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.End) {
        Text(text = label, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
        Text(text = value, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun DashboardPreview() {
    CatsmokerTheme {
        DashboardScreen(
            state = MetricsState(
                fps = 60,
                cpuPercentage = 45,
                ramUsedGb = 4.2f,
                ramTotalGb = 8.0f,
                batteryTempC = 38f,
                pingMs = 24,
                hasRoot = true,
                hasShizuku = true
            ),
            fpsHistory = listOf(55, 58, 60, 59, 60, 60, 57, 58, 60),
            onOpenSpoofDevice = {},
            onOpenEditGameFiles = {},
            onOpenGameTools = {},
            onOpenAbout = {}
        )
    }
}
