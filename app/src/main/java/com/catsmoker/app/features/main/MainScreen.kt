package com.catsmoker.app.features.main

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import android.app.Activity
import android.widget.Toast
import com.catsmoker.app.R
import com.catsmoker.app.shared.data.model.MetricsState
import com.catsmoker.app.shared.ui.components.QuickActionButton
import com.catsmoker.app.shared.ui.components.SectionCard
import com.catsmoker.app.shared.ui.components.StartAppBanner
import com.catsmoker.app.system.navigation.Routes
import com.catsmoker.app.shared.ui.theme.CatsmokerTheme
import com.catsmoker.app.shared.ui.theme.NothingRed
import java.util.Locale

@Composable
fun MainRoute(onNavigate: (String) -> Unit) {
    val viewModel: MainViewModel = hiltViewModel()
    val state by viewModel.metricsState.collectAsState()
    val fpsHistory by viewModel.fpsHistory.collectAsState()
    val cpuHistory by viewModel.cpuHistory.collectAsState()
    val ramHistory by viewModel.ramHistory.collectAsState()
    val tempHistory by viewModel.tempHistory.collectAsState()
    val pingHistory by viewModel.pingHistory.collectAsState()
    val adsEnabled by viewModel.adsEnabled.collectAsState()

    val context = LocalContext.current
    var backPressedCount by remember { mutableIntStateOf(0) }
    var lastBackPressedTime by remember { mutableLongStateOf(0L) }

    BackHandler {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastBackPressedTime < 2000) {
            backPressedCount++
        } else {
            backPressedCount = 1
        }
        lastBackPressedTime = currentTime

        if (backPressedCount >= 3) {
            (context as? Activity)?.finish()
        } else {
            Toast.makeText(
                context,
                "Tap ${3 - backPressedCount} more times to exit",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    MainScreen(
        state = state,
        fpsHistory = fpsHistory,
        cpuHistory = cpuHistory,
        ramHistory = ramHistory,
        tempHistory = tempHistory,
        pingHistory = pingHistory,
        adsEnabled = adsEnabled,
        onOpenSpoofDevice = { onNavigate(Routes.SPOOF_DEVICE) },
        onOpenEditGameFiles = { onNavigate(Routes.EDIT_GAME_FILES) },
        onOpenGamingTools = { onNavigate(Routes.GAMING_TOOLS) },
        onOpenAbout = { onNavigate(Routes.ABOUT) }
    )
}

@Composable
fun MainScreen(
    state: MetricsState,
    fpsHistory: List<Int>,
    cpuHistory: List<Int>,
    ramHistory: List<Float>,
    tempHistory: List<Float>,
    pingHistory: List<Int>,
    adsEnabled: Boolean,
    onOpenSpoofDevice: () -> Unit,
    onOpenEditGameFiles: () -> Unit,
    onOpenGamingTools: () -> Unit,
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
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                color = Color.White
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusBadge(label = stringResource(R.string.res_method_root), active = state.hasRoot, activeColor = NothingRed)
                StatusBadge(label = stringResource(R.string.res_method_shizuku), active = state.hasShizuku, activeColor = Color.White)
            }
        }

        // Content
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
        ) {
            // Performance Monitor
            SectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.dash_live_performance),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.5f)
                        )
                        Row(verticalAlignment = Alignment.Bottom) {
                            val fpsValue = if (state.hasRoot || state.hasShizuku) "${state.fps}" else "N/A"
                            Text(
                                text = fpsValue,
                                style = MaterialTheme.typography.displayLarge,
                                color = NothingRed
                            )
                            Text(
                                text = stringResource(R.string.dash_fps_label),
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White.copy(alpha = 0.5f),
                                modifier = Modifier.padding(start = 4.dp, bottom = 12.dp)
                            )
                        }
                    }
                    
                    // Indicators row
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            val cpuValue = if (state.hasRoot || state.hasShizuku) "${state.cpuPercentage}%" else "N/A"
                            CompactStat(label = "CPU", value = cpuValue, color = Color(0xFF22C55E))
                            CompactStat(label = "RAM", value = "${String.format(Locale.US, "%.1f", state.ramUsedGb)}G", color = Color(0xFF3B82F6))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            CompactStat(label = "TEMP", value = "${state.batteryTempC.toInt()}°", color = Color(0xFFF59E0B))
                            CompactStat(label = "PING", value = "${state.pingMs}ms", color = Color(0xFF8B5CF6))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Combined Chart
                CombinedChart(
                    fpsHistory = fpsHistory,
                    cpuHistory = if (state.hasRoot || state.hasShizuku) cpuHistory else emptyList(),
                    ramHistory = ramHistory,
                    tempHistory = tempHistory,
                    pingHistory = pingHistory,
                    ramTotal = state.ramTotalGb
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            Text(
                text = stringResource(R.string.dash_quick_actions),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.padding(start = 4.dp, bottom = 12.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Max),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                QuickActionButton(
                    title = stringResource(R.string.dash_spoof_title),
                    subtitle = stringResource(R.string.dash_spoof_subtitle),
                    iconContainerColor = Color.White.copy(alpha = 0.05f),
                    iconContentColor = Color.White,
                    onClick = onOpenSpoofDevice,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    icon = { Icon(Icons.Default.SettingsInputComponent, null) }
                )
                QuickActionButton(
                    title = stringResource(R.string.dash_edit_files_title),
                    subtitle = stringResource(R.string.dash_edit_files_subtitle),
                    iconContainerColor = Color.White.copy(alpha = 0.05f),
                    iconContentColor = Color.White,
                    onClick = onOpenEditGameFiles,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    icon = { Icon(Icons.Default.FolderOpen, null) }
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))

            QuickActionButton(
                title = stringResource(R.string.Gaming_tools_title),
                subtitle = stringResource(R.string.dash_gaming_tools_subtitle),
                iconContainerColor = Color.White.copy(alpha = 0.05f),
                iconContentColor = Color.White,
                onClick = onOpenGamingTools,
                isFullWidth = true,
                showChevron = true,
                icon = { Icon(Icons.Default.SportsEsports, null) }
            )

            Spacer(modifier = Modifier.height(12.dp))

            QuickActionButton(
                title = stringResource(R.string.about_header_title),
                subtitle = stringResource(R.string.dash_about_subtitle),
                iconContainerColor = Color.White.copy(alpha = 0.05f),
                iconContentColor = Color.White,
                onClick = onOpenAbout,
                isFullWidth = true,
                showChevron = true,
                icon = { Icon(Icons.Default.Info, null) }
            )

            Spacer(modifier = Modifier.height(40.dp))
        }

        if (adsEnabled) {
            StartAppBanner(modifier = Modifier.padding(bottom = 8.dp))
        }
    }
}

