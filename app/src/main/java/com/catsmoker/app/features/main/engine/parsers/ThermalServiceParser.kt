package com.catsmoker.app.features.main.engine.parsers

import kotlin.math.abs

/**
 * Pure parser for `dumpsys thermalservice` output (and sysfs `name:value` fallbacks).
 * No Android dependencies, so it stays cheap and testable.
 *
 * Single pass, O(n) in dump length, O(1) sensor slots:
 * 1. Prefer the "Current temperatures from HAL:" section when present, so static
 *    thresholds and cooling-device blocks can never be mistaken for live readings.
 * 2. Extract each `Temperature{...}` block; fields may appear in any order.
 * 3. Classify by `mType` first (AOSP Temperature types), then by `mName` keywords.
 */
object ThermalServiceParser {

    data class ThermalResult(
        val cpuC: Float? = null,
        val gpuC: Float? = null,
        val skinC: Float? = null,
        val npuC: Float? = null,
        val batteryC: Float? = null,
        val thermalStatus: Int = 0,
        /** Number of `Temperature{...}` blocks that mapped onto a known sensor slot. */
        val entryCount: Int = 0,
        /** True when the HAL explicitly reports itself as not ready. */
        val halNotReady: Boolean = false
    ) {
        val hasAnySensor: Boolean
            get() = cpuC != null || gpuC != null || skinC != null || npuC != null || batteryC != null

        /**
         * Best single "SoC temperature" for a compact readout: CPU, else GPU, else skin, else NPU.
         * Battery is deliberately excluded — the app already surfaces it separately.
         */
        val socC: Float?
            get() = cpuC ?: gpuC ?: skinC ?: npuC
    }

    // The closing brace is escaped deliberately: from Android 14 java.util.regex is backed by ICU,
    // whose parser rejects a bare `}` that is not closing a quantifier. The old engine accepted it,
    // so an unescaped one compiles on the desktop JVM and throws PatternSyntaxException on device —
    // out of a static initializer, which is unrecoverable. Leave both braces escaped.
    private val blockRegex = Regex("""Temperature\{([^}]*mValue[^}]*)\}""")
    private val valueRegex = Regex("""mValue\s*=\s*(-?[0-9.]+)""")
    private val typeRegex = Regex("""mType\s*=\s*(\d+)""")
    private val nameRegex = Regex("""mName\s*=\s*([^\s,}]+)""")
    private val statusRegex = Regex("""Thermal Status:\s*(\d+)""")

    /** Plausible on-die range in Celsius; anything outside is a unit or parsing artefact. */
    private val SANE_RANGE = 0f..150f

    /**
     * @return parsed sensors, or `null` when [output] is blank (caller treats that as EmptyOutput).
     *   A non-blank dump that yields no sensors comes back with [ThermalResult.entryCount] == 0
     *   so the caller can report ParseFailed instead of silently showing 0 °C.
     */
    fun parse(output: String): ThermalResult? {
        if (output.isBlank()) return null

        val halSection = if (output.contains("Current temperatures from HAL:")) {
            output.substringAfter("Current temperatures from HAL:")
                .substringBefore("Current cooling devices")
                .substringBefore("Temperature static thresholds")
        } else {
            ""
        }
        val section = halSection.ifBlank { output }

        var cpu: Float? = null
        var gpu: Float? = null
        var skin: Float? = null
        var npu: Float? = null
        var battery: Float? = null
        var entryCount = 0

        for (match in blockRegex.findAll(section)) {
            val body = match.groupValues[1]
            val raw = valueRegex.find(body)?.groupValues?.get(1)?.toFloatOrNull() ?: continue
            val degC = normalize(raw) ?: continue
            val type = typeRegex.find(body)?.groupValues?.get(1)?.toIntOrNull()
            val name = nameRegex.find(body)?.groupValues?.get(1).orEmpty()

            // Keep the hottest reading per slot: SoCs expose several CPU cluster sensors and the
            // peak is what actually drives throttling.
            when (classify(type, name)) {
                SensorKind.CPU -> { cpu = hotter(cpu, degC); entryCount++ }
                SensorKind.GPU -> { gpu = hotter(gpu, degC); entryCount++ }
                SensorKind.SKIN -> { skin = hotter(skin, degC); entryCount++ }
                SensorKind.NPU -> { npu = hotter(npu, degC); entryCount++ }
                SensorKind.BATTERY -> { battery = hotter(battery, degC); entryCount++ }
                SensorKind.UNKNOWN -> Unit // Unmapped HAL type; leave entryCount alone.
            }
        }

        if (entryCount == 0) {
            val fallback = parseFallbackZones(output)
            if (fallback.entryCount > 0) {
                cpu = fallback.cpuC
                gpu = fallback.gpuC
                skin = fallback.skinC
                npu = fallback.npuC
                battery = fallback.batteryC
                entryCount = fallback.entryCount
            }
        }

        val thermalStatus = statusRegex.find(output)?.groupValues?.get(1)?.toIntOrNull() ?: 0

        val halNotReady = output.contains("HAL Ready: false", ignoreCase = true) ||
            output.contains("mHalReady: false", ignoreCase = true) ||
            output.contains("Thermal HAL is not ready", ignoreCase = true)

        return ThermalResult(
            cpuC = cpu,
            gpuC = gpu,
            skinC = skin,
            npuC = npu,
            batteryC = battery,
            thermalStatus = thermalStatus,
            entryCount = entryCount,
            halNotReady = halNotReady
        )
    }

