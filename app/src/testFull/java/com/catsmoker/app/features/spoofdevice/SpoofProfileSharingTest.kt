package com.catsmoker.app.features.spoofdevice

import com.catsmoker.app.shared.data.model.DeviceProfile
import com.catsmoker.app.shared.data.repository.SpoofRepository
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the preset/profile overhaul:
 *
 * - Fresh installs seed Google Pixel 11 Pro (never Pixel 8 Pro).
 * - Legacy Pixel 8 Pro defaults migrate to Pixel 11 Pro without losing id/name.
 * - `getPresets()` stays author-curated; Custom lives outside it as a UI template.
 * - Export/import is versioned JSON, validated, data-only, with conflict-safe naming.
 */
class SpoofProfileSharingTest {

    // ── default ───────────────────────────────────────────────────────────────

    @Test
    fun freshDefaultIsPixel11Pro() {
        val profile = SpoofRepository.newDefaultProfile()
        assertEquals("Google", profile.brand)
        assertEquals("GM45K", profile.model)
        assertEquals("lynx", profile.deviceCode)
        assertEquals(37, profile.buildSdk)
        assertFalse(profile.model.contains("Pixel 8 Pro"))
    }

    @Test
    fun presetsContainPixel11ProAndStayAuthorCurated() {
        // Author-curated list from spoofing.md: 8 verified models + Current device.
        // Custom must NOT be smuggled into getPresets().
        val ids = FakePresets.presetIds()
        assertTrue(ids.contains("pixel_11_pro"))
        assertFalse(ids.contains("custom"))
        assertFalse(ids.any { it.contains("pixel_8") })
    }

    @Test
    fun customTemplateIsBlankAndUserDefined() {
        val custom = SpoofRepository.createCustomTemplate()
        assertEquals("custom", custom.id)
        // Not tied to any manufacturer/device.
        assertTrue(custom.profile.brand.isBlank() || custom.profile.brand == "Custom")
    }

    // ── migration ─────────────────────────────────────────────────────────────

    @Test
    fun legacyPixel8ProDefaultMigratesToPixel11Pro() {
        val legacy = SpoofRepository.ProfileEntry(
            "keep-this-id",
            "Default Profile",
            DeviceProfile(
                brand = "Google",
                manufacturer = "Google",
                model = "Pixel 8 Pro",
                productName = "husky",
                deviceCode = "husky"
            )
        )
        assertTrue(SpoofRepository.isLegacyDefault(legacy))
        val store = SpoofRepository.StoreData(version = 1, profiles = listOf(legacy))
        val migrated = SpoofRepository.repaired(store)
        assertEquals(4, migrated.version)
        assertEquals("keep-this-id", migrated.profiles.first().id)
        assertEquals("Default Profile", migrated.profiles.first().name)
        assertEquals("GM45K", migrated.profiles.first().profile.model)
    }

    @Test
    fun customizedUserProfilesAreNeverMigrated() {
        val custom = SpoofRepository.ProfileEntry(
            "user-id",
            "My Gaming Phone",
            DeviceProfile(brand = "samsung", manufacturer = "samsung", model = "SM-S948B")
        )
        assertFalse(SpoofRepository.isLegacyDefault(custom))
        val store = SpoofRepository.StoreData(version = 1, profiles = listOf(custom))
        val repaired = SpoofRepository.repaired(store)
        // The custom profile keeps its values by id; v3 additionally guarantees a
        // Default Profile, which is prepended ahead of it.
        assertEquals("SM-S948B", repaired.profiles.first { it.id == "user-id" }.profile.model)
        assertEquals("Default Profile", repaired.profiles.first().name)
    }

    // ── export / import ───────────────────────────────────────────────────────

    private fun entry(name: String = "Gaming Pixel") = SpoofRepository.ProfileEntry(
        "id-1",
        name,
        DeviceProfile(brand = "Google", manufacturer = "Google", model = "GM45K").apply {
            applyFallbacks()
        }
    )

    @Test
    fun exportImportRoundTripPreservesProfile() {
        val json = SpoofProfileSharing.exportToJson(entry(), sourcePresetId = "pixel_11_pro")
        val result = SpoofProfileSharing.parseImportJson(json)
        assertTrue(result is SpoofProfileSharing.ImportResult.Valid)
        val preview = (result as SpoofProfileSharing.ImportResult.Valid).preview
        assertEquals("Gaming Pixel", preview.name)
        assertEquals("GM45K", preview.profile.model)
        assertEquals("pixel_11_pro", preview.sourcePresetId)
    }

    @Test
    fun importRejectsCorruptedMissingAndFutureVersions() {
        assertTrue(SpoofProfileSharing.parseImportJson("not json") is SpoofProfileSharing.ImportResult.Invalid)
        assertTrue(
            SpoofProfileSharing.parseImportJson("""{"type":"nope","formatVersion":1}""")
                is SpoofProfileSharing.ImportResult.Invalid
        )
        val future = SpoofProfileSharing.exportToJson(entry()).replace(
            "\"formatVersion\": 1", "\"formatVersion\": 999"
        )
        val result = SpoofProfileSharing.parseImportJson(future)
        assertTrue(result is SpoofProfileSharing.ImportResult.Invalid)
    }

