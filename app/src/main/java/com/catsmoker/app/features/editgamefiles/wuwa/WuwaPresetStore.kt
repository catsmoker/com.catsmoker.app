package com.catsmoker.app.features.editgamefiles.wuwa

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson

/**
 * Persists the WuWa generator's selected preset + options in their own prefs file
 * (`wuwa_prefs` — never the shared app prefs), so a chosen preset survives process
 * death instead of silently resetting to `balanced`.
 *
 * The SharedPreferences wrapper itself is a thin pass-through (JUnit-only suites
 * have no Android): everything that can be wrong — unknown ids, corrupt blobs —
 * lives in the pure [sanitizePreset]/[encodeOptions]/[decodeOptions] below, pinned
 * by `WuwaPresetStoreTest`.
 */
class WuwaPresetStore(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    /** The restored preset, or `balanced` when nothing (or nothing valid) was stored. */
    fun loadPreset(): String = sanitizePreset(prefs.getString(KEY_PRESET, null))

    /**
     * The stored id verbatim, before sanitizing — null when nothing is stored. Read
     * alongside [loadPreset] so an id this build no longer supports can be explained on the
     * card instead of being swapped for `balanced` without a word.
     */
    fun loadPresetRaw(): String? = prefs.getString(KEY_PRESET, null)

    fun savePreset(preset: String) {
        prefs.edit().putString(KEY_PRESET, sanitizePreset(preset)).apply()
    }

    /** The restored options, or [fallback] when nothing (or nothing valid) was stored. */
    fun loadOptions(fallback: WuWaConfigGenerator.Options): WuWaConfigGenerator.Options =
        decodeOptions(prefs.getString(KEY_OPTIONS, null)) ?: fallback

    fun saveOptions(options: WuWaConfigGenerator.Options) {
        prefs.edit().putString(KEY_OPTIONS, encodeOptions(options)).apply()
    }

    companion object {
        const val PREFS_NAME = "wuwa_prefs"

        private const val KEY_PRESET = "preset"
        private const val KEY_OPTIONS = "options"

        private val gson = Gson()

        /**
         * The preset id when it names a supported preset, else `balanced`. Case is
         * significant — preset ids are lowercase identifiers, never display text.
         */
        fun sanitizePreset(id: String?): String =
            if (id != null && id in WuWaConfigGenerator.PRESETS) id else "balanced"

        fun encodeOptions(options: WuWaConfigGenerator.Options): String =
            gson.toJson(options)

        /**
         * The stored options, or null when the blob is missing, malformed, or
         * structurally incomplete (a version-skewed partial must not surface as a
         * half-parsed config — the caller falls back to the screen defaults).
         */
        fun decodeOptions(json: String?): WuWaConfigGenerator.Options? {
            if (json.isNullOrBlank()) return null
            return runCatching {
                val options = gson.fromJson(json, WuWaConfigGenerator.Options::class.java)
                // Gson leaves absent keys null on Kotlin data classes instead of applying
                // the declared defaults — reject the partial rather than surfacing it.
                if (options == null || options.mode == null || options.cvarOverrides == null) {
                    null
                } else {
                    options
                }
            }.getOrNull()
        }
    }
}
