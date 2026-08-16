package com.catsmoker.app.shared.util

import androidx.compose.ui.graphics.Color

object LogUtils {
    fun getLogColor(line: String): Color {
        val upperLine = line.uppercase()
        return when {
            // Logcat tags
            line.contains(" E/") || upperLine.contains("ERROR") || upperLine.contains("FAILED") -> Color(0xFFFF5252)
            line.contains(" W/") || upperLine.contains("WARN") || upperLine.contains("WARNING") -> Color(0xFFFFD740)
            line.contains(" I/") || upperLine.contains("INFO") -> Color(0xFF40C4FF)
            line.contains(" D/") || upperLine.contains("DEBUG") -> Color(0xFFB0BEC5)
            upperLine.contains("SUCCESS") || upperLine.contains("COMPLETE") -> Color(0xFF22C55E)
            upperLine.contains("STARTING") -> Color(0xFFE1F5FE)
            else -> Color.White.copy(alpha = 0.7f)
        }
    }
}
