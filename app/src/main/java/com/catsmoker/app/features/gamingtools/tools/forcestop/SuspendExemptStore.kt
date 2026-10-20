package com.catsmoker.app.features.gamingtools.tools.forcestop

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The apps Gaming Mode's suspend sweep must leave running — the user's exception list.
 *
 * The sweep freezes every user app (plus the curated Google and OEM lists) while the mode is
 * active. That is the point of it, but it cannot know which of those apps the user still wants
 * alive: a chat, a music player, a video app. This store is the user's answer. Packages listed
 * here are subtracted from the sweep — and from the AppOps background restriction that rides it —
 * in `SuspendSweep.resolveTargets`, and are never recorded in `affected_pkgs` because they were
 * never frozen. Deactivation therefore has nothing to wake for them.
 *
 * Its own prefs file, like every feature store. The set here means "keep running", which must
 * never be confused with [SuspendListStore]'s "freeze these" (the two are kept mutually exclusive
 * by the UI, and the engine resolves a hand-edited overlap as kept) or Auto Force Stop's
 * keep-alive set, which answers a different question: what not to force-stop when the user
 * switches away. A kept app is not protected from either of those logs' own actions.
 */
@Singleton
class SuspendExemptStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The packages to keep running. Empty is valid and means "the sweep decides". */
    fun getExemptPackages(): Set<String> =
        prefs.getStringSet(KEY_EXEMPT, emptySet()) ?: emptySet()

    /** Adds [packageName], or removes it when already picked. Returns the set afterwards. */
    fun toggleExemptPackage(packageName: String): Set<String> {
        val current = getExemptPackages().toMutableSet()
        if (!current.add(packageName)) current.remove(packageName)
        prefs.edit { putStringSet(KEY_EXEMPT, current) }
        return current
    }

    fun removeExemptPackage(packageName: String): Set<String> {
        val current = getExemptPackages().toMutableSet()
        current.remove(packageName)
        prefs.edit { putStringSet(KEY_EXEMPT, current) }
        return current
    }

    private companion object {
        const val PREFS_NAME = "SuspendExemptPrefs"
        const val KEY_EXEMPT = "exempt_packages"
    }
}
