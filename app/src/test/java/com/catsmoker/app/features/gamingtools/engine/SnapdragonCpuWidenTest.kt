package com.catsmoker.app.features.gamingtools.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Snapdragon widen-only CPU channel against HunterX-Reborn-II's
 * `common/service.sh` CPU/cpuset sections (lines 10-45, 128-139, 149-158, read
 * before this test was written) and the POCO X3 NFC node survey (surya, sm6150,
 * Android 15, 2026-10-09).
 *
 * Widen-only, never a cap: a policy target is always a member of that policy's
 * own `scaling_available_frequencies` and strictly above its current
 * `scaling_max_freq`; foreign-SoC constants (notably the reference's 2803200 /
 * 1766400, written for a different chip) can never be emitted. cpuset writes
 * only ever add CPUs, never remove any. Nodes the device does not have
 * (msm_performance, core_ctl, cpu_boost, stune, game/gamelite cpusets — all
 * absent on surya) never enter a plan.
 */
class SnapdragonCpuWidenTest {

    // POCO X3 NFC (surya, sm6150): big policy capped below its hardware max.
    private val pocoPolicy6 = SnapdragonCpuWiden.PolicyState(
        currentMaxKhz = 2169600L,
        availableKhz = listOf(
            300000L, 652800L, 806400L, 979200L, 1094400L, 1209600L, 1324800L,
            1555200L, 1708800L, 1843200L, 1939200L, 2169600L, 2208000L, 2304000L
        )
    )

    // POCO X3 NFC: little policy already at its hardware max — nothing to widen.
    private val pocoPolicy0 = SnapdragonCpuWiden.PolicyState(
        currentMaxKhz = 1804800L,
        availableKhz = listOf(
            300000L, 576000L, 768000L, 1017600L, 1248000L, 1324800L,
            1497600L, 1612800L, 1708800L, 1804800L
        )
    )

    @Test
    fun cappedPolicyWidensToItsOwnHardwareMax() {
        assertEquals(2304000L, SnapdragonCpuWiden.policyMaxTarget(pocoPolicy6))
    }

    @Test
    fun policyAlreadyAtMaxHasNoTarget() {
        assertNull(SnapdragonCpuWiden.policyMaxTarget(pocoPolicy0))
    }

    @Test
    fun targetIsAlwaysAMemberOfThePolicysOwnAvailableList() {
        // The reference's 2803200/1766400 are foreign-SoC constants; no input may
        // ever produce a value outside the policy's own available frequencies.
        val target = SnapdragonCpuWiden.policyMaxTarget(pocoPolicy6)
        assertTrue(target in pocoPolicy6.availableKhz)
    }

    @Test
    fun emptyOrGarbagePolicyReadingsHaveNoTarget() {
        assertNull(
            SnapdragonCpuWiden.policyMaxTarget(
                SnapdragonCpuWiden.PolicyState(0L, emptyList())
            )
        )
        assertNull(
            SnapdragonCpuWiden.policyMaxTarget(
                SnapdragonCpuWiden.PolicyState(-1L, listOf(300000L))
            )
        )
    }

    @Test
    fun writePlanEmitsOnlyWideningPolicyNodes() {
        val plan = SnapdragonCpuWiden.policyMaxWritePlan(
            mapOf(
                "/sys/devices/system/cpu/cpufreq/policy0" to pocoPolicy0,
                "/sys/devices/system/cpu/cpufreq/policy6" to pocoPolicy6
            )
        )
        // policy0 is already at max: no write. policy6 widens to its hardware max.
        assertEquals(1, plan.size)
        assertEquals(
            "2304000",
            plan["/sys/devices/system/cpu/cpufreq/policy6/scaling_max_freq"]
        )
    }

    @Test
    fun cpusetUnionAddsOnlyMissingCpus() {
        // surya foreground reads 0-5,7 (cpu6 excluded by the vendor).
        assertEquals("0-7", SnapdragonCpuWiden.widenCpuSet("0-5,7", setOf(6)))
    }

    @Test
    fun cpusetAlreadyWideHasNoWrite() {
        // surya top-app reads 0-7: already the widest set, so no write.
        assertNull(SnapdragonCpuWiden.widenCpuSet("0-7", setOf(6)))
    }

