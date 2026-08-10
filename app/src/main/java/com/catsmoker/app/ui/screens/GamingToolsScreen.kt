package com.catsmoker.app.ui.screens

import android.content.Intent
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.catsmoker.app.R
import com.catsmoker.app.data.model.GameInfo
import com.catsmoker.app.ui.activities.ResolutionChangerActivity
import com.catsmoker.app.util.CleaningFeature
import com.catsmoker.app.util.DnsFeature
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import com.catsmoker.app.core.GamingModeState
import com.catsmoker.app.ui.components.*
import com.catsmoker.app.ui.theme.CatsmokerTheme

@Composable
fun FeaturesScreen(
    gameList: List<GameInfo>,
    isRooted: Boolean,
    isOverlayRunning: Boolean,
    isCrosshairRunning: Boolean,
    selectedCrosshair: String,
    isVpnRunning: Boolean,
    isDndEnabled: Boolean,
    maintenanceLog: List<String>,
    gamingState: GamingModeState,
    isFixedPerformanceMode: Boolean,
    isBoostingRam: Boolean,
    showRamResult: Boolean,
    isOptimizingNet: Boolean,
    showPingResult: Boolean,
    isResettingDefaults: Boolean,
    showResetResult: Boolean,
    boosterLog: List<String>,
    boosterProgress: Float,
    boostLevel: Int,
    animationScales: Triple<Float, Float, Float>,
    alwaysFinishActivities: Boolean,
    backgroundProcessLimit: Boolean,
    angleSelections: Map<String, String>,
    onToggleOverlay: (Boolean) -> Unit,
    onToggleCrosshair: (Boolean) -> Unit,
    onSelectCrosshair: (String) -> Unit,
    onToggleVpn: (Boolean) -> Unit,
    onToggleDnd: (Boolean) -> Unit,
    onPerformMaintenance: (Boolean) -> Unit,
    onActivateGamingMode: () -> Unit,
    onDeactivateGamingMode: () -> Unit,
    onBoostRam: () -> Unit,
    onCheckPing: () -> Unit,
    onResetDefaults: () -> Unit,
    onRunBooster: (String, Boolean) -> Unit,
    onStopBooster: () -> Unit,
    onBoostChange: (Int) -> Unit,
    onSetAnimationScales: (Float, Float, Float) -> Unit,
    onToggleAlwaysFinish: (Boolean) -> Unit,
    onToggleBackgroundLimit: (Boolean) -> Unit,
    onSetAngleDriver: (String, String?) -> Unit,
    onToggleFixedPerformance: (Boolean) -> Unit,
    onLaunchGame: (String) -> Unit,
    onRemoveGame: (String) -> Unit,
    onAddGameClicked: () -> Unit,
    onBack: () -> Unit,
    onSync: () -> Unit
) {
    val context = LocalContext.current
    val isActive = gamingState is GamingModeState.Active
    val isBusy = gamingState is GamingModeState.Enabling || gamingState is GamingModeState.Disabling

    val progressTarget = when (val s = gamingState) {
        is GamingModeState.Enabling -> s.progress
        is GamingModeState.Disabling -> 0.5f
        else -> 0f
    }
    val animatedProgress by animateFloatAsState(
        targetValue = progressTarget,
        animationSpec = tween(300),
        label = "progress"
    )
    
    LaunchedEffect(Unit) {
        onSync()
    }

    ScreenScaffold(
        title = stringResource(R.string.Gaming_tools_title),
        subtitle = "Optimization Hub",
        onBack = onBack
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp)
        ) {
            // Game Library Section
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("YOUR LIBRARY", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                IconButton(onClick = onAddGameClicked, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Add, contentDescription = "Add Game", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(modifier = Modifier.height(12.dp))

            if (gameList.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                    Text("No games detected", color = Color.DarkGray, fontSize = 13.sp)
                }
            } else {
                LazyRow(
                    contentPadding = PaddingValues(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(gameList) { game ->
                        GameLibraryCard(game, onLaunchGame, onRemoveGame)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Gaming Mode Section
            HeroGamingCard(
                gamingState = gamingState,
                animatedProgress = animatedProgress,
                canActivate = true, 
                isActive = isActive,
                isBusy = isBusy,
                onActivate = onActivateGamingMode,
                onDeactivate = onDeactivateGamingMode
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Optimization Sliders
            OptimizationSlidersSection(
                isBoostingRam = isBoostingRam,
                showRamResult = showRamResult,
                isOptimizingNet = isOptimizingNet,
                showPingResult = showPingResult,
                isResettingDefaults = isResettingDefaults,
                showResetResult = showResetResult,
                onBoostRam = onBoostRam,
                onCheckPing = onCheckPing,
                onResetDefaults = onResetDefaults
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Fixed Performance Mode
            FixedPerformanceModeCard(
                enabled = isFixedPerformanceMode,
                onToggle = onToggleFixedPerformance
            )

            Spacer(modifier = Modifier.height(24.dp))

            // 1. PERFORMANCE & BOOST
            Text("PERFORMANCE & BOOST", style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.padding(bottom = 12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ExpandableToolCard(
                    title = "Boost Audio",
                    subtitle = "Amplify system volume beyond hardware limits.",
                    icon = Icons.AutoMirrored.Filled.VolumeUp
                ) {
                    BoostContent(boostLevel, onBoostChange)
                }

                ExpandableToolCard(
                    title = "App Booster",
                    subtitle = "Trigger system-level ART compilation (DEXOPT) for max performance.",
                    icon = Icons.Default.RocketLaunch
                ) {
                    AppBoosterContent(boosterLog, boosterProgress, onRunBooster, onStopBooster)
                }

                ExpandableToolCard(
                    title = "Custom Animator",
                    subtitle = "Precisely control window, transition, and animator scales.",
                    icon = Icons.Default.AutoFixHigh
                ) {
                    CustomAnimatorContent(animationScales, onSetAnimationScales)
                }

                ExpandableToolCard(
                    title = "Graphics API",
                    subtitle = "Force specific drivers (ANGLE/Native) for your games.",
                    icon = Icons.Default.SettingsInputComponent
                ) {
                    GraphicsDriverContent(gameList, angleSelections, onSetAngleDriver)
                }

                QuickActionButton(
                    title = "Resolution Changer",
                    subtitle = "Unlock iPad view or adjust display scaling & DPI.",
                    iconContainerColor = Color.White.copy(alpha = 0.05f),
                    iconContentColor = Color.White,
                    onClick = { context.startActivity(Intent(context, ResolutionChangerActivity::class.java)) },
                    isFullWidth = true,
                    showChevron = true,
                    icon = { Icon(Icons.Default.AspectRatio, null) }
                )

                FeatureToggleCard(
                    title = "FPS Monitor",
                    subtitle = "Real-time overlay showing FPS, CPU, RAM & Temp.",
                    icon = Icons.Default.BarChart,
                    checked = isOverlayRunning,
                    onCheckedChange = onToggleOverlay
                )
                
                ExpandableToolCard(
                    title = "Crosshair",
                    subtitle = "Precision aim overlay for shooters. Tap to choose style.",
                    icon = Icons.Default.AddCircleOutline,
                    isToggleable = true,
                    isToggled = isCrosshairRunning,
                    onToggleChange = onToggleCrosshair,
                    forceExpand = isCrosshairRunning
                ) {
                    CrosshairPicker(selectedCrosshair, onSelectCrosshair)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 2. FOCUS & NETWORK
            Text("FOCUS & NETWORK", style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.padding(bottom = 12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FeatureToggleCard(
                    title = "Gaming DND",
                    subtitle = "Block all non-essential notifications while playing.",
                    icon = Icons.Default.NotificationsOff,
                    checked = isDndEnabled,
                    onCheckedChange = onToggleDnd
                )
                
                FeatureToggleCard(
                    title = "Network Firewall",
                    subtitle = "Blocks background traffic & routes game data directly.",
                    icon = Icons.Default.VpnLock,
                    checked = isVpnRunning,
                    onCheckedChange = onToggleVpn
                )

                ExpandableToolCard(
                    title = "DNS Optimization",
                    subtitle = "Apply low-latency DNS (Google, Cloudflare) via Root.",
                    icon = Icons.Default.Dns
                ) {
                    DnsContent()
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 3. SYSTEM & ADVANCED
            Text("SYSTEM & ADVANCED", style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.padding(bottom = 12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FeatureToggleCard(
                    title = "Discard Activities",
                    subtitle = "Immediately destroy activities once you leave them (Low RAM).",
                    icon = Icons.Default.HistoryToggleOff,
                    checked = alwaysFinishActivities,
                    onCheckedChange = onToggleAlwaysFinish
                )

                FeatureToggleCard(
                    title = "Process Limit",
                    subtitle = "Restrict system to a single background process for focus.",
                    icon = Icons.Default.DataThresholding,
                    checked = backgroundProcessLimit,
                    onCheckedChange = onToggleBackgroundLimit
                )

                ExpandableToolCard(
                    title = "System Cleaner",
                    subtitle = "Clear cache for ALL apps, delete empty items & temp files.",
                    icon = Icons.Default.DeleteSweep
                ) {
                    CleaningContent(isRooted, maintenanceLog, onPerformMaintenance)
                }
                
            }
        }
    }
}

@Composable
fun GameLibraryCard(game: GameInfo, onLaunch: (String) -> Unit, onRemove: (String) -> Unit) {
    Surface(
        modifier = Modifier.width(140.dp).clip(RoundedCornerShape(18.dp)),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.05f))
    ) {
        Box {
            Column(modifier = Modifier.padding(12.dp)) {
                Image(
                    bitmap = game.icon.toBitmap().asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp))
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = game.appName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = game.playTime ?: "Optimized",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 10.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = { onLaunch(game.packageName) },
                    modifier = Modifier.fillMaxWidth().height(32.dp),
                    contentPadding = PaddingValues(0.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("LAUNCH", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }

            IconButton(
                onClick = { onRemove(game.packageName) },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Remove,
                    contentDescription = "Remove Game",
                    tint = Color.Gray.copy(alpha = 0.8f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
fun FeatureToggleCard(title: String, subtitle: String, icon: ImageVector, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    SectionCard {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.05f)),
                contentAlignment = Alignment.Center
            ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp)) }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 15.sp)
                Text(subtitle, color = Color.Gray, fontSize = 12.sp)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange, colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.primary, checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)))
        }
    }
}

@Composable
fun ExpandableToolCard(
    title: String, 
    subtitle: String, 
    icon: ImageVector, 
    isToggleable: Boolean = false,
    isToggled: Boolean = false,
    onToggleChange: (Boolean) -> Unit = {},
    forceExpand: Boolean = false,
    content: @Composable () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    
    LaunchedEffect(forceExpand) {
        if (forceExpand) expanded = true
    }

    SectionCard {
        Column(modifier = Modifier.fillMaxWidth().animateContentSize()) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.05f)).clickable { expanded = !expanded }, 
                    contentAlignment = Alignment.Center
                ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp)) }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f).clickable { expanded = !expanded }) {
                    Text(title, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 15.sp)
                    Text(subtitle, color = Color.Gray, fontSize = 12.sp)
                }
                if (isToggleable) {
                    Switch(checked = isToggled, onCheckedChange = onToggleChange, colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.primary, checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)))
                } else {
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, tint = Color.Gray)
                    }
                }
            }
            if (expanded) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = Color.White.copy(alpha = 0.05f))
                content()
            }
        }
    }
}

