package com.catsmoker.app.shared.util

/**
 * Static device facts in, supported-mechanism flags out — pure logic, no Context,
 * so every branch is unit-provable (`DeviceCapabilitiesTest`).
 *
 * This is the capability-detection pattern, not a registry: callers pass what the
 * platform reports (`Build.MANUFACTURER`, `Build.HARDWARE`, `Build.VERSION.SDK_INT`)
 * and get back which mechanisms that device can honor. Unsupported mechanisms are
 * never attempted — the UI hides or disables them instead. Privilege state (root /
 * Shizuku) stays with `ShellRunner`; this answers only what the hardware and the
 * OS level allow.
 */
object DeviceCapabilities {

    data class DeviceInfo(
        val manufacturer: String = "",
        val brand: String = "",
        val hardware: String = "",
        val board: String = "",
        val sdkInt: Int = 0
    )

    data class Flags(
        val isVivo: Boolean = false,
        val isSamsung: Boolean = false,
        val isXiaomi: Boolean = false,
        val isQualcomm: Boolean = false,
        val isMediaTek: Boolean = false,
        /** Huawei/HiSilicon Kirin silicon (`reference/file-engineering/BoostX-Max-CPU-Performance`). */
        val isKirin: Boolean = false,
        /** Samsung Exynos silicon, including the `universal*` board names. */
        val isExynos: Boolean = false,
        /** Unisoc/Spreadtrum silicon (`ums*` boards, `sc98*` parts). */
        val isUnisoc: Boolean = false,
        /** `cmd power set-fixed-performance-mode-enabled` exists from Android 11 (R). */
        val supportsFixedPerformanceMode: Boolean = false,
        /** `PowerManager.getThermalHeadroom` exists from API 30. */
        val supportsThermalHeadroom: Boolean = false,
        /** `device_config game_overlay` interventions need Android 12 (S). */
        val supportsGameInterventions: Boolean = false
    )

    fun detect(info: DeviceInfo): Flags {
        val m = info.manufacturer.trim().lowercase()
        val b = info.brand.trim().lowercase()
        val hw = info.hardware.trim().lowercase()
        val board = info.board.trim().lowercase()
        val silicon = "$hw $board"
        // Either field can carry the vendor name while the other carries something
        // else — same rule `isVivoOrIqoo()` always used, now in one place.
        val vivoish = m == "vivo" || m == "iqoo" ||
            m.contains("vivo") || m.contains("iqoo") ||
            b.contains("vivo") || b.contains("iqoo")
        return Flags(
            isVivo = vivoish,
            isSamsung = m == "samsung",
            isXiaomi = m == "xiaomi" || m == "redmi" || m == "poco" || m == "black shark",
            isQualcomm = "qcom" in silicon || QUALCOMM_BOARDS.any { it in silicon } ||
                Regex("""\bsm\d{4}\b""").containsMatchIn(silicon),
            isMediaTek = "mediatek" in silicon || "dimensity" in silicon || "helio" in silicon ||
                Regex("""\bmt\d{4}\b""").containsMatchIn(silicon),
            // Vendor vocabulary from BoostX's per-SoC modules, detection only. Kirin parts
            // report either `kirinNNN` or a `hiNNNN` board; Exynos parts report `exynosNNNN`
            // or the older `universalNNNN`; Unisoc reports `umsNNN`/`scNNNN`.
            isKirin = "kirin" in silicon || Regex("""\bhi\d{4}\b""").containsMatchIn(silicon),
            isExynos = "exynos" in silicon ||
                Regex("""\buniversal\d{4}\b""").containsMatchIn(silicon) ||
                Regex("""\bs5e\d{3}\b""").containsMatchIn(silicon),
            isUnisoc = "unisoc" in silicon || "spreadtrum" in silicon ||
                Regex("""\bums\d{3}\b""").containsMatchIn(silicon) ||
                Regex("""\bsc\d{4}[a-z]*\b""").containsMatchIn(silicon),
            supportsFixedPerformanceMode = info.sdkInt >= 30,
            supportsThermalHeadroom = info.sdkInt >= 30,
            supportsGameInterventions = info.sdkInt >= 31
        )
    }

    /**
     * Snapdragon board names worth matching when `hardware` is unhelpful (some OEM
     * builds report the silicon only through `board`). Deliberately short — a miss
     * only hides a Qualcomm-gated lever, never misreports one.
     */
    private val QUALCOMM_BOARDS = listOf(
        "sun", "pineapple", "kalama", "waipio", "taro", "lahaina", "lito", "bengal", "parrot"
    )
}
