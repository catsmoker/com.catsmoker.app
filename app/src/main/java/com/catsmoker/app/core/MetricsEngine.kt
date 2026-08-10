package com.catsmoker.app.core

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.TrafficStats
import android.os.BatteryManager
import com.catsmoker.app.data.model.MetricReadStatus
import com.catsmoker.app.data.model.MetricsState
import com.catsmoker.app.util.CpuInfoTopParser
import com.catsmoker.app.util.ThermalServiceParser
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import kotlin.math.roundToInt

class MetricsEngine(
    private val context: Context,
    private val shizukuManager: ShizukuManager
) {
    data class MetricsSnapshot(val timestampMs: Long, val state: MetricsState)

    private val _state = MutableStateFlow(MetricsState())
    val state: StateFlow<MetricsState> = _state.asStateFlow()

    private val _fpsHistory = MutableStateFlow<List<Int>>(emptyList())
    val fpsHistory: StateFlow<List<Int>> = _fpsHistory.asStateFlow()

    private val _snapshotHistory = MutableStateFlow<List<MetricsSnapshot>>(emptyList())
    val snapshotHistory: StateFlow<List<MetricsSnapshot>> = _snapshotHistory.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var isRunning = false

    private val _screenOverrideModules = MutableStateFlow<Set<String>>(emptySet())

    private var previousRx = 0L
    private var previousTx = 0L
    private var lastNetworkTime = 0L

    private var previousCpuTimes: CpuTimes? = null

    fun start() {
        if (isRunning) return
        isRunning = true
        
        scope.launch(Dispatchers.IO) {
            shizukuManager.init()
            while (isRunning) {
                val root = Shell.getShell().isRoot
                val shizuku = shizukuManager.hasPermission.value
                withContext(Dispatchers.Main) {
                    if (_state.value.hasRoot != root || _state.value.hasShizuku != shizuku) {
                        _state.value = _state.value.copy(hasRoot = root, hasShizuku = shizuku)
                    }
                }
                delay(2000)
            }
        }

        startSystemStatsPoll()
        startHighCadencePoll()
        startFpsMonitor()
    }

    fun stop() {
        isRunning = false
        shizukuManager.destroy()
        scope.cancel()
    }

    fun setScreenOverrideModules(modules: Set<String>) {
        _screenOverrideModules.value = modules
    }

    private fun startFpsMonitor() {
        scope.launch(Dispatchers.IO) {
            val root = Shell.getShell().isRoot
            if (root) {
                Shell.cmd("dumpsys SurfaceFlinger --timestats -clear -enable").exec()
            } else if (shizukuManager.hasPermission.value) {
                shizukuManager.executeCommand("dumpsys SurfaceFlinger --timestats -clear -enable")
            }
            
            var lastKnownFps = 0
            while (isRunning) {
                val output = if (Shell.getShell().isRoot) {
                    Shell.cmd("dumpsys SurfaceFlinger --timestats -dump").exec().out.joinToString("\n")
                } else if (shizukuManager.hasPermission.value) {
                    shizukuManager.executeCommand("dumpsys SurfaceFlinger --timestats -dump")
                } else ""
                
                val parsedFps = FPS_REGEX.find(output)?.groupValues?.get(1)?.toFloatOrNull()?.toInt() ?: 0
                val janky = JANKY_REGEX.find(output)?.groupValues?.get(1)?.toIntOrNull() ?: 0

                if (parsedFps > 0) lastKnownFps = parsedFps
                
                withContext(Dispatchers.Main) {
                    _state.value = _state.value.copy(fps = lastKnownFps, jankyFrames = janky)
                    updateFpsHistory(lastKnownFps)
                    takeSnapshot()
                }

                if (isRunning && System.currentTimeMillis() % 3000L < 1000L) {
                    if (Shell.getShell().isRoot) {
                        Shell.cmd("dumpsys SurfaceFlinger --timestats -clear -enable").submit()
                    } else if (shizukuManager.hasPermission.value) {
                        shizukuManager.executeCommand("dumpsys SurfaceFlinger --timestats -clear -enable")
                    }
                }
                
                delay(1000)
            }
        }
    }

    private fun updateFpsHistory(fps: Int) {
        val history = _fpsHistory.value.toMutableList()
        history.add(fps)
        if (history.size > 60) history.removeAt(0)
        _fpsHistory.value = history
    }

    private fun takeSnapshot() {
        val snapshots = _snapshotHistory.value.toMutableList()
        snapshots.add(MetricsSnapshot(System.currentTimeMillis(), _state.value))
        if (snapshots.size > 3600) snapshots.removeAt(0)
        _snapshotHistory.value = snapshots
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
                }
                delay(2000)
            }
        }
    }

    private fun startHighCadencePoll() {
        scope.launch(Dispatchers.IO) {
            while (isRunning) {
                pollRootOrShizukuMetrics()
                
                val clusters = readClusterState()
                val cpuFreq0 = readMhz(File("/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq"))
                val cpuPct = pollCpuPercentage()
                val ping = performPing()

                withContext(Dispatchers.Main) {
                    _state.value = _state.value.copy(
                        cpuEffMhz = clusters.effMhz,
                        cpuPerfMhz = clusters.perfMhz,
                        cpuUltraMhz = clusters.ultraMhz,
                        cpuMhz = cpuFreq0,
                        cpuPercentage = cpuPct ?: _state.value.cpuPercentage,
                        pingMs = ping
                    )
                }

                delay(2000)
            }
        }
    }

    private suspend fun pollRootOrShizukuMetrics() {
        val isRoot = Shell.getShell().isRoot
        val hasShizuku = shizukuManager.hasPermission.value
        
        if (!isRoot && !hasShizuku) {
            return
        }

        val thermalOutput = if (isRoot) {
            Shell.cmd("dumpsys thermalservice").exec().out.joinToString("\n")
        } else {
            shizukuManager.getThermalTemperatures()
        }
        val thermalResult = ThermalServiceParser.parse(thermalOutput)

        val cpuOutput = if (isRoot) {
            Shell.cmd("dumpsys cpuinfo").exec().out.joinToString("\n")
        } else {
            shizukuManager.executeCommand("dumpsys cpuinfo")
        }
        val topProcesses = CpuInfoTopParser.parseTopProcesses(cpuOutput)
        val topProcess = topProcesses.firstOrNull()

        withContext(Dispatchers.Main) {
            _state.value = _state.value.copy(
                thermalCpuC = thermalResult?.cpuC ?: 0f,
                thermalGpuC = thermalResult?.gpuC ?: 0f,
                thermalSkinC = thermalResult?.skinC ?: 0f,
                thermalStatus = thermalResult?.thermalStatus ?: 0,
                thermalReadStatus = if (thermalResult != null && thermalResult.entryCount > 0) MetricReadStatus.Ok else MetricReadStatus.ParseFailed,
                topProcesses = topProcesses,
                topProcessName = topProcess?.name,
                topProcessCpuPercent = topProcess?.cpuPercent ?: 0f,
                topProcessReadStatus = if (topProcesses.isNotEmpty()) MetricReadStatus.Ok else MetricReadStatus.EmptyOutput
            )
        }
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
        } catch (e: Exception) { 0 }
    }

    private suspend fun pollCpuPercentage(): Int? {
        return try {
            val output = if (Shell.getShell().isRoot) {
                Shell.cmd("cat /proc/stat").exec().out
            } else if (shizukuManager.hasPermission.value) {
                shizukuManager.executeCommand("cat /proc/stat").lines()
            } else {
                null
            }
            
            if (output == null || output.isEmpty()) return null
            
            val current = parseTotalCpuLine(output)
            val prev = previousCpuTimes
            previousCpuTimes = current
            if (current != null && prev != null) {
                calculateCpuUsage(prev, current)
            } else null
        } catch (e: Exception) { null }
    }

    private fun parseTotalCpuLine(lines: List<String>): CpuTimes? {
        val parts = lines.firstOrNull { it.startsWith("cpu ") }?.trim()?.split("\\s+".toRegex()) ?: return null
        if (parts.size < 5) return null
        val vals = parts.drop(1).map { it.toLongOrNull() ?: return null }
        return CpuTimes(
            total = vals.sum(),
            idle = (vals.getOrNull(3) ?: 0L) + (vals.getOrNull(4) ?: 0L)
        )
    }

    private fun calculateCpuUsage(prev: CpuTimes, curr: CpuTimes): Int {
        val diffTotal = curr.total - prev.total
        val diffIdle = curr.idle - prev.idle
        if (diffTotal <= 0) return 0
        return (((diffTotal - diffIdle).toFloat() / diffTotal) * 100f).roundToInt().coerceIn(0, 100)
    }

    private data class CpuTimes(val total: Long, val idle: Long)
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

    private suspend fun performPing(): Int {
        return try {
            val output = if (Shell.getShell().isRoot) {
                Shell.cmd("ping -c 1 8.8.8.8").exec().out.joinToString("\n")
            } else if (shizukuManager.hasPermission.value) {
                shizukuManager.executeCommand("ping -c 1 8.8.8.8")
            } else ""
            
            if (output.contains("time=")) {
                output.split("time=").getOrNull(1)
                    ?.split(" ")?.getOrNull(0)
                    ?.toFloatOrNull()
                    ?.roundToInt() ?: 0
            } else 0
        } catch (e: Exception) { 0 }
    }

    companion object {
        private val FPS_REGEX = Regex("averageFPS\\s*=\\s*([0-9.]+)")
        private val JANKY_REGEX = Regex("missedFrames\\s*=\\s*([0-9]+)")
    }
}
