package com.catsmoker.app.features.gamingtools.engine

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The panel's refresh rates, read from the display service.
 *
 * The counterpart to `shared/util/DisplayMetricsProvider`, which owns resolution and density. The two
 * questions asked here want deliberately different answers — [getMaxHardwareRefreshRate] ignores the
 * active display mode, [getCurrentRefreshRate] wants exactly it — so both live behind one class that
 * reads the display the same way.
 */
@Singleton
class DisplayRefreshRateProvider @Inject constructor(
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

    /**
     * The rate the panel is running at **right now**, read from the active display mode.
     *
     * This is the counterpart to [getMaxHardwareRefreshRate]: where that one deliberately ignores the
     * active mode, this one wants it. Android lowers the rate when content is static and raises it for
     * animation, so this value legitimately changes between two reads seconds apart — which is exactly
     * what makes it worth showing.
     *
     * `Display.getMode()` exists from API 23 and reports the mode the display service has actually
     * committed, so it is preferred over `getRefreshRate()`, which some builds keep at the panel's
     * nominal rate.
     *
     * @return the live rate in Hz, or null when the display could not be read — never a stand-in
     *   number, because a plausible 60 would be indistinguishable from a real reading.
     */
    fun getCurrentRefreshRate(): Float? {
        val display = defaultDisplay() ?: return null
        runCatching { display.mode?.refreshRate }.getOrNull()?.takeIf { it > 0f }?.let { return it }
        return runCatching { display.refreshRate }.getOrNull()?.takeIf { it > 0f }
    }

    private fun defaultDisplay(): Display? = runCatching {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
    }.getOrNull()

    private companion object {
        /** Every Android device does at least 60 Hz, so this is a safe floor. */
        const val FALLBACK_HZ = 60f
    }
}
