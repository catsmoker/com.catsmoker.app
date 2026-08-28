package com.catsmoker.app.features.main.engine

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.TrafficStats
import android.os.BatteryManager
import android.util.Log
import com.catsmoker.app.shared.data.model.FpsSource
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
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
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

    /** True once /proc/stat has been read successfully; makes it the authority for total CPU. */
    @Volatile
    private var procStatUsable = false

    /** True once `--timestats -enable` has succeeded, so dumps are worth taking. */
    private var timestatsEnabled = false

    /** The last real SurfaceFlinger average, held across the empty dump that follows a clear. */
    private var lastKnownSfFps: Int? = null

    /** Consecutive SurfaceFlinger dumps that carried no average. */
    private var sfFailures = 0

    /** Whether the vsync fallback has been launched; it is launched at most once. */
    private var choreographerStarted = false

    /**
     * Whether the vsync fallback is the channel currently in use. Read from the Choreographer
     * callback on the main thread and written from the poll loop on IO, so it is volatile.
     */
    @Volatile
    private var choreographerFpsActive = false

    fun start() {
        if (isRunning) return
        if (!scope.isActive) {
            scope = newScope()
        }
        isRunning = true

        // 1. Slow Monitor: Privileges & Status
        pollLoop(interval = 5.seconds) {
            shellRunner.refreshShizukuPermission()
            val root = shellRunner.isRootAvailable()
            val shizuku = shellRunner.shizukuHasPermission.value
            withContext(Dispatchers.Main) {
                if ((_state.value.hasRoot != root) || (_state.value.hasShizuku != shizuku)) {
                    _state.update { it.copy(hasRoot = root, hasShizuku = shizuku) }
                }
            }
        }

        // 2. Heavy Monitor: Top Processes & Detailed Info
        pollLoop(interval = 10.seconds, stagger = 1.seconds) {
            if (shellRunner.hasPrivilege()) {
                pollHeavyMetrics()
            }
        }

        startSystemStatsPoll()
        startHighCadencePoll()
        startBackgroundShellPoll()
        startFpsMonitor()
    }

    fun stop() {
        isRunning = false
        scope.cancel()
        // The vsync callback stops re-posting itself once isRunning is false, so a later start()
        // has to be able to launch it again.
        choreographerStarted = false
        choreographerFpsActive = false
        timestatsEnabled = false
        lastKnownSfFps = null
        sfFailures = 0
    }

    private fun newScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Launches a polling loop that runs [body] every [interval] until [stop], beginning after
     * [stagger] so the loops do not all fork shell commands on the same tick.
     *
     * @param setup runs once after [stagger] and before the first iteration, for baseline capture.
     */
    private fun pollLoop(
        interval: Duration,
        stagger: Duration = Duration.ZERO,
        setup: (suspend () -> Unit)? = null,
        body: suspend () -> Unit
    ) {
        scope.launch(Dispatchers.IO) {
            if (stagger > Duration.ZERO) delay(stagger)
            setup?.let { guarded(it) }
            while (isRunning) {
                guarded(body)
                delay(interval)
            }
        }
    }

    /**
     * Runs [block], absorbing whatever it throws so one bad reading cannot end the process.
     *
     * These loops live on a background dispatcher where nothing above them can catch anything, and
     * a diagnostics number is never worth the app: an `ExceptionInInitializerError` out of a
     * parser's regex used to take the whole process down. Hence [Throwable] and not `Exception` —
     * a failed static initializer is an `Error`. Cancellation is rethrown so [stop] still ends the
     * loop.
     */
    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "Metrics poll failed", t)
        }
    }

    /**
     * Picks the frame-rate channel on every tick, because privilege can arrive after start-up:
     * Shizuku may only be granted once the user reaches the dialog, and root can be authorised at
     * any point. SurfaceFlinger is preferred whenever it is reachable.
     */
    private fun startFpsMonitor() = pollLoop(interval = 1.seconds, stagger = 1.seconds) {
        if (shellRunner.hasPrivilege()) {
            // Stop the fallback from publishing over the better reading.
            choreographerFpsActive = false
            pollSurfaceFlingerFps()
        } else {
            timestatsEnabled = false
            lastKnownSfFps = null
            if (!choreographerStarted) {
                startChoreographerFpsMonitor()
                choreographerStarted = true
            }
            choreographerFpsActive = true
        }
    }

    /**
     * Frame rate from `dumpsys SurfaceFlinger --timestats`, the same source and cadence the
     * reference implementation uses. This measures the frames the display actually composited, so
     * it is the game's real frame rate rather than this app's.
     *
     * The stats accumulate until cleared, so the average is only meaningful over a window: clearing
     * happens in the first second of each three-second wall-clock cycle, which guarantees at least
     * two full seconds of frames have accumulated before the next clear. Clearing on a poll counter
     * instead would leave the very next dump empty and the reading would drop out every few
     * seconds. A dump that comes back without an average holds the last real value for a few ticks
     * rather than publishing a 0 the device never reported, and then reports the reading as lost.
     */
    private suspend fun pollSurfaceFlingerFps() {
        if (!timestatsEnabled) {
            // Start a fresh accumulation window. Do not dump yet — SurfaceFlinger needs about a
            // second of frames before it has an average to report.
            val started = shellRunner.execSafeResult(
                "dumpsys", "SurfaceFlinger", "--timestats", "-clear", "-enable"
            )
            if (started.isSuccess) {
                timestatsEnabled = true
            } else {
                publishFps(null, null, FpsSource.SurfaceFlinger, MetricReadStatus.PrivilegeDenied)
            }
            return
        }

        val dump = shellRunner.execSafeResult("dumpsys", "SurfaceFlinger", "--timestats", "-dump")
        val parsed = FPS_REGEX.find(dump.stdout)?.groupValues?.get(1)?.toFloatOrNull()?.roundToInt()
        val janky = JANKY_REGEX.find(dump.stdout)?.groupValues?.get(1)?.toIntOrNull()

        if (parsed != null && parsed > 0) {
            lastKnownSfFps = parsed
            sfFailures = 0
            publishFps(parsed, janky, FpsSource.SurfaceFlinger, MetricReadStatus.Ok)
        } else {
            sfFailures++
            val held = lastKnownSfFps
            if (held != null && sfFailures <= FPS_STALE_TOLERANCE) {
                // A gap right after a clear is normal; keep the last real value.
                publishFps(held, janky, FpsSource.SurfaceFlinger, MetricReadStatus.Ok)
            } else {
                lastKnownSfFps = null
                publishFps(
                    null, null, FpsSource.SurfaceFlinger,
                    if (dump.stdout.isBlank()) MetricReadStatus.EmptyOutput else MetricReadStatus.ParseFailed
                )
            }
        }

        if (System.currentTimeMillis() % FPS_CLEAR_CYCLE_MS < FPS_CLEAR_WINDOW_MS) {
            shellRunner.execSafeResult("dumpsys", "SurfaceFlinger", "--timestats", "-clear", "-enable")
        }
    }

    /**
     * Frame rate from this process's own vsync callbacks — the only channel available without root
     * or Shizuku.
     *
     * This counts vsyncs delivered to Catsmoker, so it tracks the display's refresh rate and this
     * app's main-thread health. It is not the frame rate of the game in front of it, which is why
     * the reading is tagged [FpsSource.Choreographer] for the UI to label.
     */
    private fun startChoreographerFpsMonitor() {
        scope.launch(Dispatchers.Main) {
            var frameCount = 0
            var lastTime = System.currentTimeMillis()
            val choreographer = android.view.Choreographer.getInstance()

            val callback = object : android.view.Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    if (!isRunning) return

                    frameCount++
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastTime >= 1000) {
                        // Count regardless, but only publish while this is the chosen channel:
                        // SurfaceFlinger's reading is the better one whenever it is available.
                        if (choreographerFpsActive) {
                            val fps = frameCount
                            _state.update {
                                it.copy(
                                    fps = fps,
                                    // Only SurfaceFlinger counts missed frames; a 0 here would
                                    // assert something this channel cannot see.
                                    jankyFrames = null,
                                    fpsSource = FpsSource.Choreographer,
                                    fpsReadStatus = MetricReadStatus.Ok
                                )
                            }
                            _fpsHistory.push(fps)
                        }
                        frameCount = 0
                        lastTime = currentTime
                    }

                    choreographer.postFrameCallback(this)
                }
            }
            choreographer.postFrameCallback(callback)
        }
    }

    private suspend fun publishFps(
        fps: Int?,
        jankyFrames: Int?,
        source: FpsSource,
        status: MetricReadStatus
    ) = withContext(Dispatchers.Main) {
        _state.update {
            it.copy(fps = fps, jankyFrames = jankyFrames, fpsSource = source, fpsReadStatus = status)
        }
        fps?.let { _fpsHistory.push(it) }
    }

    /**
     * Appends [value] to a bounded history, dropping the oldest entry once [HISTORY_CAP] is hit.
     * Publishing a fresh immutable list is what lets Compose see the change.
     */
    private fun <T> MutableStateFlow<List<T>>.push(value: T) {
        val current = this.value
        this.value = if (current.size < HISTORY_CAP) {
            current + value
        } else {
            current.subList(current.size - HISTORY_CAP + 1, current.size) + value
        }
    }

    private fun startSystemStatsPoll() = pollLoop(
        interval = 5.seconds,
        setup = {
            previousRx = TrafficStats.getTotalRxBytes()
            previousTx = TrafficStats.getTotalTxBytes()
            lastNetworkTime = System.currentTimeMillis()
        }
    ) {
        val ram = readRam()
        // One sticky broadcast serves both the temperature/level and the voltage half of the power
        // calculation, so the two readings are also from the same instant.
        val battery = batteryIntent()
        val batteryReading = readBattery(battery)
        val power = readPowerDraw(battery)
        val (rx, tx) = getNetworkSpeeds()

        withContext(Dispatchers.Main) {
            _state.update {
                it.copy(
                    ramUsedGb = ram.usedGb,
                    ramTotalGb = ram.totalGb,
                    ramReadStatus = ram.status,
                    batteryTempC = batteryReading.tempC,
                    batteryLevel = batteryReading.level,
                    batteryReadStatus = batteryReading.status,
                    powerW = power.watts,
                    powerReadStatus = power.status,
                    networkRxKbps = rx,
                    networkTxKbps = tx
                )
            }
            // Only real readings enter the sparklines; a missing sample would otherwise be drawn
            // as a dip to zero that never happened.
            ram.usedGb?.let { _ramHistory.push(it) }
            // Graph the SoC temperature when we have it; battery is the fallback.
            _state.value.displayTempC?.let { _tempHistory.push(it) }
        }
    }

    private fun startHighCadencePoll() = pollLoop(interval = 4.seconds, stagger = 500.milliseconds) {
        val cpu = pollCpuPercentage()

        withContext(Dispatchers.Main) {
            _state.update {
                when {
                    // A real delta between two /proc/stat samples: the authority for total CPU.
                    cpu.percent != null ->
                        it.copy(cpuPercentage = cpu.percent, cpuReadStatus = MetricReadStatus.Ok)
                    // /proc/stat is not readable here, so `top` in the heavy poll owns this field
                    // and publishes its own status. Overwriting it would erase a real reading.
                    !procStatUsable -> it
                    // /proc/stat works, but this sample produced no usable delta — the first one
                    // has nothing to compare against. Say so rather than keep showing a stale number.
                    else -> it.copy(cpuPercentage = null, cpuReadStatus = cpu.status)
                }
            }
            cpu.percent?.let { _cpuHistory.push(it) }
        }
    }

    private fun startBackgroundShellPoll() = pollLoop(interval = 10.seconds, stagger = 2.seconds) {
        // Guarded on its own: a thermal read that fails should not also cost the ping reading.
        guarded { pollThermalMetrics() }
        val ping = performPing()

        withContext(Dispatchers.Main) {
            _state.update { it.copy(pingMs = ping.millis, pingReadStatus = ping.status) }
            ping.millis?.let { _pingHistory.push(it) }
        }
    }

    private suspend fun pollThermalMetrics() {
        // readThermal() prefers direct sysfs, which needs no privileges — always worth trying.
        val result = ThermalServiceParser.parse(shellRunner.readThermal())
        val status = when {
            result == null -> if (shellRunner.hasPrivilege()) {
                MetricReadStatus.EmptyOutput
            } else {
                MetricReadStatus.PrivilegeDenied
            }
            result.hasAnySensor -> MetricReadStatus.Ok
            else -> MetricReadStatus.ParseFailed
        }
        withContext(Dispatchers.Main) {
            _state.update {
                it.copy(
                    thermalCpuC = result?.cpuC,
                    thermalGpuC = result?.gpuC,
                    thermalSkinC = result?.skinC,
                    thermalNpuC = result?.npuC,
                    thermalStatus = result?.thermalStatus ?: 0,
                    thermalReadStatus = status
                )
            }
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
            val cpuVal = totalCpu?.roundToInt()
            _state.update {
                val base = it.copy(
                    topProcesses = topProcesses,
                    topProcessName = topProcess?.name,
                    topProcessCpuPercent = topProcess?.cpuPercent ?: 0f,
                    topProcessReadStatus = if (topProcesses.isNotEmpty()) {
                        MetricReadStatus.Ok
                    } else {
                        MetricReadStatus.EmptyOutput
                    }
                )
                when {
                    // /proc/stat deltas are the authority for total CPU; `top`'s snapshot only fills
                    // in when that channel is unavailable, otherwise the reading visibly flip-flops
                    // between two different measurement methods.
                    procStatUsable -> base
                    cpuVal != null ->
                        base.copy(cpuPercentage = cpuVal, cpuReadStatus = MetricReadStatus.Ok)
                    else -> base.copy(
                        cpuPercentage = null,
                        cpuReadStatus = if (cpuInfoOutput.isBlank()) {
                            MetricReadStatus.EmptyOutput
                        } else {
                            MetricReadStatus.ParseFailed
                        }
                    )
                }
            }
            // This channel owns the reading while /proc/stat is unreadable, so it feeds the graph too.
            if (!procStatUsable) cpuVal?.let { _cpuHistory.push(it) }
        }
    }

    /**
     * @param percent total CPU load, or null when this poll produced no reading.
     * @param status why, when [percent] is null.
     */
    private data class CpuReading(val percent: Int?, val status: MetricReadStatus)

    /**
     * Total CPU load from the delta between two `/proc/stat` samples.
     *
     * A single sample is not a reading — the file holds cumulative jiffies since boot, so the first
     * poll can only establish a baseline. That is reported as still loading rather than as 0.
     */
    private suspend fun pollCpuPercentage(): CpuReading {
        return try {
            // ShellRunner caches whichever channel works (direct read, privileged binder, shell),
            // so this does not fork a process per poll.
            val text = shellRunner.readProcStat()
            procStatUsable = text.isNotBlank()
            if (!procStatUsable) {
                // SELinux hides /proc/stat from untrusted_app on most Android 10+ builds, so with no
                // privileged channel there is nothing to read.
                return CpuReading(
                    null,
                    if (shellRunner.hasPrivilege()) {
                        MetricReadStatus.EmptyOutput
                    } else {
                        MetricReadStatus.PrivilegeDenied
                    }
                )
            }

            val current = CpuStatParser.parseTotalCpuLine(text.lines())
            val prev = previousCpuTimes
            previousCpuTimes = current
            when {
                current == null -> CpuReading(null, MetricReadStatus.ParseFailed)
                prev == null -> CpuReading(null, MetricReadStatus.Loading)
                else -> CpuStatParser.calculateCpuUsage(prev, current)
                    ?.let { CpuReading(it, MetricReadStatus.Ok) }
                    // Two samples with no jiffies between them: there is no interval to measure
                    // over yet. Not a failure, and not a 0% either.
                    ?: CpuReading(null, MetricReadStatus.Loading)
            }
        } catch (e: Exception) {
            Log.w(TAG, "CPU load read failed", e)
            CpuReading(null, MetricReadStatus.ParseFailed)
        }
    }

    /**
     * @param millis the round-trip time, or null when no reply came back.
     * @param status why, when [millis] is null.
     */
    private data class PingReading(val millis: Int?, val status: MetricReadStatus)

    /**
     * Round-trip time to [PING_HOST], read from `ping`'s own `time=` field as the reference does.
     *
     * A run that produces no `time=` means no reply arrived — the link is down, the host is blocked,
     * or `ping` is unavailable. That is reported as no reading, not as 0 ms, which no network
     * achieves.
     */
    private suspend fun performPing(): PingReading {
        return try {
            // -W bounds the wait so a dead link cannot stall this poll for the shell's timeout.
            val output = shellRunner.exec("ping -c 1 -W $PING_TIMEOUT_SECONDS $PING_HOST")
            if (!output.contains("time=")) {
                // ping ran but nothing came back: unreachable or blocked, not a broken reading.
                PingReading(null, MetricReadStatus.EmptyOutput)
            } else {
                output.substringAfter("time=")
                    .trimStart()
                    .takeWhile { it.isDigit() || it == '.' }
                    .toFloatOrNull()
                    ?.roundToInt()
                    ?.let { PingReading(it, MetricReadStatus.Ok) }
                    ?: PingReading(null, MetricReadStatus.ParseFailed)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Ping failed", e)
            PingReading(null, MetricReadStatus.EmptyOutput)
        }
    }

    private data class RamReading(
        val usedGb: Float?,
        val totalGb: Float?,
        val status: MetricReadStatus
    )

    /**
     * RAM from `/proc/meminfo`, the same source the reference implementation reads, with
     * ActivityManager as the fallback when the file is unreadable.
     *
     * `MemAvailable` is the kernel's own estimate of what a new allocation could actually get, and
     * is the number other tools report; `MemFree + Buffers + Cached` is the pre-3.14 equivalent and
     * is used only on a kernel that does not publish `MemAvailable`. When neither channel produces
     * a total, this reports no value — a 0 GB device does not exist.
     */
    private fun readRam(): RamReading {
        val fromProc = runCatching { parseMemInfo() }
            .onFailure { Log.w(TAG, "/proc/meminfo read failed", it) }
            .getOrNull()
        if (fromProc != null) return fromProc

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return RamReading(null, null, MetricReadStatus.Unsupported)
        return runCatching {
            val mi = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(mi)
            if (mi.totalMem <= 0L) {
                RamReading(null, null, MetricReadStatus.EmptyOutput)
            } else {
                RamReading(
                    usedGb = (mi.totalMem - mi.availMem).toFloat() / BYTES_PER_GB,
                    totalGb = mi.totalMem.toFloat() / BYTES_PER_GB,
                    status = MetricReadStatus.Ok
                )
            }
        }.getOrElse { RamReading(null, null, MetricReadStatus.ParseFailed) }
    }

    /** @return the reading, or null when `/proc/meminfo` is unreadable or carries no total. */
    private fun parseMemInfo(): RamReading? {
        val file = File(PROC_MEMINFO)
        if (!file.canRead()) return null

        var totalKb = 0f
        var availKb = -1f
        var freeKb = 0f
        var buffersKb = 0f
        var cachedKb = 0f
        file.forEachLine { line ->
            val key = line.substringBefore(':', "").trim()
            if (key.isEmpty() || !line.contains(':')) return@forEachLine
            // Lines read "MemTotal:       7856132 kB" — the number is the first token after the colon.
            val value = line.substringAfter(':').trim().split(WHITESPACE).firstOrNull()
                ?.toFloatOrNull() ?: return@forEachLine
            when (key) {
                "MemTotal" -> totalKb = value
                "MemAvailable" -> availKb = value
                "MemFree" -> freeKb = value
                "Buffers" -> buffersKb = value
                // "Cached" only; "SwapCached" is a different figure and must not be folded in.
                "Cached" -> cachedKb = value
            }
        }
        if (totalKb <= 0f) return null

        val usedKb = if (availKb >= 0f) totalKb - availKb else totalKb - freeKb - buffersKb - cachedKb
        return RamReading(
            usedGb = (usedKb / KB_PER_GB).coerceAtLeast(0f),
            totalGb = totalKb / KB_PER_GB,
            status = MetricReadStatus.Ok
        )
    }

    private data class BatteryReading(
        val tempC: Float?,
        val level: Int?,
        val status: MetricReadStatus
    )

    /**
     * The sticky `ACTION_BATTERY_CHANGED` broadcast, which carries level, temperature and voltage.
     *
     * Fetched once per poll and shared by the readings that need it: passing a null receiver returns
     * the last broadcast immediately, but it is still a binder round-trip per call.
     */
    private fun batteryIntent(): Intent? = runCatching {
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }.getOrNull()

    /**
     * Battery temperature and charge level from the sticky `ACTION_BATTERY_CHANGED` broadcast, the
     * same source the reference implementation uses. Needs no permission.
     */
    private fun readBattery(intent: Intent?): BatteryReading {
        if (intent == null) return BatteryReading(null, null, MetricReadStatus.EmptyOutput)

        // EXTRA_TEMPERATURE is tenths of a degree Celsius. A device whose health HAL does not
        // report it leaves the extra absent, and a stubbed HAL reports exactly 0 — neither is a
        // battery at 0.0 °C, so neither becomes a displayed temperature.
        val rawTemp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val tempC = if (rawTemp == Int.MIN_VALUE || rawTemp == 0) null else rawTemp / 10f
        return BatteryReading(
            tempC = tempC,
            level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1).takeIf { it >= 0 },
            status = if (tempC != null) MetricReadStatus.Ok else MetricReadStatus.Unsupported
        )
    }

    private data class PowerReading(val watts: Float?, val status: MetricReadStatus)

    /**
     * Total device power draw at the battery.
     *
     * This is the only real measurement Android exposes to an app: `BATTERY_PROPERTY_CURRENT_NOW`
     * in microamps, times `EXTRA_VOLTAGE` in millivolts. There is no per-app or per-SoC power meter
     * without privileges, so the figure is the whole device.
     *
     * Both readings are taken as the platform documents them. A device that does not implement
     * `CURRENT_NOW` answers `Integer.MIN_VALUE` or 0, and a build that reports the current in the
     * wrong unit produces a product far outside what a phone battery can deliver — in either case
     * this returns no value and logs the raw numbers, rather than scaling a guess into range.
     */
    private fun readPowerDraw(intent: Intent?): PowerReading {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            ?: return PowerReading(null, MetricReadStatus.Unsupported)

        val currentUa = runCatching {
            batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        }.getOrNull() ?: return PowerReading(null, MetricReadStatus.Unsupported)

        // 0 is included in the unsupported answers: a battery that is neither charging nor
        // discharging while the screen is on does not exist, so 0 means the HAL has no value.
        if (currentUa == Int.MIN_VALUE || currentUa == 0) {
            return PowerReading(null, MetricReadStatus.Unsupported)
        }

        val voltageMv = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        if (voltageMv <= 0) return PowerReading(null, MetricReadStatus.Unsupported)

        // µA × mV is 10^-9 W. The sign only says charging or discharging, so the magnitude is the
        // draw either way.
        val watts = abs(currentUa.toDouble()) * voltageMv.toDouble() / MICRO_AMP_MILLI_VOLT_PER_WATT
        if (watts < MIN_PLAUSIBLE_WATTS || watts > MAX_PLAUSIBLE_WATTS) {
            Log.w(
                TAG,
                "Discarding implausible power reading: current_now=$currentUa uA, " +
                    "voltage=$voltageMv mV -> $watts W. This build most likely reports current_now " +
                    "in a unit other than the documented microamps; there is no way to tell which " +
                    "from here, so no value is shown rather than a rescaled guess."
            )
            return PowerReading(null, MetricReadStatus.ParseFailed)
        }
        return PowerReading(watts.toFloat(), MetricReadStatus.Ok)
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

    private companion object {
        const val TAG = "MetricsEngine"

        /** Sparkline window, in samples. */
        const val HISTORY_CAP = 60
        const val PING_HOST = "8.8.8.8"
        const val PING_TIMEOUT_SECONDS = 2

        const val PROC_MEMINFO = "/proc/meminfo"
        val WHITESPACE = Regex("\\s+")
        const val KB_PER_GB = 1024f * 1024f
        const val BYTES_PER_GB = 1024f * 1024f * 1024f

        /** µA × mV divided by this is watts. */
        const val MICRO_AMP_MILLI_VOLT_PER_WATT = 1_000_000_000.0

        /**
         * The band a real battery reading falls in. Below it means the device reported the current
         * in the wrong unit (milliamps where microamps are documented, a factor of 1000 low); above
         * it is beyond what any phone battery delivers even on the fastest charger.
         */
        const val MIN_PLAUSIBLE_WATTS = 0.02
        const val MAX_PLAUSIBLE_WATTS = 150.0

        val FPS_REGEX = Regex("averageFPS\\s*=\\s*([0-9.]+)")
        val JANKY_REGEX = Regex("missedFrames\\s*=\\s*([0-9]+)")

        /**
         * Clear the timestats window in the first [FPS_CLEAR_WINDOW_MS] of every
         * [FPS_CLEAR_CYCLE_MS], so at least two seconds of frames accumulate before the next clear.
         */
        const val FPS_CLEAR_CYCLE_MS = 3000L
        const val FPS_CLEAR_WINDOW_MS = 1000L

        /** Consecutive empty dumps tolerated before the reading is reported as lost, not stale. */
        const val FPS_STALE_TOLERANCE = 3
    }
}
