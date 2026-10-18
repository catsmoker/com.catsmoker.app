package com.catsmoker.app.features.gamingtools.tools.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the performance readout's position math (TODO M49 / V16).
 *
 * The service is device-only, but the part that decides where a stored or dragged position may
 * land is pure arithmetic and is pinned here: the legal range is the display minus the panel and
 * the edge margin, it can never be negative (a panel wider than the panel it sits on leaves zero
 * travel), and a clamp folds anything — including a stored value from a resolution the app has
 * since shrunk — back inside it.
 */
class OverlayPositionTest {

    @Test
    fun boundsAreDisplayMinusWindowMinusMargin() {
        val bounds = OverlayPosition.bounds(
            displayWidth = 1080,
            displayHeight = 2400,
            viewWidth = 200,
            viewHeight = 150,
            marginPx = 42
        )
        assertEquals(838, bounds.maxX)
        assertEquals(2208, bounds.maxY)
    }

    @Test
    fun windowWiderThanDisplayLeavesZeroTravel() {
        val bounds = OverlayPosition.bounds(
            displayWidth = 720,
            displayHeight = 1600,
            viewWidth = 800,
            viewHeight = 100,
            marginPx = 42
        )
        assertEquals(0, bounds.maxX)
        assertEquals(1458, bounds.maxY)
    }

    @Test
    fun clampFoldsNegativeAndBeyondMax() {
        val bounds = OverlayPosition.Bounds(maxX = 838, maxY = 2208)
        assertEquals(0 to 0, bounds.clamp(-120, -1))
        assertEquals(838 to 2208, bounds.clamp(5_000, 9_000))
    }

    @Test
    fun clampLeavesAFittingPositionUntouched() {
        val bounds = OverlayPosition.Bounds(maxX = 838, maxY = 2208)
        assertEquals(100 to 100, bounds.clamp(100, 100))
        assertEquals(838 to 2208, bounds.clamp(838, 2208))
    }

    @Test
    fun zeroBoundsClampEverythingToTheOrigin() {
        val bounds = OverlayPosition.bounds(
            displayWidth = 720,
            displayHeight = 1600,
            viewWidth = 720,
            viewHeight = 1600,
            marginPx = 0
        )
        assertEquals(0 to 0, bounds.clamp(300, 900))
    }
}
