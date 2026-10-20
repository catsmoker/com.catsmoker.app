package com.catsmoker.app.features.gamingtools.engine

/**
 * The OEM refresh-rate settings some skins use instead of AOSP's
 * `peak_refresh_rate`/`min_refresh_rate`, drawn from JordanTweaks' key enum
 * (`reference/file-engineering/JordanTweaks-120FPS-Unlock/Execute.sh`).
 *
 * The script writes every key it can spell to a hardcoded 120 and its uninstaller
 * restores them by writing the literal string `null` — both are counter-examples.
 * What is left is the key vocabulary: on some Xiaomi/MIUI builds the AOSP keys are
 * ignored and `user_refresh_rate` / `miui_refresh_rate` are the controls.
 *
 * **Read-only by construction.** Nothing in this file writes, and no lever currently
 * consumes a write target. A write path for any of these keys needs three things this
 * project has not gathered yet: a device that proves the key actually moves the panel,
 * a measured prior value, and a verified restore. Until then the probe only records
 * what the device already carries, which is exactly the evidence that decision needs.
 */
object OemRefreshKeys {

    /** One settings row: the namespace `settings` uses plus the key name. */
    data class Key(val namespace: String, val key: String)

    /** A candidate the device actually carries, with the rate it holds. */
    data class Reading(val key: Key, val hz: Float)

    /**
     * AOSP keys are absent on purpose — [RefreshRateController] owns them and this
     * probe exists for the vendor fallbacks. Thermal limits, mode toggles and the
     * app-list keys are absent too: they are not a plain Hz value, so they cannot be
     * snapshotted and restored as one.
     */
    val candidates: List<Key> = listOf(
        Key("system", "user_refresh_rate"),
        Key("secure", "user_refresh_rate"),
        Key("system", "miui_refresh_rate"),
        Key("secure", "miui_refresh_rate"),
        Key("secure", "max_refresh_rate")
    )

    /**
     * Parses a stored rate. Null for anything that is not a positive finite number:
     * a missing row, the literal `null` some scripts leave behind, `0` (the platform's
     * "no preference"), and words like `auto` are all "no reading here", never 0 Hz.
     */
    fun parseHz(raw: String?): Float? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val value = text.toFloatOrNull() ?: return null
        return value.takeIf { it.isFinite() && it > 0f }
    }

    /** The candidates that exist on the device, in catalog order, with their rates. */
    fun readings(values: Map<Key, String?>): List<Reading> =
        candidates.mapNotNull { key ->
            parseHz(values[key])?.let { Reading(key, it) }
        }
}
