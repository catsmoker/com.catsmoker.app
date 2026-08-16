package com.catsmoker.app.features.main.engine.parsers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CpuStatParserTest {

    @Test
    fun parsesAggregateLineAndExcludesGuestTime() {
        val lines = listOf(
            "cpu  100 20 50 800 30 0 10 0 5 5",
            "cpu0 10 2 5 80 3 0 1 0 0 0",
        )
        val times = CpuStatParser.parseTotalCpuLine(lines)
        assertEquals(830L, times!!.idle + times.iowait)
        assertEquals(1020L, times.total)
    }

    @Test
    fun computesUsageBetweenSamples() {
        val prev = CpuStatParser.CpuTimes(100, 20, 50, 800, 30, 0, 10, 0)
        val curr = CpuStatParser.CpuTimes(200, 40, 100, 1600, 60, 0, 20, 0)
        assertEquals(17, CpuStatParser.calculateCpuUsage(prev, curr))
    }
}
