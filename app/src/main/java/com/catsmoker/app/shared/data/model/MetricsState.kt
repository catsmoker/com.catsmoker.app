package com.catsmoker.app.shared.data.model

import com.catsmoker.app.features.main.engine.parsers.ProcessCpuInfo

data class MetricsState(
    val fps: Int = 0,
    val jankyFrames: Int = 0,
    val cpuEffMhz: Int = 0,
    val cpuPerfMhz: Int = 0,
    val cpuUltraMhz: Int = 0,
    val cpuMhz: Int = 0,
    val cpuPercentage: Int = 0,
    val ramUsedGb: Float = 0f,
    val ramTotalGb: Float = 0f,
    val batteryTempC: Float = 0f,
    val batteryLevel: Int = 0,
    val networkRxKbps: Float = 0f,
    val networkTxKbps: Float = 0f,
    val pingMs: Int = 0,
    
    val thermalCpuC: Float = 0f,
    val thermalGpuC: Float = 0f,
    val thermalSkinC: Float = 0f,
    val thermalStatus: Int = 0,
    val thermalReadStatus: MetricReadStatus = MetricReadStatus.Ok,

    val topProcesses: List<ProcessCpuInfo> = emptyList(),
    val topProcessName: String? = null,
    val topProcessCpuPercent: Float = 0f,
    val topProcessReadStatus: MetricReadStatus = MetricReadStatus.Ok,

    val hasRoot: Boolean = false,
    val hasShizuku: Boolean = false
)
