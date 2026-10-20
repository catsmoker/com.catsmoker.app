package com.catsmoker.app.features.gamingtools.tools.interventions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `device_config game_overlay` entry format, pinned against the reference script.
 *
 * `reference/file-engineering/GenshinConfig-main/script/genshin.sh` puts this exact string for
 * Genshin Impact, and its reverter `genshun.sh` deletes it. The format is not ours to invent: a
 * missing mode row or a mis-placed `:` makes the entry unparseable, and `device_config` accepts it
 * silently — the write "succeeds" and the intervention does nothing. That failure mode is why the
 * format is asserted here character-for-character rather than eyeballed.
 */
class GameInterventionsTest {

    @Test
    fun matchesTheReferenceScriptFormat() {
        // genshin.sh, with 120 replaced by the panel's peak: the one parameter this app varies.
        assertEquals(
            "mode=2,opengles=0,downscaleFactor=false,fps=120:mode=3,opengles=0,downscaleFactor=false,fps=120",
            GameInterventions.overlayValue(120)
        )
    }

    @Test
    fun carriesBothPerformanceAndStandardRows() {
        // One row without the other loses the cap the moment the system leaves performance mode.
        val value = GameInterventions.overlayValue(90)
        assertTrue(value.contains("mode=2,"))
        assertTrue(value.contains(":mode=3,"))
    }

    @Test
    fun fpsAppearsOnlyAsTheFrameRateBothRowsCarryIt() {
        val value = GameInterventions.overlayValue(144)
        // Exactly two occurrences — one per mode row — and no accidental truncation.
        assertEquals(2, Regex("fps=144").findAll(value).count())
        assertFalse(value.endsWith(":"))
    }

    @Test
    fun downscaleOffKeepsTheReferenceFormatByteIdentical() {
        // Opt-in only: a null downscale must not alter a single character of the pinned format.
        assertEquals(
            GameInterventions.overlayValue(120),
            GameInterventions.overlayValue(120, null)
        )
    }

    @Test
    fun downscaleFactorAppearsInBothRows() {
        val value = GameInterventions.overlayValue(120, 0.85f)
        assertEquals(2, Regex("downscaleFactor=0.85").findAll(value).count())
        assertTrue(value.contains("fps=120"))
    }

    @Test
    fun downscaleTextMatchesBothCommandParsers() {
        // The same text feeds `game_overlay` and `cmd game --downscale`; one spelling
        // for both means the fallback can never describe a different scale than the table.
        assertEquals("0.85", GameInterventions.downscaleText(0.85f))
        assertEquals("0.8", GameInterventions.downscaleText(0.8f))
        assertEquals("0.7", GameInterventions.downscaleText(0.7f))
    }

    @Test
    fun sanitizeDownscaleAcceptsOnlyTheOfficialRange() {
        // AOSP `cmd game set --downscale` accepts 0.3..0.9; anything else means "no downscale",
        // never a silently substituted scale the user did not pick.
        assertEquals(0.9f, GameInterventions.sanitizeDownscale(0.9f))
        assertEquals(0.7f, GameInterventions.sanitizeDownscale(0.7f))
        assertNull(GameInterventions.sanitizeDownscale(null))
        assertNull(GameInterventions.sanitizeDownscale(Float.NaN))
        assertNull(GameInterventions.sanitizeDownscale(1.0f))
        assertNull(GameInterventions.sanitizeDownscale(0.2f))
        assertNull(GameInterventions.sanitizeDownscale(Float.POSITIVE_INFINITY))
    }
}
