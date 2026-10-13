package com.catsmoker.app.features.main.engine.parsers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the thermal-severity synthesis against the reference `ThermalSeverity` + monitor
 * labels (`reference/gamingtools/booster/.../metrics/ThermalSeverity.kt`,
 * `Monitors.kt#statusLabel/pressureLabel`, read before this test was written): the 0-6
 * platform statuses map to NONE/LIGHT/MODERATE/SEVERE/CRITICAL/EMERGENCY/SHUTDOWN, with
 * long labels (Normal…Shutdown) and short overlay labels (OK/WARM/HOT/CRIT/!!!).
 *
 * Out-of-range statuses map to null — an unknown throttle state is "unknown", never a
 * fabricated severity.
 */
class ThermalSeverityTest {

    @Test
    fun statusesMapToTiers() {
        assertEquals(ThermalSeverity.Tier.NONE, ThermalSeverity.fromStatus(0))
        assertEquals(ThermalSeverity.Tier.LIGHT, ThermalSeverity.fromStatus(1))
        assertEquals(ThermalSeverity.Tier.MODERATE, ThermalSeverity.fromStatus(2))
        assertEquals(ThermalSeverity.Tier.SEVERE, ThermalSeverity.fromStatus(3))
        assertEquals(ThermalSeverity.Tier.CRITICAL, ThermalSeverity.fromStatus(4))
        assertEquals(ThermalSeverity.Tier.EMERGENCY, ThermalSeverity.fromStatus(5))
        assertEquals(ThermalSeverity.Tier.SHUTDOWN, ThermalSeverity.fromStatus(6))
    }

    @Test
    fun outOfRangeStatusesMapToNothing() {
        assertNull(ThermalSeverity.fromStatus(-1))
        assertNull(ThermalSeverity.fromStatus(7))
        assertNull(ThermalSeverity.fromStatus(42))
    }

    @Test
    fun longLabels() {
        assertEquals("Normal", ThermalSeverity.label(ThermalSeverity.Tier.NONE))
        assertEquals("Light", ThermalSeverity.label(ThermalSeverity.Tier.LIGHT))
        assertEquals("Moderate", ThermalSeverity.label(ThermalSeverity.Tier.MODERATE))
        assertEquals("Severe", ThermalSeverity.label(ThermalSeverity.Tier.SEVERE))
        assertEquals("Critical", ThermalSeverity.label(ThermalSeverity.Tier.CRITICAL))
        assertEquals("Emergency", ThermalSeverity.label(ThermalSeverity.Tier.EMERGENCY))
        assertEquals("Shutdown", ThermalSeverity.label(ThermalSeverity.Tier.SHUTDOWN))
    }

    @Test
    fun shortLabels() {
        assertEquals("OK", ThermalSeverity.shortLabel(ThermalSeverity.Tier.NONE))
        assertEquals("WARM", ThermalSeverity.shortLabel(ThermalSeverity.Tier.LIGHT))
        assertEquals("HOT", ThermalSeverity.shortLabel(ThermalSeverity.Tier.MODERATE))
        assertEquals("CRIT", ThermalSeverity.shortLabel(ThermalSeverity.Tier.SEVERE))
        assertEquals("CRIT", ThermalSeverity.shortLabel(ThermalSeverity.Tier.CRITICAL))
        assertEquals("!!!", ThermalSeverity.shortLabel(ThermalSeverity.Tier.EMERGENCY))
        assertEquals("!!!", ThermalSeverity.shortLabel(ThermalSeverity.Tier.SHUTDOWN))
    }

    @Test
    fun displayShortShowsTierOnlyWhenThrottling() {
        assertNull(ThermalSeverity.displayShort(0))
        assertNull(ThermalSeverity.displayShort(-1))
        assertNull(ThermalSeverity.displayShort(42))
        assertEquals("WARM", ThermalSeverity.displayShort(1))
        assertEquals("HOT", ThermalSeverity.displayShort(2))
        assertEquals("CRIT", ThermalSeverity.displayShort(3))
    }
}
