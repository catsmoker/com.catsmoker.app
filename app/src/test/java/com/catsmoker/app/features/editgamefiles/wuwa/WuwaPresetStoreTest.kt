package com.catsmoker.app.features.editgamefiles.wuwa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins WuWa preset persistence logic: every supported preset survives a
 * save/load round-trip, anything else falls back to `balanced`, and a corrupt
 * options blob restores the screen defaults instead of a half-parsed config.
 *
 * Only the pure mapping is tested here (JUnit-only suites, no Android): the
 * SharedPreferences wrapper in [WuwaPresetStore] is a thin pass-through over it.
 */
class WuwaPresetStoreTest {

    @Test
    fun everySupportedPresetSurvivesSanitize() {
        WuWaConfigGenerator.PRESET_ORDER.forEach { preset ->
            assertEquals(preset, WuwaPresetStore.sanitizePreset(preset))
        }
    }

    @Test
    fun unknownEmptyAndNullPresetsFallBackToBalanced() {
        assertEquals("balanced", WuwaPresetStore.sanitizePreset("ultra-hd"))
        assertEquals("balanced", WuwaPresetStore.sanitizePreset(""))
        assertEquals("balanced", WuwaPresetStore.sanitizePreset(null))
        assertEquals("balanced", WuwaPresetStore.sanitizePreset("Balanced"))
    }

    @Test
    fun optionsRoundTripPreservesEveryField() {
        val options = WuWaConfigGenerator.Options(
            generateScalability = true,
            generateHardware = true,
            optimizeWithCvarDb = true,
            cvarOverrides = mapOf("r.Fog" to "0")
        )
        val decoded = WuwaPresetStore.decodeOptions(WuwaPresetStore.encodeOptions(options))
        assertEquals(options, decoded)
    }

    @Test
    fun corruptOptionsDecodeToNull() {
        assertNull(WuwaPresetStore.decodeOptions(null))
        assertNull(WuwaPresetStore.decodeOptions(""))
        assertNull(WuwaPresetStore.decodeOptions("{not json"))
        assertNull(WuwaPresetStore.decodeOptions("""{"fps":"sixty"}"""))
    }

    @Test
    fun partialOptionsRestoreDefaultsForAbsentKeys() {
        // A version-skewed blob is benign: present keys apply, absent keys behave
        // exactly like a fresh install. Only malformed blobs fall back wholesale.
        val decoded = WuwaPresetStore.decodeOptions("""{"unlock120":true}""")
        assertEquals(true, decoded?.unlock120)
        assertEquals(WuWaConfigGenerator.GameMode.Overworld, decoded?.mode)
    }
}