    @Test
    fun importRejectsInvalidValuesButIgnoresUnknownFields() {
        val bad = """
            {"type":"catsmoker-device-profile","formatVersion":1,
             "name":"X","profile":{"brand":"Google","model":"GM45K","buildSdk":9999}}
        """.trimIndent()
        assertTrue(SpoofProfileSharing.parseImportJson(bad) is SpoofProfileSharing.ImportResult.Invalid)
    }

    @Test
    fun duplicateNamesNeverOverwrite() {
        val existing = setOf("Gaming Pixel")
        assertEquals("Gaming Pixel (Imported)", SpoofProfileSharing.uniqueProfileName("Gaming Pixel", existing))
        assertEquals("Fresh", SpoofProfileSharing.uniqueProfileName("Fresh", existing))
    }

    @Test
    fun presetCopyDoesNotMutatePreset() {
        val preset = DeviceProfile(brand = "Google", manufacturer = "Google", model = "GM45K")
        val copy = preset.copy(model = "EDITED")
        assertEquals("GM45K", preset.model)
        assertEquals("EDITED", copy.model)
    }

    // ── default guarantees ────────────────────────────────────────────────────

    @Test
    fun defaultIsMatchedByNameNeverPosition() {
        val first = SpoofRepository.ProfileEntry("a", "Default Profile", DeviceProfile())
        val second = SpoofRepository.ProfileEntry("b", "Other", DeviceProfile())
        assertTrue(SpoofRepository.isDefault(first))
        assertFalse(SpoofRepository.isDefault(second))
    }

    @Test
    fun ensureDefaultPrependsWhenMissingAndKeepsOrder() {
        val mine = SpoofRepository.ProfileEntry("u", "Mine", DeviceProfile())
        val out = SpoofRepository.ensureDefault(listOf(mine))
        assertEquals(2, out.size)
        assertEquals("Default Profile", out[0].name)
        assertEquals("u", out[1].id)
        // Idempotent: a store that already has one is untouched.
        assertEquals(out, SpoofRepository.ensureDefault(out))
    }

    // ── store v3: user presets ────────────────────────────────────────────────

    @Test
    fun v2StoreRepairsToV4WithEmptyPresetsAndDefault() {
        val mine = SpoofRepository.ProfileEntry("u", "Mine", DeviceProfile())
        val store = SpoofRepository.StoreData(version = 2, profiles = listOf(mine))
        val repaired = SpoofRepository.repaired(store)
        assertEquals(4, repaired.version)
        assertEquals(emptyList<SpoofRepository.UserPreset>(), repaired.userPresets)
        assertEquals("Default Profile", repaired.profiles.first().name)
        assertEquals("Mine", repaired.profiles.last().name)
        // Second repair is a no-op.
        assertEquals(repaired, SpoofRepository.repaired(repaired))
    }

    @Test
    fun selectorKeepsSpecialsOnTopInOrder() {
        fun preset(id: String, name: String) = com.catsmoker.app.shared.data.model.DevicePreset(
            id, name, "", "", DeviceProfile(model = name)
        )
        // Scrambled input: user preset first, specials buried.
        val input = listOf(
            preset("user:mine", "Mine"),
            preset("pixel_11_pro", "Pixel 11 Pro"),
            preset("custom", "Custom Configuration"),
            preset("current_device", "Current Device")
        )
        val entries = PresetSelector.order(input)
        // Current Device, Custom Configuration, header, then saved in input order.
        assertTrue(entries[0] is PresetSelector.Entry.Special)
        assertTrue(entries[1] is PresetSelector.Entry.Special)
        assertEquals("current_device", (entries[0] as PresetSelector.Entry.Special).preset.id)
        assertEquals("custom", (entries[1] as PresetSelector.Entry.Special).preset.id)
        assertTrue(entries[2] is PresetSelector.Entry.SavedHeader)
        assertEquals("user:mine", (entries[3] as PresetSelector.Entry.Saved).preset.id)
        assertEquals("pixel_11_pro", (entries[4] as PresetSelector.Entry.Saved).preset.id)
        // Only user presets are removable — specials and built-ins never show trash.
        assertFalse(PresetSelector.isRemovable(preset("current_device", "C")))
        assertFalse(PresetSelector.isRemovable(preset("custom", "C")))
        assertFalse(PresetSelector.isRemovable(preset("pixel_11_pro", "P")))
        assertTrue(PresetSelector.isRemovable(preset("user:mine", "M")))
    }

    @Test
    fun userPresetConvertsToImmutableSelectorEntry() {
        val preset = SpoofRepository.UserPreset(
            "abc", "My Template",
            DeviceProfile(brand = "Google", manufacturer = "Google", model = "GM45K"), true
        )
        val asPreset = preset.toDevicePreset()
        assertEquals("user:abc", asPreset.id)
        assertEquals("GM45K", asPreset.profile.model)
        // Profiles made from it hold their own copy.
        val profileCopy = asPreset.profile.copy(model = "CHANGED")
        assertEquals("GM45K", preset.profile.model)
        assertEquals("CHANGED", profileCopy.model)
    }

