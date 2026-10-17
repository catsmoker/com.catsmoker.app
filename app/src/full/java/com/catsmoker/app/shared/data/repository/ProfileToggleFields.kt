package com.catsmoker.app.shared.data.repository

import com.catsmoker.app.shared.data.model.LSPosedConfig

/**
 * The closed vocabulary of per-field publish switches
 * ([com.catsmoker.app.shared.data.model.DeviceProfile.disabledFields]): one id per editor-visible
 * field, and [keysFor] maps each id to every rendered key it owns — the render filter and its
 * tests read the same mapping, so they can never disagree about what a switch controls.
 *
 * Not every rendered key is owned. Deliberately outside this vocabulary: the fallback-derived
 * fields the editor never shows (`buildDescription`, `buildFlavor`, `buildProduct`,
 * `buildCharacteristics`, `userAgent`), the CPU/SoC props, and the two blank/flag gates
 * (`applyScreenMetrics`, `screenRefreshRate`). `ProfileRendererTest` pins that exemption list in
 * both directions, so a newly rendered key cannot silently bypass the switches.
 */
object ProfileToggleFields {

    const val BRAND = "brand"
    const val MANUFACTURER = "manufacturer"
    const val MODEL = "model"
    const val PRODUCT_NAME = "productName"
    const val DEVICE_CODE = "deviceCode"
    const val BOARD = "board"
    const val HARDWARE = "hardware"
    const val BOARD_PLATFORM = "boardPlatform"

    /** One id for the GL pair: the two keys render together or not at all. */
    const val GPU = "gpu"

    const val BUILD_RELEASE = "buildRelease"
    const val BUILD_SDK = "buildSdk"

    /** Owns `ro.build.display.id` too — the editor edits them as one row. */
    const val BUILD_ID = "buildId"

    const val BUILD_INCREMENTAL = "buildIncremental"
    const val SECURITY_PATCH = "securityPatch"

    /** Owns the derived `ro.build.type` / `ro.build.tags` and the per-partition fingerprints. */
    const val BUILD_FINGERPRINT = "buildFingerprint"

    const val BOOTLOADER = "bootloader"
    const val SCREEN_WIDTH = "screenWidth"
    const val SCREEN_HEIGHT = "screenHeight"
    const val SCREEN_DENSITY = "screenDensity"
    const val OPERATOR_ALPHA = "operatorAlpha"
    const val OPERATOR_NUMERIC = "operatorNumeric"
    const val SIM_OPERATOR_ALPHA = "simOperatorAlpha"
    const val SIM_OPERATOR_NUMERIC = "simOperatorNumeric"
    const val SIM_COUNTRY_ISO = "simCountryIso"
    const val TIMEZONE = "timezone"
    const val LOCALE = "locale"
    const val ANDROID_ID = "androidId"
    const val IMEI = "imei"
    const val MEID = "meid"
    const val SUBSCRIBER_ID = "subscriberId"
    const val SIM_SERIAL_NUMBER = "simSerialNumber"
    const val PHONE_NUMBER = "phoneNumber"
    const val GAID = "gaid"
    const val GSF_ID = "gsfId"
    const val MEDIA_DRM_ID = "mediaDrmId"
    const val APP_SET_ID = "appSetId"
    const val SERIAL_NUMBER = "serialNumber"

    /** The complete vocabulary, in editor order. */
    val ALL: Set<String> = linkedSetOf(
        BRAND, MANUFACTURER, MODEL, PRODUCT_NAME, DEVICE_CODE, BOARD, HARDWARE, BOARD_PLATFORM,
        GPU,
        BUILD_RELEASE, BUILD_SDK, BUILD_ID, BUILD_INCREMENTAL, SECURITY_PATCH, BUILD_FINGERPRINT,
        BOOTLOADER,
        SCREEN_WIDTH, SCREEN_HEIGHT, SCREEN_DENSITY,
        OPERATOR_ALPHA, OPERATOR_NUMERIC,
        SIM_OPERATOR_ALPHA, SIM_OPERATOR_NUMERIC, SIM_COUNTRY_ISO,
        TIMEZONE, LOCALE,
        ANDROID_ID, IMEI, MEID, SUBSCRIBER_ID, SIM_SERIAL_NUMBER, PHONE_NUMBER, GAID, GSF_ID,
        MEDIA_DRM_ID, APP_SET_ID, SERIAL_NUMBER
    )

