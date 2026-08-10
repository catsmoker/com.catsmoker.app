package com.catsmoker.app.core

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.catsmoker.app.data.model.GamingOptimizationSnapshot
import com.catsmoker.app.data.model.SettingValue
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

sealed class GamingModeState {
    object Idle : GamingModeState()
    data class Enabling(val progress: Float = 0f, val statusText: String = "Preparing…") : GamingModeState()
    object Active : GamingModeState()
    object Disabling : GamingModeState()
    data class Error(val message: String) : GamingModeState()
}

class GamingEngine(
    private val context: Context,
    private val shizukuManager: ShizukuManager,
    private val deviceDiagnosticManager: DeviceDiagnosticManager
) {
    private val _state = MutableStateFlow<GamingModeState>(GamingModeState.Idle)
    val state: StateFlow<GamingModeState> = _state.asStateFlow()

    private val _isFixedPerformanceMode = MutableStateFlow(false)
    val isFixedPerformanceMode: StateFlow<Boolean> = _isFixedPerformanceMode.asStateFlow()

    private val _alwaysFinishActivities = MutableStateFlow(false)
    val alwaysFinishActivities: StateFlow<Boolean> = _alwaysFinishActivities.asStateFlow()

    private val _backgroundProcessLimit = MutableStateFlow(false)
    val backgroundProcessLimit: StateFlow<Boolean> = _backgroundProcessLimit.asStateFlow()

    private val _boosterLog = MutableStateFlow<List<String>>(emptyList())
    val boosterLog: StateFlow<List<String>> = _boosterLog.asStateFlow()

    private val _boosterProgress = MutableStateFlow(0f)
    val boosterProgress: StateFlow<Float> = _boosterProgress.asStateFlow()

    private val _animationScales = MutableStateFlow(Triple(1f, 1f, 1f))
    val animationScales: StateFlow<Triple<Float, Float, Float>> = _animationScales.asStateFlow()

    private val boosterCancelRequested = java.util.concurrent.atomic.AtomicBoolean(false)

    private val prefs = context.getSharedPreferences("gaming_engine_prefs", Context.MODE_PRIVATE)

    init {
        val isActive = prefs.getBoolean("is_active", false)
        val isFixedPerf = prefs.getBoolean("fixed_perf_manual", false)
        
        if (isActive) {
            _state.value = GamingModeState.Active
            _isFixedPerformanceMode.value = true
        } else if (isFixedPerf) {
            _isFixedPerformanceMode.value = true
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

    // Lists ported from FrameX
    val GOOGLE_SAFE_TO_SUSPEND = listOf(
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

    val SYSTEM_CRITICAL = listOf(
        "com.android.systemui",
        "com.android.phone",
        "com.mediatek.ims"
    )

    val GAMING_DAEMONS = listOf(
        "com.vivo.gamecube",
        "com.vivo.gamewatch",
        "com.vivo.game",
        "com.iqoo.powersaving"
    )

    private val HARD_WHITELIST = setOf(
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
        
        // Ensure Shizuku state is fresh
        shizukuManager.checkPermission()
        
        val isRoot = isRootAvailable()
        val hasShizuku = shizukuManager.hasPermission.value

        if (!isRoot && !hasShizuku) {
            _state.value = GamingModeState.Error("Root or Shizuku permission required")
            return
        }

        try {
            // Phase 0 — RAM Cache Pre-Trimming
            _state.value = GamingModeState.Enabling(0.1f, "Trimming system caches…")
            execute("pm trim-caches 4G")
            
            // Phase 1 — Suspend Background Apps (Simplified implementation of FrameX logic)
            _state.value = GamingModeState.Enabling(0.3f, "Suspending background apps…")
            
            val targets = getSuspendTargets(packageName)
            val currentlyAffected = prefs.getStringSet("affected_pkgs", emptySet())?.toMutableSet() ?: mutableSetOf()
            
            for (pkg in targets) {
                if (pkg !in currentlyAffected) {
                    execute("pm suspend --user 0 $pkg")
                    currentlyAffected.add(pkg)
                }
            }
            prefs.edit().putStringSet("affected_pkgs", currentlyAffected).apply()

            // Phase 2 — DND Mode
            _state.value = GamingModeState.Enabling(0.5f, "Configuring Focus Mode…")
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.isNotificationPolicyAccessGranted) {
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
            }

            // Phase 3 — Capture Snapshot
            _state.value = GamingModeState.Enabling(0.7f, "Capturing system snapshot…")
            captureAndSaveSnapshot(packageName)

            // Phase 4 — Esports Optimizations (Hardware locks)
            _state.value = GamingModeState.Enabling(0.9f, "Applying hardware locks…")
            val maxHz = deviceDiagnosticManager.getMaxHardwareRefreshRate().toInt()
            
            execute("settings put system peak_refresh_rate $maxHz")
            execute("settings put system min_refresh_rate $maxHz")
            execute("cmd power set-fixed-performance-mode-enabled true")
            
            if (packageName != null) {
                // Ensure the game is NOT suspended and has priority
                execute("pm unsuspend --user 0 $packageName")
                execute("cmd activity set-bg-restriction-level --user 0 $packageName unrestricted")
                execute("am set-standby-bucket --user 0 $packageName active")
                execute("cmd deviceidle whitelist +$packageName")
            }
            execute("cmd deviceidle force-idle")
            execute("am kill-all")

            prefs.edit().putBoolean("is_active", true).apply()
            _isFixedPerformanceMode.value = true
            _state.value = GamingModeState.Active
        } catch (e: Exception) {
            _state.value = GamingModeState.Error(e.message ?: "Activation failed")
        }
    }

    private suspend fun disableGamingMode() {
        _state.value = GamingModeState.Disabling
        
        // Restore DND
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.isNotificationPolicyAccessGranted) {
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        }

        // Unsuspend apps
        val affected = prefs.getStringSet("affected_pkgs", emptySet()) ?: emptySet()
        for (pkg in affected) {
            execute("pm unsuspend --user 0 $pkg")
        }
        prefs.edit().remove("affected_pkgs").apply()

        // Revert locks
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
            execute("pm trim-caches 4G")
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
        delay(500)
        
        val memInfoAfter = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfoAfter)
        val availAfter = memInfoAfter.availMem
        
        val freedMb = ((availAfter - availBefore) / (1024L * 1024L)).coerceAtLeast(0L)
        return Pair(freedMb, stoppedCount)
    }

    suspend fun measureNetworkLatency(): Int? {
        try {
            val output = execute("ping -c 1 8.8.8.8")
            if (output.contains("time=")) {
                val pingMs = output.split("time=").getOrNull(1)
                    ?.split(" ")?.getOrNull(0)
                    ?.toFloatOrNull()
                    ?.toInt()
                if (pingMs != null && pingMs > 0) return pingMs
            }
        } catch (_: Exception) {}

        // Socket probe fallback (FrameX logic)
        var minPing: Int? = null
        repeat(3) {
            try {
                val start = System.currentTimeMillis()
                val socket = java.net.Socket()
                socket.connect(java.net.InetSocketAddress("8.8.8.8", 53), 1000)
                val latency = (System.currentTimeMillis() - start).toInt()
                socket.close()
                minPing = if (minPing == null) latency else minOf(minPing, latency)
            } catch (_: Exception) {}
            delay(150L)
        }
        return minPing
    }

    suspend fun resetToDeviceDefaults(): Boolean {
        _state.value = GamingModeState.Disabling
        
        // 1. Force Unsuspend ALL User Apps (Nuclear Fix for "Managed by Shell")
        val pm = context.packageManager
        val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        installedApps.filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }.forEach {
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
        
        prefs.edit().remove("affected_pkgs").putBoolean("is_active", false).putBoolean("fixed_perf_manual", false).apply()
        _isFixedPerformanceMode.value = false
        _state.value = GamingModeState.Idle
        return true
    }

    suspend fun toggleFixedPerformanceMode(enabled: Boolean) {
        if (enabled) {
            // FrameX Esports Suite
            execute("cmd power set-fixed-performance-mode-enabled true")
            execute("am compact background")
            execute("cmd pinner repin /system/framework/framework.jar")
        } else {
            execute("cmd power set-fixed-performance-mode-enabled false")
            execute("cmd deviceidle unforce")
        }
        
        prefs.edit().putBoolean("fixed_perf_manual", enabled).apply()
        _isFixedPerformanceMode.value = enabled
    }

    suspend fun toggleAlwaysFinishActivities(enabled: Boolean) {
        execute("settings put global always_finish_activities ${if (enabled) 1 else 0}")
        _alwaysFinishActivities.value = enabled
    }

    suspend fun toggleBackgroundProcessLimit(enabled: Boolean) {
        val value = if (enabled) "max_cached_processes=1" else "null"
        if (enabled) {
            execute("settings put global activity_manager_constants $value")
        } else {
            execute("settings delete global activity_manager_constants")
        }
        _backgroundProcessLimit.value = enabled
    }

    suspend fun runArtOptimization(mode: String = "speed-profile", force: Boolean = false) {
        withContext(Dispatchers.IO) {
            _boosterLog.value = emptyList()
            _boosterProgress.value = 0f
            boosterCancelRequested.set(false)
            
            addBoosterLog("🚀 Starting ART Optimization (Mode: $mode)...")
            
            val pm = context.packageManager
            val userAdded = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
                .getStringSet("user_games", emptySet()) ?: emptySet()
            
            // Focus on library games first for high impact
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { (it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 || it.packageName in userAdded }
                .map { it.packageName }
                .distinct()
            
            if (apps.isEmpty()) {
                addBoosterLog("❌ No eligible apps found.")
                return@withContext
            }

            addBoosterLog("📦 Found ${apps.size} apps to optimize.")
            
            apps.forEachIndexed { index, pkg ->
                if (boosterCancelRequested.get()) {
                    addBoosterLog("⏹ Optimization Cancelled.")
                    _boosterProgress.value = 0f
                    return@withContext
                }

                addBoosterLog("⚡ Optimizing: $pkg")
                val forceFlag = if (force) "-f" else ""
                val cmd = "cmd package compile -m $mode $forceFlag $pkg"
                execute(cmd)
                
                _boosterProgress.value = (index + 1).toFloat() / apps.size.toFloat()
            }
            
            addBoosterLog("✅ Optimization Complete!")
            _boosterProgress.value = 1f
        }
    }

    suspend fun cancelArtOptimization() {
        boosterCancelRequested.set(true)
        shizukuManager.killCurrentProcess()
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

    suspend fun execute(command: String): String {
        return withContext(Dispatchers.IO) {
            if (isRootAvailable()) {
                Shell.cmd(command).exec().out.joinToString("\n")
            } else if (shizukuManager.hasPermission.value) {
                shizukuManager.executeCommand(command)
            } else ""
        }
    }

    suspend fun executeWithResult(command: String): com.catsmoker.app.shizuku.CommandResult? {
        return withContext(Dispatchers.IO) {
            if (isRootAvailable()) {
                val res = Shell.cmd(command).exec()
                com.catsmoker.app.shizuku.CommandResult().apply {
                    output = res.out.joinToString("\n")
                    exitCode = res.code
                }
            } else if (shizukuManager.hasPermission.value) {
                shizukuManager.executeCommandWithResult(command)
            } else null
        }
    }

    private suspend fun isRootAvailable(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Shell.getShell().isRoot
            } catch (_: Exception) {
                false
            }
        }
    }


    private fun getSuspendTargets(activeGamePkg: String?): List<String> {
        val pm = context.packageManager
        val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        
        // Load library data from common AppPrefs
        val appPrefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val userGames = appPrefs.getStringSet("user_games", emptySet()) ?: emptySet()
        val removedGames = appPrefs.getStringSet("removed_games", emptySet()) ?: emptySet()

        val userApps = installedApps.filter { ai ->
            val pkg = ai.packageName
            
            // Whitelist logic: Games in library OR system-categorized games (unless removed) OR hardcoded safety list
            val isLibraryGame = (ai.category == ApplicationInfo.CATEGORY_GAME || pkg in userGames) && pkg !in removedGames
            val isWhitelisted = pkg == activeGamePkg || isLibraryGame || pkg in HARD_WHITELIST || pkg in SYSTEM_CRITICAL || pkg in GAMING_DAEMONS
            
            (ai.flags and ApplicationInfo.FLAG_SYSTEM) == 0 && !isWhitelisted
        }.map { it.packageName }
        
        val googleApps = GOOGLE_SAFE_TO_SUSPEND.filter { pkg ->
            // Check if Google app is in library before suspending
            val isInLibrary = pkg in userGames || try {
                pm.getApplicationInfo(pkg, 0).category == ApplicationInfo.CATEGORY_GAME && pkg !in removedGames
            } catch (_: Exception) { false }

            pkg != activeGamePkg && !isInLibrary && installedApps.any { it.packageName == pkg }
        }
        
        return (userApps + googleApps).distinct()
    }

    private suspend fun captureAndSaveSnapshot(packageName: String?) {
        val minRefresh = getSetting("system", "min_refresh_rate")
        val peakRefresh = getSetting("system", "peak_refresh_rate")
        val touchSpeed = getSetting("system", "touch_response_speed")
        val displayMode = getSetting("secure", "user_preferred_display_mode_id")

        val snapshot = GamingOptimizationSnapshot(
            activeGamePackage = packageName,
            activeGameUid = null,
            timestamp = System.currentTimeMillis(),
            minRefreshRate = SettingValue.fromCommandOutput(minRefresh),
            peakRefreshRate = SettingValue.fromCommandOutput(peakRefresh),
            touchResponseSpeed = SettingValue.fromCommandOutput(touchSpeed),
            userPreferredDisplayModeId = SettingValue.fromCommandOutput(displayMode),
            affectedPackages = emptySet()
        )
        prefs.edit().putString("last_snapshot", snapshot.toJson()).apply()
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
        }
        prefs.edit().remove("last_snapshot").apply()
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

    private suspend fun getSetting(namespace: String, key: String): String {
        return execute("settings get $namespace $key")
    }
}
