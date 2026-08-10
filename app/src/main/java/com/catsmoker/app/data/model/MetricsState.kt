package com.catsmoker.app.data.model

import com.catsmoker.app.util.CpuInfoTopParser

data class MetricsState(
    val fps: Int = 0,
    val jankyFrames: Int = 0,
    val ramUsedGb: Float = 0f,
    val ramTotalGb: Float = 0f,
    val batteryTempC: Float = 0f,
    val batteryLevel: Int = 0,
    val thermalCpuC: Float = 0f,
    val thermalGpuC: Float = 0f,
    val thermalSkinC: Float = 0f,
    val thermalStatus: Int = 0,
    val thermalReadStatus: MetricReadStatus = MetricReadStatus.Loading,
    val topProcessName: String? = null,
    val topProcessCpuPercent: Float = 0f,
    val topProcesses: List<CpuInfoTopParser.TopProcess> = emptyList(),
    val topProcessReadStatus: MetricReadStatus = MetricReadStatus.Loading,
    val networkRxKbps: Float = 0f,
    val networkTxKbps: Float = 0f,
    val pingMs: Int = 0,
    val cpuEffMhz: Int = 0,
    val cpuPerfMhz: Int = 0,
    val cpuUltraMhz: Int = 0,
    val cpuMhz: Int = 0,
    val cpuPercentage: Int? = null,
    val hasRoot: Boolean = false,
    val hasShizuku: Boolean = false
)
