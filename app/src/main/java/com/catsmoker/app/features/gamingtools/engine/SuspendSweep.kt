package com.catsmoker.app.features.gamingtools.engine

import com.catsmoker.app.features.gamingtools.tools.forcestop.SuspendListStore

/**
 * The rule that turns the three suspend inputs into the packages one activation freezes:
 * the automatic sweep's targets, the user's extra picks, and the user's keep list.
 *
 * Kept pure so a JVM test pins the precedence: keep wins. A package the user put on the keep
 * list is subtracted from both halves — the seven sources of the sweep and the extra picks —
 * because freezing one the user marked as kept is the exact complaint the keep list answers.
 * The UI keeps the two list stores mutually exclusive, so the overlap this resolves is only
 * reachable from hand-edited prefs; it must still never freeze a kept app.
 *
 * Sanitization (trim, drop blanks, deduplicate, stable sort) matches [SuspendListStore.filterTargets]
 * so the suspend loop gets one clean, deterministic target list.
 */
object SuspendSweep {

    fun resolveTargets(
        sweepTargets: Collection<String>,
        extraPicks: Set<String>,
        exempt: Set<String>,
        activeGamePkg: String?,
        selfPkg: String
    ): List<String> = (sweepTargets + SuspendListStore.filterTargets(extraPicks, activeGamePkg, selfPkg))
        .map { it.trim() }
        .filter { it.isNotEmpty() && it !in exempt }
        .distinct()
        .sorted()
}
