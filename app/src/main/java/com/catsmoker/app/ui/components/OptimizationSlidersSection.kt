package com.catsmoker.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun OptimizationSlidersSection(
    isBoostingRam: Boolean,
    showRamResult: Boolean,
    isOptimizingNet: Boolean,
    showPingResult: Boolean,
    isResettingDefaults: Boolean,
    showResetResult: Boolean,
    onBoostRam: () -> Unit,
    onCheckPing: () -> Unit,
    onResetDefaults: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "OPTIMIZATION",
            style = MaterialTheme.typography.labelSmall,
            color = Color.Gray,
            modifier = Modifier.padding(bottom = 12.dp, start = 4.dp)
        )

        OptimizationButton(
            text = if (showRamResult) "MEMORY BOOSTED" else "BOOST MEMORY",
            icon = Icons.Default.Bolt,
            accentColor = MaterialTheme.colorScheme.primary,
            isBusy = isBoostingRam,
            onClick = onBoostRam
        )

        Spacer(modifier = Modifier.height(12.dp))

        OptimizationButton(
            text = if (showPingResult) "PING CHECKED" else "CHECK PING",
            icon = Icons.Default.SettingsInputAntenna,
            accentColor = Color(0xFF10B981),
            isBusy = isOptimizingNet,
            onClick = onCheckPing
        )

        Spacer(modifier = Modifier.height(12.dp))

        OptimizationButton(
            text = if (showResetResult) "RESET DONE" else "RESET DEVICE DEFAULTS",
            icon = Icons.Default.RestoreFromTrash,
            accentColor = Color(0xFFF59E0B),
            isBusy = isResettingDefaults,
            onClick = onResetDefaults
        )
    }
}

@Composable
fun OptimizationButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accentColor: Color,
    isBusy: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = !isBusy,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = accentColor.copy(alpha = 0.15f),
            contentColor = accentColor,
            disabledContainerColor = Color.White.copy(alpha = 0.05f)
        ),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.3f))
    ) {
        if (isBusy) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = accentColor,
                strokeWidth = 2.dp
            )
        } else {
            Icon(icon, null, modifier = Modifier.size(20.dp))
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(text, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
    }
}
