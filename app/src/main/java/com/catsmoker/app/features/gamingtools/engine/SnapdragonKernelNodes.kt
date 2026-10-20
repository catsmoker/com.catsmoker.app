package com.catsmoker.app.features.gamingtools.engine

/**
 * The kernel-node halves of HunterX-Reborn-II's service payload that [SnapdragonCpuWiden] does not
 * cover: the `schedutil` rate limits and hispeed jump, `msm_performance`'s touch boost and declared
 * CPU ceilings, `core_ctl`'s hotplug floor/ceiling and `cpu_boost`'s input boost
 * (`common/service.sh` lines 10-45 and 115-139, `common/post-fs-data.sh` lines 2-7, read before
 * this file was written), as data and raise-only rules.
 *
 * Three rules hold for every family, and they are what makes the reference's payload portable:
 *  - **Raise-only, enable-only, never narrow.** No rule lowers a value, disables a boost or removes
 *    CPUs from a set. The reference's own numbers are inexpressible by construction — 2803200 /
 *    1766400 were written for a different SoC, and `input_boost_freq 0:1400000` is a hand-picked
 *    point — because every target derives from the device's own accounting: a cpufreq policy's
 *    `scaling_available_frequencies` (its hardware max), its `related_cpus` (a cluster's real core
 *    count) or a value the node itself declares.
 *  - **Ambiguity means no write.** `hispeed_freq` is never enabled against an ambiguous
 *    `hispeed_load`: 0 means "jump disabled" on some kernels and "always jump" on others, so the
 *    rule requires a declared 1..100 threshold. `msm_performance`'s `cpu_max_freq = 0` means "no cap
 *    declared", so only a *declared* ceiling is raised, never invented.
 *  - **Live thermal and GPU controls stay out.** Nothing here names a `thermal_*` node, a KGSL
 *    `pwrlevel`/`governor` node or a GPU clock. The reference's two GPU governor writes target the
 *    same node through two symlinks (`/sys/devices/soc/…kgsl-3d0/devfreq/…/governor` then
 *    `/sys/class/kgsl/kgsl-3d0/devfreq/governor`), and the later one — `msm-adreno-tz` — is the
 *    vendor's own default; KGSL's `min_pwrlevel`/`max_pwrlevel` are a live thermal clamp. Both are
 *    therefore surveyed and deliberately not ported (see `docs/GAMING_MODE.md`); the ban is pinned
 *    by `SnapdragonKernelNodesTest`.
 *
 * Nodes that are *read* to gate a write but never written: [HISPEED_LOAD] (the device's own boost
 * threshold, 1..100, which a raise would make less eager and a lower would make a hand-picked
 * constant) and [INPUT_BOOST_MS] (a boost window; if the device declares none, the boost never
 * happens and there is nothing to raise). Nodes deliberately not written at all, with the reason:
 * `msm_performance/cpu_min_freq` (a floor with no device-derived target), `core_ctl`'s
 * `enable`/`busy_up_thres`/`busy_down_thres`/`offline_delay_ms`/`task_thres` (hotplug heuristics
 * with no raise direction), `schedutil/down_rate_limit_us` (a raise is an invented constant) and
 * `schedutil/pl` (a mechanism toggle, not a magnitude). Behavior — presence gates, snapshot priors,
 * read-back verification and restore — lives in [GamingEngine].
 */
object SnapdragonKernelNodes {

    /** `schedutil` sub-directory of a cpufreq policy directory. */
    const val SCHEDUTIL_DIR_NAME = "schedutil"

    /** `schedutil/up_rate_limit_us`: the rate limiter on frequency *increases*. */
    const val UP_RATE_LIMIT_US = "up_rate_limit_us"

    /** `schedutil/hispeed_freq`: the frequency jumped to above the hispeed load threshold. */
    const val HISPEED_FREQ = "hispeed_freq"

    /** `schedutil/hispeed_load`: that threshold, read as a gate and never written. */
    const val HISPEED_LOAD = "hispeed_load"

    /** `schedutil/iowait_boost_enable`: whether I/O waits raise the frequency. */
    const val IOWAIT_BOOST_ENABLE = "iowait_boost_enable"