@Composable
fun CrosshairPicker(selected: String, onSelect: (String) -> Unit) {
    val context = LocalContext.current
    val scopes = remember { 
        (1..7).map { "scope$it.png" }
    }
    
    Column {
        Text("SELECT STYLE", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            scopes.forEach { scope ->
                val isSelected = selected == scope
                val bitmap = remember(scope) {
                    try {
                        context.assets.open("crosshair/$scope").use { input ->
                            BitmapFactory.decodeStream(input)
                        }
                    } catch (_: Exception) { null }
                }

                Surface(
                    modifier = Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).clickable { onSelect(scope) },
                    color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.03f),
                    border = if (isSelected) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(8.dp)) {
                        if (bitmap != null) {
                            Image(bitmap = bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize())
                        } else {
                            Icon(Icons.Default.BrokenImage, null, tint = Color.DarkGray)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CleaningContent(isRooted: Boolean, log: List<String>, onPerform: (Boolean) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Select cleaning depth:", fontSize = 12.sp, color = Color.LightGray)
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onPerform(false) }, modifier = Modifier.weight(1f)) { Text("Safe Clean") }
            if (isRooted) {
                Button(onClick = { onPerform(true) }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.6f))) { Text("Deep Clean") }
            }
        }
        
        if (log.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Box(
                modifier = Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.4f)).padding(10.dp)
            ) {
                val scroll = rememberScrollState()
                LaunchedEffect(log.size) { scroll.animateScrollTo(scroll.maxValue) }
                Text(log.joinToString("\n"), fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 11.sp, color = Color(0xFF22C55E), modifier = Modifier.verticalScroll(scroll))
            }
        }
    }
}

