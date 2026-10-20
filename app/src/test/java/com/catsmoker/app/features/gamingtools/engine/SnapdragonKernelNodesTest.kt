package com.catsmoker.app.features.gamingtools.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SnapdragonKernelNodes]: every rule is raise-only (or enable-if-zero), every
 * target derives from the device's own data, and anything unparseable or ambiguous yields no
 * write — never a fabricated one.
 */
class SnapdragonKernelNodesTest {

    // --- schedutil up_rate_limit_us -------------------------------------------------------

    @Test
    fun `up rate limit is removed to zero when the device rate-limits increases`() {
        assertEquals("0", SnapdragonKernelNodes.noUpRateLimit("500"))
    }

    @Test
    fun `up rate limit writes nothing when it is already zero`() {
        assertNull(SnapdragonKernelNodes.noUpRateLimit("0"))
    }

    @Test
    fun `up rate limit writes nothing when unreadable or unparseable`() {
        assertNull(SnapdragonKernelNodes.noUpRateLimit(null))
        assertNull(SnapdragonKernelNodes.noUpRateLimit(""))
        assertNull(SnapdragonKernelNodes.noUpRateLimit("abc"))
        assertNull(SnapdragonKernelNodes.noUpRateLimit("-1"))
    }

    // --- schedutil hispeed_freq -----------------------------------------------------------

    @Test
    fun `hispeed target is the policy's own hardware max when the jump is disabled`() {
        assertEquals(
            "1804800",
            SnapdragonKernelNodes.hispeedTarget(
                current = "0",
                availableKhz = listOf(300_000L, 1_804_800L),
                hispeedLoad = "90"
            )
        )
    }

    @Test
    fun `hispeed target raises a below-max jump and leaves one at max alone`() {
        assertEquals("1804800", SnapdragonKernelNodes.hispeedTarget("1708800", listOf(1_804_800L), "90"))
        assertNull(SnapdragonKernelNodes.hispeedTarget("1804800", listOf(1_804_800L), "90"))
        assertNull(SnapdragonKernelNodes.hispeedTarget("2000000", listOf(1_804_800L), "90"))
    }

    @Test
    fun `hispeed target refuses to enable a jump whose threshold is ambiguous`() {
        // 0 means "disabled" on some kernels and "always jump" on others: never guess.
        assertNull(SnapdragonKernelNodes.hispeedTarget("0", listOf(1_804_800L), "0"))
        assertNull(SnapdragonKernelNodes.hispeedTarget("0", listOf(1_804_800L), null))
        assertNull(SnapdragonKernelNodes.hispeedTarget("0", listOf(1_804_800L), "150"))
        assertNull(SnapdragonKernelNodes.hispeedTarget("0", listOf(1_804_800L), "abc"))
    }

    @Test
    fun `hispeed target writes nothing without an own-frequency table or a parseable current`() {
        assertNull(SnapdragonKernelNodes.hispeedTarget("0", emptyList(), "90"))
        assertNull(SnapdragonKernelNodes.hispeedTarget("abc", listOf(1_804_800L), "90"))
        assertNull(SnapdragonKernelNodes.hispeedTarget(null, listOf(1_804_800L), "90"))
    }

    // --- enable-if-zero flags -------------------------------------------------------------

    @Test
    fun `enable flags turn a declared-off boost on and write nothing otherwise`() {
        assertEquals("1", SnapdragonKernelNodes.enableIfZero("0"))
        assertNull(SnapdragonKernelNodes.enableIfZero("1"))
        assertNull(SnapdragonKernelNodes.enableIfZero(null))
        assertNull(SnapdragonKernelNodes.enableIfZero("abc"))
        assertNull(SnapdragonKernelNodes.enableIfZero("2"))
    }

    // --- per-CPU `cpu:freq` lists ----------------------------------------------------------

    @Test
    fun `freq pairs parse space and comma separated cpu-colon-freq tokens`() {
        assertEquals(
            mapOf(0 to 0L, 4 to 1_400_000L),
            SnapdragonKernelNodes.parseFreqPairs("0:0 4:1400000")
        )
        assertEquals(
            mapOf(0 to 300_000L, 1 to 0L),
            SnapdragonKernelNodes.parseFreqPairs("0:300000,1:0")
        )
    }

