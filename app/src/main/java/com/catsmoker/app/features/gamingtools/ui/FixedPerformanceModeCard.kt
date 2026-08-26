package com.catsmoker.app.features.gamingtools.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.catsmoker.app.shared.ui.components.SectionCard

/**
 * The standalone Fixed Performance Mode switch, with an account of what it actually does.
 *
 * The card used to say only "Locks CPU/GPU frequencies to max for consistent FPS", which is both
 * vague and slightly wrong — the vendor's power HAL picks the operating point, and on most SoCs it is
 * a *sustainable* one rather than the boost ceiling. The explanations are now collapsed by default and
 * written in plain words, but they still say who chooses the clocks and what the switch cannot promise.
 *
 * The Android 11 requirement is checked here rather than assumed: on older builds `cmd power` has no
 * `set-fixed-performance-mode-enabled` sub-command at all, so the switch is disabled and says why
 * instead of flipping and doing nothing.
 */
@Composable
fun FixedPerformanceModeCard(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    SectionCard {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Red.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Speed,
                        null,
                        tint = Color.Red,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Fixed Performance Mode",
                        fontWeight = FontWeight.Bold,
                        color = if (supported) Color.White else Color.Gray,
                        fontSize = 15.sp
                    )
                    Text(
                        text = "Keeps your phone at one steady speed.",
                        color = Color.Gray,
                        fontSize = 12.sp
                    )
                }

                Switch(
                    checked = enabled && supported,
                    onCheckedChange = onToggle,
                    enabled = supported,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.Red,
                        checkedTrackColor = Color.Red.copy(alpha = 0.3f)
                    )
                )
            }

            if (!supported) {
                Spacer(modifier = Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFFE57373).copy(alpha = 0.12f))
                        .padding(12.dp)
                ) {
                    Text(
                        text = "Your phone is too old for this. Android ${Build.VERSION.RELEASE} " +
                            "does not have the setting — it arrived in Android 11.",
                        color = Color(0xFFE57373),
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            CollapsibleExplainer(
                title = "What is this?",
                lines = listOf(
                    "Normally your phone speeds up and slows down all the time depending on how busy " +
                        "it is. This asks it to stay at one steady speed instead.",
                    "That makes games feel smoother, because the speed stops jumping around. It does " +
                        "not make your phone faster overall — the steady speed is chosen by the " +
                        "company that made your phone's chip, and it is often a little below the " +
                        "fastest it can go.",
                    "Nothing else changes. Screen speed, touch and background apps are separate " +
                        "switches."
                )
            )

            Spacer(modifier = Modifier.height(10.dp))
            CollapsibleExplainer(
                title = "Should I turn it on?",
                accent = Color(0xFFFFB300),
                lines = listOf(
                    "Turn it on for a gaming session, not all day. It uses more battery and makes " +
                        "your phone warmer, and a hot phone slows itself down anyway.",
                    "Some phones ignore this setting completely. Android does not tell an app whether " +
                        "it worked, so this app can only say that your phone accepted the request — " +
                        "it will not claim more than that.",
                    "Gaming Mode already asks for this while it is on. This switch is for using it " +
                        "by itself."
                )
            )
        }
    }
}
