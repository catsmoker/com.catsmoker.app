package com.catsmoker.app.shared.data.repository

import com.catsmoker.app.shared.data.model.DeviceProfile
import com.catsmoker.app.shared.data.model.LSPosedConfig

/**
 * Renders a [DeviceProfile] into the `key=value` document every channel distributes
 * (Settings.Global sections, the provider prefs, each user-facing preview). Moved out of
 * [SpoofRepository] on purpose: this is a pure transformation, and living on the
 * Android-dependent store class kept the render contract out of JVM tests.
 *
 * The per-field publish switches filter here — the single filtering point, so every channel and
 * preview stays consistent. Keys owned by a field id in [DeviceProfile.disabledFields] (see
 * [ProfileToggleFields.keysFor]) are omitted; the Xposed module needs no changes, because an
 * absent key is the same "leave the real value alone" signal a blank field already emits. Values
 * are never rewritten, so switching a field back on publishes exactly the same values again.
 *
 * The generated Magisk module is outside this contract by design: it extracts only its bounded
 * model-key set and falls back to the profile's own `model` field when the rendered key is
 * absent, so the switches do not change what it flashes.
 */
object ProfileRenderer {

    /**
     * The `ro.product.<partition>.*` aliases emitted beside the primary identity keys — also the
     * set [ProfileToggleFields] expands its ownership lookups from, so filter and vocabulary
     * cannot drift apart.
     */
    val PARTITIONS: List<String> =
        listOf("product", "system", "system_ext", "vendor", "vendor_dlkm", "odm", "bootimage", "system_dlkm")