    @Test
    fun `freq pairs skip malformed tokens rather than inventing entries`() {
        val parsed = SnapdragonKernelNodes.parseFreqPairs("0:300000 broken 2 3:x 4:1o0 :5")
        assertEquals(mapOf(0 to 300_000L), parsed)
    }

    @Test
    fun `freq pairs format sorted by cpu`() {
        assertEquals(
            "0:1804800 4:1804800",
            SnapdragonKernelNodes.formatFreqPairs(mapOf(4 to 1_804_800L, 0 to 1_804_800L))
        )
    }

    @Test
    fun `msm performance caps are only ever raised, never invented`() {
        val hw = mapOf(0 to 1_804_800L, 4 to 1_804_800L, 6 to 2_304_000L)
        // A zero entry means "no cap declared" — nothing to remove.
        assertTrue(SnapdragonKernelNodes.raiseExistingCapPairs("0:0 4:0 6:0", hw).isEmpty())
        // A declared cap below the hardware max is a real ceiling: raise it.
        assertEquals(
            mapOf(4 to 1_804_800L),
            SnapdragonKernelNodes.raiseExistingCapPairs("4:1400000", hw)
        )
        // Already at or above the hardware max: nothing to widen.
        assertTrue(SnapdragonKernelNodes.raiseExistingCapPairs("4:1804800", hw).isEmpty())
        assertTrue(SnapdragonKernelNodes.raiseExistingCapPairs("4:2000000", hw).isEmpty())
        // Unreadable/unparseable node: no plan at all.
        assertTrue(SnapdragonKernelNodes.raiseExistingCapPairs(null, hw).isEmpty())
    }

    @Test
    fun `cpu boost frequencies are raised toward each cpu's own policy max`() {
        val hw = mapOf(0 to 1_804_800L, 5 to 1_804_800L, 6 to 2_304_000L)
        assertEquals(
            mapOf(0 to 1_804_800L, 6 to 2_304_000L),
            SnapdragonKernelNodes.raiseBoostPairs("0:0 5:1804800 6:1209600", hw)
        )
        // CPUs the device does not report, and entries above their policy max, are left alone.
        assertEquals(
            mapOf(0 to 1_804_800L),
            SnapdragonKernelNodes.raiseBoostPairs("0:0 9:800000 6:2400000", hw)
        )
        assertTrue(SnapdragonKernelNodes.raiseBoostPairs(null, hw).isEmpty())
    }

    // --- core_ctl -------------------------------------------------------------------------

    @Test
    fun `core control floors and ceilings are raised toward the cluster's real core count only`() {
        assertEquals("4", SnapdragonKernelNodes.clusterRaise("1", 4))
        assertNull(SnapdragonKernelNodes.clusterRaise("4", 4))
        // Never narrows: a value above the derived count is left where the kernel put it.
        assertNull(SnapdragonKernelNodes.clusterRaise("6", 4))
        assertNull(SnapdragonKernelNodes.clusterRaise(null, 4))
        assertNull(SnapdragonKernelNodes.clusterRaise("abc", 4))
        assertNull(SnapdragonKernelNodes.clusterRaise("1", 0))
    }

    // --- the deliberate non-writes --------------------------------------------------------

    @Test
    fun `no writable node names kgsl, pwrlevels or thermal`() {
        // The reference's GPU lines are deliberately not ported: its two governor writes target
        // the same node through different symlinks (the later one, msm-adreno-tz, is the device's
        // own default), and KGSL pwrlevels are a live thermal clamp. Nothing here may name them.
        val banned = listOf("kgsl", "pwrlevel", "thermal", "gpu")
        for (name in SnapdragonKernelNodes.writableFileNames) {
            for (fragment in banned) {
                assertTrue(
                    "writable node '$name' names '$fragment'",
                    !name.lowercase().contains(fragment)
                )
            }
        }
    }
}
