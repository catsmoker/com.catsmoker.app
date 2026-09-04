package com.catsmoker.app.features.gamingtools.tools.crosshair

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where the user put the crosshair.
 *
 * Stored as an offset in pixels from the centre of the screen, not as an absolute position, because
 * the centre is the only anchor that survives the things this app itself changes. The Resolution
 * Changer rewrites `wm size` and `wm density` underneath the overlay, and the screen also rotates;
 * an absolute `x`/`y` saved at 1080x2400 puts the crosshair somewhere arbitrary — potentially
 * off-screen — once the panel reports 720x1600. An offset from centre keeps a crosshair the user
 * placed slightly below the middle slightly below the middle at any resolution.
 *
 * A zero offset therefore means "centred", which is both the default and what [clear] restores. That
 * makes the untouched case indistinguishable from an explicit re-centre on purpose: the two should
 * behave identically, and neither needs a stored value.
 *
 * Lives in `AppPrefs` alongside `selected_scope`, the crosshair's other persisted setting — the app
 * keeps eight unrelated preference files and this belongs with the one that already owns this feature.
 */
@Singleton
class CrosshairPositionStore @Inject constructor(
    @ApplicationContext context: Context
) {

    private val prefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)

    /** Horizontal offset from screen centre, in pixels. Positive is right. */
    val offsetX: Int get() = prefs.getInt(KEY_OFFSET_X, 0)

    /** Vertical offset from screen centre, in pixels. Positive is down. */
    val offsetY: Int get() = prefs.getInt(KEY_OFFSET_Y, 0)

    /** True when the crosshair is somewhere other than the centre, so the UI can offer a re-centre. */
    val isOffCentre: Boolean get() = offsetX != 0 || offsetY != 0

    fun save(offsetX: Int, offsetY: Int) {
        prefs.edit {
            putInt(KEY_OFFSET_X, offsetX)
            putInt(KEY_OFFSET_Y, offsetY)
        }
    }

    /** Back to centred. Removes the keys rather than writing zeroes, so the state is a clean default. */
    fun clear() {
        prefs.edit {
            remove(KEY_OFFSET_X)
            remove(KEY_OFFSET_Y)
        }
    }

    private companion object {
        const val KEY_OFFSET_X = "crosshair_offset_x"
        const val KEY_OFFSET_Y = "crosshair_offset_y"
    }
}
