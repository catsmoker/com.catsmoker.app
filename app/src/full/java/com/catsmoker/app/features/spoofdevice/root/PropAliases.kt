package com.catsmoker.app.features.spoofdevice.root

/**
 * The property-key spellings a profile answers beyond the literal keys it carries.
 *
 * Two readers share this vocabulary: the module's Java lookup
 * ([LSPosedModule.lookupProp], which every `SystemProperties`, Build-field and `getprop`
 * subprocess read goes through) and the native hook channel ([NativeSpoofChannel]), which
 * serializes the same rules into its payload. Answering on one side only is exactly the
 * disagreement those channels exist to prevent, so the lists are authored here once.
 *
 * Both kinds exist because apps read keys under names a rendered profile never spells out.
 * Neither invents a value: a direct alias answers only when the profile answers its canonical
 * key, and a suffix rule only when the canonical key is in the reader's table — a miss leaves
 * the device's own value untouched.
 */
internal object PropAliases {

    /**
     * Keys apps read that a rendered profile never spells out.
     *
     * The renderer emits canonical `ro.product.*` / `ro.build.*` names, while older readers and
     * the bootloader-mirrored spellings (`ro.boot.*`) ask for these.
     */
    val DIRECT: Map<String, String> = mapOf(
        "ro.build.product" to "ro.product.device",
        "ro.build.device" to "ro.product.device",
        "ro.boot.hardware" to "ro.hardware",
        "ro.boot.serialno" to "ro.serialno",
        "ro.build.version.release_or_codename" to "ro.build.version.release",
        "ro.build.version.release_or_preview_display" to "ro.build.version.release",
        "ro.product.cpu.abi2" to "ro.product.cpu.abi"
    )

    /** One partition/legacy spelling: a key carrying [prefix] and [suffix] answers [canonical]. */
    data class SuffixRule(val prefix: String, val suffix: String, val canonical: String)

    /**
     * Maps an OEM partition variant such as `ro.product.vendor.model` onto its canonical key.
     *
     * Partitioned spellings (`ro.system.*`, `ro.vendor.*`, `ro.odm.*`, `ro.product.vendor.*`)
     * are what device-info and anti-cheat readers actually use on Android 10+, while the profile
     * carries the canonical key. The prefix and suffix include their separating dots, so a
     * near-miss key (`ro.product.foo-model`) never matches. Order is the resolution order (first
     * match wins) — it only matters if a key ever matches two rules.
     */
    val SUFFIX: List<SuffixRule> = listOf(
        SuffixRule("ro.", ".build.fingerprint", "ro.build.fingerprint"),
        SuffixRule("ro.", ".build.version.incremental", "ro.build.version.incremental"),
        SuffixRule("ro.", ".build.version.release", "ro.build.version.release"),
        SuffixRule("ro.", ".build.version.sdk", "ro.build.version.sdk"),
        SuffixRule("ro.", ".build.version.security_patch", "ro.build.version.security_patch"),
        SuffixRule("ro.", ".build.id", "ro.build.id"),
        SuffixRule("ro.product.", ".manufacturer", "ro.product.manufacturer"),
        SuffixRule("ro.product.", ".brand", "ro.product.brand"),
        SuffixRule("ro.product.", ".model", "ro.product.model"),
        SuffixRule("ro.product.", ".device", "ro.product.device"),
        SuffixRule("ro.product.", ".name", "ro.product.name")
    )

    /** Canonical key [key] answers through, or null when no rule covers it. */
    fun suffixTarget(key: String): String? =
        SUFFIX.firstOrNull { key.startsWith(it.prefix) && key.endsWith(it.suffix) }?.canonical

    /**
     * [props] plus one entry per direct alias whose canonical key the profile answers.
     *
     * A key the profile carries itself always wins — its own spelling is the authored one. The
     * suffix rules need no entries here: [NativeSpoofChannel] sends them as rules and the Java
     * lookup calls [suffixTarget], so one list serves both readers.
     */
    fun expandAliases(props: Map<String, String>): Map<String, String> {
        val table = LinkedHashMap(props)
        for ((alias, canonical) in DIRECT) {
            if (!table.containsKey(alias)) table[canonical]?.let { table[alias] = it }
        }
        return table
    }
}