    @Test
    fun cpusetNeverNarrows() {
        // Extra CPUs outside the current set join it; nothing listed is dropped,
        // so a reference-style narrowing write (e.g. foreground to 2-3,6-7) is
        // inexpressible: widening 2-3,6-7 can only stay or grow.
        assertEquals("2-7", SnapdragonCpuWiden.widenCpuSet("2-3,6-7", setOf(4, 5)))
        assertNull(SnapdragonCpuWiden.widenCpuSet("2-3,6-7", setOf(2, 7)))
    }

    @Test
    fun cpusetGarbageParsesToNoWrite() {
        assertNull(SnapdragonCpuWiden.widenCpuSet("", setOf(0)))
        assertNull(SnapdragonCpuWiden.widenCpuSet("all", setOf(0)))
        assertNull(SnapdragonCpuWiden.widenCpuSet("0-7", emptySet()))
    }

    @Test
    fun candidatesCoverOnlyGameCpusets() {
        // background / system-background widening would boost background work
        // against the performance goal, so they are not candidates even with a
        // readable prior; stune / game / gamelite nodes do not exist on modern
        // cgroupv2 kernels and are not named either.
        assertEquals(
            listOf("/dev/cpuset/top-app/cpus", "/dev/cpuset/foreground/cpus"),
            SnapdragonCpuWiden.CPUSET_CANDIDATES
        )
    }

    @Test
    fun cpusetWritePlanWidensOnlyKnownCandidates() {
        // surya priors: top-app already widest (no write), foreground missing
        // cpu6 (widens to 0-7); a background prior is ignored even when present.
        val plan = SnapdragonCpuWiden.cpusetWritePlan(
            priors = mapOf(
                "/dev/cpuset/top-app/cpus" to "0-7",
                "/dev/cpuset/foreground/cpus" to "0-5,7",
                "/dev/cpuset/background/cpus" to "4-5"
            ),
            fullSet = setOf(0, 1, 2, 3, 4, 5, 6, 7)
        )
        assertEquals(mapOf("/dev/cpuset/foreground/cpus" to "0-7"), plan)
    }

    @Test
    fun cpusetWritePlanSkipsUnparseablePriors() {
        val plan = SnapdragonCpuWiden.cpusetWritePlan(
            priors = mapOf("/dev/cpuset/top-app/cpus" to "all"),
            fullSet = setOf(0, 1, 2, 3, 4, 5, 6, 7)
        )
        assertTrue(plan.isEmpty())
    }
    @Test
    fun cpusetWritePlanWithEmptyFullSetWritesNothing() {
        // Unreadable `present` means the full set is unknown: widening against
        // a guessed set would risk narrowing, so the plan stays empty while the
        // policy-max half still applies independently.
        val plan = SnapdragonCpuWiden.cpusetWritePlan(
            priors = mapOf("/dev/cpuset/foreground/cpus" to "0-5,7"),
            fullSet = emptySet()
        )
        assertTrue(plan.isEmpty())
    }

    @Test
    fun restoreValuesWithCommasAreSafe() {
        // Device proof (POCO X3 NFC, 2026-10-10): the revert silently skipped
        // the foreground prior `0-5,7` because the shared shell gate rejects
        // commas — while the widened `0-7` had applied fine. cpuset bodies
        // need commas (and only commas beyond the shared alphabet), so restore
        // priors must accept them; anything with real shell metacharacters
        // stays refused.
        assertTrue(SnapdragonCpuWiden.isSafeNodeValue("0-5,7"))
        assertTrue(SnapdragonCpuWiden.isSafeNodeValue("0-7"))
        assertTrue(SnapdragonCpuWiden.isSafeNodeValue("2304000"))
        // The per-CPU `cpu:freq` priors of the boost nodes (`msm_performance/cpu_max_freq`,
        // `cpu_boost/input_boost_freq`) carry colons — also inert to `sh` and therefore accepted.
        assertTrue(SnapdragonCpuWiden.isSafeNodeValue("0:0 4:1804800"))
        assertFalse(SnapdragonCpuWiden.isSafeNodeValue(""))
        assertFalse(SnapdragonCpuWiden.isSafeNodeValue("   "))
        assertFalse(SnapdragonCpuWiden.isSafeNodeValue("0-7;reboot"))
        assertFalse(SnapdragonCpuWiden.isSafeNodeValue("`reboot`"))
        assertFalse(SnapdragonCpuWiden.isSafeNodeValue("\$x"))
        assertFalse(SnapdragonCpuWiden.isSafeNodeValue("a|b"))
        assertFalse(SnapdragonCpuWiden.isSafeNodeValue("0:0 > /sys/power/state"))
    }
}
