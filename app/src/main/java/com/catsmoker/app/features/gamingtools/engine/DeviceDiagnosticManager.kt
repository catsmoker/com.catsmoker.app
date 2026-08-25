package com.catsmoker.app.features.gamingtools.engine

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceDiagnosticManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /**
     * The highest refresh rate the panel actually supports.
     *
     * A display's `refreshRate` is whatever mode is *active* right now, and Android drops to 60 Hz
     * whenever content is static — so locking `peak_refresh_rate` to it would cap a 120 Hz panel at
     * 60. The supported-mode list is the only stable answer. It also has to come from
     * [DisplayManager]: `Context.getDisplay()` throws on an application context from API 30 up.
     */
    fun getMaxHardwareRefreshRate(): Float {
        val display = defaultDisplay() ?: return FALLBACK_HZ
        val modes = runCatching { display.supportedModes }.getOrNull()
        if (modes.isNullOrEmpty()) {
            return runCatching { display.refreshRate }.getOrNull()?.takeIf { it > 0f } ?: FALLBACK_HZ
        }

        // Prefer modes at the current resolution: some panels only reach their top rate in a
        // lower-resolution mode group, and pinning that would silently downscale the display.
        val current = runCatching { display.mode }.getOrNull()
        val sameResolution = modes.filter {
            current == null ||
                (it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight)
        }
        val candidates = sameResolution.ifEmpty { modes.toList() }
        return candidates.maxOf { it.refreshRate }.takeIf { it > 0f } ?: FALLBACK_HZ
    }

    private fun defaultDisplay(): Display? = runCatching {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
    }.getOrNull()

    fun isVivoOrIqoo(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()
        return manufacturer.contains("vivo") || manufacturer.contains("iqoo") ||
            brand.contains("vivo") || brand.contains("iqoo")
    }

    private companion object {
        /** Every Android device does at least 60 Hz, so this is a safe floor. */
        const val FALLBACK_HZ = 60f
    }
}
