package com.catsmoker.app.features.gamingtools.engine

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.AudioManager
import android.provider.Settings
import androidx.core.content.edit
import com.catsmoker.app.shared.data.model.GamingOptimizationSnapshot
import com.catsmoker.app.shared.data.model.SettingValue
import com.catsmoker.app.features.gamingtools.engine.parsers.DexoptStatusParser
import com.catsmoker.app.features.gamingtools.tools.firewall.BackgroundDataRestrictor
import com.catsmoker.app.system.shell.ShellRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

sealed class GamingModeState {
    object Idle : GamingModeState()
    data class Enabling(val progress: Float = 0f, val statusText: String = "Preparing…") : GamingModeState()
    object Active : GamingModeState()
    object Disabling : GamingModeState()
    data class Error(val message: String) : GamingModeState()
}

/**
 * What activation actually managed to apply, verified by reading each value back.
 *
 * The card used to show four fixed strings whether or not anything landed. Every field here is
 * either a measured fact or absent, and [unavailable] carries the human-readable reason for each
 * optimization the device refused — that is what gets shown instead of a slogan.
 */
data class GamingModeReport(
    val fixedPerformance: Boolean = false,
    /** Refresh rate the panel was actually pinned to, or null when the ROM ignored the keys. */
    val lockedRefreshHz: Int? = null,
    val touchResponseBoost: Boolean = false,
    val suspendedPackages: Int = 0,
    val suspendFailures: Int = 0,
    val dndEngaged: Boolean = false,
    /** null when no game was targeted, so the UI can say "not applicable" instead of "failed". */
    val networkWhitelisted: Boolean? = null,
    val unavailable: List<String> = emptyList()
)

/**
 * The three animation scales Android's Developer Options exposes, with the keys it writes.
 *
 * Kept as the real `Settings.Global` constants: these are the same settings the system screen edits,
 * so a change here is the same change made there — not an app-local imitation of one.
 */
enum class AnimationScaleKind(val key: String, val label: String) {
    WINDOW(Settings.Global.WINDOW_ANIMATION_SCALE, "Window animation scale"),
    TRANSITION(Settings.Global.TRANSITION_ANIMATION_SCALE, "Transition animation scale"),
    ANIMATOR(Settings.Global.ANIMATOR_DURATION_SCALE, "Animator duration scale")
}

/**
 * How the ART dexopt sweep ended, or that it never began.
 *
 * Mirrors the reference project's `OptimizationResult`, including the distinction it draws between
 * a cancelled run and a completed one — its repository carries an explicit "never overwrite a
 * cancellation with 'Completed'" guard, and that only works if the two are separate states.
 */
sealed class BoosterOutcome {
    /** Nothing has been run in this session. */
    object Idle : BoosterOutcome()
    object Running : BoosterOutcome()
    object Completed : BoosterOutcome()
    object Cancelled : BoosterOutcome()

    /** The sweep could not start at all; [reason] is what the device or the shell refused. */
    data class Unavailable(val reason: String) : BoosterOutcome()

    /** The sweep started and then broke; [reason] is the real error text. */
    data class Failed(val reason: String) : BoosterOutcome()
}

/**
 * What the ART dexopt sweep is doing right now.
 *
 * Modelled on the reference project's `OptimizationProgress`: the running flag, the counts and the
 * outcome travel together, so the UI can tell "preparing", "compiling app 12 of 90", "cancelled
 * after 12" and "finished" apart. A bare progress float could not — it reads 0 both before a run
 * starts and after one is cancelled, which is why the old card showed nothing in either case.
 *
 * Every count is incremented from a command that actually ran and actually answered.
 */
data class BoosterState(
    val isRunning: Boolean = false,
    /** Package being compiled at this moment, or null between packages. */
    val currentPackage: String? = null,
    /** Apps the sweep will visit. 0 until the package list has been queried. */
    val totalCount: Int = 0,
    /** Apps `cmd package compile` accepted. */
    val optimizedCount: Int = 0,
    /** Apps already in the requested compile filter, so no command was run for them. */
    val skippedCount: Int = 0,
    /** Apps the platform refused to compile. */
    val failedCount: Int = 0,
    val outcome: BoosterOutcome = BoosterOutcome.Idle
) {
    /** Apps visited, whether compiled, skipped or refused. */
    val processedCount: Int get() = optimizedCount + skippedCount + failedCount

    /**
     * Fraction done, or null while the total is still unknown. The UI shows an indeterminate bar
     * then, rather than a 0% that would read as a run that has stalled.
     */
    val progress: Float?
        get() = if (totalCount > 0) processedCount.toFloat() / totalCount.toFloat() else null
}

