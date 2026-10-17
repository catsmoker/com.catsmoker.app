package com.catsmoker.app.features.editgamefiles.wuwa

import android.os.Build

/**
 * On-device chipset read for the WuWa recommendation card.
 *
 * Ported from the reference WuWa app's `ChipsetDetector.detect` (read in full): the same
 * Build-field reads, the same family checks, the same uppercase SoC name. What it is *for*
 * differs deliberately: the reference feeds its benchmark tuner, while here the result is an
 * unscored note beside the SmartBrain recommendation ("Snapdragon-class SoC"). The GPU tier
 * the scorer weighs comes from the GL renderer string this device reported via EGL
 * ([WuwaDeviceInfo]) — a family guess must never outrank a measurement, and an unknown SoC
 * says "Unknown" rather than borrowing a tier.
 */
object WuwaChipset {

    data class ChipsetInfo(
        val socName: String,
        val manufacturer: String,
        val board: String,
        val isSnapdragon: Boolean,
        val isMediatek: Boolean,
        val isExynos: Boolean,
        val isTensor: Boolean
    ) {
        /** One-word family for display; "Unknown" when nothing matched. */
        val family: String
            get() = when {
                isSnapdragon -> "Snapdragon"
                isMediatek -> "MediaTek"
                isExynos -> "Exynos"
                isTensor -> "Tensor"
                else -> "Unknown"
            }
    }

    /** Reads this device's chipset. Never throws: angering Build lookups fail as "unknown". */
    fun detectDevice(): ChipsetInfo = runCatching {
        detect(
            soc = Build.HARDWARE ?: "",
            board = Build.BOARD ?: "",
            manufacturer = Build.MANUFACTURER ?: ""
        )
    }.getOrElse { ChipsetInfo("", "", "", false, false, false, false) }

    fun detect(soc: String, board: String, manufacturer: String): ChipsetInfo {
        val socLower = soc.lowercase()
        val boardLower = board.lowercase()
        val manufacturerLower = manufacturer.lowercase()

        val isSnapdragon =
            socLower.contains("sm") || socLower.contains("qcom") ||
                boardLower.contains("kalama") || boardLower.contains("shima") ||
                boardLower.contains("lahaina") || boardLower.contains("kona") ||
                socLower.contains("sun") || socLower.contains("taro") ||
                socLower.contains("pitti") || socLower.contains("parrot") ||
                socLower.contains("crow") || socLower.contains("garnet")

        val isMediatek = socLower.contains("mt") || manufacturerLower.contains("mediatek")
        val isExynos = socLower.contains("exynos") || boardLower.contains("exynos")
        val isTensor = socLower.contains("gs") || socLower.contains("tensor") || boardLower.contains("gscaler")

        return ChipsetInfo(
            socName = soc.uppercase(),
            manufacturer = manufacturerLower,
            board = boardLower,
            isSnapdragon = isSnapdragon,
            isMediatek = isMediatek,
            isExynos = isExynos,
            isTensor = isTensor
        )
    }
}