    /**
     * Handles `zone_name:raw_value` lines, which is what the sysfs sweep in
     * [com.catsmoker.app.system.shell.ShellRunner] emits when `dumpsys thermalservice`
     * is unavailable or the HAL is not ready.
     */
    private fun parseFallbackZones(output: String): ThermalResult {
        var cpu: Float? = null
        var gpu: Float? = null
        var skin: Float? = null
        var npu: Float? = null
        var battery: Float? = null
        var count = 0

        for (rawLine in output.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || !line.contains(':')) continue
            val parts = line.split(':')
            if (parts.size < 2) continue

            val degC = normalize(parts.last().trim().toFloatOrNull() ?: continue) ?: continue
            val name = parts[parts.size - 2].trim()

            when (classify(null, name)) {
                SensorKind.CPU -> { cpu = hotter(cpu, degC); count++ }
                SensorKind.GPU -> { gpu = hotter(gpu, degC); count++ }
                SensorKind.SKIN -> { skin = hotter(skin, degC); count++ }
                SensorKind.NPU -> { npu = hotter(npu, degC); count++ }
                SensorKind.BATTERY -> { battery = hotter(battery, degC); count++ }
                SensorKind.UNKNOWN -> Unit
            }
        }

        return ThermalResult(
            cpuC = cpu, gpuC = gpu, skinC = skin, npuC = npu, batteryC = battery, entryCount = count
        )
    }

    /** Kernel zones report milli-degrees; the HAL reports degrees. Normalise and sanity-check. */
    private fun normalize(raw: Float): Float? {
        val degC = if (abs(raw) > 200f) raw / 1000f else raw
        return if (degC in SANE_RANGE) degC else null
    }

    private fun hotter(current: Float?, candidate: Float): Float =
        if (current == null || candidate > current) candidate else current

    private enum class SensorKind { CPU, GPU, SKIN, NPU, BATTERY, UNKNOWN }

    /**
     * AOSP `Temperature` types: 0=CPU, 1=GPU, 2=BATTERY, 3=SKIN, 9=NPU.
     * The name fallback covers the common Qualcomm/MediaTek/OEM zone labels.
     */
    private fun classify(type: Int?, name: String): SensorKind {
        when (type) {
            0 -> return SensorKind.CPU
            1 -> return SensorKind.GPU
            2 -> return SensorKind.BATTERY
            3 -> return SensorKind.SKIN
            9 -> return SensorKind.NPU
        }

        // Vendors spell the same zone with either separator — `quiet-therm-adc` on most Qualcomm
        // HALs, `quiet_therm` elsewhere; likewise `ap-ntc` / `AP_NTC`. Fold them together, or the
        // keyword list silently matches only half the devices in the wild.
        val n = name.uppercase().replace('-', '_')
        return when {
            n.contains("SKIN") || n.contains("QUIET_THERM") || n.contains("XO_THERM") -> SensorKind.SKIN
            n.contains("GPU") || n.contains("GRAPHICS") || n.contains("MALI") -> SensorKind.GPU
            n.contains("NPU") || n.contains("TPU") || n.contains("APU") || n.contains("Q6_HVX") -> SensorKind.NPU
            n.contains("BATTERY") || n.contains("BATT") -> SensorKind.BATTERY
            n.contains("CPU") || n.contains("SOC") || n.contains("CLUSTER") ||
                n.contains("TSENS") || n.contains("AP_NTC") || n.contains("BIG") ||
                n.contains("LITTLE") -> SensorKind.CPU
            else -> SensorKind.UNKNOWN
        }
    }
}
