package com.catsmoker.app.features.gamingtools.tools.overlay

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where the user put the performance readout.
 *
 * Absolute pixels from the screen's top-left corner, matching the window's own
 * `Gravity.TOP or Gravity.START` anchor — not the centre-relative offset the crosshair uses. The
 * crosshair is symmetric about its window and anchored to the middle of the screen; this panel is a
 * corner-parked readout whose historic default is (100, 100), and a corner anchor preserves that
 * pixel meaning. The service clamps the value against the live display on read, so a panel shrunk
 * by the app's own Resolution Changer cannot leave the readout off-screen.
 *
 * A missing key answers the default, which doubles as "not moved": the untouched case and an
 * explicit reset behave identically, so neither needs a stored value.
 *
 * Lives in `overlay_prefs` alongside `perf_overlay_was_running`, the file this feature already owns.
 */
@Singleton
class PerformanceOverlayPositionStore @Inject constructor(
    @ApplicationContext context: Context
) {

    private val prefs = context.getSharedPreferences(
        PerformanceOverlayService.PREFS_NAME,
        Context.MODE_PRIVATE
    )

    /** Absolute x of the window from the screen's left edge, in pixels. */
    val x: Int get() = prefs.getInt(KEY_X, DEFAULT_X)

    /** Absolute y of the window from the screen's top edge, in pixels. */
    val y: Int get() = prefs.getInt(KEY_Y, DEFAULT_Y)

    /** True when the readout sits somewhere other than the default corner, so a reset is worth offering. */
    val movedFromDefault: Boolean get() = x != DEFAULT_X || y != DEFAULT_Y

    fun save(x: Int, y: Int) {
        prefs.edit {
            putInt(KEY_X, x)
            putInt(KEY_Y, y)
        }
    }

    /** Back to the default corner. Removes the keys rather than writing the defaults back. */
    fun clear() {
        prefs.edit {
            remove(KEY_X)
            remove(KEY_Y)
        }
    }

    companion object {
        /** The historic fixed position, kept as the default so existing users see no change. */
        const val DEFAULT_X = 100
        const val DEFAULT_Y = 100

        private const val KEY_X = "perf_overlay_x"
        private const val KEY_Y = "perf_overlay_y"
    }
}
