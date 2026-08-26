package com.catsmoker.app.features.gamingtools.tools.forcestop

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The apps Auto Force Stop must leave alone.
 *
 * This list is a *keep-alive* list, and it used to be the reverse — the apps to close. The old
 * meaning made the feature's own name wrong: with two apps ticked it closed those two and left
 * everything else running. Now every app you switch away from is closed except the ones ticked here,
 * which is what "Auto Force Stop" says on the card.
 *
 * The stored key changed with the meaning (`keep_alive_packages`, not `selected_packages`), so an
 * install that already had apps ticked under the old semantics starts from an empty keep list rather
 * than silently reinterpreting the old set as protected — the reading would have been wrong and the
 * user was never asked.
 */
@Singleton
class KeepAliveStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences("AutoForceStopPrefs", Context.MODE_PRIVATE)

    /** The packages to keep running. Empty is valid and means "close every app I leave". */
    fun getKeptPackages(): Set<String> =
        prefs.getStringSet(KEY_KEEP_ALIVE, emptySet()) ?: emptySet()

    /** @return the keep list after the toggle, so the caller does not have to re-read it. */
    fun toggleKeptPackage(packageName: String): Set<String> {
        val current = getKeptPackages().toMutableSet()
        if (!current.add(packageName)) current.remove(packageName)
        prefs.edit { putStringSet(KEY_KEEP_ALIVE, current) }
        return current
    }

    private companion object {
        const val KEY_KEEP_ALIVE = "keep_alive_packages"
    }
}
