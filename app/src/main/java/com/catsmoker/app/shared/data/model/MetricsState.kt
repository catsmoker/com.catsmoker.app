package com.catsmoker.app.shared.data.model

import com.catsmoker.app.features.main.engine.parsers.ProcessCpuInfo

/**
 * Where a frame-rate reading came from. The two channels measure different things, so the UI has
 * to be able to say which one produced the number.
 */
enum class FpsSource {
    /**
     * `dumpsys SurfaceFlinger --timestats`: frames the display actually composited, device-wide.
     * This is the game's frame rate, and it needs root or Shizuku.
     */
    SurfaceFlinger,

    /**
     * This process's own `Choreographer` vsync callbacks. Needs no privileges, but it counts
     * vsyncs delivered to Catsmoker — which tracks the display's refresh rate and this app's own
     * main-thread health, not the frame rate of the game in front of it.
     */
    Choreographer,

    /** Nothing has produced a reading yet. */
    None
}

/**
 * Every live metric, with a null wherever the device did not give a reading.
 *
 * Null is never substituted with 0: a missing sensor and a genuine zero are different facts, and
 * only the metric's [MetricReadStatus] can say which one applies.
 */
data class MetricsState(
    val fps: Int? = null,
    val fpsSource: FpsSource = FpsSource.None,
    val fpsReadStatus: MetricReadStatus = MetricReadStatus.Loading,

    /**
     * The panel's live rate (the vsync denominator) and measured maximum, or null without the
     * display provider. Both measured, never caps: the ceiling only bounds the fallback count
     * below, it never lowers anything the device reports.
     */
    val fpsTargetHz: Float? = null,
    val fpsCeilingHz: Float? = null,

    /**
     * Frames SurfaceFlinger counted as missed in the current window. Only the SurfaceFlinger
     * channel reports this, so it is null on the Choreographer fallback.
     */
    val jankyFrames: Int? = null,

    /**
     * Missed / (presented + missed) over the samples that carried a missed count, or null when
     * no sample did. The fallback's frames join the average but never this ratio — a 0 there
     * would assert something unmeasured.
     */
    val jankPercent: Float? = null,

    /**
     * Frame-time percentiles from the same `--timestats` dump as [fps]: the bucket the 95th
     * and 99th frame of the window landed in, in milliseconds. They share [fpsReadStatus] —
     * same dump, same privilege, same gap — and are null whenever the histogram is absent, so
     * a stutter the average hides is visible without inventing a number. Null on the
     * Choreographer fallback, exactly like [jankyFrames].
     */
    val frameTimeP95Ms: Int? = null,
    val frameTimeP99Ms: Int? = null,

    /**
     * Total CPU load across all cores, or null when it could not be measured. `/proc/stat` is
     * hidden from ordinary apps by SELinux on most builds, so this usually needs root or Shizuku.
     */
    val cpuPercentage: Int? = null,
    val cpuReadStatus: MetricReadStatus = MetricReadStatus.Loading,

    /**
     * Per-cluster current frequencies in MHz (efficiency / performance / ultra), each null
     * when its cluster cannot be placed. Read from cpufreq policy dirs — no privilege, no
     * shell — so a locked-down kernel simply yields nulls with [cpuClusterReadStatus].
     */
    val cpuClusterEffMhz: Int? = null,
    val cpuClusterPerfMhz: Int? = null,
    val cpuClusterUltraMhz: Int? = null,
    val cpuClusterReadStatus: MetricReadStatus = MetricReadStatus.Loading,

    /**
     * The 3D core: utilization in whole percent and the current core clock in MHz, from the
     * vendor's own sysfs nodes (kgsl / Mali / MediaTek GED — see
     * [com.catsmoker.app.features.main.engine.GpuTelemetryReader]). No public Android API
     * reports either fact, and the nodes are commonly hidden from the app UID by SELinux, so
     * both are null with [gpuReadStatus] saying why: root/Shizuku needed, or a kernel that
     * exposes none of the vocabulary. Null is never a 0 the device did not report.
     */
    val gpuUtilizationPercent: Int? = null,
    val gpuClockMhz: Int? = null,
    val gpuReadStatus: MetricReadStatus = MetricReadStatus.Loading,

    val ramUsedGb: Float? = null,
    val ramTotalGb: Float? = null,
    val ramReadStatus: MetricReadStatus = MetricReadStatus.Loading,

    val batteryTempC: Float? = null,
    val batteryLevel: Int? = null,
    val batteryReadStatus: MetricReadStatus = MetricReadStatus.Loading,

    /** Total device power draw at the battery, in watts, or null when it cannot be measured. */
    val powerW: Float? = null,
    val powerReadStatus: MetricReadStatus = MetricReadStatus.Loading,

    val networkRxKbps: Float = 0f,
    val networkTxKbps: Float = 0f,

    /** Round-trip time to the ping host, or null when no reply came back. Never 0. */
    val pingMs: Int? = null,
    val pingReadStatus: MetricReadStatus = MetricReadStatus.Loading,

    // Null means "no such sensor / not readable" — distinct from a genuine 0 °C reading.
    val thermalCpuC: Float? = null,
    val thermalGpuC: Float? = null,
    val thermalSkinC: Float? = null,
    val thermalNpuC: Float? = null,
    val thermalStatus: Int = 0,
    val thermalReadStatus: MetricReadStatus = MetricReadStatus.Loading,

    /**
     * `PowerManager.getThermalHeadroom` (API 30+): 0.0 is full headroom, 1.0 the SEVERE
     * threshold, above 1.0 heavier throttling. Needs no privilege. Null with [thermalHeadroomStatus]
     * when the HAL exposes nothing (NaN) or the version predates the API.
     */
    val thermalHeadroom: Float? = null,
    val thermalHeadroomStatus: MetricReadStatus = MetricReadStatus.Loading,

    val topProcesses: List<ProcessCpuInfo> = emptyList(),
    val topProcessName: String? = null,
    val topProcessCpuPercent: Float = 0f,
    val topProcessReadStatus: MetricReadStatus = MetricReadStatus.Loading,

    val hasRoot: Boolean = false,
    val hasShizuku: Boolean = false
) {
    /**
     * Temperature to headline in compact readouts: the hottest SoC sensor when the thermal service
     * gives us one, otherwise the battery sensor. Null when neither reported anything.
     */
    val displayTempC: Float?
        get() = thermalCpuC ?: thermalGpuC ?: thermalSkinC ?: thermalNpuC ?: batteryTempC

    /** True when [displayTempC] came from the SoC rather than the battery. */
    val displayTempIsSoc: Boolean
        get() = thermalCpuC != null || thermalGpuC != null || thermalSkinC != null || thermalNpuC != null

    /** The status behind [displayTempC], so an absent temperature can explain itself. */
    val displayTempReadStatus: MetricReadStatus
        get() = when {
            displayTempIsSoc -> thermalReadStatus
            batteryTempC != null -> batteryReadStatus
            // Neither channel produced a value; the thermal one carries the more specific reason.
            thermalReadStatus != MetricReadStatus.Ok -> thermalReadStatus
            else -> batteryReadStatus
        }
}
