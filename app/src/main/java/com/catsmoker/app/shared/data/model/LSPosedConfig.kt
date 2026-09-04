package com.catsmoker.app.shared.data.model

/**
 * Contract shared by the app UI and the Xposed module that runs inside other apps' processes.
 *
 * The single vocabulary on both sides is system-property names — exactly what
 * [com.catsmoker.app.shared.data.repository.SpoofRepository.renderConfig] emits for Magisk — so
 * `Build.MODEL` is derived from `ro.product.model` rather than duplicated under a second name.
 */
object LSPosedConfig {
    const val PREFS_NAME = "lsposed_prefs"
    const val KEY_ENABLED = "lsposed_enabled"
    const val KEY_TARGET_PACKAGES = "lsposed_target_packages"
    const val KEY_DEVICE_PROPS = "lsposed_device_props"
    const val KEY_MAGISK_PROPS = "magisk_system_props"
    const val KEY_GLOBAL_ENABLED = "catsmoker_lsposed_enabled"
    const val KEY_GLOBAL_TARGET_PACKAGES_B64 = "catsmoker_lsposed_target_packages_b64"
    const val KEY_GLOBAL_DEVICE_PROPS_B64 = "catsmoker_lsposed_device_props_b64"

    /**
     * Every assigned package's profile in one document, base64'd into Settings.Global.
     *
     * Settings.Global is readable from any process, which the config ContentProvider is not:
     * package-visibility filtering on API 30+ hides our authority from apps that never declared
     * a `<queries>` entry for it, and those are precisely the apps being spoofed.
     */
    const val KEY_GLOBAL_PROFILES_B64 = "catsmoker_lsposed_profiles_b64"

    /** Broadcast that tells already-running targets to re-read their profile. */
    const val ACTION_CONFIG_CHANGED = "com.catsmoker.app.action.CONFIG_CHANGED"

    /** Key inside a rendered profile listing packages the user opted out of spoofing. */
    const val KEY_SAFE_MODE_PACKAGES = "safe_mode.packages"

    /**
     * Key inside a rendered profile saying whether the profile's screen metrics should be applied.
     *
     * Named after the reference project's `ConfigManager.KEY_APPLY_SCREEN_METRICS` so both sides of
     * the config file speak the same vocabulary.
     */
    const val KEY_APPLY_SCREEN_METRICS = "device.apply_screen_metrics"

    /** Accepts the `1` / `true` spellings a rendered or hand-edited config can carry. */
    fun isFlagEnabled(value: String?): Boolean =
        value == "1" || value.equals("true", ignoreCase = true)

    /**
     * Prefixes that mark a rendered-profile entry as a real Android system property.
     *
     * A rendered profile is the vocabulary for every delivery channel, so it also carries keys only
     * this app's hooks understand — `device.imei`, `screen.width`, `safe_mode.packages`. Those have
     * no business reaching a channel that writes the real property store or a `getprop` dump, where
     * they are a giveaway rather than a disguise.
     *
     * This list lives here, not beside either consumer, because two channels must filter by the
     * same one. [com.catsmoker.app.features.spoofdevice.root.GetPropInterceptor] held it privately
     * and filtered correctly while the exported Magisk module wrote `renderConfig` output straight
     * into `system.prop` — so one identical profile was clean in-process and self-reporting once
     * flashed.
     */
    val SYSTEM_PROPERTY_PREFIXES = listOf(
        "ro.", "persist.", "gsm.", "net.", "dalvik.", "sys.", "vendor.", "debug."
    )

    /** True when [key] names a real system property rather than one of our own profile keys. */
    fun isSystemProperty(key: String): Boolean = SYSTEM_PROPERTY_PREFIXES.any(key::startsWith)

    /**
     * The real system properties of a rendered profile, in render order.
     *
     * Goes through [parseDeviceProps] so a channel publishing to the property store applies exactly
     * the comment, blank-line and malformed-line handling the hooks apply when reading the same
     * text back.
     */
    fun filterToSystemProperties(rendered: String?): Map<String, String> =
        parseDeviceProps(rendered).filterKeys(::isSystemProperty)

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
            // A rendered profile's own comments carry no '=' and so were already dropped, but a
            // hand-edited config commenting a property out ("# ro.product.model=Pixel") would
            // otherwise be read back as a property literally named "# ro.product.model".
            if (trimmed.startsWith('#')) return@forEach
            val idx = trimmed.indexOf('=')
            if (idx <= 0 || idx == trimmed.length - 1) return@forEach
            val key = trimmed.substring(0, idx).trim()
            val value = trimmed.substring(idx + 1).trim()
            if (key.isNotEmpty() && value.isNotEmpty()) result[key] = value
        }
        return result
    }

    /**
     * Joins per-package rendered profiles into one document with `[package]` section headers.
     * Rendered profiles only ever contain `key=value` and `#` lines, so `[` cannot collide.
     */
    fun renderSections(configs: Map<String, String>): String {
        val sb = StringBuilder()
        for ((packageName, config) in configs) {
            if (packageName.isBlank() || config.isBlank()) continue
            sb.append('[').append(packageName).append("]\n")
            sb.append(config.trimEnd('\n')).append('\n')
        }
        return sb.toString()
    }

    /** Extracts one package's section from a [renderSections] document. */
    fun parseSection(raw: String?, packageName: String): String? {
        if (raw.isNullOrBlank() || packageName.isBlank()) return null
        val sb = StringBuilder()
        var inSection = false
        raw.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                inSection = trimmed.substring(1, trimmed.length - 1) == packageName
                return@forEach
            }
            if (inSection) sb.append(line).append('\n')
        }
        return sb.toString().ifBlank { null }
    }
}
