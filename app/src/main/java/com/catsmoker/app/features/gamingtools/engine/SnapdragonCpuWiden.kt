package com.catsmoker.app.features.gamingtools.engine

/**
 * Snapdragon widen-only CPU channel: the `scaling_max_freq` + cpuset halves of
 * HunterX-Reborn-II's `common/service.sh` CPU/cpuset sections (lines 10-45,
 * 128-139, 149-158, read before this file was written), as data.
 *
 * Widen-only, never a cap: a policy target is always the maximum of that
 * policy's own `scaling_available_frequencies` and is emitted only when it is
 * strictly above the policy's current `scaling_max_freq` — so the reference's
 * foreign-SoC constants (2803200/1766400, written for a different chip) are
 * inexpressible here, and a policy already at its hardware max (POCO X3 NFC
 * policy0: 1804800 = avail max) yields no write. cpuset writes only ever add
 * CPUs to the set the device already has (POCO X3 NFC top-app already reads
 * 0-7, so it yields no write); the reference's narrowing assignments
 * (foreground to 2-3,6-7, background to 2-3) are inexpressible by construction.
 *
 * Nodes the device does not have never enter a plan: the reference's
 * `msm_performance`, `core_ctl`, `cpu_boost` and `stune` halves have no target
 * on devices without those nodes (all four absent on the POCO X3 NFC survey,
 * 2026-10-09), and the `game`/`gamelite` cpusets are likewise absent there.
 * Behavior (presence gates, snapshot priors in
 * `GamingOptimizationSnapshot.snapdragonCpuNodes`, read-back, restore) lives in
 * [GamingEngine], which consults only nodes the device actually has.
 */
object SnapdragonCpuWiden {

    /** A cpufreq policy's measured ceiling and the frequencies its kernel allows. */
    data class PolicyState(
        val currentMaxKhz: Long,
        val availableKhz: List<Long>
    )

    const val SCALING_MAX_FREQ = "scaling_max_freq"
    const val SCALING_AVAILABLE_FREQUENCIES = "scaling_available_frequencies"

    /**
     * `related_cpus` of a policy directory: the CPU ids the kernel itself reports as that policy's
     * cluster. The only source [GamingEngine] uses for a cluster's real core count — both the
     * `core_ctl` raise target and the per-CPU `cpu:freq` raise targets derive from it, never from a
     * reference table.
     */
    const val RELATED_CPUS = "related_cpus"

    /**
     * The only cpuset nodes Gaming Mode ever widens: the game-visible sets.
     * `background` / `system-background` / `restricted` are deliberately not
     * candidates — widening them would invite background work onto the big
     * cores, against the performance goal — and the reference's `game` /
     * `gamelite` nodes plus every `stune` knob do not exist on modern
     * cgroupv2 kernels, so they are not named either.
     */
    val CPUSET_CANDIDATES: List<String> = listOf(
        "/dev/cpuset/top-app/cpus",
        "/dev/cpuset/foreground/cpus"
    )

    /**
     * The widened ceiling for one policy: its own hardware max when that is
     * strictly above its current max, else null (nothing to widen).
     */
    fun policyMaxTarget(state: PolicyState): Long? {
        val hardwareMax = state.availableKhz.maxOrNull() ?: return null
        if (state.currentMaxKhz <= 0L || hardwareMax <= 0L) return null
        return if (hardwareMax > state.currentMaxKhz) hardwareMax else null
    }

    /**
     * `scaling_max_freq` writes for policies with headroom: policy dir →
     * target kHz. Policies already at max contribute nothing, so restore
     * touches nothing it never changed.
     */
    fun policyMaxWritePlan(policies: Map<String, PolicyState>): Map<String, String> {
        val plan = linkedMapOf<String, String>()
        for ((policyDir, state) in policies) {
            val target = policyMaxTarget(state) ?: continue
            plan["$policyDir/$SCALING_MAX_FREQ"] = target.toString()
        }
        return plan
    }

    /**
     * Parses a cpuset `cpus` body (`0-7`, `0-5,7`) into CPU ids. Garbage parses
     * to nothing — never a fabricated write.
     */
    fun parseCpuSet(text: String): Set<Int> {
        val cpus = sortedSetOf<Int>()
        for (part in text.split(",")) {
            val range = part.trim().split("-")
            val numbers = range.map { it.trim().toIntOrNull() ?: return emptySet() }
            if (numbers.any { it < 0 }) return emptySet()
            when (numbers.size) {
                1 -> cpus.add(numbers[0])
                2 -> {
                    if (numbers[1] < numbers[0]) return emptySet()
                    for (cpu in numbers[0]..numbers[1]) cpus.add(cpu)
                }
                else -> return emptySet()
            }
        }
        return cpus.ifEmpty { emptySet() }
    }

    /** Formats CPU ids back into cpuset range bodies (`0-7`). */
    fun formatCpuSet(cpus: Set<Int>): String {
        if (cpus.isEmpty()) return ""
        val sorted = cpus.sorted()
        val parts = mutableListOf<String>()
        var start = sorted[0]
        var prev = sorted[0]
        for (cpu in sorted.drop(1)) {
            if (cpu == prev + 1) {
                prev = cpu
            } else {
                parts.add(if (start == prev) "$start" else "$start-$prev")
                start = cpu
                prev = cpu
            }
        }
        parts.add(if (start == prev) "$start" else "$start-$prev")
        return parts.joinToString(",")
    }

    /**
     * Whether [value] is safe to embed in a `sh -c 'echo … > node'` restore
     * script. cpuset bodies need commas (`0-5,7`) and the per-CPU boost lists
     * need colons (`0:0 4:1804800`) beyond the shared letters/digits/space/
     * dot/dash alphabet; anything with real shell metacharacters — including
     * the quoting, substitution and redirection characters — is refused
     * rather than quoted-and-hoped.
     */
    fun isSafeNodeValue(value: String): Boolean =
        value.isNotBlank() && value.all { it.isLetterOrDigit() || it in "._-,: " }

    /**
     * The widened cpuset body: the current set plus [extra], or null when that
     * changes nothing or the current body does not parse. Only ever adds —
     * narrowing is inexpressible.
     */
    fun widenCpuSet(current: String, extra: Set<Int>): String? {
        if (extra.isEmpty()) return null
        val have = parseCpuSet(current)
        if (have.isEmpty()) return null
        val widened = have + extra
        return if (widened == have) null else formatCpuSet(widened)
    }

    /**
     * cpuset writes for the game-visible sets: candidate node → widened body.
     * Only priors for [CPUSET_CANDIDATES] enter the plan — a readable
     * `background` prior is ignored even when supplied — and only when the
     * widening actually adds CPUs. An unknown [fullSet] (unreadable `present`)
     * writes nothing: widening against a guessed set could narrow.
     */
    fun cpusetWritePlan(priors: Map<String, String>, fullSet: Set<Int>): Map<String, String> {
        val plan = linkedMapOf<String, String>()
        if (fullSet.isEmpty()) return plan
        for (node in CPUSET_CANDIDATES) {
            val prior = priors[node] ?: continue
            widenCpuSet(prior, fullSet)?.let { plan[node] = it }
        }
        return plan
    }
}