@Composable
fun DnsContent() {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Root DNS Overrides:", fontSize = 12.sp, color = Color.LightGray)
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { DnsFeature.applyRootDns("1.1.1.1", "1.0.0.1"); Toast.makeText(context, "Cloudflare Applied", Toast.LENGTH_SHORT).show() }, modifier = Modifier.weight(1f)) { Text("Cloudflare") }
            OutlinedButton(onClick = { DnsFeature.applyRootDns("8.8.8.8", "8.8.4.4"); Toast.makeText(context, "Google Applied", Toast.LENGTH_SHORT).show() }, modifier = Modifier.weight(1f)) { Text("Google") }
        }
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(onClick = { DnsFeature.resetRootDns(); Toast.makeText(context, "DNS Reset", Toast.LENGTH_SHORT).show() }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Reset to System Default") }
    }
}

@Composable
fun BoostContent(level: Int, onLevelChange: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Boost Level", fontSize = 14.sp, color = Color.White)
            Text("${level * 10}%", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = level.toFloat(),
            onValueChange = { onLevelChange(it.toInt()) },
            valueRange = 0f..10f,
            steps = 9,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = Color.White.copy(alpha = 0.1f)
            )
        )
        Text(
            "⚠️ Caution: High boost levels may distort audio or damage speakers. Use responsibly.",
            fontSize = 10.sp,
            color = Color.Gray,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
fun GraphicsDriverContent(games: List<GameInfo>, selections: Map<String, String>, onSet: (String, String?) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (games.isEmpty()) {
            Text("Add games to library to override drivers", fontSize = 12.sp, color = Color.Gray)
        } else {
            games.forEach { game ->
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    Text(game.appName, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(modifier = Modifier.height(8.dp))
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        val current = selections[game.packageName]
                        SegmentedButton(
                            selected = current == null,
                            onClick = { onSet(game.packageName, null) },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3)
                        ) { Text("DEF", fontSize = 9.sp) }
                        SegmentedButton(
                            selected = current == "native",
                            onClick = { onSet(game.packageName, "native") },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3)
                        ) { Text("NAT", fontSize = 9.sp) }
                        SegmentedButton(
                            selected = current == "angle",
                            onClick = { onSet(game.packageName, "angle") },
                            shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3)
                        ) { Text("ANGLE", fontSize = 9.sp) }
                    }
                }
            }
        }
    }
}

