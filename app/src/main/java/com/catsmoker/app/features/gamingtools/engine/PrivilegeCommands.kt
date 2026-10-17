package com.catsmoker.app.features.gamingtools.engine

/**
 * Centralized privileged command construction (consolidation C5).
 *
 * Centralises command *construction* only — sequencing, snapshots, verification
 * and recovery stay with each caller in GamingEngine. Every string here matches
 * the command the engine already sent; this only gives the literals one home so
 * a typo cannot make two callers diverge.
 */
object PrivilegeCommands {

    /** `cmd power set-fixed-performance-mode-enabled`. */
    fun fixedPerformanceArgs(enabled: Boolean): Array<String> =
        arrayOf("cmd", "power", "set-fixed-performance-mode-enabled", if (enabled) "true" else "false")

    /** Memory-reclaim trio shared by Gaming Mode prep, RAM reclaim and fixed-perf entry. */
    fun memoryReclaimCommands(): List<String> = listOf(
        "pm trim-caches 4G",
        "am compact background",
        "am kill-all"
    )

    const val PINNER_REPIN = "cmd pinner repin /system/framework/framework.jar"
    const val DEVICE_IDLE_FORCE = "cmd deviceidle force-idle"
    const val DEVICE_IDLE_UNFORCE = "cmd deviceidle unforce"
    const val FIXED_PERF_ON = "cmd power set-fixed-performance-mode-enabled true"
    const val FIXED_PERF_OFF = "cmd power set-fixed-performance-mode-enabled false"
    const val KILL_ALL = "am kill-all"
    const val TRIM_CACHES = "pm trim-caches 4G"
    const val COMPACT_BACKGROUND = "am compact background"

    fun forceStopArgs(pkg: String): Array<String> = arrayOf("am", "force-stop", pkg)

    fun suspendArgs(pkg: String): Array<String> = arrayOf("pm", "suspend", "--user", "0", pkg)

    fun unsuspendArgs(pkg: String): Array<String> = arrayOf("pm", "unsuspend", "--user", "0", pkg)

    /**
     * `cmd appops get <pkg> RUN_IN_BACKGROUND` — the prior-mode read that snapshots one
     * package's background op before Gaming Mode writes it (M42).
     */
    fun appOpsGetArgs(pkg: String): Array<String> =
        arrayOf("cmd", "appops", "get", pkg, AppOpsVerdict.OP_RUN_IN_BACKGROUND)

    /**
     * `cmd appops set <pkg> RUN_IN_BACKGROUND <mode>` — [mode] is a word validated through
     * [AppOpsVerdict], so a restore can only ever write a mode the device itself reported.
     */
    fun appOpsSetArgs(pkg: String, mode: String): Array<String> =
        arrayOf("cmd", "appops", "set", pkg, AppOpsVerdict.OP_RUN_IN_BACKGROUND, mode)

    fun deviceIdleWhitelistAdd(pkg: String): String = "cmd deviceidle whitelist +$pkg"

    fun deviceIdleWhitelistRemove(pkg: String): String = "cmd deviceidle whitelist -$pkg"

    fun netpolicyWhitelistAdd(uid: Int): String =
        "cmd netpolicy add restrict-background-whitelist $uid"

    fun netpolicyWhitelistRemove(uid: Int): String =
        "cmd netpolicy remove restrict-background-whitelist $uid"
}
