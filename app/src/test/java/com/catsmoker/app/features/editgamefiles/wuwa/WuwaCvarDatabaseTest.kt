package com.catsmoker.app.features.editgamefiles.wuwa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the CVar-database hygiene pass against
 * `reference/gamingtools/WuWa-Config-Android-main/.../config/CvarDatabase.kt`
 * (`optimizeIniTextImpl` + `extractCvarValues`, read in full before this test was written):
 * unknown cvars are commented out (never deleted), monitored cvars matching the game default
 * are flagged redundant, `-CVars=` removal directives pass through verbatim, and
 * section-specific INI keys (`Paths=`, …) are never mistaken for cvars.
 */
class WuwaCvarDatabaseTest {

    private val all = setOf("r.fog", "r.viewdistancescale", "r.shadow.maxresolution")
    private val monitored = setOf("r.fog")
    private val defaults = mapOf("r.fog" to "1")

    @Test
    fun cvarDbGateRequiresALoadedDatabase() {
        assertFalse(WuwaCvarDbGate.canEnable(isLoaded = false))
        assertTrue(WuwaCvarDbGate.canEnable(isLoaded = true))
        assertTrue(WuwaCvarDbGate.effectiveDb(requested = true, isLoaded = true))
        assertFalse(WuwaCvarDbGate.effectiveDb(requested = true, isLoaded = false))
        assertFalse(WuwaCvarDbGate.effectiveDb(requested = false, isLoaded = true))
        assertFalse(WuwaCvarDbGate.effectiveDb(requested = false, isLoaded = false))
    }

    @Test
    fun unknownCvarIsCommentedNotDeleted() {
        val out = optimizeWuWaIniText("r.MadeUp.Futurism=42", all, monitored, defaults)
        assertTrue(out.contains(";r.MadeUp.Futurism=42 ; [CvarDB] unknown CVar"))
    }

    @Test
    fun monitoredDefaultMatchIsFlaggedRedundant() {
        val out = optimizeWuWaIniText("r.Fog=1", all, monitored, defaults)
        assertTrue(out.contains("[CvarDB] redundant"))
        assertTrue(out.trimStart().startsWith(";"))
    }

    @Test
    fun nonDefaultAndUnmonitoredLinesPassThrough() {
        assertEquals("r.Fog=0", optimizeWuWaIniText("r.Fog=0", all, monitored, defaults).trim())
        assertEquals(
            "r.ViewDistanceScale=1.5",
            optimizeWuWaIniText("r.ViewDistanceScale=1.5", all, monitored, defaults).trim()
        )
    }

    @Test
    fun removalDirectivesPassThroughVerbatim() {
        val line = "-CVars=r.Fog=1"
        assertEquals(line, optimizeWuWaIniText(line, all, monitored, defaults).trim())
    }

    @Test
    fun sectionKeysAreNeverCvars() {
        // `Paths=` lives in [Core.System], not in any cvar namespace: commenting it out would
        // break content resolution, so the prefix guard keeps it.
        val text = "[Core.System]\nPaths=../../../Engine/Content"
        val out = optimizeWuWaIniText(text, all, monitored, defaults)
        assertFalse(out.contains("[CvarDB]"))
        assertTrue(out.contains("Paths=../../../Engine/Content"))
    }

    @Test
    fun cvarDirectivePrefixAndInlineCommentsAreHandled() {
        // `+CVars=` is stripped before the key split; a trailing `;` comment is stripped
        // before the default comparison.
        val out = optimizeWuWaIniText("+CVars=r.Fog=1 ; cinematic override", all, monitored, defaults)
        assertTrue(out.contains("[CvarDB] redundant"))
    }

    @Test
    fun structureLinesPassThrough() {
        val text = "[SystemSettings]\n; a comment\n\n# another\n// yet another"
        assertEquals(text, optimizeWuWaIniText(text, all, monitored, defaults))
    }

    @Test
    fun extractCvarValuesSkipsStructureAndDirectives() {
        val text = """
            [SystemSettings]
            ; comment
            +CVars=r.Fog=0
            r.ViewDistanceScale=1.5
            Paths=../../../Engine/Content
            -CVars=r.Fog=1
        """.trimIndent()
        val values = extractWuWaCvarValues(text)
        // Last occurrence wins, exactly like the reference's map put.
        assertEquals("1", values["r.Fog"])
        assertEquals("1.5", values["r.ViewDistanceScale"])
        assertEquals("../../../Engine/Content", values["Paths"])
    }
}
