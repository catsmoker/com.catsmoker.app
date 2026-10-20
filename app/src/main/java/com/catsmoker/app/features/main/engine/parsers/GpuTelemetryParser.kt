package com.catsmoker.app.features.main.engine.parsers

/**
 * GPU utilization and clock, parsed from the vendor sysfs nodes the SoC kernels expose.
 *
 * No public Android API reports either fact — `dumpsys SurfaceFlinger` covers the display,
 * not the 3D core — so the only reading surface is the vendor's own sysfs, the same one
 * TMPAD's string table points at (`reference/pubg-closed-source-tools/TMPAD`, read at
 * dex-string level 2026-10-19: `gpu_busy_percentage`, `kgsl`, `mali0`, `gpufreq`). The
 * node names and their units differ per vendor, and the reader that supplies the node text
 * is the only place that knows which node it read — hence the explicit [ClockUnit]: a
 * value is never rescaled by guesswork, the caller states how the node reports.
 *
 * Every parser is total and honest: text that is not a value yields null, a percentage
 * outside 0..100 is not a percentage, and a clock that cannot be a mobile GPU's (below
 * 1 MHz, above 10 GHz) is a unit mistake rather than a reading — the same plausibility
 * rule the battery power path already applies, so no fabricated number reaches the UI.
 */
object GpuTelemetryParser {

    /**
     * The unit a GPU clock node reports in. `devfreq/cur_freq` and `gpuclk` are Hz,
     * MediaTek's GED HAL reports kHz, and `clock_mhz` is MHz — a kernel that reuses one
     * node name for another unit cannot be detected from the text alone, so the node
     * table in the reader declares it and this parser applies it.
     */
    enum class ClockUnit { HZ, KHZ, MHZ }

    /** A GPU clock above this is a unit mistake, not a clock (no mobile GPU runs at 10 GHz). */
    const val MAX_PLAUSIBLE_MHZ = 10_000

    /**
     * One utilization reading in whole percent, from node text like `34 %` (kgsl),
     * `34` (Mali `utilization`, MediaTek `gpu_utilization`, kgsl `gpu_load`) or a padded
     * vendor dump. Only the first token is considered: MediaTek's `gpu_utilization`
     * carries a second number that is not utilization.
     *
     * Blank text, a non-number, a negative value or anything above 100 yields null — a
     * percentage the device never reported is never displayed.
     */
    fun parseUtilization(raw: String?): Int? {
        val token = raw?.trim()?.split(WHITESPACE, LIMIT)?.firstOrNull() ?: return null
        val value = token.removeSuffix("%").trim().toDoubleOrNull() ?: return null
        if (!value.isFinite() || value < 0.0 || value > 100.0) return null
        return Math.round(value).toInt()
    }

    /**
     * Qualcomm's `gpubusy`, a `busy total` pair of counters in the same unit: the share is
     * `busy / total`, the kernel's own average over its sampling window. A `0 0` pair (the
     * GPU idle since boot, or a kernel that never filled the counters) has no total to
     * divide by and yields null, not 0.
     */
    fun parseBusyPair(raw: String?): Int? {
        val tokens = raw?.trim()?.split(WHITESPACE) ?: return null
        if (tokens.size < 2) return null
        val busy = tokens[0].toDoubleOrNull() ?: return null
        val total = tokens[1].toDoubleOrNull() ?: return null
        if (!busy.isFinite() || !total.isFinite() || total <= 0.0 || busy < 0.0) return null
        val percent = busy * 100.0 / total
        if (percent > 100.0) return null
        return Math.round(percent).toInt()
    }

    /**
     * The GPU's current core clock in MHz, from [raw] in [unit]. Null when the text is not
     * a number or the result is outside the plausible range.
     */
    fun parseClockMhz(raw: String?, unit: ClockUnit): Int? {
        val token = raw?.trim()?.split(WHITESPACE, LIMIT)?.firstOrNull() ?: return null
        val value = token.toDoubleOrNull() ?: return null
        if (!value.isFinite() || value <= 0.0) return null
        val mhz = when (unit) {
            ClockUnit.HZ -> value / 1_000_000.0
            ClockUnit.KHZ -> value / 1_000.0
            ClockUnit.MHZ -> value
        }
        if (mhz < 1.0 || mhz > MAX_PLAUSIBLE_MHZ.toDouble()) return null
        return Math.round(mhz).toInt()
    }

    private val WHITESPACE = Regex("\\s+")

    /** `split` needs a positive limit to stop after the first token. */
    private const val LIMIT = 2
}
