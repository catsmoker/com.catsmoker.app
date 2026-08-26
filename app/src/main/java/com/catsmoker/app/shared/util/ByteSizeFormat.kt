package com.catsmoker.app.shared.util

import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow

/**
 * Formats a measured byte count for display — `1536` reads as `1.5 KB`.
 *
 * Every caller is reporting a number the device gave back (bytes freed, bytes found by the scanner),
 * so `0` is a legitimate result and returns `"0 B"` rather than being hidden.
 */
fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (log10(bytes.toDouble()) / log10(1024.0)).toInt()
    return String.format(Locale.US, "%.1f %s", bytes / 1024.0.pow(digitGroups.toDouble()), units[digitGroups])
}
