package com.catsmoker.app.features.editgamefiles.hsr

import com.catsmoker.app.features.editgamefiles.hsr.HsrPlayerPrefsXml.Value.IntValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the flat-format path live HSR ≥4.x files take (sibling ints, no
 * `GraphicsSettings_Model` blob): reading never fabricates JSON-backed values,
 * and writes only touch keys the live file already holds.
 */
class HsrFlatFormatTest {

    /** Mirrors the POCO device's live file (uid key omitted — irrelevant here). */
    private fun pristineLike(): Map<String, HsrPlayerPrefsXml.Value> = mapOf(
        "Screenmanager%20Resolution%20Width" to IntValue(2400),
        "Screenmanager%20Resolution%20Height" to IntValue(1080),
        "Screenmanager%20Fullscreen%20mode" to IntValue(-1),
        "GraphicsSettings_GraphicsQuality" to IntValue(2),
        "GraphicsSettings_Version" to IntValue(10)
    )

    @Test
    fun fromFlatMapReadsSiblingsAndDefaultsTheRest() {
        val settings = HsrGraphicsSettings.fromFlatMap(pristineLike())
        assertEquals(2, settings.graphicsQuality)
        assertEquals(2400, settings.screenWidth)
        assertEquals(1080, settings.screenHeight)
        assertEquals(-1, settings.fullscreenMode)
        assertEquals(10, settings.version)
        // JSON-backed fields are unread here — defaults, never zeros-for-unknown.
        assertEquals(60, settings.fps)
        assertEquals(3, settings.shadowQuality)
    }

    @Test
    fun fromFlatMapWithEmptyMapYieldsDefaults() {
        val settings = HsrGraphicsSettings.fromFlatMap(emptyMap())
        assertEquals(HsrGraphicsSettings(), settings)
    }

    @Test
    fun flatUpdatesOnlyTouchPresentKeys() {
        val settings = HsrGraphicsSettings.fromFlatMap(pristineLike())
        settings.graphicsQuality = 3
        // Live file without a version entry: the write must not invent one.
        val present = setOf(
            "Screenmanager%20Resolution%20Width",
            "Screenmanager%20Resolution%20Height",
            "Screenmanager%20Fullscreen%20mode",
            "GraphicsSettings_GraphicsQuality"
        )
        val updates = HsrGraphicsSettings.flatUpdates(present, settings)
        assertEquals(
            setOf(
                "Screenmanager%20Resolution%20Width",
                "Screenmanager%20Resolution%20Height",
                "Screenmanager%20Fullscreen%20mode",
                "GraphicsSettings_GraphicsQuality"
            ),
            updates.keys
        )
        assertEquals(3, updates["GraphicsSettings_GraphicsQuality"]?.value)
        assertEquals(2400, updates["Screenmanager%20Resolution%20Width"]?.value)
    }

    @Test
    fun flatUpdatesFollowTheSettings() {
        val settings = HsrGraphicsSettings.fromFlatMap(pristineLike())
        settings.screenWidth = 1280
        settings.screenHeight = 720
        val updates = HsrGraphicsSettings.flatUpdates(pristineLike().keys, settings)
        assertEquals(1280, updates["Screenmanager%20Resolution%20Width"]?.value)
        assertEquals(720, updates["Screenmanager%20Resolution%20Height"]?.value)
        assertEquals(10, updates["GraphicsSettings_Version"]?.value)
    }

    @Test
    fun flatRoundTripKeepsEverySibling() {
        val settings = HsrGraphicsSettings.fromFlatMap(pristineLike())
        val updates = HsrGraphicsSettings.flatUpdates(pristineLike().keys, settings)
        assertTrue(updates.keys.containsAll(pristineLike().keys))
    }
}