@Composable
fun CombinedChart(
    fpsHistory: List<Int>,
    cpuHistory: List<Int>,
    ramHistory: List<Float>,
    tempHistory: List<Float>,
    pingHistory: List<Int>,
    ramTotal: Float
) {
    val nothingRed = NothingRed
    
    Spacer(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.03f))
            .padding(8.dp)
            .drawWithCache {
                val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                
                val fpsP = createPath(fpsHistory.map { it.toFloat() }, size, 60f)
                val cpuP = createPath(cpuHistory.map { it.toFloat() }, size, 100f)
                val ramP = createPath(ramHistory, size, ramTotal)
                val tempP = createPath(tempHistory, size, 60f)
                val pingP = createPath(pingHistory.map { it.toFloat() }, size, 200f)

                onDrawBehind {
                    drawPath(fpsP, nothingRed, style = stroke)
                    drawPath(cpuP, Color(0xFF22C55E), style = stroke)
                    drawPath(ramP, Color(0xFF3B82F6), style = stroke)
                    drawPath(tempP, Color(0xFFF59E0B), style = stroke)
                    drawPath(pingP, Color(0xFF8B5CF6), style = stroke)
                }
            }
    )
}

private fun createPath(history: List<Float>, size: androidx.compose.ui.geometry.Size, baseMax: Float): androidx.compose.ui.graphics.Path {
    val path = androidx.compose.ui.graphics.Path()
    if (history.size < 2) return path
    val w = size.width
    val h = size.height
    val currentMax = history.maxOrNull()?.coerceAtLeast(baseMax)?.coerceAtLeast(1f) ?: 1f
    
    history.forEachIndexed { i, val_ ->
        val x = w * i / (history.size - 1).toFloat()
        val y = h * (1f - val_ / currentMax)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    return path
}

@Composable
fun StatusBadge(label: String, active: Boolean, activeColor: Color) {
    Box(
        modifier = Modifier
            .background(
                if (active) activeColor.copy(alpha = 0.1f) else Color.White.copy(alpha = 0.03f),
                RoundedCornerShape(8.dp)
            )
            .border(
                1.dp,
                if (active) activeColor.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.05f),
                RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                modifier = Modifier
                    .size(4.dp)
                    .background(if (active) activeColor else Color.White.copy(alpha = 0.3f), CircleShape)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (active) Color.White else Color.White.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
fun CompactStat(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.End) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color.copy(alpha = 0.6f),
            fontSize = 9.sp
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge,
            color = color
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun MainPreview() {
    CatsmokerTheme {
        MainScreen(
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
            cpuHistory = listOf(40, 45, 50, 42, 45),
            ramHistory = listOf(4.0f, 4.2f, 4.1f),
            tempHistory = listOf(37f, 38f, 38f),
            pingHistory = listOf(20, 24, 22),
            adsEnabled = true,
            onOpenSpoofDevice = {},
            onOpenEditGameFiles = {},
            onOpenGamingTools = {},
            onOpenAbout = {}
        )
    }
}
