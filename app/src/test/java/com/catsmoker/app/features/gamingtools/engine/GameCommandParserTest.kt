package com.catsmoker.app.features.gamingtools.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `cmd game` output shapes, captured verbatim from a POCO X3 NFC (surya) on Android 13
 * on 2026-10-19 while auditing TMPAD. The GameManager CLI is the device's own
 * interface, so the parser is tested against its real words, not invented ones.
 */
class GameCommandParserTest {

    @Test
    fun parsesCurrentAndAvailableModes() {
        val modes = GameCommandParser.parseModes(
            "com.catsmoker.fpslab current mode: standard, available game modes: [standard,custom]"
        )
        assertEquals("standard", modes?.current)
        assertEquals(listOf("standard", "custom"), modes?.available)
        assertFalse(modes!!.offers("performance"))
    }

    @Test
    fun parsesPerformanceWhenOffered() {
        val modes = GameCommandParser.parseModes(
            "com.example.game current mode: performance, available game modes: [standard,performance,battery,custom]"
        )
        assertEquals("performance", modes?.current)
        assertTrue(modes!!.offers("performance"))
    }

    @Test
    fun unparseableModesStayNull() {
        assertNull(GameCommandParser.parseModes(""))
        assertNull(GameCommandParser.parseModes("No such package"))
        assertNull(GameCommandParser.parseModes("current mode: standard")) // no available list
    }

    @Test
    fun parsesCustomInterventionFromListConfigs() {
        // Verbatim from `cmd game set --downscale 0.9 --fps 120` on the same device.
        val state = GameCommandParser.parseIntervention(
            "com.catsmoker.fpslab interventions: \n" +
                "[Name:com.catsmoker.fpslab Modes: {4=[Game Mode:4,Scaling:0.9," +
                "Use Angle:false,Fps:120,Loading Boost Duration:-1]}]"
        )
        assertEquals(4, state?.gameMode)
        assertEquals(120, state?.fps)
        assertEquals(0.9f, state?.scaling)
    }

    @Test
    fun noInterventionReadsAsNull() {
        assertNull(
            GameCommandParser.parseIntervention(
                "com.catsmoker.fpslab interventions: \n No intervention found for package com.catsmoker.fpslab"
            )
        )
    }

    @Test
    fun interventionWithoutScalingStaysNull() {
        val state = GameCommandParser.parseIntervention(
            "[Name:com.example Modes: {4=[Game Mode:4,Use Angle:false,Fps:60]}]"
        )
        assertEquals(60, state?.fps)
        assertNull(state?.scaling)
    }

    @Test
    fun performanceModeCommandUsesTheModesSubcommand() {
        // The old `cmd game set --mode performance` shape is invalid: `set` parses its
        // flags as an intervention config, and `--mode` hits a NumberFormatException.
        assertEquals(
            listOf("cmd", "game", "mode", "performance", "com.example.game"),
            GameCommandParser.performanceModeArgs("com.example.game")
        )
    }

    @Test
    fun customInterventionArgsCarryOnlyTheChosenValues() {
        assertEquals(
            listOf("cmd", "game", "set", "--fps", "120", "com.example.game"),
            GameCommandParser.customInterventionArgs("com.example.game", 120, null)
        )
        assertEquals(
            listOf(
                "cmd", "game", "set", "--fps", "120", "--downscale", "0.85", "com.example.game"
            ),
            GameCommandParser.customInterventionArgs("com.example.game", 120, 0.85f)
        )
        // Out-of-range scales are dropped by the same sanitizer the overlay table uses,
        // never substituted with a nearby value.
        assertEquals(
            listOf("cmd", "game", "set", "--fps", "120", "com.example.game"),
            GameCommandParser.customInterventionArgs("com.example.game", 120, 0.2f)
        )
    }
}