    /** Qualcomm's performance-module parameter directory. */
    const val MSM_PERFORMANCE_PARAMETERS = "/sys/module/msm_performance/parameters"

    /** `msm_performance/parameters/touchboost`: the module's touch boost switch. */
    const val TOUCHBOOST = "touchboost"

    /** `msm_performance/parameters/cpu_max_freq`: per-CPU declared frequency ceilings. */
    const val CPU_MAX_FREQ = "cpu_max_freq"

    /** `core_ctl` sub-directory of a cluster's first CPU (`cpu0/core_ctl`, `cpu4/core_ctl`, …). */
    const val CORE_CTL_DIR_NAME = "core_ctl"

    /** `core_ctl/min_cpus`: the hotplug controller's online floor for the cluster. */
    const val MIN_CPUS = "min_cpus"

    /** `core_ctl/max_cpus`: the most CPUs the controller will keep online. */
    const val MAX_CPUS = "max_cpus"

    /** CAF's CPU-boost module parameter directory. */
    const val CPU_BOOST_PARAMETERS = "/sys/module/cpu_boost/parameters"

    /** `cpu_boost/parameters/input_boost_enabled`: the module's own master switch. */
    const val INPUT_BOOST_ENABLED = "input_boost_enabled"

    /** `cpu_boost/parameters/input_boost_freq`: per-CPU frequencies applied while boosting. */
    const val INPUT_BOOST_FREQ = "input_boost_freq"

    /** `cpu_boost/parameters/input_boost_ms`: how long an input boost lasts — read, never written. */
    const val INPUT_BOOST_MS = "input_boost_ms"

    /**
     * Every file this object can ever write, by file name. The set exists so the deliberate
     * non-writes above can be asserted, not merely intended: [SnapdragonKernelNodesTest] fails if
     * any name here starts naming a GPU, a pwrlevel or a thermal control.
     */
    val writableFileNames: List<String> = listOf(
        UP_RATE_LIMIT_US,
        HISPEED_FREQ,
        IOWAIT_BOOST_ENABLE,
        TOUCHBOOST,
        CPU_MAX_FREQ,
        MIN_CPUS,
        MAX_CPUS,
        INPUT_BOOST_ENABLED,
        INPUT_BOOST_FREQ
    )

    /** Files read only to gate a write. Naming them keeps the read set explicit and testable. */
    val gateFileNames: List<String> = listOf(HISPEED_LOAD, INPUT_BOOST_MS)

    /**
     * The `up_rate_limit_us` target: `0` — the structural minimum, no rate limit on frequency
     * increases, i.e. the fastest ramp the governor can make. Null when the device is already
     * unlimited or the node does not parse as a number (never a guessed write).
     *
     * The reference writes 1000 here, which is *slower* than the 500 the lab device ships: the
     * performance direction for a limiter on the way up is down, so the rule removes it instead of
     * copying a foreign constant.
     */
    fun noUpRateLimit(current: String?): String? {
        val value = current?.trim()?.toLongOrNull() ?: return null
        return if (value > 0L) "0" else null
    }

    /**
     * The `hispeed_freq` target: the policy's own hardware max when that is strictly above the
     * node's current value (`0` = the jump is disabled, so enabling it at the max is the strongest
     * form the device's own table allows), else null.
     *
     * [hispeedLoad] gates the whole rule: a threshold that is absent, unparseable or outside 1..100
     * leaves the jump alone, because `hispeed_load = 0` is read as "disabled" by some kernels and as
     * "always jump" by others — writing a jump frequency into that ambiguity could pin the policy.
     */
    fun hispeedTarget(current: String?, availableKhz: List<Long>, hispeedLoad: String?): String? {
        val threshold = hispeedLoad?.trim()?.toIntOrNull() ?: return null
        if (threshold !in 1..100) return null
        val hardwareMax = availableKhz.maxOrNull() ?: return null
        if (hardwareMax <= 0L) return null
        val now = current?.trim()?.toLongOrNull() ?: return null
        return if (hardwareMax > now) hardwareMax.toString() else null
    }

