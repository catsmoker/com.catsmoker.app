package com.catsmoker.app.features.spoofdevice

import com.catsmoker.app.shared.data.model.DeviceProfile
import com.catsmoker.app.shared.data.repository.SpoofRepository
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Portable user-profile sharing: export → share → import.
 *
 * Design around the real model:
 * - The payload is a [DeviceProfile] verbatim (no field is dropped, none added), so a
 *   round-trip preserves every spoofing field the editor supports.
 * - The envelope carries [FORMAT_VERSION], [DOCUMENT_TYPE], the display [name] and the
 *   optional [sourcePresetId] (display hint only — never trusted for logic).
 * - Internal store IDs are never exported and never honored on import: every import
 *   mints a fresh UUID, so an imported file can neither collide with nor hijack an
 *   existing profile.
 * - Data-only: the importer deserializes into [DeviceProfile] and nothing else. Profile
 *   fields are later rendered by `SpoofRepository.renderConfig` exactly like a
 *   hand-entered profile — they are never executed, never written to arbitrary files,
 *   never treated as shell.
 */
object SpoofProfileSharing {
    const val FORMAT_VERSION = 1
    const val DOCUMENT_TYPE = "catsmoker-device-profile"
    const val EXPORT_EXTENSION = "catsmoker.json"
    const val EXPORT_MIME_TYPE = "application/json"

    const val MAX_NAME_LENGTH = 64
    const val MAX_STRING_FIELD = 512
    const val MAX_SDK = 99
    const val MAX_DIMENSION_PX = 8192
    const val MAX_DENSITY_DPI = 1000
    const val MAX_REFRESH_HZ = 1000

    /**
     * Bounds for the M117 user-declared hardware facts. 0 is the editor's blank (allowed);
     * a non-zero value must fit the range, so a hostile import cannot publish an absurd
     * "RAM = 2,147,483,647 MB" that a target would read as a lie. Heaps share one range —
     * the large-heap class is a bigger number of the same thing.
     */
    const val MAX_CPU_CORES = 1024
    const val MIN_RAM_MB = 64
    const val MAX_RAM_MB = 1_048_576
    const val MIN_HEAP_MB = 4
    const val MAX_HEAP_MB = 4096

    /** M146 seed cap: 16-hex values are 16 chars; 128 leaves room for typed passphrases. */
    const val MAX_SEED_LENGTH = 128

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    data class ExportedDocument(
        val type: String = DOCUMENT_TYPE,
        val formatVersion: Int = FORMAT_VERSION,
        val name: String = "",
        val sourcePresetId: String? = null,
        val appVersion: String? = null,
        val profile: DeviceProfile = DeviceProfile()
    )

    data class ImportPreview(
        val name: String,
        val sourcePresetId: String?,
        val profile: DeviceProfile
    )

    sealed interface ImportResult {
        data class Valid(val preview: ImportPreview) : ImportResult
        data class Invalid(val reason: String) : ImportResult
    }

    /** Serialises a user profile to human-readable versioned JSON for export/share. */
    fun exportToJson(
        entry: SpoofRepository.ProfileEntry,
        sourcePresetId: String? = null,
        appVersion: String? = null
    ): String = gson.toJson(
        ExportedDocument(
            name = entry.name,
            sourcePresetId = sourcePresetId?.takeIf { it.isNotBlank() },
            appVersion = appVersion,
            profile = entry.profile
        )
    )

