package com.catsmoker.app.shared.data.model

import java.util.Locale

data class DeviceProfile(
    var brand: String = "",
    var manufacturer: String = "",
    var model: String = "",
    var productName: String = "",
    var deviceCode: String = "",
    var board: String = "",
    var hardware: String = "",
    var boardPlatform: String = "",
    var buildFingerprint: String = "",
    var buildId: String = "",
    var buildDisplayId: String = "",
    var buildIncremental: String = "",
    var buildRelease: String = "",
    var buildSdk: Int = 0,
    var securityPatch: String = "",
    var buildDescription: String = "",
    var buildFlavor: String = "",
    var buildProduct: String = "",
    var buildCharacteristics: String = "nosdcard",
    var screenWidth: Int = 1080,
    var screenHeight: Int = 2400,
    var screenDensity: Int = 420,
    var screenRefreshRate: Int = 0,
    /**
     * Whether the target should SEE the screen block above (display metrics, Configuration,
     * size getters, bounds). Explicit opt-in, off by default: the fields carry real panel
     * specs of preset devices, and applying another panel's geometry to a game changes what
     * it renders — a choice, never a side effect of filling the fields in.
     */
    var applyScreenMetrics: Boolean = false,
    var operatorAlpha: String = "",
    var operatorNumeric: String = "",
    var simOperatorAlpha: String = "",
    var simOperatorNumeric: String = "",
    var simCountryIso: String = "",
    var timezone: String = "",
    var locale: String = "",
    var userAgent: String = "",
    var serialNumber: String = "",
    var bootloader: String = "",
    var androidId: String = "",
    var cpuAbi: String = "",
    var cpuAbiList: String = "",
    var cpuAbiList64: String = "",
    var cpuAbiList32: String = "",
    var socModel: String = "",
    var socManufacturer: String = "",
    var gpuVendor: String = "",
    var gpuRenderer: String = "",
    var imei: String = "",
    var meid: String = "",
    var subscriberId: String = "",
    var simSerialNumber: String = "",
    var phoneNumber: String = "",
    var gaid: String = "",
    var gsfId: String = "",
    var mediaDrmId: String = "",
    var appSetId: String = "",
    /**
     * M116: report emulator device files (`qemu`/`goldfish`/`ranchu`/`vbox` paths) as absent to the
     * target's Java `File` probes. Off by default: it does nothing useful on most real phones, is
     * observable (a filtered listing can be noticed), and covers Java probes only — native opens
     * are not hooked. See [LSPosedConfig.KEY_EMULATOR_HIDE].
     */
    var hideEmulatorArtifacts: Boolean = false,
    /**
     * M117: user-declared hardware facts, each published only when non-zero (0 = leave the real
     * reading). These are numbers about a device the user names — presets and the Default profile
     * stay 0. RAM and both heap classes are MB; see `SpoofHardwareFacts` for the shared
     * conversions.
     */
    var hwCpuCores: Int = 0,
    var hwRamMb: Int = 0,
    var hwHeapMb: Int = 0,
    var hwLargeHeapMb: Int = 0,
    /**
     * M146: per-app Android ID seed. Used only while [androidId] (and, per the documented
     * extension, [serialNumber]) is blank: the module then derives a distinct stable value per
     * assigned package from this seed. The literal fields always win when set; blank = off.
     */
    var androidIdSeed: String = "",
    /**
     * Ids of fields that must not be published (the per-field kill switches; the closed id
     * vocabulary is [com.catsmoker.app.shared.data.repository.ProfileToggleFields]). Empty —
     * the default and the author presets — publishes everything, exactly as before this field
     * existed.
     *
     * Switching a field off only suppresses its rendered keys; the values stay in the profile,
     * so switching it back on publishes the same values with no re-entry. The generated Magisk
     * module is unaffected (its bounded model set falls back to the profile's own `model` field).
     */
    var disabledFields: Set<String> = emptySet()
) {
    fun applyFallbacks() {
        brand = brand.trim()
        manufacturer = manufacturer.ifBlank { brand }.trim()
        model = model.ifBlank { "Unknown device" }.trim()
        deviceCode = deviceCode.ifBlank { model.slugify() }.trim()
        productName = productName.ifBlank { deviceCode }.trim()
        board = board.ifBlank { deviceCode }.trim()
        hardware = hardware.ifBlank { board }.trim()
        boardPlatform = boardPlatform.ifBlank { board }.trim()
        buildRelease = buildRelease.ifBlank { "14" }.trim()
        buildSdk = if (buildSdk > 0) buildSdk else 34
        buildId = buildId.ifBlank { "UKQ1.230917.001" }.trim()
        buildDisplayId = buildDisplayId.ifBlank { buildId }.trim()
        buildIncremental = buildIncremental.ifBlank { buildId.replace(".", "") }.trim()
        securityPatch = securityPatch.ifBlank { "2024-05-05" }.trim()
        buildProduct = buildProduct.ifBlank { productName }.trim()
        buildFlavor = buildFlavor.ifBlank { "$productName-user" }.trim()
        buildCharacteristics = buildCharacteristics.ifBlank { "nosdcard" }.trim()

        if (buildDescription.isBlank()) {
            buildDescription = String.format(
                Locale.US,
                "%s-user %s %s %s release-keys",
                deviceCode, buildRelease, buildId, buildIncremental
            )
        }

        if (buildFingerprint.isBlank()) {
            val b = (if (brand.isNotBlank()) brand else manufacturer).lowercase(Locale.US)
            buildFingerprint = String.format(
                Locale.US,
                "%s/%s/%s:%s/%s/%s:user/release-keys",
                b, productName, deviceCode, buildRelease, buildId, buildIncremental
            )
        }

        if (userAgent.isBlank()) {
            userAgent = String.format(
                Locale.US,
                "Mozilla/5.0 (Linux; Android %s; %s) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
                buildRelease, model
            )
        }

        cpuAbi = cpuAbi.ifBlank { "arm64-v8a" }
        cpuAbiList = cpuAbiList.ifBlank { "arm64-v8a,armeabi-v7a,armeabi" }
        cpuAbiList64 = cpuAbiList64.ifBlank { "arm64-v8a" }
        cpuAbiList32 = cpuAbiList32.ifBlank { "armeabi-v7a,armeabi" }
        socModel = socModel.ifBlank { boardPlatform }
        socManufacturer = socManufacturer.ifBlank { manufacturer }
        // GPU strings have no fallback on purpose. The preset devices' GPU identifiers were
        // never verified, so every preset carries these blank — which renders as "do not spoof
        // the GL strings" rather than an invented "Adreno (TM) 750" that would disagree with
        // the real silicon the same way a fabricated board codename would.
        // screenRefreshRate is the same shape: the presets' panel tiers were verified as models
        // present in a game's table, not as panel specs, so a default rate here would be an
        // invented number where the real one is already read by every hook that needs it.
    }

    private fun String.slugify(): String {
        return this.lowercase(Locale.US).replace(" ", "_").replace("-", "_")
    }

    companion object {
        /**
         * Null-coalescing for [disabledFields] at the two JSON entry points (store repair and
         * profile import): Gson bypasses the constructor, so the set can arrive null (JSON
         * written before the field existed) or carry non-string elements (stripped release
         * signatures, hand-edited files). Returns a canonical set; callers copy only when the
         * value actually changed.
         */
        fun normalizeDisabledFields(raw: Set<String>?): Set<String> =
            raw?.filterIsInstance<String>()?.toSet().orEmpty()

        /**
         * Null-coalescing for [androidIdSeed] at the same two JSON entry points: Gson bypasses
         * constructors, so JSON written before the field existed leaves it null despite the
         * non-null type, and a hand-edited file can carry a non-string value that deserializes
         * as null. Returns "" for anything unusable; callers copy only when the value changed.
         */
        fun normalizeAndroidIdSeed(raw: String?): String = raw.orEmpty()
    }
}
