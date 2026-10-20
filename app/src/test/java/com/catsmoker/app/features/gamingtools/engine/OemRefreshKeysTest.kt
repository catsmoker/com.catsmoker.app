package com.catsmoker.app.features.gamingtools.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The read-only OEM refresh-key probe, drawn from JordanTweaks' key enum
 * (`reference/file-engineering/JordanTweaks-120FPS-Unlock/Execute.sh`).
 *
 * Only the keys that name a rate are candidates; the script's other settings are
 * thermal limits, mode toggles and booleans, and its values are hardcoded 120s. This
 * catalog is evidence-gathering, not a write list: a key that is not a plain Hz value
 * cannot be restored from a measured snapshot and is therefore not here.
 */
class OemRefreshKeysTest {

    @Test
    fun catalogCoversTheOemNamespacesThatCarryAPanelRate() {
        assertTrue(OemRefreshKeys.candidates.any { it.namespace == "system" && it.key == "user_refresh_rate" })
        assertTrue(OemRefreshKeys.candidates.any { it.namespace == "secure" && it.key == "user_refresh_rate" })
        assertTrue(OemRefreshKeys.candidates.any { it.key == "miui_refresh_rate" })
        // AOSP's own keys are owned by RefreshRateController already; the probe is for the
        // vendor fallbacks, so mixing them in would double-report one control.
        assertTrue(OemRefreshKeys.candidates.none { it.key == "peak_refresh_rate" })
        assertTrue(OemRefreshKeys.candidates.none { it.key == "min_refresh_rate" })
        // Thermal limits and mode toggles are not rates: never candidates.
        assertTrue(OemRefreshKeys.candidates.none { it.key.contains("thermal") })
        assertTrue(OemRefreshKeys.candidates.none { it.key == "refresh_rate_mode" })
    }

    @Test
    fun parsesOnlyPlausibleRates() {
        assertEquals(120f, OemRefreshKeys.parseHz("120"))
        assertEquals(120f, OemRefreshKeys.parseHz(" 120.0 "))
        assertEquals(90f, OemRefreshKeys.parseHz("90"))
        assertNull(OemRefreshKeys.parseHz(null))
        assertNull(OemRefreshKeys.parseHz(""))
        assertNull(OemRefreshKeys.parseHz("null"))
        assertNull(OemRefreshKeys.parseHz("0"))
        assertNull(OemRefreshKeys.parseHz("-1"))
        assertNull(OemRefreshKeys.parseHz("auto"))
    }

    @Test
    fun readingsKeepOnlyKeysTheDeviceActuallyCarries() {
        val systemUser = OemRefreshKeys.Key("system", "user_refresh_rate")
        val secureUser = OemRefreshKeys.Key("secure", "user_refresh_rate")
        val readings = OemRefreshKeys.readings(
            mapOf(
                systemUser to "120",
                secureUser to null,
                OemRefreshKeys.Key("system", "miui_refresh_rate") to "0"
            )
        )
        assertEquals(listOf(OemRefreshKeys.Reading(systemUser, 120f)), readings)
    }
}