class GamingEngine(
    private val context: Context,
    private val shellRunner: ShellRunner,
    val deviceDiagnosticManager: DeviceDiagnosticManager
) {
    private val _state = MutableStateFlow<GamingModeState>(GamingModeState.Idle)
    val state: StateFlow<GamingModeState> = _state.asStateFlow()

    private val _report = MutableStateFlow(GamingModeReport())
    val report: StateFlow<GamingModeReport> = _report.asStateFlow()

    private val _isFixedPerformanceMode = MutableStateFlow(value = false)
    val isFixedPerformanceMode: StateFlow<Boolean> = _isFixedPerformanceMode.asStateFlow()

    private val _alwaysFinishActivities = MutableStateFlow(false)
    val alwaysFinishActivities: StateFlow<Boolean> = _alwaysFinishActivities.asStateFlow()

    private val _backgroundProcessLimit = MutableStateFlow(false)
    val backgroundProcessLimit: StateFlow<Boolean> = _backgroundProcessLimit.asStateFlow()

    private val _boosterLog = MutableStateFlow<List<String>>(emptyList())
    val boosterLog: StateFlow<List<String>> = _boosterLog.asStateFlow()

    private val _boosterState = MutableStateFlow(BoosterState())
    val boosterState: StateFlow<BoosterState> = _boosterState.asStateFlow()

    private val _animationScales = MutableStateFlow(Triple(1f, 1f, 1f))
    val animationScales: StateFlow<Triple<Float, Float, Float>> = _animationScales.asStateFlow()

    private val boosterCancelRequested = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Held for the whole of one sweep and released in its `finally`.
     *
     * The published [BoosterState] cannot be the guard: cancellation clears `isRunning` immediately
     * so the UI updates without waiting for the current compile, which would leave a window where a
     * second Start could begin walking the package list alongside the first.
     */
    private val boosterActive = java.util.concurrent.atomic.AtomicBoolean(false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var currentCompileProcess: Process? = null

    private val prefs = context.getSharedPreferences("gaming_engine_prefs", Context.MODE_PRIVATE)

    init {
        val isActive = prefs.getBoolean("is_active", false)
        val isFixedPerf = prefs.getBoolean("fixed_perf_manual", false)
        
        if (isActive) {
            _state.value = GamingModeState.Active
            _isFixedPerformanceMode.value = true
            scope.launch { recoverPersistedState() }
        } else if (isFixedPerf) {
            _isFixedPerformanceMode.value = true
            scope.launch { reapplyFixedPerformanceMode() }
        }

        _alwaysFinishActivities.value = getGlobalInt(android.provider.Settings.Global.ALWAYS_FINISH_ACTIVITIES) == 1
        _backgroundProcessLimit.value = getGlobalString("activity_manager_constants")?.contains("max_cached_processes=1") == true

        refreshAnimationScales()
    }

    private fun getGlobalInt(key: String): Int {
        return try { android.provider.Settings.Global.getInt(context.contentResolver, key, 0) } catch (_: Exception) { 0 }
    }

    private fun getGlobalString(key: String): String? {
        return android.provider.Settings.Global.getString(context.contentResolver, key)
    }

    private fun getSystemString(key: String): String? {
        return android.provider.Settings.System.getString(context.contentResolver, key)
    }

    val googleSafeToSuspend = listOf(
        "com.google.android.youtube",
        "com.google.android.apps.photos",
        "com.google.android.apps.maps",
        "com.google.android.gm",
        "com.google.android.apps.messaging",
        "com.google.android.calendar",
        "com.google.android.googlequicksearchbox",
        "com.google.android.apps.bard",
        "com.google.android.apps.nbu.files",
        "com.google.android.apps.wellbeing",
        "com.google.android.projection.gearhead",
        "com.google.android.apps.authenticator2",
        "com.google.android.apps.restore",
        "com.android.chrome"
    )

    val systemCritical = listOf(
        "com.vivo.pem",                // Power Event Manager
        "com.vivo.abe",                // App Behavior Engine
        "com.vivo.daemonService",      // Hardware daemon
        "com.vivo.sps",                // System Power Service
        "com.vivo.pie",                // Framework extension
        "com.vivo.fingerprintui",
        "com.vivo.fingerprint",
        "com.vivo.fingerprintvit",
        "com.vivo.faceui",
        "com.vivo.faceunlock",
        "com.vivo.systemuiplugin",
        "com.vivo.networkstate",
        "com.vivo.connbase",
        "com.android.systemui",
        "com.android.phone",
        "com.mediatek.ims"              // VoLTE
    )

    val gamingDaemons = listOf(
        "com.vivo.gamecube",
        "com.vivo.gamewatch",
        "com.vivo.game",
        "com.iqoo.powersaving",        // Prevents thermal throttling
        "com.microsoft.deviceintegrationservice"  // ThermalInfoService bridge
    )

    private val vivoGameCubeApps = "game_cube_apps"
    private val vivoSpeedModeApps = "speed_mode_apps"

    private val hardWhitelist = setOf(
        "moe.shizuku.privileged.api",
        context.packageName,
        "com.topjohnwu.magisk"
    )

    suspend fun toggleGamingMode(active: Boolean, packageName: String? = null) {
        withContext(Dispatchers.IO) {
            if (active) {
                enableGamingMode(packageName)
            } else {
                disableGamingMode()
            }
        }
    }

    private suspend fun enableGamingMode(packageName: String?) {
        _state.value = GamingModeState.Enabling(0f, "Initializing…")
        shellRunner.refreshShizukuPermission()
        val isRoot = shellRunner.isRootAvailable()
        val hasShizuku = shellRunner.shizukuHasPermission.value
        if (!isRoot && !hasShizuku) {
            _state.value = GamingModeState.Error("Root or Shizuku permission required")
            return
        }
        try {
            _state.value = GamingModeState.Enabling(0.05f, "Capturing system snapshot…")
            if (!captureAndSaveSnapshot(packageName)) {
                _state.value = GamingModeState.Error("Could not read current system settings")
                return
            }
            val unavailable = mutableListOf<String>()

            _state.value = GamingModeState.Enabling(0.15f, "Trimming system caches…")
            execute("pm trim-caches 4G")
            execute("am compact background")
            runCatching { execute("cmd pinner repin /system/framework/framework.jar") }

            _state.value = GamingModeState.Enabling(0.35f, "Suspending background apps…")
            val targets = getSuspendTargets(packageName)
            val currentlyAffected = prefs.getStringSet("affected_pkgs", emptySet())?.toMutableSet() ?: mutableSetOf()
            var suspendedNow = 0
            var suspendFailures = 0
            for (pkg in targets) {
                if (pkg in currentlyAffected) continue
                // Record only what actually got suspended. disableGamingMode unsuspends exactly
                // this set, so a package listed here that was never frozen makes the revert lie —
                // and a package frozen but not listed would be left frozen for good.
                val result = shellRunner.execSafeResult("pm", "suspend", "--user", "0", pkg)
                val refused = result.stdout.contains("state: false", ignoreCase = true)
                if (result.isSuccess && !refused) {
                    currentlyAffected.add(pkg)
                    suspendedNow++
                } else {
                    suspendFailures++
                }
            }
            prefs.edit { putStringSet("affected_pkgs", currentlyAffected) }
            if (suspendFailures > 0) {
                unavailable += "$suspendFailures app(s) could not be suspended"
            }

            _state.value = GamingModeState.Enabling(0.6f, "Configuring Focus Mode…")
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            var dndEngaged = false
            if (nm.isNotificationPolicyAccessGranted) {
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
                dndEngaged = nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_NONE
                if (!dndEngaged) unavailable += "Do Not Disturb (the system did not apply the filter)"
            } else {
                unavailable += "Do Not Disturb (notification policy access not granted)"
            }

            _state.value = GamingModeState.Enabling(0.9f, "Applying hardware locks…")
            val maxHz = deviceDiagnosticManager.getMaxHardwareRefreshRate().toInt()
            val peakOk = putSettingVerified("system", "peak_refresh_rate", maxHz.toString())
            val minOk = putSettingVerified("system", "min_refresh_rate", maxHz.toString())
            val lockedHz = if (peakOk || minOk) maxHz else null
            if (lockedHz == null) {
                unavailable += "Refresh-rate lock (this ROM ignores min/peak_refresh_rate)"
            }
            // Touch sampling boost. Only OEMs that ship the key honour it; captureAndSaveSnapshot
            // already recorded the old value, so disableGamingMode puts it back.
            val touchOk = putSettingVerified("system", "touch_response_speed", "2")
            if (!touchOk) unavailable += "Touch response boost (key not supported on this device)"

            val fixedPerfOk = shellRunner
                .execSafeResult("cmd", "power", "set-fixed-performance-mode-enabled", "true")
                .isSuccess
            if (!fixedPerfOk) unavailable += "Fixed performance mode (PowerHAL rejected the request)"

            var networkWhitelisted: Boolean? = null
            if (packageName != null) {
                execute("pm unsuspend --user 0 $packageName")
                execute("cmd activity set-bg-restriction-level --user 0 $packageName unrestricted")
                execute("am set-standby-bucket --user 0 $packageName active")
                execute("cmd deviceidle whitelist +$packageName")
                networkWhitelisted = applyPerGameOptimizations(packageName, maxHz)
            }
            execute("cmd deviceidle force-idle")
            execute("am kill-all")

            prefs.edit { putBoolean("is_active", true) }
            _isFixedPerformanceMode.value = fixedPerfOk
            _report.value = GamingModeReport(
                fixedPerformance = fixedPerfOk,
                lockedRefreshHz = lockedHz,
                touchResponseBoost = touchOk,
                suspendedPackages = suspendedNow,
                suspendFailures = suspendFailures,
                dndEngaged = dndEngaged,
                networkWhitelisted = networkWhitelisted,
                unavailable = unavailable
            )
            _state.value = GamingModeState.Active
        } catch (e: Exception) {
            _state.value = GamingModeState.Error(e.message ?: "Activation failed")
        }
    }

    private suspend fun disableGamingMode() {
        _state.value = GamingModeState.Disabling
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.isNotificationPolicyAccessGranted) {
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        }
        val affected = prefs.getStringSet("affected_pkgs", emptySet()) ?: emptySet()
        for (pkg in affected) {
            execute("pm unsuspend --user 0 $pkg")
        }
        prefs.edit { remove("affected_pkgs") }
        execute("cmd deviceidle unforce")
        execute("cmd power set-fixed-performance-mode-enabled false")
        revertFromSnapshot()
        prefs.edit().putBoolean("is_active", false).putBoolean("fixed_perf_manual", false).apply()
        _isFixedPerformanceMode.value = false
        _report.value = GamingModeReport()
        _state.value = GamingModeState.Idle
    }

    /**
     * Result of a manual RAM boost.
     *
     * @param freedMb megabytes of available memory gained, measured before and after. null when the
     *   device would not let us read the memory figures at all — the UI must say "unknown" rather
     *   than print a zero that looks like a measurement.
     * @param stoppedCount packages `am force-stop` actually accepted.
     * @param attemptedCount packages it was asked to stop.
     */
    data class RamBoostResult(
        val freedMb: Long?,
        val stoppedCount: Int,
        val attemptedCount: Int
    )

    /**
     * Frees background memory and measures the result.
     *
     * The "before" reading is taken *first*, before any cache trimming, so the number covers the
     * whole operation. `MemAvailable` from /proc/meminfo is the kernel's own estimate of what can be
     * handed out without swapping — the same source the reference RamMonitor parses — with
     * [android.app.ActivityManager.MemoryInfo] as the fallback when the file cannot be read.
     */
    suspend fun manualBoostRam(): RamBoostResult {
        val availBefore = readAvailableMemoryBytes()
        var stoppedCount = 0
        var attempted = 0
        try {
            execute("pm trim-caches 4G")
            execute("am compact background")
            val targets = getSuspendTargets(null)
            attempted = targets.size
            for (pkg in targets) {
                // execute() never throws for a command that merely failed, so counting attempts
                // would report apps that were never stopped. Only a zero exit counts.
                if (shellRunner.execSafeResult("am", "force-stop", pkg).isSuccess) stoppedCount++
            }
            execute("am kill-all")
        } catch (_: Exception) {}
        // The kills are asynchronous; give the kernel a moment to reclaim before measuring again.
        delay(500.milliseconds)
        val availAfter = readAvailableMemoryBytes()
        val freedMb = if (availBefore != null && availAfter != null) {
            ((availAfter - availBefore) / (1024L * 1024L)).coerceAtLeast(0L)
        } else {
            null
        }
        return RamBoostResult(freedMb = freedMb, stoppedCount = stoppedCount, attemptedCount = attempted)
    }

    /** @return available bytes, or null when neither /proc/meminfo nor ActivityManager could answer. */
    private fun readAvailableMemoryBytes(): Long? {
        parseMemAvailableBytes()?.let { return it }
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val info = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            info.availMem
        } catch (_: Exception) {
            null
        }
    }

    private fun parseMemAvailableBytes(): Long? = try {
        java.io.File("/proc/meminfo").useLines { lines ->
            lines.firstOrNull { it.startsWith("MemAvailable:") }
                ?.split(WHITESPACE)
                ?.getOrNull(1)
                ?.toLongOrNull()
                ?.times(1024L)
        }
    } catch (_: Exception) {
        null
    }

    suspend fun resetToDeviceDefaults(): Boolean {
        _state.value = GamingModeState.Disabling
        val pm = context.packageManager
        val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        installedApps.asSequence().filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }.forEach {
            execute("pm unsuspend --user 0 ${it.packageName}")
        }
        val cmds = listOf(
            "settings delete system min_refresh_rate",
            "settings delete system peak_refresh_rate",
            "settings put secure user_preferred_display_mode_id -1",
            "settings delete global vivo_screen_refresh_rate_mode",
            "settings delete system touch_response_speed",
            "settings delete global game_cube_apps",
            "settings delete global speed_mode_apps",
            "settings delete global vivo_high_refresh_rate_apps",
            "settings delete global vivo_screen_refresh_rate_apps_list",
            "cmd power set-fixed-performance-mode-enabled false",
            "cmd thermalservice reset",
            "cmd deviceidle unforce"
        )
        cmds.forEach { execute(it) }
        prefs.edit {
            remove("affected_pkgs")
            putBoolean("is_active", false)
            putBoolean("fixed_perf_manual", false)
        }
        _isFixedPerformanceMode.value = false
        _state.value = GamingModeState.Idle
        return true
    }

    suspend fun toggleFixedPerformanceMode(enabled: Boolean) {
        if (enabled) {
            execute("cmd power set-fixed-performance-mode-enabled true")
            execute("am compact background")
            execute("cmd pinner repin /system/framework/framework.jar")
        } else {
            execute("cmd power set-fixed-performance-mode-enabled false")
            execute("cmd deviceidle unforce")
        }
        prefs.edit { putBoolean("fixed_perf_manual", enabled) }
        _isFixedPerformanceMode.value = enabled
    }

    /** @return true when the setting actually holds the requested value afterwards. */
    suspend fun toggleAlwaysFinishActivities(enabled: Boolean): Boolean {
        val target = if (enabled) 1 else 0
        execute("settings put global always_finish_activities $target")
        // `settings put` is silent on success, so confirm by reading the value back.
        val applied = getGlobalInt(Settings.Global.ALWAYS_FINISH_ACTIVITIES) == target
        _alwaysFinishActivities.value = applied && enabled
        return applied
    }

    /**
     * Merges (or removes) `max_cached_processes` inside `activity_manager_constants` instead of
     * replacing the whole CSV, so any other constants the ROM set survive.
     * @return true when the change is visible in settings afterwards.
     */
    suspend fun toggleBackgroundProcessLimit(enabled: Boolean): Boolean {
        val current = getGlobalString("activity_manager_constants").orEmpty()
        val merged = if (enabled) {
            upsertCsvKey(current, "max_cached_processes", "1")
        } else {
            removeCsvKey(current, "max_cached_processes")
        }
        if (merged.isBlank()) {
            execute("settings delete global activity_manager_constants")
        } else {
            execute("settings put global activity_manager_constants $merged")
        }
        val readBack = getGlobalString("activity_manager_constants").orEmpty()
        val applied = readBack.contains("max_cached_processes=1") == enabled
        _backgroundProcessLimit.value = applied && enabled
        return applied
    }

    // The standalone refresh-rate control lives in GameDeveloperOptions, which implements Android's
    // own "Force peak refresh rate" switch (min_refresh_rate as a float, verified by read-back).
    // Gaming mode still writes both min and peak itself, and still restores both from its snapshot.

    /**
     * Compiles every user app with `cmd package compile` — the same command Android's own `pm
     * compile` front-end runs, so this is real ART dexopt rather than an imitation of it.
     *
     * Follows the reference project's optimisation run: a privilege check before anything is
     * claimed, its own package left out so a forced recompile cannot kill the process doing the
     * compiling, a cancellation flag checked between packages, and a completion that can never
     * overwrite a cancellation. Each package is counted from what the shell actually answered, so
     * the closing summary is a tally of real results and not of attempts made.
     */
    suspend fun runArtOptimization(mode: String = "speed-profile", force: Boolean = false) {
        if (!boosterActive.compareAndSet(false, true)) {
            addBoosterLog("⚠ An optimization is already running.")
            return
        }
        try {
            _boosterLog.value = emptyList()
            boosterCancelRequested.set(false)
            _boosterState.value = BoosterState(isRunning = true, outcome = BoosterOutcome.Running)
            addBoosterLog("🚀 Starting ART Optimization (mode: $mode${if (force) ", forced" else ""})…")

            // `cmd package compile` is refused for other packages without a privileged shell.
            // Checking first means the log says why nothing happened, instead of listing apps that
            // were never touched and then reporting a success that never took place.
            if (!shellRunner.hasPrivilege()) {
                fail(BoosterOutcome.Unavailable(NO_PRIVILEGE_REASON), "❌ $NO_PRIVILEGE_REASON")
                return
            }

            val apps = eligibleBoosterPackages()
            if (apps.isEmpty()) {
                fail(BoosterOutcome.Unavailable(NO_APPS_REASON), "❌ $NO_APPS_REASON")
                return
            }
            addBoosterLog("📦 Found ${apps.size} apps to optimize.")
            _boosterState.update { it.copy(totalCount = apps.size) }

            val useRoot = shellRunner.isRootAvailable()
            addBoosterLog(if (useRoot) "🔑 Running through the root shell." else "🔑 Running through Shizuku.")

            // Without -f the platform skips an app that is already in the requested filter, so ask
            // it up front what each app is compiled with and say which ones are being skipped.
            val currentStatuses = if (force) emptyMap() else queryDexoptStatuses()

            for (pkg in apps) {
                if (boosterCancelRequested.get()) return

                if (!force && currentStatuses[pkg] == mode) {
                    addBoosterLog("⏭ Skipping (already $mode): $pkg")
                    _boosterState.update { it.copy(currentPackage = null, skippedCount = it.skippedCount + 1) }
                    continue
                }

                _boosterState.update { it.copy(currentPackage = pkg) }
                addBoosterLog("⚡ Optimizing: $pkg")
                val outcome = runCompileCommand(mode, force, pkg, useRoot)

                // A cancel that landed mid-compile: the partial result is not a failure of this app.
                if (boosterCancelRequested.get()) return

                if (outcome.succeeded) {
                    addBoosterLog("✓ $pkg${outcome.detail.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""}")
                    _boosterState.update { it.copy(currentPackage = null, optimizedCount = it.optimizedCount + 1) }
                } else {
                    addBoosterLog("✗ $pkg — ${outcome.detail}")
                    _boosterState.update { it.copy(currentPackage = null, failedCount = it.failedCount + 1) }
                }
            }

            // The reference's own guard: a cancellation that arrived during the last compile must
            // not be reported as a completed sweep.
            if (boosterCancelRequested.get()) return

            val finished = _boosterState.value
            addBoosterLog(
                "✅ Complete — ${finished.optimizedCount} optimized, " +
                    "${finished.skippedCount} already current, ${finished.failedCount} failed."
            )
            _boosterState.value = finished.copy(
                isRunning = false,
                currentPackage = null,
                outcome = BoosterOutcome.Completed
            )
        } catch (e: CancellationException) {
            markBoosterCancelled()
            throw e
        } catch (e: Exception) {
            val reason = e.message ?: e.javaClass.simpleName
            fail(BoosterOutcome.Failed(reason), "❌ Optimization failed: $reason")
        } finally {
            // Whatever happened, nothing is left compiling and a new run can start.
            if (boosterCancelRequested.get()) markBoosterCancelled()
            currentCompileProcess?.destroy()
            currentCompileProcess = null
            boosterActive.set(false)
        }
    }

    /** Ends the run with a stated reason instead of a silent stop. */
    private fun fail(outcome: BoosterOutcome, logLine: String) {
        addBoosterLog(logLine)
        _boosterState.update { it.copy(isRunning = false, currentPackage = null, outcome = outcome) }
    }

    /**
     * The apps worth compiling: everything the user installed, plus any system app they added to
     * their own game library.
     */
    private fun eligibleBoosterPackages(): List<String> {
        val userAdded = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
            .getStringSet("user_games", emptySet()) ?: emptySet()
        return context.packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            .asSequence()
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || it.packageName in userAdded }
            .map { it.packageName }
            // Recompiling ourselves restarts this process and takes the sweep down with it. The
            // reference excludes its own package for the same reason.
            .filter { it != context.packageName }
            .distinct()
            .toList()
    }

    /**
     * Records a cancellation without discarding the counts: the run stopped where it stopped, and
     * the UI reports how far it got rather than resetting as if it had never started.
     */
    private fun markBoosterCancelled() {
        val current = _boosterState.value
        // Only a run still in progress can be cancelled. Once it has reported how it ended —
        // completed, unavailable, failed, or already cancelled — that stands: a Stop that arrives
        // in the moment after the last package must not rewrite a finished sweep as a cancelled one.
        if (current.outcome !is BoosterOutcome.Running) return
        addBoosterLog("⏹ Optimization cancelled after ${current.processedCount} apps.")
        _boosterState.value = current.copy(
            isRunning = false,
            currentPackage = null,
            outcome = BoosterOutcome.Cancelled
        )
    }

    private suspend fun queryDexoptStatuses(): Map<String, String> {
        return withContext(Dispatchers.IO) {
            DexoptStatusParser.parse(execute("dumpsys package dexopt"))
        }
    }

    /** What `cmd package compile` actually reported for one package. */
    private data class CompileOutcome(val succeeded: Boolean, val detail: String)

    /**
     * Compiles one package and reports what the platform said about it.
     *
     * The root path spawns `su` directly instead of going through [ShellRunner] because cancelling
     * has to be able to `destroy()` this exact process: both libsu and the Shizuku binder call
     * block until the compile returns, and a dexopt of a large app is not quick.
     */
    private suspend fun runCompileCommand(
        mode: String,
        force: Boolean,
        pkg: String,
        useRoot: Boolean
    ): CompileOutcome = withContext(Dispatchers.IO) {
        val args = buildList {
            add("cmd"); add("package"); add("compile"); add("-m"); add(mode)
            if (force) add("-f")
            add(pkg)
        }
        if (!useRoot) {
            val result = shellRunner.execSafeResult(*args.toTypedArray())
            return@withContext classifyCompileOutput(result.exitCode, result.text)
        }
        try {
            val process = ProcessBuilder("su", "-c", args.joinToString(" "))
                .redirectErrorStream(true)
                .start()
            currentCompileProcess = process
            // Drained on this thread: an unread pipe fills up and stalls the compiler, and the
            // output is also the only place the platform says whether it accepted the request.
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exit = process.waitFor()
            currentCompileProcess = null
            classifyCompileOutput(exit, output)
        } catch (e: Exception) {
            currentCompileProcess = null
            CompileOutcome(false, e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Decides whether one compile was accepted, from the exit code and the shell's own words.
     *
     * `cmd package compile` prints its verdict — `Success`, or a `Failure`/`Error:` line — and some
     * builds still exit 0 after refusing, so both signals are read. A silent exit 0 is taken at
     * face value; inventing a failure there would be as wrong as inventing a success.
     */
    private fun classifyCompileOutput(exitCode: Int, output: String): CompileOutcome {
        val text = output.trim()
        val lower = text.lowercase(java.util.Locale.US)
        return when {
            exitCode != 0 -> CompileOutcome(false, text.ifBlank { "exit code $exitCode" })
            lower.startsWith("failure") || lower.contains("error:") -> CompileOutcome(false, text)
            else -> CompileOutcome(true, text.takeUnless { it.equals("Success", ignoreCase = true) }.orEmpty())
        }
    }

    /**
     * User-initiated stop. Says plainly when there was nothing to stop, matching the reference's
     * own "No optimization is currently running." response.
     */
    suspend fun cancelArtOptimization() {
        if (!requestBoosterCancel()) {
            addBoosterLog("ℹ No optimization is currently running.")
            return
        }
        // The platform forks dex2oat on our behalf, and it outlives the shell that asked for it.
        shellRunner.killCurrentProcess()
    }

    /**
     * Stop from a component that is being torn down.
     *
     * The flag and the `destroy()` run on the calling thread, so the sweep is unblocked before this
     * returns even though the caller's scope is about to die; the `pkill` needs a coroutine and
     * goes to the engine's own scope, which outlives the service.
     *
     * @return true when a run was actually in progress.
     */
    fun cancelArtOptimizationNow(): Boolean {
        if (!requestBoosterCancel()) return false
        scope.launch { shellRunner.killCurrentProcess() }
        return true
    }

    /**
     * Raises the cancellation flag and unblocks the compile in flight.
     *
     * @return false when no sweep was running, so callers can say so rather than report a stop
     *   that stopped nothing.
     */
    private fun requestBoosterCancel(): Boolean {
        if (!boosterActive.get()) return false
        boosterCancelRequested.set(true)
        // Ends the blocking read and waitFor in runCompileCommand, so the loop reaches its next
        // cancellation check instead of waiting out the current dexopt.
        currentCompileProcess?.destroy()
        currentCompileProcess = null
        markBoosterCancelled()
        return true
    }

    /**
     * Reads the three animation scales out of `Settings.Global`.
     *
     * The `1f` fallbacks are the platform's own defaults for these keys — an unset key genuinely
     * means "normal speed" — so this is the real effective value, not a placeholder. Same getters
     * the reference project uses in `SettingsManager.getWindowAnimationScale` and friends.
     */
    fun refreshAnimationScales() {
        val cr = context.contentResolver
        val w = Settings.Global.getFloat(cr, Settings.Global.WINDOW_ANIMATION_SCALE, 1f)
        val t = Settings.Global.getFloat(cr, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f)
        val a = Settings.Global.getFloat(cr, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        _animationScales.value = Triple(w, t, a)
    }

    /**
     * True when this app has a channel that can write `Settings.Global`.
     *
     * Root and Shizuku both run `settings put`; a plain install cannot, unless `WRITE_SECURE_SETTINGS`
     * was granted over adb, which is the reference project's own fallback path. Anything else and the
     * write will be refused, so the UI is told up front rather than after a silent failure.
     */
    fun canWriteAnimationScales(): Boolean =
        shellRunner.hasPrivilege() || hasWriteSecureSettings()

    private fun hasWriteSecureSettings(): Boolean =
        context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Writes one animation scale and confirms it by reading the value back.
     *
     * Both routes are the reference project's (`SettingsManager.setGlobalFloat`): the privileged
     * shell first, then a direct `Settings.Global.putFloat` for a build where `WRITE_SECURE_SETTINGS`
     * has been granted. `settings put` exits 0 without checking that the key was accepted, so the
     * read-back — not the exit code — decides what this reports.
     *
     * @return true only when the setting now actually holds [value].
     */
    suspend fun setAnimationScale(scale: AnimationScaleKind, value: Float): Boolean =
        withContext(Dispatchers.IO) {
            // Two decimals, matching the reference, so 0.5 is written as "0.50" rather than in
            // whatever form Float.toString() happens to pick.
            val formatted = String.format(java.util.Locale.US, "%.2f", value)

            if (shellRunner.hasPrivilege()) {
                shellRunner.execSafeResult("settings", "put", "global", scale.key, formatted)
            }
            if (!readsBack(scale, value) && hasWriteSecureSettings()) {
                runCatching { Settings.Global.putFloat(context.contentResolver, scale.key, value) }
            }

            val applied = readsBack(scale, value)
            refreshAnimationScales()
            applied
        }

    /** Compares numerically: a ROM may store "0.5" for a written "0.50", and both mean the same. */
    private fun readsBack(scale: AnimationScaleKind, value: Float): Boolean {
        val current = runCatching {
            Settings.Global.getFloat(context.contentResolver, scale.key, Float.NaN)
        }.getOrDefault(Float.NaN)
        return !current.isNaN() && kotlin.math.abs(current - value) < 0.005f
    }

    /**
     * Sets all three scales.
     *
     * @return the scales that could not be written, so the caller can name them instead of
     *   reporting a blanket success.
     */
    suspend fun setAnimationScales(window: Float, transition: Float, animator: Float): List<AnimationScaleKind> {
        val failed = mutableListOf<AnimationScaleKind>()
        if (!setAnimationScale(AnimationScaleKind.WINDOW, window)) failed += AnimationScaleKind.WINDOW
        if (!setAnimationScale(AnimationScaleKind.TRANSITION, transition)) failed += AnimationScaleKind.TRANSITION
        if (!setAnimationScale(AnimationScaleKind.ANIMATOR, animator)) failed += AnimationScaleKind.ANIMATOR
        return failed
    }

    /**
     * Appends one line to the booster log.
     *
     * [MutableStateFlow.update] rather than a read-then-write, because the sweep logs from the IO
     * dispatcher while a stop can come in from the main thread, and a lost line would be a step
     * that happened with nothing to show it.
     */
    private fun addBoosterLog(msg: String) {
        _boosterLog.update { it + msg }
    }

    suspend fun execute(command: String): String = shellRunner.exec(command)

    suspend fun executeSafe(vararg args: String): String = shellRunner.execSafe(*args)

    private fun getSuspendTargets(activeGamePkg: String?): List<String> {
        val pm = context.packageManager
        val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val appPrefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val userGames = appPrefs.getStringSet("user_games", emptySet()) ?: emptySet()
        val removedGames = appPrefs.getStringSet("removed_games", emptySet()) ?: emptySet()
        val launcherPkgs = getLauncherPackages()
        val userApps = installedApps.filter { ai ->
            val pkg = ai.packageName
            val isLibraryGame = (ai.category == ApplicationInfo.CATEGORY_GAME || pkg in userGames) && pkg !in removedGames
            val isWhitelisted = pkg == activeGamePkg || isLibraryGame || pkg in hardWhitelist ||
                pkg in systemCritical || pkg in gamingDaemons || pkg in launcherPkgs
            (ai.flags and ApplicationInfo.FLAG_SYSTEM) == 0 && !isWhitelisted
        }.map { it.packageName }
        val googleApps = googleSafeToSuspend.filter { pkg ->
            val isInLibrary = pkg in userGames || try {
                pm.getApplicationInfo(pkg, 0).category == ApplicationInfo.CATEGORY_GAME && pkg !in removedGames
            } catch (_: Exception) { false }
            pkg != activeGamePkg && pkg !in launcherPkgs && !isInLibrary &&
                installedApps.any { it.packageName == pkg }
        }
        return (userApps + googleApps).distinct()
    }

    private fun getLauncherPackages(): Set<String> {
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_HOME)
        return context.packageManager.queryIntentActivities(intent, 0)
            .mapNotNull { it.activityInfo?.packageName }
            .toSet()
    }

    private suspend fun captureAndSaveSnapshot(packageName: String?): Boolean {
        val minRefresh = readSettingOrNull("system", "min_refresh_rate") ?: return false
        val peakRefresh = readSettingOrNull("system", "peak_refresh_rate") ?: return false
        val touchSpeed = readSettingOrNull("system", "touch_response_speed") ?: return false
        val displayMode = readSettingOrNull("secure", "user_preferred_display_mode_id") ?: return false
        
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_RING)
        
        val cr = context.contentResolver
        val origBrightnessMode = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
        val origRotation = Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 1)

        var uid: Int? = null
        var uidWhitelistedBefore = false
        if (packageName != null) {
            uid = runCatching { context.packageManager.getApplicationInfo(packageName, 0).uid }.getOrNull()
            uidWhitelistedBefore = (uid != null) && isUidWhitelisted(uid)
        }
        val isVivo = (packageName != null) && deviceDiagnosticManager.isVivoOrIqoo()
        val vivoCube = if (isVivo) getGlobalString(vivoGameCubeApps) else null
        val vivoSpeed = if (isVivo) getGlobalString(vivoSpeedModeApps) else null
        val snapshot = GamingOptimizationSnapshot(
            activeGamePackage = packageName,
            activeGameUid = uid,
            timestamp = System.currentTimeMillis(),
            minRefreshRate = minRefresh,
            peakRefreshRate = peakRefresh,
            touchResponseSpeed = touchSpeed,
            userPreferredDisplayModeId = displayMode,
            affectedPackages = emptySet(),
            uidWhitelistedBefore = uidWhitelistedBefore,
            vivoGameCubeApps = vivoCube,
            vivoSpeedModeApps = vivoSpeed,
            originalRingtoneVolume = currentVol,
            originalBrightnessMode = origBrightnessMode,
            originalRotation = origRotation
        )
        prefs.edit { putString("last_snapshot", snapshot.toJson()) }
        return true
    }

    /**
     * Reads one setting for the pre-activation snapshot.
     *
     * The provider is asked first: reading `Settings.System`/`Secure`/`Global` needs no permission
     * at all, so it cannot fail for the reasons a shell can (no root, dead Shizuku binder) and it
     * costs no process fork. A key that is simply not set comes back as
     * `SettingValue("", existed = false)` — a legitimate answer, and the reason activation no
     * longer aborts on devices that never shipped `touch_response_speed`.
     *
     * @return null only when neither the provider nor a privileged shell could answer at all,
     *   which is the same "un-restorable state, do not touch anything" signal the reference uses.
     */
    private suspend fun readSettingOrNull(namespace: String, key: String): SettingValue? {
        readSettingViaProvider(namespace, key)?.let { return it }
        val result = shellRunner.execSafeResult("settings", "get", namespace, key)
        if (!result.isSuccess) return null
        return SettingValue.fromCommandOutput(result.stdout)
    }

    private fun readSettingViaProvider(namespace: String, key: String): SettingValue? {
        val cr = context.contentResolver
        return try {
            val raw = when (namespace) {
                "system" -> Settings.System.getString(cr, key)
                "secure" -> Settings.Secure.getString(cr, key)
                "global" -> Settings.Global.getString(cr, key)
                else -> return null
            }
            SettingValue.fromCommandOutput(raw.orEmpty())
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Writes a setting and confirms it stuck by reading it back.
     *
     * `settings put` exits 0 with empty stdout even on ROMs that accept the command and silently
     * drop an unknown key, so the read-back is the only honest success signal. Numbers are compared
     * numerically because some providers normalise `120` to `120.0`.
     */
    private suspend fun putSettingVerified(namespace: String, key: String, value: String): Boolean {
        shellRunner.execSafeResult("settings", "put", namespace, key, value)
        val readBack = readSettingOrNull(namespace, key)?.takeIf { it.existed }?.value ?: return false
        val actual = readBack.toFloatOrNull()
        val wanted = value.toFloatOrNull()
        return if (actual != null && wanted != null) actual == wanted else readBack == value
    }

    /** @return true when the game's uid was actually added to the background-data whitelist. */
    private suspend fun applyPerGameOptimizations(packageName: String, maxHz: Int): Boolean {
        try {
            val snapshot = prefs.getString("last_snapshot", null)
                ?.let { GamingOptimizationSnapshot.fromJson(it) }
            val uid = snapshot?.activeGameUid
                ?: runCatching { context.packageManager.getApplicationInfo(packageName, 0).uid }.getOrNull()
                ?: return false

            // Apply volume override (mute ringtone)
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            try { audioManager.setStreamVolume(AudioManager.STREAM_RING, 0, 0) } catch (_: Exception) {}

            // Apply Settings overrides if possible
            if (Settings.System.canWrite(context)) {
                val cr = context.contentResolver
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, 0)
            }

            execute("cmd netpolicy add restrict-background-whitelist $uid")
            execute("cmd game set --mode performance --fps $maxHz $packageName")
            if (deviceDiagnosticManager.isVivoOrIqoo()) {
                val cube = getGlobalString(vivoGameCubeApps) ?: ""
                val speed = getGlobalString(vivoSpeedModeApps) ?: ""
                execute("settings put global $vivoGameCubeApps ${appendToCsv(cube, packageName)}")
                execute("settings put global $vivoSpeedModeApps ${appendToCsv(speed, packageName)}")
            }
            // netpolicy has no read-back on its own, but the whitelist can be listed.
            return isUidWhitelisted(uid)
        } catch (_: Exception) {
            return false
        }
    }

    private suspend fun revertFromSnapshot() {
        val json = prefs.getString("last_snapshot", null) ?: return
        val snapshot = GamingOptimizationSnapshot.fromJson(json) ?: return
        
        // Restore volume
        snapshot.originalRingtoneVolume?.let { vol ->
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            try { audioManager.setStreamVolume(AudioManager.STREAM_RING, vol, 0) } catch (_: Exception) {}
        }

        // Restore Settings
        if (Settings.System.canWrite(context)) {
            val cr = context.contentResolver
            snapshot.originalBrightnessMode?.let { Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, it) }
            snapshot.originalRotation?.let { Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, it) }
        }

        restoreSetting("system", "min_refresh_rate", snapshot.minRefreshRate)
        restoreSetting("system", "peak_refresh_rate", snapshot.peakRefreshRate)
        restoreSetting("system", "touch_response_speed", snapshot.touchResponseSpeed)
        restoreSetting("secure", "user_preferred_display_mode_id", snapshot.userPreferredDisplayModeId)
        if (snapshot.activeGamePackage != null) {
            val pkg = snapshot.activeGamePackage
            execute("cmd activity set-bg-restriction-level --user 0 $pkg adaptive_bucket")
            execute("am set-standby-bucket --user 0 $pkg working_set")
            execute("cmd deviceidle whitelist -$pkg")
            execute("cmd game reset --user 0 $pkg")
        }
        if (snapshot.activeGameUid != null && !snapshot.uidWhitelistedBefore) {
            execute("cmd netpolicy remove restrict-background-whitelist ${snapshot.activeGameUid}")
        }
        if (deviceDiagnosticManager.isVivoOrIqoo() && (snapshot.activeGamePackage != null)) {
            restoreGlobalSetting(vivoGameCubeApps, snapshot.vivoGameCubeApps)
            restoreGlobalSetting(vivoSpeedModeApps, snapshot.vivoSpeedModeApps)
        }
        prefs.edit { remove("last_snapshot") }
    }

    private suspend fun restoreGlobalSetting(key: String, original: String?) {
        if (original == null) return
        if (original.isBlank()) execute("settings delete global $key")
        else execute("settings put global $key $original")
    }

    /**
     * Whether [uid] is exempt from Data Saver, per `cmd netpolicy`.
     *
     * The command answers on a single line — `Restrict background whitelisted UIDs: 10123 10456` — so
     * the UIDs are tokens within that line. Treating each line as one UID (as this did) never matched
     * anything, which made a successful whitelist report as a failure in the activation result.
     */
    private suspend fun isUidWhitelisted(uid: Int): Boolean {
        val output = execute("cmd netpolicy list restrict-background-whitelist")
        return uid in BackgroundDataRestrictor.parseUidList(output)
    }

    private fun appendToCsv(list: String, pkg: String): String {
        val items = list.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        return if (pkg in items) list else (items + pkg).joinToString(",")
    }

    private fun upsertCsvKey(csv: String, key: String, value: String): String {
        val kept = csv.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("$key=") }
        return (kept + "$key=$value").joinToString(",")
    }

    private fun removeCsvKey(csv: String, key: String): String {
        return csv.split(",")
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("$key=") }
            .joinToString(",")
    }

    private suspend fun restoreSetting(namespace: String, key: String, sv: SettingValue?) {
        if (sv == null) return
        val cmd = if (sv.existed && sv.value.isNotBlank()) {
            "settings put $namespace $key ${sv.value}"
        } else {
            "settings delete $namespace $key"
        }
        execute(cmd)
    }

    /**
     * Re-applies the gaming-mode writes after a process restart and rebuilds [report] from what
     * actually landed.
     *
     * The flags come from the verified results rather than from the persisted "it was on before"
     * bit: a reboot, a ROM update or a settings reset between runs can silently undo any of them,
     * and showing the old state would be reporting a change that is no longer in effect.
     */
    private suspend fun recoverPersistedState() {
        try {
            val unavailable = mutableListOf<String>()
            val maxHz = deviceDiagnosticManager.getMaxHardwareRefreshRate().toInt()
            val peakOk = putSettingVerified("system", "peak_refresh_rate", maxHz.toString())
            val minOk = putSettingVerified("system", "min_refresh_rate", maxHz.toString())
            val lockedHz = if (peakOk || minOk) maxHz else null
            if (lockedHz == null) {
                unavailable += "Refresh-rate lock (this ROM ignores min/peak_refresh_rate)"
            }

            val touchOk = putSettingVerified("system", "touch_response_speed", "2")
            if (!touchOk) unavailable += "Touch response boost (key not supported on this device)"

            val fixedPerfOk = shellRunner
                .execSafeResult("cmd", "power", "set-fixed-performance-mode-enabled", "true")
                .isSuccess
            if (!fixedPerfOk) unavailable += "Fixed performance mode (PowerHAL rejected the request)"
            execute("cmd deviceidle force-idle")

            // OEM specific recovery
            if (deviceDiagnosticManager.isVivoOrIqoo()) {
                execute("cmd thermalservice reset")
            }

            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val dndEngaged = nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_NONE

            val snapshot = prefs.getString("last_snapshot", null)
                ?.let { GamingOptimizationSnapshot.fromJson(it) }
            val pkg = snapshot?.activeGamePackage
            var networkWhitelisted: Boolean? = null
            if (pkg != null) {
                execute("pm unsuspend --user 0 $pkg")
                execute("cmd activity set-bg-restriction-level --user 0 $pkg unrestricted")
                execute("am set-standby-bucket --user 0 $pkg active")
                execute("cmd deviceidle whitelist +$pkg")
                networkWhitelisted = snapshot.activeGameUid?.let { isUidWhitelisted(it) } == true
            }

            _isFixedPerformanceMode.value = fixedPerfOk
            _report.value = GamingModeReport(
                fixedPerformance = fixedPerfOk,
                lockedRefreshHz = lockedHz,
                touchResponseBoost = touchOk,
                // The suspends happened in the previous process; this set is the record of them and
                // is exactly what disableGamingMode will unsuspend.
                suspendedPackages = prefs.getStringSet("affected_pkgs", emptySet())?.size ?: 0,
                dndEngaged = dndEngaged,
                networkWhitelisted = networkWhitelisted,
                unavailable = unavailable
            )
        } catch (_: Exception) {}
    }

    private suspend fun reapplyFixedPerformanceMode() {
        val applied = runCatching {
            shellRunner.execSafeResult("cmd", "power", "set-fixed-performance-mode-enabled", "true").isSuccess
        }.getOrDefault(false)
        _isFixedPerformanceMode.value = applied
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")

        const val NO_PRIVILEGE_REASON =
            "ART compilation needs root or Shizuku: Android refuses `cmd package compile` for " +
                "other packages from an ordinary app, so nothing was run."
        const val NO_APPS_REASON =
            "No eligible apps found: nothing installed by the user, and no system app added to " +
                "the game library."
    }
}
