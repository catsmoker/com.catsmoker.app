package com.catsmoker.app.features.gamingtools.engine

/**
 * The per-SoC property vocabulary from BoostX's "Processor Boost Modules"
 * (`reference/file-engineering/BoostX-Max-CPU-Performance/Processor Boost Modules`).
 *
 * The scripts around these names are counter-examples and stay out of the app: the
 * APK burns synthetic CPU load, the `.sh` payloads set `persist.` props with no
 * read-back and "remove" them by writing empty strings or `0`, and the
 * thermal-disable keys (`persist.sys.thermal.*`,
 * `persist.vendor.mtk.thermal.*`, `persist.sys.exynos.thermal.*`) turn the thermal
 * engine off.
 *
 * What survives is the vocabulary, and only as a presence probe. A key the device
 * already carries is evidence a future gated lever could read, snapshot and restore;
 * a key it does not carry is an answer too. **Nothing here writes**, and no key that
 * names a thermal disable appears in the probe at all.
 */
object VendorPerformanceKeys {

    enum class Vendor { KIRIN, EXYNOS, UNISOC, MEDIATEK }

    /** One property the device actually carries; a blank value is present-but-unset. */
    data class Reading(val key: String, val value: String?)

    private val byVendor: Map<Vendor, List<String>> = mapOf(
        Vendor.KIRIN to listOf(
            "persist.sys.huawei.gpu.turbo",
            "persist.sys.hiai.perf.mode",
            "ro.config.hw_op_perf",
            "persist.sys.perf.level",
            "persist.vendor.display.perf.mode"
        ),
        Vendor.EXYNOS to listOf(
            "persist.sys.dvfs.level",
            "ro.config.cpu_level_max",
            "persist.vendor.display.perf.mode"
        ),
        Vendor.UNISOC to listOf(
            "persist.sys.performance.level",
            "ro.vendor.sprd_sys_performance",
            "persist.vendor.display.perf.mode"
        ),
        Vendor.MEDIATEK to listOf(
            "persist.vendor.mtk.ux.tuning.level",
            "persist.vendor.mtk_fpsgo_v3_support",
            "persist.vendor.extreme.display.mode",
            "ro.vendor.mtk_config_max_fg_apps"
        )
    )

    fun candidatesFor(vendor: Vendor): List<String> = byVendor[vendor].orEmpty()

    /** Every candidate across vendors, de-duplicated — the probe's full read list. */
    val boostKeys: List<String> = byVendor.values.flatten().distinct()

    /**
     * Filters a full `getprop` dump down to the catalog keys the device carries, in
     * catalog order. Only a key that appears in the dump is returned; a key the ROM
     * does not define is absent, never reported as empty.
     */
    fun readPresent(getPropOutput: String, keys: List<String> = boostKeys): List<Reading> {
        val props = parseGetProp(getPropOutput)
        return keys.mapNotNull { key ->
            props[key]?.let { Reading(key, it.ifBlank { null }) }
        }
    }

    /** Parses the `[key]: [value]` lines `getprop` prints; unparseable lines are skipped. */
    fun parseGetProp(output: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        for (line in output.lineSequence()) {
            val match = PROP_LINE.matchEntire(line.trim()) ?: continue
            map[match.groupValues[1]] = match.groupValues[2]
        }
        return map
    }

    private val PROP_LINE = Regex("""\[([^\]]+)]:\s*\[(.*)]""")
}
