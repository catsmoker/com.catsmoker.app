package com.catsmoker.app.features.spoofdevice.root

import com.catsmoker.app.shared.data.model.LSPosedConfig

/**
 * User-declared hardware facts (M117): the reading helpers and the MB→bytes/kB conversions the
 * hooks and the editor's units share, in one place so a hook and a test can never disagree
 * about the math.
 *
 * Adapted from the reference's `HardwareHooks`
 * (`reference/spoofdevice/example-1/.../hooks/HardwareHooks.java`, read in full): the same
 * covered surfaces — `Runtime.availableProcessors()`, `ActivityManager.getMemoryInfo` totalMem,
 * `getMemoryClass`/`getLargeMemoryClass`, and the `RandomAccessFile.readLine` rewrite of
 * `/proc/meminfo`'s `MemTotal:` line.
 *
 * Deliberately not ported: the reference's debug/`availMem` rescaling and any `cpuinfo` content
 * work. Untouched surfaces stay real — `Runtime.maxMemory`, `availMem`, `Debug.*`, native
 * `getauxval`/`sysconf`, and the actual `/proc/cpuinfo` text are not covered, and a careful
 * reader can see the values disagree. Values are MB as the editor labels them; 0 (parse failure
 * included) means "off", so a typo can only under-publish, never publish nonsense.
 */
object SpoofHardwareFacts {

    fun cpuCores(props: Map<String, String>): Int? = positive(props[LSPosedConfig.KEY_HW_CPU_CORES])

    fun ramMb(props: Map<String, String>): Int? = positive(props[LSPosedConfig.KEY_HW_RAM_MB])

    fun heapMb(props: Map<String, String>): Int? = positive(props[LSPosedConfig.KEY_HW_HEAP_MB])

    fun largeHeapMb(props: Map<String, String>): Int? = positive(props[LSPosedConfig.KEY_HW_LARGE_HEAP_MB])

    fun ramBytes(ramMb: Int): Long = ramMb.toLong() * 1024L * 1024L

    fun ramKb(ramMb: Int): Long = ramMb.toLong() * 1024L

    /**
     * Rewrites the `MemTotal:` line of a `/proc/meminfo` read to [totalKb].
     *
     * Any other line — and a `MemTotal:` line with no digits to replace — is returned unchanged,
     * so the hook can assign the result unconditionally. The device's own spacing and `kB` unit
     * are preserved.
     */
    fun rewriteMemTotal(line: String, totalKb: Long): String {
        if (!line.startsWith(MEM_TOTAL_LABEL)) return line
        val digits = MEM_TOTAL_DIGITS.find(line)?.groups?.get(2) ?: return line
        return line.replaceRange(digits.range, totalKb.toString())
    }

    /** The editor's blank is 0 — parse strictly; anything unusable or non-positive is "off". */
    private fun positive(raw: String?): Int? = raw?.toIntOrNull()?.takeIf { it > 0 }

    private const val MEM_TOTAL_LABEL = "MemTotal:"

    private val MEM_TOTAL_DIGITS = Regex("^(MemTotal:\\s+)(\\d+)")
}