    /**
     * Every rendered key owned by [id]; unknown ids own nothing (forward-compatible — a
     * hand-edited or newer set simply suppresses whatever this build understands).
     *
     * Ownership is by key, not by value provenance: the SIM operator keys are owned by the row
     * that renders them even when a blank field is filled by its network fallback.
     */
    fun keysFor(id: String): Set<String> = when (id) {
        BRAND -> productAliases("brand")
        MANUFACTURER -> productAliases("manufacturer")
        MODEL -> productAliases("model")
        PRODUCT_NAME -> productAliases("name")
        DEVICE_CODE -> productAliases("device")
        BOARD -> setOf("ro.product.board")
        HARDWARE -> setOf("ro.hardware")
        BOARD_PLATFORM -> setOf("ro.board.platform")
        GPU -> setOf(LSPosedConfig.KEY_GPU_VENDOR, LSPosedConfig.KEY_GPU_RENDERER)
        BUILD_RELEASE -> setOf("ro.build.version.release")
        BUILD_SDK -> setOf("ro.build.version.sdk")
        BUILD_ID -> setOf("ro.build.id", "ro.build.display.id")
        BUILD_INCREMENTAL -> setOf("ro.build.version.incremental")
        SECURITY_PATCH -> setOf("ro.build.version.security_patch")
        BUILD_FINGERPRINT ->
            setOf("ro.build.fingerprint", "ro.build.type", "ro.build.tags") +
                ProfileRenderer.PARTITIONS.map { "ro.$it.build.fingerprint" }
        BOOTLOADER -> setOf("ro.bootloader")
        SCREEN_WIDTH -> setOf("screen.width")
        SCREEN_HEIGHT -> setOf("screen.height")
        SCREEN_DENSITY -> setOf("screen.density")
        OPERATOR_ALPHA -> setOf("gsm.operator.alpha")
        OPERATOR_NUMERIC -> setOf("gsm.operator.numeric")
        SIM_OPERATOR_ALPHA -> setOf("gsm.sim.operator.alpha")
        SIM_OPERATOR_NUMERIC -> setOf("gsm.sim.operator.numeric")
        SIM_COUNTRY_ISO -> setOf("gsm.sim.operator.iso-country")
        TIMEZONE -> setOf("persist.sys.timezone")
        LOCALE -> setOf("persist.sys.locale")
        ANDROID_ID -> setOf("ANDROID_ID")
        IMEI -> setOf("device.imei")
        MEID -> setOf("device.meid")
        SUBSCRIBER_ID -> setOf("device.imsi")
        SIM_SERIAL_NUMBER -> setOf("device.iccid")
        PHONE_NUMBER -> setOf("device.phone_number")
        GAID -> setOf("device.gaid")
        GSF_ID -> setOf("device.gsf_id")
        MEDIA_DRM_ID -> setOf("device.media_drm_id")
        APP_SET_ID -> setOf("device.app_set_id")
        SERIAL_NUMBER -> setOf("ro.serialno")
        else -> emptySet()
    }

    /** Union of [keysFor] over every id in [ids]. */
    fun keysOf(ids: Set<String>): Set<String> = ids.flatMapTo(mutableSetOf()) { keysFor(it) }

    private fun productAliases(suffix: String): Set<String> =
        setOf("ro.product.$suffix") + ProfileRenderer.PARTITIONS.map { "ro.product.$it.$suffix" }
}
