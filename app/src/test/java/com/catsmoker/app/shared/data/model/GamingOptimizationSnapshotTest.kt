package com.catsmoker.app.shared.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Snapshot compatibility for the Gaming Mode restore contract.
 *
 * Every field is either a measured fact or "do not touch" (null / empty):
 * adding the Snapdragon CPU-node map must keep old snapshots restoring
 * exactly what they recorded, and a snapshot that never saw the new field
 * must behave as "no Snapdragon nodes" rather than failing the whole
 * restore. Partial application is expressed per-node — one refused node
 * never blocks the restore of the others.
 */
class GamingOptimizationSnapshotTest {

    @Test
    fun snapdragonNodesRoundTripThroughJson() {
        val snapshot = GamingOptimizationSnapshot(
            activeGamePackage = null,
            activeGameUid = null,
            timestamp = 1L,
            minRefreshRate = null,
            peakRefreshRate = null,
            touchResponseSpeed = null,
            userPreferredDisplayModeId = null,
            affectedPackages = emptySet(),
            snapdragonCpuNodes = mapOf(
                "/sys/devices/system/cpu/cpufreq/policy6/scaling_max_freq" to "2169600",
                "/dev/cpuset/foreground/cpus" to "0-5,7"
            )
        )
        val parsed = GamingOptimizationSnapshot.fromJson(snapshot.toJson())
        assertEquals(snapshot.snapdragonCpuNodes, parsed?.snapdragonCpuNodes)
    }

    @Test
    fun snapshotWithoutTheNewFieldReadsAsNoSnapdragonNodes() {
        // An old snapshot predates the Snapdragon map: the missing key must
        // behave as "nothing to restore", never as a parse failure.
        val oldJson = """
            {"activeGamePackage":null,"activeGameUid":null,"timestamp":1,
             "affectedPackages":[],"mediatekGameNodes":{}}
        """.trimIndent()
        val parsed = GamingOptimizationSnapshot.fromJson(oldJson)
        assertTrue(parsed != null)
        assertTrue(parsed!!.snapdragonCpuNodes.orEmpty().isEmpty())
    }

    @Test
    fun garbageJsonStillParsesToNull() {
        assertNull(GamingOptimizationSnapshot.fromJson("not json"))
    }
}
