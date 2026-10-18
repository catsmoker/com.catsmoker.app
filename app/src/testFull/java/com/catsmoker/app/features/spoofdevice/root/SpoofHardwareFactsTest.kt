package com.catsmoker.app.features.spoofdevice.root

import com.catsmoker.app.shared.data.model.LSPosedConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the M117 reading helpers and the MB→bytes/kB conversions both the hooks and the editor
 * units rely on, plus the `/proc/meminfo` rewrite shape.
 *
 * The hooks themselves (Runtime/ActivityManager/RandomAccessFile) need a live Xposed runtime;
 * what they delegate to is pure and pinned here. "Malformed value = off" is the fail-safe: a
 * hand-edited config can only under-publish, never publish nonsense.
 */
class SpoofHardwareFactsTest {

    private val full = mapOf(
        LSPosedConfig.KEY_HW_CPU_CORES to "8",
        LSPosedConfig.KEY_HW_RAM_MB to "12288",
        LSPosedConfig.KEY_HW_HEAP_MB to "512",
        LSPosedConfig.KEY_HW_LARGE_HEAP_MB to "1024"
    )

    @Test
    fun readsEveryFactFromItsOwnKey() {
        assertEquals(8, SpoofHardwareFacts.cpuCores(full))
        assertEquals(12288, SpoofHardwareFacts.ramMb(full))
        assertEquals(512, SpoofHardwareFacts.heapMb(full))
        assertEquals(1024, SpoofHardwareFacts.largeHeapMb(full))
    }

    @Test
    fun absentOrUnusableValuesMeanOff() {
        for (props in listOf(
            emptyMap(),
            mapOf(LSPosedConfig.KEY_HW_CPU_CORES to ""),
            mapOf(LSPosedConfig.KEY_HW_CPU_CORES to "0"),
            mapOf(LSPosedConfig.KEY_HW_CPU_CORES to "-4"),
            mapOf(LSPosedConfig.KEY_HW_CPU_CORES to "eight"),
            mapOf(LSPosedConfig.KEY_HW_CPU_CORES to "8.5"),
            mapOf(LSPosedConfig.KEY_HW_CPU_CORES to "99999999999999999999")
        )) {
            assertNull(SpoofHardwareFacts.cpuCores(props))
            assertNull(SpoofHardwareFacts.ramMb(props))
            assertNull(SpoofHardwareFacts.heapMb(props))
            assertNull(SpoofHardwareFacts.largeHeapMb(props))
        }
    }

    @Test
    fun megabytesConvertExactlyOnce() {
        // The single conversion authority: `getMemoryInfo.totalMem` is bytes, meminfo is kB.
        assertEquals(12884901888L, SpoofHardwareFacts.ramBytes(12288))
        assertEquals(12582912L, SpoofHardwareFacts.ramKb(12288))
        assertEquals(1048576L, SpoofHardwareFacts.ramBytes(1))
        assertEquals(1024L, SpoofHardwareFacts.ramKb(1))
    }

    @Test
    fun memTotalIsRewrittenInPlaceAndEverythingElsePassesThrough() {
        val original = "MemTotal:       16384000 kB"
        assertEquals("MemTotal:       12582912 kB", SpoofHardwareFacts.rewriteMemTotal(original, 12582912L))
        // Spacing and unit stay the device's own; only the number changes.
        assertEquals("MemTotal: 12582912 kB", SpoofHardwareFacts.rewriteMemTotal("MemTotal: 16384000 kB", 12582912L))
        assertEquals("MemTotal:\t12582912 kB", SpoofHardwareFacts.rewriteMemTotal("MemTotal:\t16384000 kB", 12582912L))
    }

    @Test
    fun nonMemTotalLinesAndDigitlessLabelsStayByteIdentical() {
        for (line in listOf(
            "MemFree:         1048576 kB",
            "MemAvailable:    8388608 kB",
            "Buffers:               0 kB",
            "# comment",
            "",
            "MemTotal: kB",
            "MemTotal:unknown kB"
        )) {
            assertEquals(line, SpoofHardwareFacts.rewriteMemTotal(line, 12582912L))
        }
    }
}