@Composable
fun CustomAnimatorContent(scales: Triple<Float, Float, Float>, onApply: (Float, Float, Float) -> Unit) {
    var windowScale by remember(scales) { mutableStateOf(scales.first) }
    var transitionScale by remember(scales) { mutableStateOf(scales.second) }
    var animatorScale by remember(scales) { mutableStateOf(scales.third) }

    Column(modifier = Modifier.fillMaxWidth()) {
        ScaleSlider("Window Animation", windowScale) { windowScale = it }
        Spacer(modifier = Modifier.height(12.dp))
        ScaleSlider("Transition Animation", transitionScale) { transitionScale = it }
        Spacer(modifier = Modifier.height(12.dp))
        ScaleSlider("Animator Duration", animatorScale) { animatorScale = it }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { windowScale = 0.5f; transitionScale = 0.5f; animatorScale = 0.5f },
                modifier = Modifier.weight(1f)
            ) { Text("0.5X ALL", fontSize = 10.sp) }
            OutlinedButton(
                onClick = { windowScale = 0f; transitionScale = 0f; animatorScale = 0f },
                modifier = Modifier.weight(1f)
            ) { Text("OFF ALL", fontSize = 10.sp) }
            Button(
                onClick = { onApply(windowScale, transitionScale, animatorScale) },
                modifier = Modifier.weight(1.5f)
            ) { Text("APPLY", fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun ScaleSlider(label: String, value: Float, onValueChange: (Float) -> Unit) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 12.sp, color = Color.Gray)
            Text("${String.format(java.util.Locale.US, "%.2f", value)}x", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = 0f..5f,
            steps = 49,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = Color.White.copy(alpha = 0.1f)
            )
        )
    }
}