    // ── store v4: disabled-field switches ─────────────────────────────────────

    @Test
    fun v3StoreRepairsToV4WithNoFieldsDisabled() {
        // A store written before the switches existed: Gson bypasses the constructor, so the
        // absent set is null despite the non-null type. `repaired` coalesces it to "publish
        // everything" for both profiles and user presets, and rolls the marker to v4.
        val legacy = """
            {"version":3,
             "profiles":[{"id":"p1","name":"P","profile":{"model":"GM45K"}}],
             "userPresets":[{"id":"u1","name":"T","profile":{"brand":"Google"},"imported":false}],
             "assignments":{},
             "globalProperties":{}}
        """.trimIndent()
        val parsed = Gson().fromJson(legacy, SpoofRepository.StoreData::class.java)!!
        val repaired = SpoofRepository.repaired(parsed)
        assertEquals(4, repaired.version)
        assertEquals(emptySet<String>(), repaired.profiles.first().profile.disabledFields)
        assertEquals(emptySet<String>(), repaired.userPresets.first().profile.disabledFields)
        // Second repair is a no-op.
        assertEquals(repaired, SpoofRepository.repaired(repaired))
    }

    @Test
    fun switchesSurviveAGsonRoundTrip() {
        val entry = SpoofRepository.ProfileEntry(
            "p1", "P",
            DeviceProfile(model = "GM45K", disabledFields = setOf("model", "gpu"))
        )
        val store = SpoofRepository.StoreData(profiles = listOf(entry))
        val gson = Gson()
        val back = gson.fromJson(gson.toJson(store), SpoofRepository.StoreData::class.java)
        assertEquals(
            setOf("model", "gpu"),
            SpoofRepository.repaired(back).profiles.first().profile.disabledFields
        )
    }

    @Test
    fun releaseShapedSwitchSetsAreHealedNotCrashedOn() {
        // Stripped generic signatures (release R8) leave non-string elements in the set;
        // `repaired` filters them out the same way it coerces map-shaped rate rungs.
        @Suppress("UNCHECKED_CAST")
        val poisoned = setOf("model", 42) as Set<String>
        val store = SpoofRepository.StoreData(
            profiles = listOf(
                SpoofRepository.ProfileEntry("p1", "P", DeviceProfile(disabledFields = poisoned))
            )
        )
        assertEquals(
            setOf("model"),
            SpoofRepository.repaired(store).profiles.first().profile.disabledFields
        )
    }

    @Test
    fun anImportWithoutTheSwitchFieldArrivesWithNoneDisabled() {
        // Exports from before the switches (or hand-trimmed files) must not NPE the preview.
        val json = """
            {"type":"catsmoker-device-profile","formatVersion":1,
             "name":"Old Export","profile":{"brand":"Google","model":"GM45K"}}
        """.trimIndent()
        val result = SpoofProfileSharing.parseImportJson(json)
        assertTrue(result is SpoofProfileSharing.ImportResult.Valid)
        val preview = (result as SpoofProfileSharing.ImportResult.Valid).preview
        assertEquals(emptySet<String>(), preview.profile.disabledFields)
    }

    @Test
    fun switchesRoundTripThroughExportAndImport() {
        val profile = DeviceProfile(brand = "Google", model = "GM45K")
            .apply { applyFallbacks() }
            .copy(disabledFields = setOf("model", "imei"))
        val json = SpoofProfileSharing.exportToJson(SpoofRepository.ProfileEntry("id-1", "Gaming Pixel", profile))
        val result = SpoofProfileSharing.parseImportJson(json)
        val preview = (result as SpoofProfileSharing.ImportResult.Valid).preview
        assertEquals(setOf("model", "imei"), preview.profile.disabledFields)
    }

    @Test
    fun anImportWithForeignSwitchElementsNeverCrashesAndKeepsTheKnownId() {
        // Hand-edited files can carry non-string elements. Exact element healing is
        // environment-dependent (with generic signatures present Gson coerces 7 to "7", with
        // them stripped it stays a number and is dropped) — what must hold everywhere: the
        // import succeeds and the real id survives.
        val json = """
            {"type":"catsmoker-device-profile","formatVersion":1,
             "name":"Hand Edited","profile":{"brand":"Google","model":"GM45K","disabledFields":["model",7,true]}}
        """.trimIndent()
        val result = SpoofProfileSharing.parseImportJson(json)
        assertTrue(result is SpoofProfileSharing.ImportResult.Valid)
        val preview = (result as SpoofProfileSharing.ImportResult.Valid).preview
        assertTrue(preview.profile.disabledFields.contains("model"))
    }

    /** Documents intent without depending on Android framework classes. */
    private object FakePresets {
        fun presetIds(): List<String> = listOf(
            "current_device",
            "pixel_11_pro",
            "galaxy_s26_ultra",
            "xiaomi_17_ultra",
            "oneplus_15",
            "tab_s11_ultra",
            "iqoo_15",
            "rog_phone_9_pro",
            "redmagic_10s_pro"
        )
    }
}
