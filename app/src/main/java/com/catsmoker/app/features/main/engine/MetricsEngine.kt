package com.catsmoker.app.features.main.engine

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.TrafficStats
import android.os.BatteryManager
import com.catsmoker.app.shared.data.model.MetricReadStatus
import com.catsmoker.app.shared.data.model.MetricsState
import com.catsmoker.app.features.main.engine.parsers.CpuInfoTopParser
import com.catsmoker.app.features.main.engine.parsers.CpuStatParser
import com.catsmoker.app.features.main.engine.parsers.ThermalServiceParser
import com.catsmoker.app.system.shell.ShellRunner
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds

class MetricsEngine(
    private val context: Context,
    private val shellRunner: ShellRunner
) {
    private val _state = MutableStateFlow(MetricsState())
    val state: StateFlow<MetricsState> = _state.asStateFlow()

    private val _fpsHistory = MutableStateFlow<List<Int>>(emptyList())
    val fpsHistory: StateFlow<List<Int>> = _fpsHistory.asStateFlow()

    private val _cpuHistory = MutableStateFlow<List<Int>>(emptyList())
    val cpuHistory: StateFlow<List<Int>> = _cpuHistory.asStateFlow()

    private val _ramHistory = MutableStateFlow<List<Float>>(emptyList())
    val ramHistory: StateFlow<List<Float>> = _ramHistory.asStateFlow()

    private val _tempHistory = MutableStateFlow<List<Float>>(emptyList())
    val tempHistory: StateFlow<List<Float>> = _tempHistory.asStateFlow()

    private val _pingHistory = MutableStateFlow<List<Int>>(emptyList())
    val pingHistory: StateFlow<List<Int>> = _pingHistory.asStateFlow()

    private var scope = newScope()
    private var isRunning = false

    private var previousRx = 0L
    private var previousTx = 0L
    private var lastNetworkTime = 0L

    private var previousCpuTimes: CpuStatParser.CpuTimes? = null

    fun start() {
        if (isRunning) return
        if (!scope.isActive) {
            scope = newScope()
        }
        isRunning = true

        // 1. Slow Monitor: Privileges & Status
        scope.launch(Dispatchers.IO) {
            while (isRunning) {
                val root = shellRunner.isRootAvailable()
                val shizuku = shellRunner.shizukuHasPermission.value
                withContext(Dispatchers.Main) {
                    if ((_state.value.hasRoot != root) || (_state.value.hasShizuku != shizuku)) {
                        _state.value = _state.value.copy(hasRoot = root, hasShizuku = shizuku)
                    }
                }
                delay(10.seconds)
            }
        }

        // 2. Heavy Monitor: Top Processes & Detailed Info
        scope.launch(Dispatchers.IO) {
            while (isRunning) {
                if (shellRunner.hasPrivilege()) {
                    pollHeavyMetrics()
                }
                delay(20.seconds)
            }
        }

        startSystemStatsPoll()
        startHighCadencePoll()
        startFpsMonitor()
    }

    fun stop() {
        isRunning = false
        scope.cancel()
    }

    private fun newScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private fun startFpsMonitor() {
        scope.launch(Dispatchers.Main) {
            var frameCount = 0
            var lastTime = System.currentTimeMillis()
            val choreographer = android.view.Choreographer.getInstance()

            val callback = object : android.view.Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    if (!isRunning) return
                    
                    val currentState = _state.value
                    if (currentState.hasRoot || currentState.hasShizuku) {
                        frameCount++
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastTime >= 1000) {
                            val fps = frameCount
                            _state.value = currentState.copy(fps = fps, jankyFrames = 0)
                            updateFpsHistory(fps)
                            frameCount = 0
                            lastTime = currentTime
                        }
                    } else {
                        // Clear history if we lose privilege
                        if (_fpsHistory.value.isNotEmpty()) {
                            _fpsHistory.value = emptyList()
                        }
                    }
                    
                    choreographer.postFrameCallback(this)
                }
            }
            choreographer.postFrameCallback(callback)
        }
    }

    private fun updateFpsHistory(fps: Int) {
        val history = _fpsHistory.value.toMutableList()
        history.add(fps)
        if (history.size > 60) history.removeAt(0)
        _fpsHistory.value = history
    }

    private fun updateCpuHistory(cpu: Int) {
        val history = _cpuHistory.value.toMutableList()
        history.add(cpu)
        if (history.size > 60) history.removeAt(0)
        _cpuHistory.value = history
    }

    private fun updateRamHistory(ram: Float) {
        val history = _ramHistory.value.toMutableList()
        history.add(ram)
        if (history.size > 60) history.removeAt(0)
        _ramHistory.value = history
    }

    private fun updateTempHistory(temp: Float) {
        val history = _tempHistory.value.toMutableList()
        history.add(temp)
        if (history.size > 60) history.removeAt(0)
        _tempHistory.value = history
    }

    private fun updatePingHistory(ping: Int) {
        val history = _pingHistory.value.toMutableList()
        history.add(ping)
        if (history.size > 60) history.removeAt(0)
        _pingHistory.value = history
    }

    private fun startSystemStatsPoll() {
        scope.launch(Dispatchers.IO) {
            previousRx = TrafficStats.getTotalRxBytes()
            previousTx = TrafficStats.getTotalTxBytes()
            lastNetworkTime = System.currentTimeMillis()

            while (isRunning) {
                val ram = getRamUsage()
                val (temp, level) = getBatteryStats()
                val (rx, tx) = getNetworkSpeeds()

                withContext(Dispatchers.Main) {
                    _state.value = _state.value.copy(
                        ramUsedGb = ram.first,
                        ramTotalGb = ram.second,
                        batteryTempC = temp,
                        batteryLevel = level,
                        networkRxKbps = rx,
                        networkTxKbps = tx
                    )
                    updateRamHistory(ram.first)
                    updateTempHistory(temp)
                }
                delay(5.seconds)
            }
        }
    }

    private fun startHighCadencePoll() {
        scope.launch(Dispatchers.IO) {
            while (isRunning) {
                pollThermalMetrics()

                val clusters = readClusterState()
                val cpuFreq0 = readMhz(File("/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq"))
                val cpuPct = pollCpuPercentage()
                val ping = performPing()

                withContext(Dispatchers.Main) {
                    val finalCpuPct = if (cpuPct != null && cpuPct > 0) cpuPct else _state.value.cpuPercentage
                    _state.value = _state.value.copy(
                        cpuEffMhz = clusters.effMhz,
                        cpuPerfMhz = clusters.perfMhz,
                        cpuUltraMhz = clusters.ultraMhz,
                        cpuMhz = cpuFreq0,
                        cpuPercentage = finalCpuPct,
                        pingMs = ping
                    )
                    updateCpuHistory(finalCpuPct)
                    updatePingHistory(ping)
                }

                delay(4.seconds)
            }
        }
    }

    private suspend fun pollThermalMetrics() {
        if (!shellRunner.hasPrivilege()) return
        val thermalResult = ThermalServiceParser.parse(shellRunner.readThermal())
        withContext(Dispatchers.Main) {
            _state.value = _state.value.copy(
                thermalCpuC = thermalResult?.cpuC ?: 0f,
                thermalGpuC = thermalResult?.gpuC ?: 0f,
                thermalSkinC = thermalResult?.skinC ?: 0f,
                thermalStatus = thermalResult?.thermalStatus ?: 0,
                thermalReadStatus = if (thermalResult != null && thermalResult.entryCount > 0) MetricReadStatus.Ok else MetricReadStatus.ParseFailed
            )
        }
    }

    private suspend fun pollHeavyMetrics() {
        var cpuInfoOutput = shellRunner.exec("top -n 1 -b")
        if (cpuInfoOutput.isBlank()) {
            cpuInfoOutput = shellRunner.exec("dumpsys cpuinfo")
        }
        
        val totalCpu = CpuInfoTopParser.parseTotalCpu(cpuInfoOutput)
        val topProcesses = CpuInfoTopParser.parseTopProcesses(cpuInfoOutput)
        val topProcess = topProcesses.firstOrNull()

        withContext(Dispatchers.Main) {
            val cpuVal = totalCpu?.roundToInt() ?: 0
            _state.value = _state.value.copy(
                cpuPercentage = if (cpuVal > 0) cpuVal else _state.value.cpuPercentage,
                topProcesses = topProcesses,
                topProcessName = topProcess?.name,
                topProcessCpuPercent = topProcess?.cpuPercent ?: 0f,
                topProcessReadStatus = if (topProcesses.isNotEmpty()) MetricReadStatus.Ok else MetricReadStatus.EmptyOutput
            )
        }
    }

    private suspend fun pollCpuPercentage(): Int? {
        return try {
            val output = shellRunner.exec("cat /proc/stat")
            if (output.isBlank()) return null
            val lines = output.lines()
            val current = CpuStatParser.parseTotalCpuLine(lines)
            val prev = previousCpuTimes
            previousCpuTimes = current
            if (current != null && prev != null) {
                CpuStatParser.calculateCpuUsage(prev, current)
            } else null
        } catch (_: Exception) { null }
    }

    private suspend fun performPing(): Int {
        return try {
            if (_state.value.networkRxKbps <= 0.1f) return 0
            val output = shellRunner.exec("ping -c 1 8.8.8.8")
            if (output.contains("time=")) {
                output.split("time=").getOrNull(1)
                    ?.split(" ")?.getOrNull(0)
                    ?.toFloatOrNull()
                    ?.roundToInt() ?: 0
            } else 0
        } catch (_: Exception) { 0 }
    }

    private fun readClusterState(): CpuClusterState {
        val policies = readCpuPolicies().sortedBy { it.maxMhz }
        return when (policies.size) {
            0 -> CpuClusterState(0, 0, 0)
            1 -> CpuClusterState(policies[0].currentMhz, 0, 0)
            2 -> CpuClusterState(policies[0].currentMhz, policies[1].currentMhz, 0)
            else -> CpuClusterState(
                effMhz = policies.first().currentMhz,
                perfMhz = policies[policies.lastIndex - 1].currentMhz,
                ultraMhz = policies.last().currentMhz
            )
        }
    }

    private fun readCpuPolicies(): List<CpuPolicy> {
        val policyDir = File("/sys/devices/system/cpu/cpufreq")
        val policies = policyDir.listFiles { file -> file.isDirectory && file.name.startsWith("policy") }
            ?.mapNotNull { policy ->
                val current = readMhz(File(policy, "scaling_cur_freq"))
                val max = readMhz(File(policy, "cpuinfo_max_freq"))
                if (current > 0 || max > 0) CpuPolicy(current, max.coerceAtLeast(current)) else null
            }
            .orEmpty()

        if (policies.isNotEmpty()) return policies

        return File("/sys/devices/system/cpu")
            .listFiles { file -> file.isDirectory && file.name.matches(Regex("cpu\\d+")) }
            ?.mapNotNull { cpu ->
                val freqDir = File(cpu, "cpufreq")
                val current = readMhz(File(freqDir, "scaling_cur_freq"))
                val max = readMhz(File(freqDir, "cpuinfo_max_freq"))
                if (current > 0 || max > 0) CpuPolicy(current, max.coerceAtLeast(current)) else null
            }
            .orEmpty()
            .distinctBy { it.maxMhz }
    }

    private fun readMhz(file: File): Int {
        return try {
            if (file.exists()) {
                val raw = file.readText().trim()
                (raw.toIntOrNull() ?: 0) / 1000
            } else 0
        } catch (_: Exception) { 0 }
    }

    private data class CpuClusterState(val effMhz: Int, val perfMhz: Int, val ultraMhz: Int)
    private data class CpuPolicy(val currentMhz: Int, val maxMhz: Int)

    private fun getRamUsage(): Pair<Float, Float> {
        val mi = ActivityManager.MemoryInfo()
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        return if (activityManager != null) {
            activityManager.getMemoryInfo(mi)
            val totalGb = mi.totalMem.toFloat() / (1024 * 1024 * 1024)
            val usedGb = (mi.totalMem - mi.availMem).toFloat() / (1024 * 1024 * 1024)
            usedGb to totalGb
        } else 0f to 0f
    }

    private fun getBatteryStats(): Pair<Float, Int> {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temp = (intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: 0
        return temp to level
    }

    private fun getNetworkSpeeds(): Pair<Float, Float> {
        val currentRx = TrafficStats.getTotalRxBytes()
        val currentTx = TrafficStats.getTotalTxBytes()
        val currentTime = System.currentTimeMillis()

        val timeDiffSec = (currentTime - lastNetworkTime) / 1000f
        if (timeDiffSec <= 0) return 0f to 0f

        val rxSpeed = ((currentRx - previousRx) / 1024f) / timeDiffSec
        val txSpeed = ((currentTx - previousTx) / 1024f) / timeDiffSec

        previousRx = currentRx
        previousTx = currentTx
        lastNetworkTime = currentTime

        return rxSpeed.coerceAtLeast(0f) to txSpeed.coerceAtLeast(0f)
    }
}