@Composable
fun AppBoosterContent(log: List<String>, progress: Float, onRun: (String, Boolean) -> Unit, onStop: () -> Unit) {
    var mode by remember { mutableStateOf("speed-profile") }
    var force by remember { mutableStateOf(false) }
    val isRunning = progress > 0f && progress < 1f

    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Optimization Mode:", fontSize = 12.sp, color = Color.LightGray)
        Spacer(modifier = Modifier.height(8.dp))
        
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("speed-profile", "speed", "everything").forEach { m ->
                val isSelected = mode == m
                OutlinedButton(
                    onClick = { mode = m },
                    modifier = Modifier.weight(1f),
                    enabled = !isRunning,
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f) else Color.Transparent,
                        contentColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray
                    ),
                    border = BorderStroke(1.dp, if (isSelected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.1f))
                ) {
                    Text(m.replace("-", "\n").uppercase(), fontSize = 9.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = 10.sp)
                }
            }
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = force, onCheckedChange = { force = it }, enabled = !isRunning)
            Text("Force re-compile (Slower)", fontSize = 12.sp, color = Color.Gray)
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        
        if (isRunning) {
            Button(
                onClick = onStop,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.2f), contentColor = Color.Red)
            ) {
                Icon(Icons.Default.Stop, null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("STOP OPTIMIZATION", fontWeight = FontWeight.Bold)
            }
        } else {
            Button(
                onClick = { onRun(mode, force) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("START BOOST", fontWeight = FontWeight.Bold)
            }
        }

        if (progress > 0f || log.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            
            if (progress > 0f) {
                LinearProgressIndicator(
                    progress = { progress }, 
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.2f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.List, null, tint = Color.Gray, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("ACTIVITY FEED", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(modifier = Modifier.fillMaxWidth().height(140.dp)) {
                        val scroll = rememberScrollState()
                        LaunchedEffect(log.size) { scroll.animateScrollTo(scroll.maxValue) }
                        Column(modifier = Modifier.verticalScroll(scroll)) {
                            log.forEach { entry ->
                                val (icon, color) = when {
                                    entry.contains("✅") -> Icons.Default.CheckCircle to Color(0xFF4CAF50)
                                    entry.contains("❌") || entry.contains("⏹") -> Icons.Default.Cancel to Color(0xFFF44336)
                                    entry.contains("🚀") -> Icons.Default.RocketLaunch to Color(0xFF9C27B0)
                                    entry.contains("⚡") -> Icons.Default.Bolt to Color(0xFFEAB308)
                                    else -> Icons.Default.Info to Color(0xFF38BDF8)
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .padding(vertical = 4.dp)
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(color.copy(alpha = 0.1f))
                                        .padding(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    Icon(icon, null, tint = color, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = entry.replace(Regex("[🚀✅❌⏹⚡]"), "").trim(),
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = Color.White.copy(alpha = 0.9f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun FeaturesPreview() {
    CatsmokerTheme {
        FeaturesScreen(
            gameList = emptyList(),
            isRooted = true,
            isOverlayRunning = false,
            isCrosshairRunning = true,
            selectedCrosshair = "scope2.png",
            isVpnRunning = false,
            isDndEnabled = true,
            maintenanceLog = listOf("[12:00:00] Initialized", "[12:00:01] Cleaning..."),
            gamingState = GamingModeState.Idle,
            isFixedPerformanceMode = false,
            isBoostingRam = false,
            showRamResult = false,
            isOptimizingNet = false,
            showPingResult = false,
            isResettingDefaults = false,
            showResetResult = false,
            boosterLog = emptyList(),
            boosterProgress = 0f,
            boostLevel = 0,
            animationScales = Triple(1f, 1f, 1f),
            alwaysFinishActivities = false,
            backgroundProcessLimit = false,
            angleSelections = emptyMap(),
            onToggleOverlay = {},
            onToggleCrosshair = {},
            onSelectCrosshair = {},
            onToggleVpn = {},
            onToggleDnd = {},
            onPerformMaintenance = {},
            onActivateGamingMode = {},
            onDeactivateGamingMode = {},
            onBoostRam = {},
            onCheckPing = {},
            onResetDefaults = {},
            onRunBooster = { _, _ -> },
            onStopBooster = {},
            onBoostChange = {},
            onSetAnimationScales = { _, _, _ -> },
            onToggleAlwaysFinish = {},
            onToggleBackgroundLimit = {},
            onSetAngleDriver = { _, _ -> },
            onToggleFixedPerformance = {},
            onLaunchGame = {},
            onRemoveGame = {},
            onAddGameClicked = {},
            onBack = {},
            onSync = {}
        )
    }
}

@Composable
fun AppPickerDialog(
    apps: List<GameInfo>,
    onDismiss: () -> Unit,
    onAppSelected: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add App to Library") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                if (apps.isEmpty()) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                } else {
                    androidx.compose.foundation.lazy.LazyColumn {
                        items(apps) { app ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onAppSelected(app.packageName) }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Image(
                                    bitmap = app.icon.toBitmap().asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier.size(32.dp).clip(RoundedCornerShape(4.dp))
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(app.appName, style = MaterialTheme.typography.bodyMedium, color = Color.White)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
