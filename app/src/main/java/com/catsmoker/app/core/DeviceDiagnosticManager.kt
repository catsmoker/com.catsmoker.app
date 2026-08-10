package com.catsmoker.app.core

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display

class DeviceDiagnosticManager(private val context: Context) {
    fun isVivoOrIqoo(): Boolean {
        val manufacturer = Build.MANUFACTURER?.lowercase() ?: ""
        val brand = Build.BRAND?.lowercase() ?: ""
        return manufacturer.contains("vivo") || manufacturer.contains("iqoo") ||
                brand.contains("vivo") || brand.contains("iqoo")
    }

    fun getDeviceModelInfo(): String {
        return "${Build.MANUFACTURER.uppercase()} ${Build.MODEL} (${Build.BRAND})"
    }

    fun getMaxHardwareRefreshRate(): Float {
        return try {
            val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
            var maxHz = 60.0f
            display?.supportedModes?.forEach { mode ->
                if (mode.refreshRate > maxHz) {
                    maxHz = mode.refreshRate
                }
            }
            maxHz
        } catch (e: Exception) {
            60f
        }
    }
}