    /**
     * Validates untrusted import text without throwing.
     *
     * Rejects: unparseable JSON, wrong type, missing/unsupported version, blank or
     * over-long names, missing profile objects, over-long strings, out-of-range
     * numerics. Unknown fields are ignored (forward-compatible).
     */
    fun parseImportJson(text: String): ImportResult {
        if (text.isBlank()) return ImportResult.Invalid("Invalid Catsmoker profile: file is empty.")
        val root = try {
            JsonParser.parseString(text)
        } catch (_: Exception) {
            return ImportResult.Invalid("Invalid Catsmoker profile: file is corrupted.")
        }
        if (!root.isJsonObject) return ImportResult.Invalid("Invalid Catsmoker profile: file is corrupted.")
        val obj = root.asJsonObject

        if (obj.get("type")?.takeIf { it.isJsonPrimitive }?.asString != DOCUMENT_TYPE) {
            return ImportResult.Invalid("Invalid Catsmoker profile: not a Catsmoker device profile.")
        }
        val version = obj.get("formatVersion")?.takeIf { it.isJsonPrimitive }?.let {
            try {
                it.asInt
            } catch (_: Exception) {
                null
            }
        } ?: return ImportResult.Invalid("Invalid Catsmoker profile: missing version.")
        if (version > FORMAT_VERSION) {
            return ImportResult.Invalid(
                "This profile was created with an unsupported version (v$version). " +
                    "Update Catsmoker and try again."
            )
        }
        if (version < FORMAT_VERSION) {
            return ImportResult.Invalid("Invalid Catsmoker profile: unsupported version (v$version).")
        }
        val name = obj.get("name")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
        if (name.isEmpty() || name.length > MAX_NAME_LENGTH) {
            return ImportResult.Invalid("Invalid Catsmoker profile: profile name is missing or too long.")
        }
        val profileEl = obj.get("profile")?.takeIf { it.isJsonObject }
            ?: return ImportResult.Invalid("Invalid Catsmoker profile: configuration is missing.")
        val profile = try {
            gson.fromJson(profileEl, DeviceProfile::class.java) ?: DeviceProfile()
        } catch (_: Exception) {
            return ImportResult.Invalid("Invalid Catsmoker profile: configuration is corrupted.")
        }
        // Gson bypasses the constructor: an export written before the switches existed (or a
        // hand-edited file) can leave `disabledFields` null or carry non-string elements.
        // Normalize before anything reads it — validation and the preview never see a null set.
        profile.disabledFields = DeviceProfile.normalizeDisabledFields(profile.disabledFields)
        // Same null problem for the M146 seed (a String field Gson can leave null despite the
        // non-null type); validation and the preview read the normalized value.
        profile.androidIdSeed = DeviceProfile.normalizeAndroidIdSeed(profile.androidIdSeed)
        val fieldError = validateProfile(profile)
        if (fieldError != null) return ImportResult.Invalid(fieldError)

        val sourcePresetId = obj.get("sourcePresetId")
            ?.takeIf { it.isJsonPrimitive }?.asString?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= MAX_NAME_LENGTH }
        return ImportResult.Valid(
            ImportPreview(
                name = name,
                sourcePresetId = sourcePresetId,
                profile = profile.apply { applyFallbacks() }
            )
        )
    }

    private fun validateProfile(profile: DeviceProfile): String? {
        // Every String field stays within bounds (reflection keeps this true when fields
        // are added — the importer never has to enumerate them by hand).
        for (field in DeviceProfile::class.java.declaredFields) {
            if (field.type != String::class.java) continue
            field.isAccessible = true
            val value = (field.get(profile) as? String).orEmpty()
            if (value.length > MAX_STRING_FIELD) {
                return "Invalid Catsmoker profile: field \"${field.name}\" is too long."
            }
        }
        if (profile.buildSdk !in 0..MAX_SDK) {
            return "Invalid Catsmoker profile: SDK level ${profile.buildSdk} is out of range."
        }
        if (profile.screenWidth !in 0..MAX_DIMENSION_PX || profile.screenHeight !in 0..MAX_DIMENSION_PX) {
            return "Invalid Catsmoker profile: screen dimensions are out of range."
        }
        if (profile.screenDensity !in 0..MAX_DENSITY_DPI) {
            return "Invalid Catsmoker profile: screen density is out of range."
        }
        if (profile.screenRefreshRate !in 0..MAX_REFRESH_HZ) {
            return "Invalid Catsmoker profile: refresh rate is out of range."
        }
        if (profile.hwCpuCores != 0 && profile.hwCpuCores !in 1..MAX_CPU_CORES) {
            return "Invalid Catsmoker profile: CPU core count ${profile.hwCpuCores} is out of range."
        }
        if (profile.hwRamMb != 0 && profile.hwRamMb !in MIN_RAM_MB..MAX_RAM_MB) {
            return "Invalid Catsmoker profile: RAM ${profile.hwRamMb} MB is out of range."
        }
        if (profile.hwHeapMb != 0 && profile.hwHeapMb !in MIN_HEAP_MB..MAX_HEAP_MB) {
            return "Invalid Catsmoker profile: heap ${profile.hwHeapMb} MB is out of range."
        }
        if (profile.hwLargeHeapMb != 0 && profile.hwLargeHeapMb !in MIN_HEAP_MB..MAX_HEAP_MB) {
            return "Invalid Catsmoker profile: large heap ${profile.hwLargeHeapMb} MB is out of range."
        }
        if (profile.androidIdSeed.length > MAX_SEED_LENGTH) {
            return "Invalid Catsmoker profile: Android ID seed is too long."
        }
        return null
    }

    /**
     * Conflict-safe display name: never overwrites — appends " (Imported)" and counts
     * up until free.
     */
    fun uniqueProfileName(base: String, existing: Set<String>): String {
        val trimmed = base.trim().take(MAX_NAME_LENGTH).ifEmpty { "Imported Profile" }
        if (!existing.contains(trimmed)) return trimmed
        var n = 1
        while (true) {
            val suffix = if (n == 1) " (Imported)" else " (Imported $n)"
            val candidate = (trimmed + suffix).take(MAX_NAME_LENGTH)
            if (!existing.contains(candidate)) return candidate
            n++
        }
    }

    /** Compact preview lines for the import dialog — model facts only, no internals. */
    fun previewLines(profile: DeviceProfile): List<Pair<String, String>> = listOf(
        "Manufacturer" to profile.manufacturer.ifBlank { profile.brand }.ifBlank { "—" },
        "Model" to profile.model.ifBlank { "—" },
        "Device" to profile.deviceCode.ifBlank { "—" },
        "Build" to listOf(profile.buildRelease, profile.buildId)
            .filter { it.isNotBlank() }.joinToString(" / ").ifBlank { "—" }
    )

}
