package com.catsmoker.app.features.spoofdevice.root

/**
 * The emulator-artifact file predicate (M116), ported from the reference's
 * `EmulatorDetectionHooks` (`reference/spoofdevice/example-1/.../hooks/EmulatorDetectionHooks.java`,
 * read in full): the same exact path list and the same `goldfish`/`ranchu`/`vbox`/`qemu`
 * keywords, matched case-insensitively.
 *
 * One deliberate bound the reference does not have: keyword matching applies only under the
 * system subtrees ([SCAN_ROOTS]). The reference's bare substring test would also hide a user
 * file such as `/sdcard/notes/vbox.txt` from the target; the exact list is unaffected either way
 * (every entry already lives under those roots), so the visible behavior on real emulator
 * artifacts is identical.
 *
 * What this is not: a property-layer concealment. The reference's `ro.kernel.qemu=0`-style
 * property zeros are deliberately not ported — a real device has no such keys to correct, and
 * *adding* `=0` keys is itself emulator-shaped. Only Java `File` probes are covered; native
 * `open()`/`stat()` calls bypass this entirely, and a filtered directory listing is observable,
 * so the feature is an opt-in for emulator-like setups, not a stealth layer.
 */
object EmulatorArtifacts {

    /** Reference list, verbatim. */
    val EXACT_PATHS: Set<String> = setOf(
        "/dev/qemu_pipe",
        "/dev/goldfish_pipe",
        "/sys/qemu_trace",
        "/system/lib/libc_malloc_debug_qemu.so",
        "/system/lib64/libc_malloc_debug_qemu.so",
        "/sys/devices/virtual/misc/goldfish_pipe",
        "/sys/devices/virtual/misc/goldfish_sync"
    )

    /** Reference keywords, matched anywhere in a bounded path. */
    val KEYWORDS: List<String> = listOf("goldfish", "ranchu", "vbox", "qemu")

    /** Subtrees keyword matching is bounded to (see the class KDoc). */
    val SCAN_ROOTS: List<String> = listOf(
        "/dev", "/proc", "/sys", "/system", "/vendor", "/product", "/odm"
    )

    /** True when a target's `File` probe for [path] must answer "absent". */
    fun isHidden(path: String): Boolean {
        if (path.isEmpty()) return false
        val lower = path.lowercase()
        if (lower in EXACT_PATHS) return true
        if (!underScanRoot(lower)) return false
        return KEYWORDS.any(lower::contains)
    }

    private fun underScanRoot(lowerPath: String): Boolean =
        SCAN_ROOTS.any { lowerPath == it || lowerPath.startsWith("$it/") }
}
