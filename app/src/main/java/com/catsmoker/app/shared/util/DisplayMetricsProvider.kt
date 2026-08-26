package com.catsmoker.app.shared.util

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The one place the app reads this device's real screen resolution and density.
 *
 * Spoof profiles and the Resolution Changer used to read the display through different APIs —
 * `Resources.displayMetrics` on one side, `WindowManager.maximumWindowMetrics` on the other — so the
 * two screens could quote different numbers for the same panel. Both now go through here, which
 * keeps them consistent and puts the version gates and fallbacks in a single place.
 *
 * The derived values ([Snapshot.smallestWidthDp], [Snapshot.isTablet], [densityForSmallestWidthDp])
 * are the reference project's own formulas, taken from `DevicePresetCatalog.isTablet` and
 * `SettingsManager.setSmallestWidth`, so a value computed here matches what the reference computes.
 *
 * Nothing here invents a number: when no API on the device answers, [current] returns a snapshot
 * marked [Source.UNAVAILABLE] with zeroed fields and [Snapshot.isValid] `false`, and callers are
 * expected to say so rather than show a zero.
 */
@Singleton
class DisplayMetricsProvider @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /**
     * Which real API produced a snapshot, so a screen or log can name its own source.
     *
     * @param label the API's own name. Kept exact because it is what makes a log or a bug report
     *   diagnosable — two of these disagree on devices with display cutouts, and knowing which one
     *   answered is the whole difference.
     * @param plainLabel the same fact in words a non-technical user can read. Shown on the card;
     *   [label] is never put in front of a user.
     */
    enum class Source(val label: String, val plainLabel: String) {
        /** API 30+. Full display area including system decorations — what `wm size` reports. */
        WINDOW_METRICS("WindowManager.maximumWindowMetrics", "your phone's own screen information"),

        /** Pre-API-30 equivalent of the above. */
        REAL_METRICS("Display.getRealMetrics", "your phone's own screen information"),

        /** The reference project's source. Excludes system decorations on some devices. */
        RESOURCES("Resources.displayMetrics", "your phone's display settings"),

        /**
         * `wm size` / `wm density`, which name the panel's own resolution even while an override is
         * in force. Only reachable through a privileged channel, so callers read it opportunistically.
         */
        SHELL_WM("wm size / wm density", "your phone's screen manager"),

        /** No API answered; the numbers in the snapshot are not usable. */
        UNAVAILABLE("unavailable", "nowhere — your phone would not say")
    }

    data class Snapshot(
        val widthPixels: Int,
        val heightPixels: Int,
        val densityDpi: Int,
        val source: Source
    ) {
        /** False when the platform gave us nothing usable — never render these numbers then. */
        val isValid: Boolean get() = widthPixels > 0 && heightPixels > 0 && densityDpi > 0

        /**
         * The shorter edge in dp — the `sw###dp` qualifier, and the figure `wm density` moves.
         *
         * Same expression as the reference's `DevicePresetCatalog.isTablet`.
         */
        val smallestWidthDp: Int
            get() = if (!isValid) 0
            else ((min(widthPixels, heightPixels) * DisplayMetrics.DENSITY_DEFAULT.toFloat()) / densityDpi)
                .roundToInt()

        /** The reference's tablet test: a shorter edge of 600dp or more. */
        val isTablet: Boolean get() = smallestWidthDp >= 600

        /** `1080x2400`, spelled the way `wm size` takes it. */
        val sizeLabel: String get() = if (isValid) "${widthPixels}x$heightPixels" else "unavailable"

        /** `1080x2400 · 420dpi`, for one line of UI. */
        val label: String get() = if (isValid) "$sizeLabel · ${densityDpi}dpi" else "unavailable"
    }

    /**
     * Reads the display now.
     *
     * Note this reports the resolution currently in effect, so it follows a `wm size` override
     * rather than always naming the panel's native resolution.
     */
    fun current(): Snapshot {
        val density = readDensityDpi()

        // Preferred: the documented full-display bounds, which include the system decorations that
        // Resources.displayMetrics leaves out on some devices. API 30 only — minSdk here is 27.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = runCatching {
                context.getSystemService(WindowManager::class.java)?.maximumWindowMetrics?.bounds
            }.getOrNull()
            if (bounds != null && bounds.width() > 0 && bounds.height() > 0 && density > 0) {
                return Snapshot(bounds.width(), bounds.height(), density, Source.WINDOW_METRICS)
            }
        }

        // Pre-30, and wherever the call above came back empty: the real (not app-window) metrics.
        val real = runCatching {
            @Suppress("DEPRECATION")
            val display = context.getSystemService(WindowManager::class.java)?.defaultDisplay
            DisplayMetrics().also { metrics ->
                @Suppress("DEPRECATION")
                display?.getRealMetrics(metrics)
            }
        }.getOrNull()
        if (real != null && real.widthPixels > 0 && real.heightPixels > 0) {
            val dpi = if (density > 0) density else real.densityDpi
            if (dpi > 0) return Snapshot(real.widthPixels, real.heightPixels, dpi, Source.REAL_METRICS)
        }

        // Last resort, and the reference project's own reading.
        val resources = context.resources.displayMetrics
        if (resources.widthPixels > 0 && resources.heightPixels > 0) {
            val dpi = if (density > 0) density else resources.densityDpi
            if (dpi > 0) {
                return Snapshot(resources.widthPixels, resources.heightPixels, dpi, Source.RESOURCES)
            }
        }

        return Snapshot(0, 0, 0, Source.UNAVAILABLE)
    }

    /**
     * Density that would make [targetSmallestWidthDp] the device's shorter edge in dp.
     *
     * Copied from the reference's `SettingsManager.setSmallestWidth`, including the 72..1000 clamp
     * `wm density` itself enforces.
     *
     * @return the density to write, or null when the target or the snapshot cannot support one.
     */
    fun densityForSmallestWidthDp(
        targetSmallestWidthDp: Int,
        snapshot: Snapshot = current()
    ): Int? {
        if (targetSmallestWidthDp <= 0 || !snapshot.isValid) return null
        val smallestPx = min(snapshot.widthPixels, snapshot.heightPixels).toFloat()
        return (smallestPx * DisplayMetrics.DENSITY_DEFAULT / targetSmallestWidthDp)
            .roundToInt()
            .coerceIn(MIN_DENSITY_DPI, MAX_DENSITY_DPI)
    }

    /**
     * Scales a resolution by [percent] and moves the density with it.
     *
     * Density is scaled by the same factor on purpose: that keeps the shorter edge at a constant dp
     * count, so app layouts stay the size the user is used to instead of everything growing as the
     * pixel count drops. Both edges are rounded to an even number of pixels, which is what display
     * pipelines expect of a surface size.
     *
     * @return the scaled target, or null when [percent] or [snapshot] cannot produce a usable one.
     */
    fun scaledBy(percent: Int, snapshot: Snapshot = current()): Snapshot? {
        if (!snapshot.isValid || percent <= 0 || percent > 100) return null
        val factor = percent / 100f
        val width = (snapshot.widthPixels * factor).roundToInt().roundToEven()
        val height = (snapshot.heightPixels * factor).roundToInt().roundToEven()
        val density = (snapshot.densityDpi * factor).roundToInt()
        if (width < MIN_DIMENSION_PX || height < MIN_DIMENSION_PX) return null
        if (density < MIN_DENSITY_DPI || density > MAX_DENSITY_DPI) return null
        return snapshot.copy(widthPixels = width, heightPixels = height, densityDpi = density)
    }

    /** Configuration is the authority for density: it is the value a `wm density` override lands in. */
    private fun readDensityDpi(): Int {
        val fromConfiguration = runCatching { context.resources.configuration.densityDpi }.getOrDefault(0)
        if (fromConfiguration > 0) return fromConfiguration
        return runCatching { context.resources.displayMetrics.densityDpi }.getOrDefault(0)
    }

    private fun Int.roundToEven(): Int = this - (this % 2)

    companion object {
        /** `wm density` refuses anything outside this range, so neither do we. */
        const val MIN_DENSITY_DPI = 72
        const val MAX_DENSITY_DPI = 1000

        /** Below this a surface is too small for the system UI to lay itself out. */
        const val MIN_DIMENSION_PX = 320
    }
}
