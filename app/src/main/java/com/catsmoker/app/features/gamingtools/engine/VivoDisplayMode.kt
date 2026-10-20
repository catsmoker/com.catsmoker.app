package com.catsmoker.app.features.gamingtools.engine

/**
 * vivo/iQOO's own display-mode switch: which enumerated `Display.Mode` to pin, and the two
 * settings that pin it.
 *
 * Origin, with one deliberate divergence: `reference/gamingtools/booster/.../gaming/
 * EsportsOptimizationEngine.kt` (read in full before this file was written) locks the vivo
 * panel by writing `settings put secure user_preferred_display_mode_id 4` and
 * `settings put global vivo_screen_refresh_rate_mode 1`. The **4 is its author's hardcoded
 * mode id for one 1080x2400@120 panel**, and mode ids are display-driver facts, not a
 * vocabulary — the same id on another panel is a different mode, or no mode at all. This
 * port therefore only ever writes an id the *device's own* `Display.getSupportedModes()`
 * reported, at the rate the app already measured as the panel's peak ([RefreshRateController.peakHz]).
 * A panel with no mode at that rate writes nothing and says so.
 *
 * The reference's second write (`vivo_screen_refresh_rate_mode = 1`) is a vendor mode
 * switch, not a rate: OriginOS honours its own switch over the standard
 * `min_refresh_rate`/`peak_refresh_rate` keys on some builds, which is why it is written
 * beside them. What `1` means on any given OriginOS build is the vendor's business; the
 * app records whatever the device held before activation and puts it back on the way out.
 *
 * Note the reference also writes this mode id unconditionally and does **not** enumerate
 * modes; its own revert writes `-1` for an absent prior value, while this app's snapshot
 * restores "the key was not set" as a delete — the platform's own "no preference" state.
 */
object VivoDisplayMode {

    /** vivo's secure setting naming the preferred hardware mode. */
    const val KEY_PREFERRED_DISPLAY_MODE_ID = "user_preferred_display_mode_id"

    /** vivo's global refresh-mode switch, written beside the standard rate keys. */
    const val KEY_SCREEN_REFRESH_RATE_MODE = OemPackageResolver.KEY_VIVO_SCREEN_REFRESH_RATE_MODE

    /**
     * The value the reference writes to [KEY_SCREEN_REFRESH_RATE_MODE] during its lock,
     * reused verbatim from [OemPackageResolver.VIVO_REFRESH_RATE_MODE_VALUE] so one constant
     * describes the write and the KDoc that documents it.
     */
    const val REFRESH_RATE_MODE_VALUE = OemPackageResolver.VIVO_REFRESH_RATE_MODE_VALUE

    /**
     * One enumerated display mode, reduced to the facts this decision needs. `Display.Mode`
     * itself is an Android class, so the controller converts; the choice stays pure and
     * unit-testable without a device.
     */
    data class Mode(
        val id: Int,
        val width: Int,
        val height: Int,
        val refreshRateHz: Float
    )

    /**
     * The mode to pin for [peakHz], or null when doing so would change nothing or is not
     * possible:
     * - no enumerated mode runs at [peakHz] (within [toleranceHz]) — the panel has no such
     *   mode, so no id is invented;
     * - the mode already in effect *is* that mode — there is nothing to write, and writing
     *   the same id back would only cost two settings writes.
     *
     * Among the modes at the peak rate the largest panel wins (the highest-resolution mode
     * at that rate is the one a user means by "the best mode"), ties broken by the lowest
     * mode id so the choice is stable across calls and devices.
     */
    fun bestModeId(
        modes: List<Mode>,
        peakHz: Float,
        currentModeId: Int?,
        toleranceHz: Float = RefreshRateController.HZ_TOLERANCE
    ): Int? {
        if (peakHz <= 0f) return null
        val atPeak = modes.filter { kotlin.math.abs(it.refreshRateHz - peakHz) <= toleranceHz }
        val chosen = atPeak.maxWithOrNull(
            compareBy({ it.width.toLong() * it.height }, { it.refreshRateHz }, { -it.id })
        ) ?: return null
        return if (chosen.id == currentModeId) null else chosen.id
    }
}
