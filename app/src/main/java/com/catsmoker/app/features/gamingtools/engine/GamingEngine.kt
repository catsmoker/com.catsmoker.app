package com.catsmoker.app.features.gamingtools.engine

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.core.content.edit
import com.catsmoker.app.shared.data.model.GamingOptimizationSnapshot
import com.catsmoker.app.shared.data.model.SettingValue
import com.catsmoker.app.features.gamingtools.engine.parsers.DexoptStatusParser
import com.catsmoker.app.system.shell.ShellRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

class GamingEngine(
    private val context: Context,
    private val shellRunner: ShellRunner,
    val deviceDiagnosticManager: DeviceDiagnosticManager
) {
    private val _state = MutableStateFlow<GamingModeState>(GamingModeState.Idle)
    val state: StateFlow<GamingModeState> = _state.asStateFlow()

    private val _isFixedPerformanceMode = MutableStateFlow(value = false)
    val isFixedPerformanceMode: StateFlow<Boolean> = _isFixedPerformanceMode.asStateFlow()

    private val _alwaysFinishActivities = MutableStateFlow(false)
    val alwaysFinishActivities: StateFlow<Boolean> = _alwaysFinishActivities.asStateFlow()

    private val _backgroundProcessLimit = MutableStateFlow(false)
    val backgroundProcessLimit: StateFlow<Boolean> = _backgroundProcessLimit.asStateFlow()

    private val _refreshRateLock = MutableStateFlow(false)
    val refreshRateLock: StateFlow<Boolean> = _refreshRateLock.asStateFlow()

    private val _boosterLog = MutableStateFlow<List<String>>(emptyList())
    val boosterLog: StateFlow<List<String>> = _boosterLog.asStateFlow()

    private val _boosterProgress = MutableStateFlow(0f)
    val boosterProgress: StateFlow<Float> = _boosterProgress.asStateFlow()

    private val _animationScales = MutableStateFlow(Triple(1f, 1f, 1f))
    val animationScales: StateFlow<Triple<Float, Float, Float>> = _animationScales.asStateFlow()

    private val boosterCancelRequested = java.util.concurrent.atomic.AtomicBoolean(false)

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
        
        val minHz = getSystemString("min_refresh_rate")
        val peakHz = getSystemString("peak_refresh_rate")
        _refreshRateLock.value = minHz != null && peakHz != null && minHz == peakHz && minHz != "0.0"

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
        "com.android.systemui",
        "com.android.phone",
        "com.mediatek.ims"
    )

    val gamingDaemons = listOf(
        "com.vivo.gamecube",
        "com.vivo.gamewatch",
        "com.vivo.game",
        "com.iqoo.powersaving"
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
            _state.value = GamingModeState.Enabling(0.15f, "Trimming system caches…")
            execute("pm trim-caches 4G")
            execute("am compact background")
            runCatching { execute("cmd pinner repin /system/framework/framework.jar") }
            
            _state.value = GamingModeState.Enabling(0.35f, "Suspending background apps…")
            val targets = getSuspendTargets(packageName)
            val currentlyAffected = prefs.getStringSet("affected_pkgs", emptySet())?.toMutableSet() ?: mutableSetOf()
            for (pkg in targets) {
                if (pkg !in currentlyAffected) {
                    execute("pm suspend --user 0 $pkg")
                    currentlyAffected.add(pkg)
                }
            }
            prefs.edit { putStringSet("affected_pkgs", currentlyAffected) }
            _state.value = GamingModeState.Enabling(0.6f, "Configuring Focus Mode…")
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.isNotificationPolicyAccessGranted) {
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
            }
            _state.value = GamingModeState.Enabling(0.9f, "Applying hardware locks…")
            val maxHz = deviceDiagnosticManager.getMaxHardwareRefreshRate().toInt()
            execute("settings put system peak_refresh_rate $maxHz")
            execute("settings put system min_refresh_rate $maxHz")
            execute("cmd power set-fixed-performance-mode-enabled true")
            if (packageName != null) {
                execute("pm unsuspend --user 0 $packageName")
                execute("cmd activity set-bg-restriction-level --user 0 $packageName unrestricted")
                execute("am set-standby-bucket --user 0 $packageName active")
                execute("cmd deviceidle whitelist +$packageName")
                applyPerGameOptimizations(packageName, maxHz)
            }
            execute("cmd deviceidle force-idle")
            execute("am kill-all")
            prefs.edit { putBoolean("is_active", true) }
            _isFixedPerformanceMode.value = true
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
        _state.value = GamingModeState.Idle
    }

    suspend fun manualBoostRam(): Pair<Long, Int> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memInfoBefore = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfoBefore)
        val availBefore = memInfoBefore.availMem
        var stoppedCount = 0
        try {
            shellRunner.trimCaches()
            val targets = getSuspendTargets(null)
            for (pkg in targets) {
                try {
                    execute("am force-stop $pkg")
                    stoppedCount++
                } catch (_: Exception) {}
            }
            execute("am kill-all")
        } catch (_: Exception) {}
        System.gc()
        delay(500.milliseconds)
        val memInfoAfter = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfoAfter)
        val availAfter = memInfoAfter.availMem
        val freedMb = ((availAfter - availBefore) / (1024L * 1024L)).coerceAtLeast(0L)
        return Pair(freedMb, stoppedCount)
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

    suspend fun toggleAlwaysFinishActivities(enabled: Boolean) {
        execute("settings put global always_finish_activities ${if (enabled) 1 else 0}")
        _alwaysFinishActivities.value = enabled
    }

    suspend fun toggleBackgroundProcessLimit(enabled: Boolean) {
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
        _backgroundProcessLimit.value = enabled
    }

    suspend fun toggleRefreshRateLock(enabled: Boolean) {
        if (enabled) {
            val maxHz = deviceDiagnosticManager.getMaxHardwareRefreshRate().toInt()
            execute("settings put system peak_refresh_rate $maxHz")
            execute("settings put system min_refresh_rate $maxHz")
        } else {
            execute("settings delete system min_refresh_rate")
            execute("settings delete system peak_refresh_rate")
        }
        _refreshRateLock.value = enabled
    }

    suspend fun runArtOptimization(mode: String = "speed-profile", force: Boolean = false) {
        _boosterLog.value = emptyList()
        _boosterProgress.value = 0f
        boosterCancelRequested.set(false)
        addBoosterLog("🚀 Starting ART Optimization (Mode: $mode)...")
        val pm = context.packageManager
        val userAdded = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
            .getStringSet("user_games", emptySet()) ?: emptySet()
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .asSequence()
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || it.packageName in userAdded }
            .map { it.packageName }
            .distinct()
            .toList()
        if (apps.isEmpty()) {
            addBoosterLog("❌ No eligible apps found.")
            return
        }
        addBoosterLog("📦 Found ${apps.size} apps to optimize.")
        val useRoot = shellRunner.isRootAvailable()
        val currentStatuses = if (!force) queryDexoptStatuses() else emptyMap()
        try {
            apps.forEachIndexed { index, pkg ->
                if (boosterCancelRequested.get()) {
                    addBoosterLog("⏹ Optimization Cancelled.")
                    _boosterProgress.value = 0f
                    return
                }
                if (!force && currentStatuses[pkg] == mode) {
                    addBoosterLog("⏭ Skipping (already $mode): $pkg")
                } else {
                    addBoosterLog("⚡ Optimizing: $pkg")
                    val forceFlag = if (force) "-f" else ""
                    val cmd = "cmd package compile -m $mode $forceFlag $pkg"
                    runCompileCommand(cmd, useRoot)
                }
                _boosterProgress.value = (index + 1).toFloat() / apps.size.toFloat()
            }
            addBoosterLog("✅ Optimization Complete!")
            _boosterProgress.value = 1f
        } catch (_: CancellationException) {
            currentCompileProcess?.destroy()
            currentCompileProcess = null
            addBoosterLog("⏹ Optimization Cancelled.")
            _boosterProgress.value = 0f
        } finally {
            currentCompileProcess?.destroy()
            currentCompileProcess = null
        }
    }

    private suspend fun queryDexoptStatuses(): Map<String, String> {
        return withContext(Dispatchers.IO) {
            DexoptStatusParser.parse(execute("dumpsys package dexopt"))
        }
    }

    private suspend fun runCompileCommand(cmd: String, useRoot: Boolean) {
        withContext(Dispatchers.IO) {
            if (useRoot) {
                var started = false
                try {
                    val process = ProcessBuilder("su", "-c", cmd)
                        .redirectErrorStream(true)
                        .start()
                    started = true
                    currentCompileProcess = process
                    val drainer = Thread {
                        try { process.inputStream.readBytes() } catch (_: Exception) {}
                    }
                    drainer.isDaemon = true
                    drainer.start()
                    process.waitFor()
                    currentCompileProcess = null
                } catch (_: Exception) {
                    currentCompileProcess = null
                    if (!started) execute(cmd)
                }
            } else if (shellRunner.shizukuHasPermission.value) {
                shellRunner.exec(cmd)
            }
        }
    }

    suspend fun cancelArtOptimization() {
        boosterCancelRequested.set(true)
        currentCompileProcess?.destroy()
        currentCompileProcess = null
        shellRunner.killCurrentProcess()
    }

    fun refreshAnimationScales() {
        val cr = context.contentResolver
        val w = android.provider.Settings.Global.getFloat(cr, android.provider.Settings.Global.WINDOW_ANIMATION_SCALE, 1f)
        val t = android.provider.Settings.Global.getFloat(cr, android.provider.Settings.Global.TRANSITION_ANIMATION_SCALE, 1f)
        val a = android.provider.Settings.Global.getFloat(cr, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        _animationScales.value = Triple(w, t, a)
    }

    suspend fun setAnimationScales(window: Float, transition: Float, animator: Float) {
        execute("settings put global window_animation_scale $window")
        execute("settings put global transition_animation_scale $transition")
        execute("settings put global animator_duration_scale $animator")
        refreshAnimationScales()
    }

    private fun addBoosterLog(msg: String) {
        val current = _boosterLog.value.toMutableList()
        current.add(msg)
        _boosterLog.value = current
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
            vivoSpeedModeApps = vivoSpeed
        )
        prefs.edit { putString("last_snapshot", snapshot.toJson()) }
        return true
    }

    private suspend fun readSettingOrNull(namespace: String, key: String): SettingValue? {
        val result = shellRunner.run("settings get $namespace $key")
        if (!result.isSuccess) return null
        return SettingValue.fromCommandOutput(result.out.joinToString("\n"))
    }

    private suspend fun applyPerGameOptimizations(packageName: String, maxHz: Int) {
        try {
            val snapshot = prefs.getString("last_snapshot", null)
                ?.let { GamingOptimizationSnapshot.fromJson(it) }
            val uid = snapshot?.activeGameUid
                ?: runCatching { context.packageManager.getApplicationInfo(packageName, 0).uid }.getOrNull()
                ?: return
            execute("cmd netpolicy add restrict-background-whitelist $uid")
            execute("cmd game set --mode performance --fps $maxHz $packageName")
            if (deviceDiagnosticManager.isVivoOrIqoo()) {
                val cube = getGlobalString(vivoGameCubeApps) ?: ""
                val speed = getGlobalString(vivoSpeedModeApps) ?: ""
                execute("settings put global $vivoGameCubeApps ${appendToCsv(cube, packageName)}")
                execute("settings put global $vivoSpeedModeApps ${appendToCsv(speed, packageName)}")
            }
        } catch (_: Exception) {}
    }

    private suspend fun revertFromSnapshot() {
        val json = prefs.getString("last_snapshot", null) ?: return
        val snapshot = GamingOptimizationSnapshot.fromJson(json) ?: return
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

    private suspend fun isUidWhitelisted(uid: Int): Boolean {
        val output = execute("cmd netpolicy list restrict-background-whitelist")
        return output.lineSequence().any { it.trim().toIntOrNull() == uid }
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

    private suspend fun recoverPersistedState() {
        try {
            val maxHz = deviceDiagnosticManager.getMaxHardwareRefreshRate().toInt()
            execute("settings put system peak_refresh_rate $maxHz")
            execute("settings put system min_refresh_rate $maxHz")
            execute("cmd power set-fixed-performance-mode-enabled true")
            execute("cmd deviceidle force-idle")
            val snapshot = prefs.getString("last_snapshot", null)
                ?.let { GamingOptimizationSnapshot.fromJson(it) }
            val pkg = snapshot?.activeGamePackage
            if (pkg != null) {
                execute("pm unsuspend --user 0 $pkg")
                execute("cmd activity set-bg-restriction-level --user 0 $pkg unrestricted")
                execute("am set-standby-bucket --user 0 $pkg active")
                execute("cmd deviceidle whitelist +$pkg")
            }
        } catch (_: Exception) {}
    }

    private suspend fun reapplyFixedPerformanceMode() {
        try {
            execute("cmd power set-fixed-performance-mode-enabled true")
        } catch (_: Exception) {}
    }
}
