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

/**
 * The Gaming Mode card.
 *
 * @param canActivate whether root or Shizuku is available. Every optimization Gaming Mode applies is a
 *   privileged command, so without one of those channels the whole feature can do nothing at all. The
 *   power button is disabled and the reason is stated on the card rather than letting the user press a
 *   live button and watch it fail. It goes live on its own as soon as either channel appears, because
 *   the state comes from the privilege flow the screen re-reads on every sync.
 */
@Composable
fun GamingModeCard(
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
                            is GamingModeState.Idle ->
                                if (canActivate) "Optimizations Ready" else "Locked"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        color = when {
                            gamingState is GamingModeState.Error -> MaterialTheme.colorScheme.error
                            // Item 8: a feature that cannot run must look like it cannot run.
                            !canActivate -> Color.Gray
                            else -> Color.White
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
                        tint = when {
                            isActive -> MaterialTheme.colorScheme.primary
                            !canActivate -> Color.Gray
                            else -> Color.White
                        },
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            // Item 2: the requirement is stated on the card, above everything else, so the greyed-out
            // button is never a mystery. It disappears by itself the moment either channel appears.
            if (!canActivate) {
                Spacer(modifier = Modifier.height(16.dp))
                NoticeBlock(
                    text = "Needs root or Shizuku.\nEverything Gaming Mode does has to be done by " +
                        "your phone's system, and Android only lets an app ask for that through root " +
                        "or Shizuku. Shizuku is a free helper app that lends this app those powers " +
                        "without root. It turns on by itself once one of them is available.",
                    tint = Color(0xFFFFB300)
                )
                Spacer(modifier = Modifier.height(8.dp))
                CollapsibleExplainer(
                    title = "What does Gaming Mode do?",
                    lines = listOf(
                        "It gets your phone ready to play in one tap: keeps the screen at its fastest, " +
                            "pauses other apps, silences notifications, and stops your phone slowing " +
                            "itself down.",
                        "It writes down how your phone was set up before it changes anything, so " +
                            "turning it off puts everything back exactly as it was.",
                        "Anything your phone refuses is listed on this card as refused. Nothing is " +
                            "claimed unless your phone confirmed it."
                    )
                )
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
                    GamingModeResultRow(
                        label = "PowerHAL",
                        value = if (report.fixedPerformance) "Fixed performance mode" else "Not applied",
                        applied = report.fixedPerformance
                    )
                    GamingModeResultRow(
                        label = "Display",
                        value = report.lockedRefreshHz?.let { "Locked $it Hz" } ?: "Rate not locked",
                        applied = report.lockedRefreshHz != null
                    )
                    GamingModeResultRow(
                        label = "Touch response",
                        value = if (report.touchResponseBoost) "Boosted" else "Not supported",
                        applied = report.touchResponseBoost
                    )
                    GamingModeResultRow(
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
                    GamingModeResultRow(
                        label = "Do Not Disturb",
                        value = if (report.dndEngaged) "Engaged" else "Off",
                        applied = report.dndEngaged
                    )
                    report.networkWhitelisted?.let { whitelisted ->
                        GamingModeResultRow(
                            label = "Game background data",
                            value = if (whitelisted) "Unrestricted" else "Not whitelisted",
                            applied = whitelisted
                        )
                    }
                    // Null means no game was targeted or the device predates game interventions
                    // (Android 12) — not applicable, so the row is omitted rather than shown as
                    // refused, exactly like the background-data row above.
                    report.gameInterventionApplied?.let { applied ->
                        GamingModeResultRow(
                            label = "Game frame cap",
                            value = if (applied) "Raised to panel peak" else "Not raised",
                            applied = applied
                        )
                    }
                    // Reported from the read-back of always_finish_activities, so "Applied" means the
                    // setting holds 1 right now rather than that the command was sent.
                    GamingModeResultRow(
                        label = "Discard activities",
                        value = if (report.discardActivities) "On" else "Not applied",
                        applied = report.discardActivities
                    )
                    GamingModeResultRow(
                        label = "Process limit",
                        value = if (report.processLimit) "1 cached process" else "Not applied",
                        applied = report.processLimit
                    )
                    GamingModeResultRow(
                        label = "Other apps' background data",
                        value = when (report.backgroundDataRestricted) {
                            // null means the user's own switch was already on, so this run left it
                            // alone — saying "not applied" would misreport a deliberate decision.
                            null -> "Left as you set it"
                            true -> "Blocked on metered"
                            false -> "Not blocked"
                        },
                        applied = report.backgroundDataRestricted != false
                    )
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
