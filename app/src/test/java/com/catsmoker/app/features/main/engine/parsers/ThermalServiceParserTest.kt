package com.catsmoker.app.features.main.engine.parsers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Note on the crash these tests were written after: the `Temperature{...}` pattern shipped with an
 * unescaped closing brace, which the JVM's regex engine accepts and the device's ICU-backed one
 * rejects. No JVM test can catch that — it needs an instrumented run. What these lock down is the
 * parse *behaviour*, so the escaping fix stays provably behaviour-neutral.
 */
class ThermalServiceParserTest {

    @Test
    fun prefersHalSectionOverStaticThresholds() {
        // The thresholds block repeats the same field names with trip-point values. Reading it as
        // live data is how a 95 °C "reading" appears on an idle device.
        val dump = """
            Thermal Status: 2
            Current temperatures from HAL:
             Temperature{mValue=42.5, mType=0, mName=cpu-0-0-usr, mStatus=0}
             Temperature{mValue=39.0, mType=1, mName=gpu-usr, mStatus=0}
            Temperature static thresholds:
             Temperature{mValue=95.0, mType=0, mName=cpu-0-0-usr, mStatus=0}
        """.trimIndent()

        val result = ThermalServiceParser.parse(dump)!!
        assertEquals(42.5f, result.cpuC!!, 0.01f)
        assertEquals(39.0f, result.gpuC!!, 0.01f)
        assertEquals(2, result.thermalStatus)
        assertEquals(2, result.entryCount)
    }

    @Test
    fun keepsHottestReadingPerSlot() {
        // SoCs expose several CPU cluster sensors; the peak is what drives throttling.
        val dump = """
            Current temperatures from HAL:
             Temperature{mValue=40.0, mType=0, mName=cpu-0-0-usr}
             Temperature{mValue=51.2, mType=0, mName=cpu-1-0-usr}
             Temperature{mValue=45.0, mType=0, mName=cpu-2-0-usr}
        """.trimIndent()

        assertEquals(51.2f, ThermalServiceParser.parse(dump)!!.cpuC!!, 0.01f)
    }

    @Test
    fun classifiesByNameWhenTypeIsAbsent() {
        val dump = """
            Temperature{mValue=38.0, mName=quiet-therm-adc}
            Temperature{mValue=44.0, mName=mali-gpu}
            Temperature{mValue=33.0, mName=battery}
            Temperature{mValue=41.0, mName=npu-usr}
        """.trimIndent()

        val result = ThermalServiceParser.parse(dump)!!
        assertEquals(38.0f, result.skinC!!, 0.01f)
        assertEquals(44.0f, result.gpuC!!, 0.01f)
        assertEquals(33.0f, result.batteryC!!, 0.01f)
        assertEquals(41.0f, result.npuC!!, 0.01f)
        assertNull(result.cpuC)
    }

    @Test
    fun socTemperatureFallsBackThroughSlotsAndIgnoresBattery() {
        val gpuOnly = ThermalServiceParser.parse("Temperature{mValue=47.0, mType=1, mName=gpu}")!!
        assertEquals(47.0f, gpuOnly.socC!!, 0.01f)

        // Battery is surfaced separately, so it must never stand in for the SoC reading.
        val batteryOnly = ThermalServiceParser.parse("Temperature{mValue=30.0, mType=2, mName=batt}")!!
        assertNull(batteryOnly.socC)
        assertTrue(batteryOnly.hasAnySensor)
    }

    @Test
    fun normalisesMilliDegreesAndDropsImplausibleValues() {
        val dump = """
            Temperature{mValue=45300, mType=0, mName=cpu}
            Temperature{mValue=999999, mType=1, mName=gpu}
        """.trimIndent()

        val result = ThermalServiceParser.parse(dump)!!
        assertEquals(45.3f, result.cpuC!!, 0.01f)
        // 999999 → 999.999 °C, outside the sane range, so the slot stays empty rather than lying.
        assertNull(result.gpuC)
        assertEquals(1, result.entryCount)
    }

    @Test
    fun parsesSysfsFallbackLines() {
        // What ShellRunner's sysfs sweep emits when dumpsys is unavailable.
        val output = """
            cpu-1-0-usr:48200
            gpu-usr:41000
            unknown-zone:37000
        """.trimIndent()

        val result = ThermalServiceParser.parse(output)!!
        assertEquals(48.2f, result.cpuC!!, 0.01f)
        assertEquals(41.0f, result.gpuC!!, 0.01f)
        assertEquals(2, result.entryCount)
    }

    @Test
    fun reportsBlankAsNullAndUnparseableAsZeroEntries() {
        assertNull(ThermalServiceParser.parse(""))
        assertNull(ThermalServiceParser.parse("   \n  "))

        // Non-blank but sensorless: the caller needs to tell this apart from "no output" so it can
        // show ParseFailed instead of a confident 0 °C.
        val result = ThermalServiceParser.parse("Permission denied")!!
        assertEquals(0, result.entryCount)
        assertTrue(!result.hasAnySensor)
    }

    @Test
    fun detectsHalNotReady() {
        assertTrue(ThermalServiceParser.parse("IsStatusOverride: false\nHAL Ready: false")!!.halNotReady)
        assertTrue(!ThermalServiceParser.parse("Temperature{mValue=40.0, mType=0}")!!.halNotReady)
    }
}