    /**
     * The target for a switch the reference turns on: `1` when the node declares `0` (off), else
     * null — already on, unreadable or not a 0/1 flag means no write. Used for
     * [TOUCHBOOST], [IOWAIT_BOOST_ENABLE] and [INPUT_BOOST_ENABLED].
     */
    fun enableIfZero(current: String?): String? =
        if (current?.trim()?.toLongOrNull() == 0L) "1" else null

    /**
     * The target for a `core_ctl` floor/ceiling: [clusterSize] — the number of CPUs the kernel
     * itself reports in that cluster (`related_cpus` of the policy owning the core_ctl directory) —
     * when that is strictly above the current value, else null.
     *
     * `max_cpus` raised to the cluster's real core count is the reference's "all cores available"
     * intent expressed with device data (it wrote 4, its own big-cluster size); a value already
     * above the derived count is left where the kernel put it, so the rule can never narrow.
     */
    fun clusterRaise(current: String?, clusterSize: Int): String? {
        if (clusterSize <= 0) return null
        val now = current?.trim()?.toIntOrNull() ?: return null
        return if (clusterSize > now) clusterSize.toString() else null
    }

    /**
     * Parses a per-CPU `cpu:freq` list (`0:0 4:1400000`, whitespace- or comma-separated). Tokens
     * that are not a `cpu:khz` pair of non-negative numbers are skipped — garbage parses to fewer
     * entries, never to a fabricated one.
     */
    fun parseFreqPairs(text: String?): Map<Int, Long> {
        val pairs = linkedMapOf<Int, Long>()
        if (text == null) return pairs
        for (token in text.split(WHITESPACE_OR_COMMA)) {
            val parts = token.split(":")
            if (parts.size != 2) continue
            val cpu = parts[0].trim().toIntOrNull() ?: continue
            val khz = parts[1].trim().toLongOrNull() ?: continue
            if (cpu < 0 || khz < 0L) continue
            pairs[cpu] = khz
        }
        return pairs
    }

    /** Formats [pairs] back into a `cpu:freq` body, ordered by CPU id. */
    fun formatFreqPairs(pairs: Map<Int, Long>): String =
        pairs.toSortedMap().entries.joinToString(" ") { (cpu, khz) -> "$cpu:$khz" }

    /**
     * [CPU_MAX_FREQ] raise plan: each CPU whose entry is a *declared* ceiling (`> 0`) below the
     * hardware max of the policy it belongs to → that policy's max in kHz. Zero entries mean "no
     * cap declared" (nothing to remove) and CPUs/values the device does not report are left alone,
     * so the plan can only ever raise a real limit.
     */
    fun raiseExistingCapPairs(current: String?, hardwareMaxKhz: Map<Int, Long>): Map<Int, Long> {
        val plan = linkedMapOf<Int, Long>()
        for ((cpu, declared) in parseFreqPairs(current)) {
            val hardwareMax = hardwareMaxKhz[cpu] ?: continue
            if (declared > 0L && hardwareMax > declared) plan[cpu] = hardwareMax
        }
        return plan
    }

    /**
     * [INPUT_BOOST_FREQ] raise plan: each CPU whose boost frequency is below the hardware max of its
     * own policy (`0` = that CPU is not boosted at all) → that max. CPUs the device does not report
     * and entries already at or above their policy max are left alone.
     *
     * The engine only consults this when the device declares a boost window
     * ([INPUT_BOOST_MS] > 0): a boost with no duration never happens, so raising frequencies for it
     * would be a write that changes nothing while looking like a success.
     */
    fun raiseBoostPairs(current: String?, hardwareMaxKhz: Map<Int, Long>): Map<Int, Long> {
        val plan = linkedMapOf<Int, Long>()
        for ((cpu, now) in parseFreqPairs(current)) {
            val hardwareMax = hardwareMaxKhz[cpu] ?: continue
            if (hardwareMax > 0L && now < hardwareMax) plan[cpu] = hardwareMax
        }
        return plan
    }

    private val WHITESPACE_OR_COMMA = Regex("""[\s,]+""")
}
