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
     * Frames SurfaceFlinger counted as missed in the current window. Only the SurfaceFlinger
     * channel reports this, so it is null on the Choreographer fallback.
     */
    val jankyFrames: Int? = null,

    val cpuEffMhz: Int = 0,
    val cpuPerfMhz: Int = 0,
    val cpuUltraMhz: Int = 0,
    val cpuMhz: Int = 0,

    /**
     * Total CPU load across all cores, or null when it could not be measured. `/proc/stat` is
     * hidden from ordinary apps by SELinux on most builds, so this usually needs root or Shizuku.
     */
    val cpuPercentage: Int? = null,
    val cpuReadStatus: MetricReadStatus = MetricReadStatus.Loading,

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
