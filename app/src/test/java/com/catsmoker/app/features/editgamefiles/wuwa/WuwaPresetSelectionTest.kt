package com.catsmoker.app.features.editgamefiles.wuwa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the preset-selection contract the Smart Recommended bug violated: whatever preset is
 * selected is what generation produces — a non-`balanced` selection must never come out as
 * `balanced`'s configuration, and the recommendation can only ever select presets this
 * build actually ships.
 *
 * The ViewModel itself is Android-bound (no JUnit-Robolectric infra here), so these tests
 * pin the pure layers the selection flows through: [WuwaPresetStore.sanitizePreset],
 * [WuWaConfigGenerator.generate], and [WuwaSmartBrain.recommend].
 */
class WuwaPresetSelectionTest {

    private val options = WuWaConfigGenerator.Options()

    @Test
    fun everySupportedPresetGeneratesItsOwnConfiguration() {
        WuWaConfigGenerator.PRESET_ORDER.forEach { preset ->
            val configs = WuWaConfigGenerator.generate(preset, options, WuWaConfigGenerator.DeviceInfo())
            assertTrue(
                "engine header must name the selected preset $preset",
                configs.engine.contains("; │   [ PRESET ] : ${preset.uppercase()}")
            )
            assertEquals(
                "GameUserSettings must carry $preset's render percentage",
                true,
                configs.gameUserSettings.contains(
                    "sg.ResolutionQuality=${WuWaConfigGenerator.PRESETS.getValue(preset).screen}"
                )
            )
        }
    }

    @Test
    fun selectingPerformanceNeverFallsBackToBalanced() {
        val performance = WuWaConfigGenerator.generate("performance", options, WuWaConfigGenerator.DeviceInfo())
        val balanced = WuWaConfigGenerator.generate("balanced", options, WuWaConfigGenerator.DeviceInfo())

        assertNotEquals(performance.engine, balanced.engine)
        assertTrue(performance.engine.contains("[ PRESET ] : PERFORMANCE"))
        assertFalse(performance.engine.contains("[ PRESET ] : BALANCED"))
        // The per-preset knobs really differ — the bug's symptom was these coming out as
        // balanced's values regardless of the selection.
        assertTrue(performance.gameUserSettings.contains("sg.ResolutionQuality=60"))
        assertTrue(balanced.gameUserSettings.contains("sg.ResolutionQuality=80"))
        assertTrue(performance.deviceProfiles.contains("r.Mobile.DeviceEvaluation=1"))
        assertTrue(balanced.deviceProfiles.contains("r.Mobile.DeviceEvaluation=2"))
    }

    @Test
    fun unknownPresetIsALastDitchFallbackBehindTheStoreBoundary() {
        // The generator's own defensive fallback (documented in its KDoc), unreachable from
        // the UI: selectPreset refuses unsupported ids and the store maps them at load.
        val configs = WuWaConfigGenerator.generate("not-a-preset", options, WuWaConfigGenerator.DeviceInfo())
        assertTrue(configs.engine.contains("[ PRESET ] : BALANCED"))
        assertEquals("balanced", WuwaPresetStore.sanitizePreset("not-a-preset"))
        WuWaConfigGenerator.PRESET_ORDER.forEach { preset ->
            assertEquals(preset, WuwaPresetStore.sanitizePreset(preset))
        }
    }

    @Test
    fun deviceFactsDriveTheTierAndResolution() {
        val tailored = WuWaConfigGenerator.generate(
            "balanced",
            options,
            WuWaConfigGenerator.DeviceInfo(
                gpu = "Adreno (TM) 740",
                deviceModel = "Test Phone",
                socModel = "SM8550",
                resolution = "1080x2400"
            )
        )
        val fallback = WuWaConfigGenerator.generate("balanced", options, WuWaConfigGenerator.DeviceInfo())

        // High-end tier raises the shared distance values; the all-null fallback stays lowest.
        assertTrue(tailored.deviceProfiles.contains("r.Kuro.MobileISMDecideDistance=14000.0"))
        assertTrue(fallback.deviceProfiles.contains("r.Kuro.MobileISMDecideDistance=7000.0"))
        // A recognised chipset gets the targeted profile instead of the universal one.
        assertTrue(tailored.deviceProfiles.contains("+DeviceProfileNameAndTypes=Android_Adreno740,Android"))
        assertTrue(fallback.deviceProfiles.contains("Universal Android preset"))
        // The panel's own resolution replaces the 1280x720 fallback.
        assertTrue(tailored.gameUserSettings.contains("ResolutionSizeX=1080"))
        assertTrue(fallback.gameUserSettings.contains("ResolutionSizeX=1280"))
    }

    @Test
    fun smartBrainOnlyEverRecommendsSupportedPresets() {
        val cases = listOf(
            WuwaSmartBrain.DeviceSignals(),
            WuwaSmartBrain.DeviceSignals(gpu = "Adreno (TM) 830", ramMb = 12000, vulkanAvailable = true, resolution = "1080x2400"),
            WuwaSmartBrain.DeviceSignals(gpu = "PowerVR GM 9446", ramMb = 3000, lowRamDevice = true),
            WuwaSmartBrain.DeviceSignals(gpu = "Adreno (TM) 618", ramMb = 5600, vulkanAvailable = true, resolution = "1080x2400"),
            WuwaSmartBrain.DeviceSignals(gpu = "Adreno (TM) 740", ramMb = 8000, resolution = "1440x3200", vulkanAvailable = true)
        )
        cases.forEach { signals ->
            val rec = WuwaSmartBrain.recommend(signals)
            assertTrue(
                "recommendation ${rec.preset} must be a shipped preset",
                rec.preset in WuWaConfigGenerator.PRESETS
            )
        }
    }
}
