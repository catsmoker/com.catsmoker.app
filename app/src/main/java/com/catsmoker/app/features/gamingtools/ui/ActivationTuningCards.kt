package com.catsmoker.app.features.gamingtools.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.catsmoker.app.R
import com.catsmoker.app.features.gamingtools.engine.PointerSpeed
import com.catsmoker.app.features.gamingtools.tools.interventions.GameInterventions
import com.catsmoker.app.shared.ui.components.ChipFlowRow
import java.util.Locale

/**
 * Next-activation tuning rows, shown as labeled subsections of the Resolution card.
 *
 * Render scale lowers the game's *internal* render resolution (`game_overlay`
 * downscaleFactor); touch speed only changes the pointer speed
 * (`Settings.System.pointer_speed`). Same state, same callbacks and same lock rule
 * as when they lived in [GamingModeCard]: the choice waits for the next activation
 * and never applies mid-session, so the chips lock while Gaming Mode is active or
 * busy, or without root/Shizuku.
 */
/**
 * Render-scale picker for the next Gaming Mode activation: full resolution plus the official
 * downscale steps (0.9 near-lossless down to the 0.7 floor the platform docs recommend).
 *
 * A stored scale that is not one of the chips is shown as it is rather than snapped — the
 * chips would otherwise misreport what the next activation will write.
 */
@Composable
internal fun DownscaleRow(
    current: Float?,
    enabled: Boolean,
    onSelect: (Float?) -> Unit
) {
    Column {
        Text(
            text = stringResource(R.string.gt_downscale_title),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 1.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.gt_downscale_sub),
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        if (current != null && GameInterventions.DOWNSCALE_CHOICES.none { kotlin.math.abs(it - current) < 0.005f }) {
            Text(formatDownscale(current), fontSize = 10.sp, color = Color(0xFFFFB74D))
        }
        // Seven chips wrap onto two lines on a phone instead of overflowing the row.
        ChipFlowRow {
            FilterChip(
                selected = current == null,
                onClick = { onSelect(null) },
                enabled = enabled,
                label = { Text(stringResource(R.string.gt_downscale_off), fontSize = 11.sp) }
            )
            GameInterventions.DOWNSCALE_CHOICES.forEach { value ->
                FilterChip(
                    selected = current != null && kotlin.math.abs(current - value) < 0.005f,
                    onClick = { onSelect(value) },
                    enabled = enabled,
                    label = { Text(formatDownscale(value), fontSize = 11.sp) }
                )
            }
        }
    }
}

/** `0.85x` — the same spelling the intervention entry carries. */
internal fun formatDownscale(value: Float): String =
    String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.') + "x"

/** `+2`, `-3` — the sign always shows, so slower and faster never look alike. */
internal fun formatPointerSpeed(speed: Int): String =
    if (speed > 0) "+$speed" else "$speed"

/**
 * Touch-speed picker for the next Gaming Mode activation: stock (untouched) plus symmetric
 * steps each way. Stock is the default because "don't touch it" already is stock.
 */
@Composable
internal fun PointerSpeedRow(
    current: Int?,
    enabled: Boolean,
    onSelect: (Int?) -> Unit
) {
    Column {
        Text(
            text = stringResource(R.string.gt_pointer_title),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 1.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.gt_pointer_sub),
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        // A stored speed outside the offered steps is shown as it is rather than snapped —
        // the chips would otherwise misreport what the next activation writes.
        if (current != null && !PointerSpeed.CHOICES.contains(current)) {
            Text(formatPointerSpeed(current), fontSize = 10.sp, color = Color(0xFFFFB74D))
        }
        // Seven chips wrap onto two lines on a phone instead of overflowing the row.
        ChipFlowRow {
            FilterChip(
                selected = current == null,
                onClick = { onSelect(null) },
                enabled = enabled,
                label = { Text(stringResource(R.string.gt_pointer_stock), fontSize = 11.sp) }
            )
            PointerSpeed.CHOICES.forEach { value ->
                FilterChip(
                    selected = current == value,
                    onClick = { onSelect(value) },
                    enabled = enabled,
                    label = { Text(formatPointerSpeed(value), fontSize = 11.sp) }
                )
            }
        }
    }
}
