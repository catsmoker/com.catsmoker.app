package com.catsmoker.app.features.main.engine.parsers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Behaviour lock for the GPU sysfs parsers.
 *
 * Each vendor writes its own shape (kgsl `34 %`, Mali `34`, `gpubusy` counter pairs,
 * devfreq Hz, GED kHz), and every wrong guess would show up as a plausible-looking number
 * on the overlay. Null is the only acceptable answer for anything that is not a reading.
 */
class GpuTelemetryParserTest {

    // ------------------------------------------------------------- utilization

    @Test
    fun kgslPercentageWithSuffixParses() {
        assertEquals(34, GpuTelemetryParser.parseUtilization("34 %"))
    }

    @Test
    fun bareIntegerParses() {
        assertEquals(0, GpuTelemetryParser.parseUtilization("0"))
        assertEquals(100, GpuTelemetryParser.parseUtilization("100"))
    }

    @Test
    fun paddedAndTrailingNewlineParse() {
        assertEquals(12, GpuTelemetryParser.parseUtilization("  12 \n"))
    }

    @Test
    fun firstTokenOnlyForMediaTekGedShape() {
        // GED's gpu_utilization carries a second number that is not utilization.
        assertEquals(37, GpuTelemetryParser.parseUtilization("37 4"))
    }

    @Test
    fun aboveHundredIsNotAPercentage() {
        assertNull(GpuTelemetryParser.parseUtilization("1234"))
    }

    @Test
    fun negativeAndGarbageAreNotReadings() {
        assertNull(GpuTelemetryParser.parseUtilization("-1"))
        assertNull(GpuTelemetryParser.parseUtilization("busy"))
        assertNull(GpuTelemetryParser.parseUtilization(""))
        assertNull(GpuTelemetryParser.parseUtilization(null))
    }

    @Test
    fun fractionRoundsToWholePercent() {
        assertEquals(56, GpuTelemetryParser.parseUtilization("55.6"))
    }

    // --------------------------------------------------------------- gpubusy

    @Test
    fun busyPairBecomesAPercentage() {
        assertEquals(25, GpuTelemetryParser.parseBusyPair("25 100"))
    }

    @Test
    fun zeroPairHasNoTotalToDivideBy() {
        assertNull(GpuTelemetryParser.parseBusyPair("0 0"))
    }

    @Test
    fun busyPairNeedsTwoNumbers() {
        assertNull(GpuTelemetryParser.parseBusyPair("42"))
        assertNull(GpuTelemetryParser.parseBusyPair("x y"))
        assertNull(GpuTelemetryParser.parseBusyPair(null))
    }

    @Test
    fun busyAboveTotalIsNotAPercentage() {
        assertNull(GpuTelemetryParser.parseBusyPair("150 100"))
    }

    // ------------------------------------------------------------------ clock

    @Test
    fun hertzNodeBecomesMhz() {
        assertEquals(180, GpuTelemetryParser.parseClockMhz("180000000", GpuTelemetryParser.ClockUnit.HZ))
    }

    @Test
    fun kilohertzNodeBecomesMhz() {
        assertEquals(800, GpuTelemetryParser.parseClockMhz("800000", GpuTelemetryParser.ClockUnit.KHZ))
    }

    @Test
    fun megahertzNodeStaysAsItIs() {
        assertEquals(490, GpuTelemetryParser.parseClockMhz("490", GpuTelemetryParser.ClockUnit.MHZ))
    }

    @Test
    fun zeroAndGarbageAreNotClocks() {
        assertNull(GpuTelemetryParser.parseClockMhz("0", GpuTelemetryParser.ClockUnit.MHZ))
        assertNull(GpuTelemetryParser.parseClockMhz("", GpuTelemetryParser.ClockUnit.HZ))
        assertNull(GpuTelemetryParser.parseClockMhz(null, GpuTelemetryParser.ClockUnit.HZ))
        assertNull(GpuTelemetryParser.parseClockMhz("n/a", GpuTelemetryParser.ClockUnit.HZ))
    }

    @Test
    fun implausibleClockIsAUnitMistake() {
        // A kHz value read as MHz would land here; no mobile GPU runs at 10+ GHz.
        assertNull(GpuTelemetryParser.parseClockMhz("800000000", GpuTelemetryParser.ClockUnit.MHZ))
    }

    @Test
    fun subMegahertzValueIsNotAClock() {
        // A kHz value read as Hz lands at 0.18 MHz — nothing that can be a core clock.
        assertNull(GpuTelemetryParser.parseClockMhz("180", GpuTelemetryParser.ClockUnit.HZ))
    }
}
