package com.catsmoker.app.shared.data.model

object LSPosedConfig {
    const val PREFS_NAME = "lsposed_prefs"
    const val KEY_ENABLED = "lsposed_enabled"
    const val KEY_TARGET_PACKAGES = "lsposed_target_packages"
    const val KEY_DEVICE_PROPS = "lsposed_device_props"
    const val KEY_MAGISK_PROPS = "magisk_system_props"
    const val KEY_GLOBAL_ENABLED = "catsmoker_lsposed_enabled"
    const val KEY_GLOBAL_TARGET_PACKAGES_B64 = "catsmoker_lsposed_target_packages_b64"
    const val KEY_GLOBAL_DEVICE_PROPS_B64 = "catsmoker_lsposed_device_props_b64"

    val DEFAULT_TARGET_PACKAGES: Set<String> = setOf(
        "com.cpuid.cpu_z", "com.activision.callofduty.shooter", "com.tencent.ig", "com.pubg.imobile"
    )

    val DEFAULT_DEVICE_PROPS: Map<String, String> = mapOf(
        "MANUFACTURER" to "OnePlus",
        "MODEL" to "OPD2415",
        "BRAND" to "OnePlus",
        "PRODUCT" to "OPD2415",
        "DEVICE" to "OnePlusPad3"
    )

    fun parseTargetPackages(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        return raw.split("\n", ",").asSequence().map { it.trim() }.filter { it.isNotEmpty() }.toCollection(LinkedHashSet())
    }

    fun parseDeviceProps(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        raw.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach
            val idx = trimmed.indexOf('=')
            if (idx <= 0 || idx == trimmed.length - 1) return@forEach
            val key = trimmed.substring(0, idx).trim()
            val value = trimmed.substring(idx + 1).trim()
            if (key.isNotEmpty() && value.isNotEmpty()) result[key] = value
        }
        return result
    }
}
