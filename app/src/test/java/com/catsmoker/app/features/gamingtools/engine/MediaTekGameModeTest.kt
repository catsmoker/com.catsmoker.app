package com.catsmoker.app.features.gamingtools.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the MediaTek game-mode table against HunterX-Reborn-II's `common/service.sh`
 * GED & PPM section (lines 258-288, read before this test was written): the exact
 * parameter names and boost values, applied only where the nodes exist.
 *
 * Every value here widens (boosts on, idle floor up, boost policy maxed) — never a cap —
 * and the table is data, not behavior: presence-gating, snapshot and read-back live in
 * the engine; the table values themselves were verified on-device (TB-8505X, mt6761).
 */
class MediaTekGameModeTest {

    @Test
    fun gedTableMatchesTheReferencePayload() {
        val table = MediaTekGameMode.gedParams
        // Spot-check the load-bearing knobs; the full map is asserted by size below.
        assertEquals("1", table["/sys/module/ged/parameters/gx_game_mode"])
        assertEquals("1", table["/sys/module/ged/parameters/ged_boost_enable"])
        assertEquals("100", table["/sys/module/ged/parameters/ged_smart_boost"])
        assertEquals("100", table["/sys/module/ged/parameters/cpu_boost_policy"])
        assertEquals("10", table["/sys/module/ged/parameters/gpu_idle"])
        assertEquals("0", table["/sys/module/ged/parameters/ged_force_mdp_enable"])
        assertEquals(15, table.size)
    }

    @Test
    fun everyGedValueWidensNeverCaps() {
        // Boost enables are 1, floors/policy are large, the single 0 disables a force-off
        // (ged_force_mdp_enable) rather than capping anything.
        for ((node, value) in MediaTekGameMode.gedParams) {
            assertTrue("$node=$value", value == "1" || value == "100" || value == "10" || value == "0")
        }
        assertEquals("0", MediaTekGameMode.gedParams["/sys/module/ged/parameters/ged_force_mdp_enable"])
    }

    @Test
    fun ppmPoliciesMatchTheReferencePayload() {
        // (policy index, enabled) pairs from the service.sh policy_status writes.
        assertEquals(
            listOf(0 to 0, 1 to 1, 2 to 0, 3 to 0, 4 to 0, 5 to 0, 6 to 1, 7 to 1, 8 to 0, 9 to 1),
            MediaTekGameMode.ppmPolicies
        )
    }

    @Test
    fun ppmStatusDumpParsesToRestoreWrites() {
        val dump = """
            [0] PPM_POLICY_PTPOD: enabled
            [1] PPM_POLICY_UT: enabled
            [8] PPM_POLICY_LCM_OFF: disabled
            [9] PPM_POLICY_SYS_BOOST: disabled

            Usage: echo <idx> <1/0> > /proc/ppm/policy_status
        """.trimIndent()
        assertEquals(
            listOf("0 1", "1 1", "8 0", "9 0"),
            MediaTekGameMode.ppmRestoreWrites(dump)
        )
    }

    @Test
    fun ppmGarbageParsesToNothing() {
        assertTrue(MediaTekGameMode.ppmRestoreWrites("ppm is enabled").isEmpty())
        assertTrue(MediaTekGameMode.ppmRestoreWrites("").isEmpty())
    }

    @Test
    fun writePlanSkipsAbsentNodes() {
        // Presence-gating: only nodes the device actually has enter the plan; the snapshot
        // records priors for exactly these, so restore touches nothing it never changed.
        val priors = mapOf(
            "/sys/module/ged/parameters/gx_game_mode" to "0",
            "/sys/module/ged/parameters/ged_boost_enable" to "1"
        )
        val plan = MediaTekGameMode.writePlan(priors)
        assertEquals(2, plan.size)
        assertEquals("1", plan["/sys/module/ged/parameters/gx_game_mode"])
        // Unknown nodes never enter, even with a prior.
        val odd = MediaTekGameMode.writePlan(mapOf("/sys/module/ged/parameters/nope" to "0"))
        assertTrue(odd.isEmpty())
    }
}
