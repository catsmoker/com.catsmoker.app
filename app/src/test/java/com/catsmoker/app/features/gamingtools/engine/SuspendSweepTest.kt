package com.catsmoker.app.features.gamingtools.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour lock for the sweep's target resolution — the one rule that turns the automatic sweep
 * plus the user's two suspend lists into the set an activation freezes.
 *
 * The precedence pinned here is deliberate: a package on the keep list is never frozen, even when
 * it also sits on the Extra suspended apps list. The UI keeps the two lists mutually exclusive, so
 * the overlap is only reachable from hand-edited prefs — and in that case "kept" wins, because
 * leaving an app running is the safe direction and freezing one the user marked as kept is exactly
 * the complaint the keep list exists to answer.
 */
class SuspendSweepTest {

    private fun resolve(
        sweepTargets: Collection<String> = emptyList(),
        extraPicks: Set<String> = emptySet(),
        exempt: Set<String> = emptySet(),
        activeGamePkg: String? = null
    ) = SuspendSweep.resolveTargets(
        sweepTargets = sweepTargets,
        extraPicks = extraPicks,
        exempt = exempt,
        activeGamePkg = activeGamePkg,
        selfPkg = SELF
    )

    @Test
    fun sweepTargetsPassThroughCleanDeduplicatedAndSorted() {
        assertEquals(
            listOf("com.example.a", "com.example.b"),
            resolve(sweepTargets = listOf("com.example.b", " com.example.a ", "com.example.a", ""))
        )
    }

    @Test
    fun extraPicksJoinTheAutomaticSweep() {
        assertEquals(
            listOf("com.example.a", "com.example.b", "com.example.c"),
            resolve(sweepTargets = listOf("com.example.a"), extraPicks = setOf("com.example.c", "com.example.b"))
        )
    }

    @Test
    fun keptPackagesAreDroppedFromBothHalvesAndBeatExtraPicks() {
        assertEquals(
            listOf("com.example.a"),
            resolve(
                sweepTargets = listOf("com.example.a", "com.example.b"),
                extraPicks = setOf("com.example.c"),
                exempt = setOf("com.example.b", "com.example.c")
            )
        )
    }

    @Test
    fun keptEntriesWithBlanksAndDuplicatesResolveCleanly() {
        assertEquals(
            listOf("com.example.a"),
            resolve(
                sweepTargets = listOf("com.example.a", "com.example.b"),
                exempt = setOf("com.example.b", "", "  ", "com.example.b")
            )
        )
    }

    @Test
    fun activeGameAndSelfStayOutEvenWhenExtraPicked() {
        assertTrue(
            resolve(
                extraPicks = setOf("com.game.played", SELF),
                activeGamePkg = "com.game.played"
            ).isEmpty()
        )
    }

    @Test
    fun nothingAnywhereStaysEmpty() {
        assertTrue(resolve().isEmpty())
    }

    private companion object {
        const val SELF = "com.catsmoker.app"
    }
}
