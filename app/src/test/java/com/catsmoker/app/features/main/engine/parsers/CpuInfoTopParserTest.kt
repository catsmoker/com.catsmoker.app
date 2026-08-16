package com.catsmoker.app.features.main.engine.parsers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CpuInfoTopParserTest {

    @Test
    fun parsesDecimalAndIntegerPercentages() {
        val output = """
            Load: 3.42 / 3.33 / 3.28
            CPU usage from 335451ms to 437434ms ago with 99% awake:
              2.8% 1847/system_server: 1.6% user + 1.2% kernel / faults: 17 minor
              12% 234/com.example.game: 10% user + 2% kernel
              0.5% 331/kswapd0: 0% user + 0.5% kernel
        """.trimIndent()

        val result = CpuInfoTopParser.parseTopProcesses(output)

        assertEquals(3, result.size)
        val top = result[0]
        assertEquals("com.example.game", top.name)
        assertEquals(12f, top.cpuPercent, 0.0001f)
    }

    @Test
    fun emptyOutputReturnsEmptyList() {
        assertTrue(CpuInfoTopParser.parseTopProcesses("").isEmpty())
    }
}