    fun render(profile: DeviceProfile, globalProps: Map<String, String>): String {
        val sb = StringBuilder()
        sb.append("# Catsmoker generated profile\n\n")

        fun addProp(key: String, value: String?) {
            if (!value.isNullOrBlank()) {
                sb.append("$key=$value\n")
            }
        }

        // Identity & Partitions
        addProp("ro.product.brand", profile.brand)
        addProp("ro.product.manufacturer", profile.manufacturer)
        addProp("ro.product.model", profile.model)
        addProp("ro.product.name", profile.productName)
        addProp("ro.product.device", profile.deviceCode)
        addProp("ro.product.board", profile.board)
        addProp("ro.hardware", profile.hardware)
        addProp("ro.board.platform", profile.boardPlatform)

        PARTITIONS.forEach { p ->
            addProp("ro.product.$p.brand", profile.brand)
            addProp("ro.product.$p.manufacturer", profile.manufacturer)
            addProp("ro.product.$p.model", profile.model)
            addProp("ro.product.$p.name", profile.productName)
            addProp("ro.product.$p.device", profile.deviceCode)
        }

        // Build & Fingerprints
        addProp("ro.build.fingerprint", profile.buildFingerprint)
        addProp("ro.build.id", profile.buildId)
        addProp("ro.build.display.id", profile.buildDisplayId)
        addProp("ro.build.version.incremental", profile.buildIncremental)
        addProp("ro.build.version.release", profile.buildRelease)
        addProp("ro.build.version.sdk", if (profile.buildSdk > 0) profile.buildSdk.toString() else "")
        addProp("ro.build.version.security_patch", profile.securityPatch)
        addProp("ro.build.description", profile.buildDescription)
        addProp("ro.build.flavor", profile.buildFlavor)
        addProp("ro.build.product", profile.buildProduct)
        addProp("ro.build.characteristics", profile.buildCharacteristics)

        // Keep Build.TYPE/Build.TAGS consistent with the fingerprint: a "release-keys"
        // fingerprint next to a leftover "test-keys" tag is exactly what detection looks for.
        val (buildType, buildTags) = splitFingerprintSuffix(profile.buildFingerprint)
        addProp("ro.build.type", buildType)
        addProp("ro.build.tags", buildTags)

        // CPU & SoC
        addProp("ro.product.cpu.abi", profile.cpuAbi)
        addProp("ro.product.cpu.abilist", profile.cpuAbiList)
        addProp("ro.product.cpu.abilist64", profile.cpuAbiList64)
        addProp("ro.product.cpu.abilist32", profile.cpuAbiList32)
        addProp("ro.soc.model", profile.socModel)
        addProp("ro.soc.manufacturer", profile.socManufacturer)

        // GPU identity for the GL-string hooks. Rendered as a pair or not at all — the two keys
        // are only ever set together by the editor, and a lone vendor beside a real renderer is
        // a stronger mismatch signal than neither (see DeviceProfile.applyFallbacks).
        if (profile.gpuVendor.isNotBlank() && profile.gpuRenderer.isNotBlank()) {
            addProp(LSPosedConfig.KEY_GPU_VENDOR, profile.gpuVendor)
            addProp(LSPosedConfig.KEY_GPU_RENDERER, profile.gpuRenderer)
        }

        PARTITIONS.forEach { p ->
            addProp("ro.$p.build.fingerprint", profile.buildFingerprint)
        }

        // Screen
        addProp("screen.width", if (profile.screenWidth > 0) profile.screenWidth.toString() else "")
        addProp("screen.height", if (profile.screenHeight > 0) profile.screenHeight.toString() else "")
        addProp("screen.density", if (profile.screenDensity > 0) profile.screenDensity.toString() else "")
        // The metrics gate: explicit opt-in, blank/off by default. Rendered as one of our own
        // profile keys (never a system property), read only by the display-metrics hooks —
        // absent means the target keeps its real geometry even with screen.* values present.
        if (profile.applyScreenMetrics) {
            addProp(LSPosedConfig.KEY_APPLY_SCREEN_METRICS, "1")
        }
        // Rendered as one of our own profile keys (never a system property), read only by the
        // Display hook — zero means the hook is not installed for this profile at all, the same
        // blank-means-off contract the GPU strings follow.
        addProp(
            LSPosedConfig.KEY_SCREEN_REFRESH_RATE,
            if (profile.screenRefreshRate > 0) profile.screenRefreshRate.toString() else ""
        )

        // Network
        addProp("gsm.operator.alpha", profile.operatorAlpha)
        addProp("gsm.operator.numeric", profile.operatorNumeric)
        addProp("gsm.sim.operator.alpha", profile.simOperatorAlpha.ifBlank { profile.operatorAlpha })
        addProp("gsm.sim.operator.numeric", profile.simOperatorNumeric.ifBlank { profile.operatorNumeric })
        addProp("gsm.sim.operator.iso-country", profile.simCountryIso)
        addProp("persist.sys.timezone", profile.timezone)
        addProp("persist.sys.locale", profile.locale)

        // WebView
        addProp("webview.user_agent", profile.userAgent)

        // IDs
        addProp("ro.serialno", profile.serialNumber)
        addProp("ro.bootloader", profile.bootloader)
        addProp("ANDROID_ID", profile.androidId)
        addProp("device.imei", profile.imei)
        addProp("device.meid", profile.meid)
        addProp("device.imsi", profile.subscriberId)
        addProp("device.iccid", profile.simSerialNumber)
        addProp("device.phone_number", profile.phoneNumber)
        addProp("device.gaid", profile.gaid)
        addProp("device.gsf_id", profile.gsfId)
        addProp("device.media_drm_id", profile.mediaDrmId)
        addProp("device.app_set_id", profile.appSetId)

        // Global/Extra
        if (globalProps.isNotEmpty()) {
            sb.append("\n# Global Settings\n")
            globalProps.forEach { (k, v) ->
                if (v.isNotBlank()) sb.append("$k=$v\n")
            }
        }

        return suppressDisabledFields(sb.toString(), profile.disabledFields)
    }

    /**
     * Drops every rendered line whose key a switched-off field owns. Line-level and key-exact:
     * comments and blank lines pass through, and the global block (safe-mode list and friends)
     * is untouched — the vocabulary never names its keys.
     */
    private fun suppressDisabledFields(text: String, disabledFields: Set<String>): String {
        val suppressed = ProfileToggleFields.keysOf(disabledFields)
        if (suppressed.isEmpty()) return text
        return text.split("\n").filterNot { line ->
            val eq = line.indexOf('=')
            eq > 0 && line.substring(0, eq) in suppressed
        }.joinToString("\n")
    }

    /**
     * Pulls `type` and `tags` out of a fingerprint's `…:<type>/<tags>` tail.
     *
     * @return the parsed pair, falling back to the stock `user` / `release-keys` for a fingerprint
     *   that does not carry the suffix.
     */
    private fun splitFingerprintSuffix(fingerprint: String): Pair<String, String> {
        val tail = fingerprint.substringAfterLast(':', "")
        val type = tail.substringBefore('/', "")
        val tags = tail.substringAfter('/', "")
        return Pair(
            type.ifBlank { "user" },
            tags.ifBlank { "release-keys" }
        )
    }
}
