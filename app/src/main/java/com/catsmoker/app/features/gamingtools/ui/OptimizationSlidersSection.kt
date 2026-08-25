package com.catsmoker.app.features.gamingtools.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.catsmoker.app.shared.ui.components.SectionCard

@Composable
fun OptimizationSlidersSection(
    isBoostingRam: Boolean,
    ramResult: String?,
    isResettingDefaults: Boolean,
    resetResult: String?,
    onBoostRam: () -> Unit,
    onResetDefaults: () -> Unit
) {
    SectionCard {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "SYSTEM TWEAKS",
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray,
                letterSpacing = 1.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onBoostRam,
                    modifier = Modifier.weight(1f),
                    enabled = !isBoostingRam,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (isBoostingRam) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Boost RAM")
                    }
                }

                OutlinedButton(
                    onClick = onResetDefaults,
                    modifier = Modifier.weight(1f),
                    enabled = !isResettingDefaults,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (isResettingDefaults) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Reset OS Defaults")
                    }
                }
            }

            // The toast is transient; keeping the measured result on the card lets the user read it.
            val lastResult = ramResult ?: resetResult
            if (lastResult != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = lastResult,
                    color = Color.LightGray,
                    fontSize = 12.sp
                )
            }
        }
    }
}
