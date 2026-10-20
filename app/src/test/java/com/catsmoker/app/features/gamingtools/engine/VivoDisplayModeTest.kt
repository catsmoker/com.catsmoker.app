package com.catsmoker.app.features.gamingtools.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Behaviour lock for the vivo display-mode choice.
 *
 * The reference locks a hardcoded id 4; the whole point of this port is that no id is ever
 * written that the device did not enumerate, and that a panel with no mode at the peak
 * rate produces no write at all (rather than a guessed one).
 */
class VivoDisplayModeTest {

    private val fhd120 = VivoDisplayMode.Mode(id = 4, width = 1080, height = 2400, refreshRateHz = 120f)
    private val fhd60 = VivoDisplayMode.Mode(id = 1, width = 1080, height = 2400, refreshRateHz = 60f)
    private val qhd120 = VivoDisplayMode.Mode(id = 7, width = 1440, height = 3200, refreshRateHz = 120f)
    private val hd120 = VivoDisplayMode.Mode(id = 9, width = 720, height = 1600, refreshRateHz = 120f)

    @Test
    fun picksTheModeAtTheMeasuredPeak() {
        assertEquals(4, VivoDisplayMode.bestModeId(listOf(fhd60, fhd120), peakHz = 120f, currentModeId = 1))
    }

    @Test
    fun highestResolutionAtThePeakWins() {
        assertEquals(7, VivoDisplayMode.bestModeId(listOf(fhd120, qhd120, hd120), peakHz = 120f, currentModeId = 1))
    }

    @Test
    fun tieBreaksToTheLowestModeId() {
        val twin = VivoDisplayMode.Mode(id = 3, width = 1080, height = 2400, refreshRateHz = 120f)
        assertEquals(3, VivoDisplayMode.bestModeId(listOf(fhd120, twin), peakHz = 120f, currentModeId = 1))
    }

    @Test
    fun alreadyAtThatModeIsNoWrite() {
        assertNull(VivoDisplayMode.bestModeId(listOf(fhd60, fhd120), peakHz = 120f, currentModeId = 4))
    }

    @Test
    fun noModeAtThePeakRateWritesNothing() {
        assertNull(VivoDisplayMode.bestModeId(listOf(fhd60), peakHz = 120f, currentModeId = 1))
    }

    @Test
    fun unreadablePanelPeakWritesNothing() {
        assertNull(VivoDisplayMode.bestModeId(listOf(fhd120), peakHz = 0f, currentModeId = 1))
    }

    @Test
    fun vendorRateJitterStillMatchesThePeak() {
        // Real panels report 119.999985 for a nominal 120 Hz mode.
        val jittery = VivoDisplayMode.Mode(id = 5, width = 1080, height = 2400, refreshRateHz = 119.999985f)
        assertEquals(5, VivoDisplayMode.bestModeId(listOf(jittery), peakHz = 120f, currentModeId = 1))
    }

    @Test
    fun emptyModeListWritesNothing() {
        assertNull(VivoDisplayMode.bestModeId(emptyList(), peakHz = 120f, currentModeId = 1))
    }
}
