package com.catsmoker.app.features.gamingtools.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.catsmoker.app.features.gamingtools.engine.GamingModeReport
import com.catsmoker.app.features.gamingtools.engine.GamingModeState
import com.catsmoker.app.shared.ui.components.SectionCard

@Composable
fun HeroGamingCard(
    gamingState: GamingModeState,
    report: GamingModeReport,
    animatedProgress: Float,
    canActivate: Boolean,
    isActive: Boolean,
    isBusy: Boolean,
    onActivate: () -> Unit,
    onDeactivate: () -> Unit
) {
    SectionCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "GAMING MODE",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isActive) MaterialTheme.colorScheme.primary else Color.Gray,
                        letterSpacing = 1.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = when (gamingState) {
                            is GamingModeState.Active -> "System Locked"
                            // The engine already reports which step it is on, so show that rather
                            // than a generic word.
                            is GamingModeState.Enabling -> gamingState.statusText
                            is GamingModeState.Disabling -> "Reverting…"
                            is GamingModeState.Error -> "Activation failed"
                            is GamingModeState.Idle -> "Optimizations Ready"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        color = if (gamingState is GamingModeState.Error) {
                            MaterialTheme.colorScheme.error
                        } else {
                            Color.White
                        }
                    )
                }

                IconButton(
                    onClick = { if (isActive) onDeactivate() else onActivate() },
                    enabled = !isBusy && canActivate,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(
                            if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                            else Color.White.copy(alpha = 0.05f)
                        )
                        .border(
                            1.dp,
                            if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                            else Color.White.copy(alpha = 0.1f),
                            CircleShape
                        )
                ) {
                    Icon(
                        imageVector = Icons.Default.PowerSettingsNew,
                        contentDescription = null,
                        tint = if (isActive) MaterialTheme.colorScheme.primary else Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Animated Status Bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.05f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(animatedProgress)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                                    MaterialTheme.colorScheme.primary
                                )
                            )
                        )
                )
            }

            if (gamingState is GamingModeState.Error) {
                Spacer(modifier = Modifier.height(16.dp))
                NoticeBlock(text = gamingState.message, tint = MaterialTheme.colorScheme.error)
            }

            if (isActive) {
                Spacer(modifier = Modifier.height(24.dp))
                // Every row below is a value the engine read back from the device after writing it,
                // so a change the ROM refused shows as refused instead of as a success.
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    EsportsStatusRow(
                        label = "PowerHAL",
                        value = if (report.fixedPerformance) "Fixed performance mode" else "Not applied",
                        applied = report.fixedPerformance
                    )
                    EsportsStatusRow(
                        label = "Display",
                        value = report.lockedRefreshHz?.let { "Locked $it Hz" } ?: "Rate not locked",
                        applied = report.lockedRefreshHz != null
                    )
                    EsportsStatusRow(
                        label = "Touch response",
                        value = if (report.touchResponseBoost) "Boosted" else "Not supported",
                        applied = report.touchResponseBoost
                    )
                    EsportsStatusRow(
                        label = "Background apps",
                        value = when {
                            report.suspendedPackages > 0 && report.suspendFailures > 0 ->
                                "${report.suspendedPackages} suspended, ${report.suspendFailures} refused"
                            report.suspendedPackages > 0 -> "${report.suspendedPackages} suspended"
                            report.suspendFailures > 0 -> "${report.suspendFailures} refused"
                            else -> "None to suspend"
                        },
                        applied = report.suspendedPackages > 0
                    )
                    EsportsStatusRow(
                        label = "Do Not Disturb",
                        value = if (report.dndEngaged) "Engaged" else "Off",
                        applied = report.dndEngaged
                    )
                    report.networkWhitelisted?.let { whitelisted ->
                        EsportsStatusRow(
                            label = "Game background data",
                            value = if (whitelisted) "Unrestricted" else "Not whitelisted",
                            applied = whitelisted
                        )
                    }
                }

                if (report.unavailable.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    NoticeBlock(
                        text = "Not available on this device:\n" +
                            report.unavailable.joinToString("\n") { "• $it" },
                        tint = Color(0xFFFFB300)
                    )
                }
            }
        }
    }
}

/** Small tinted panel used for an activation error or the list of refused optimizations. */
@Composable
private fun NoticeBlock(text: String, tint: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(tint.copy(alpha = 0.10f))
            .border(1.dp, tint.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(text = text, color = tint, fontSize = 12.sp, lineHeight = 18.sp)
    }
}
