package com.catsmoker.app.features.gamingtools

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.catsmoker.app.features.gamingtools.engine.GamingEngine
import com.catsmoker.app.shared.data.model.GameInfo
import com.catsmoker.app.features.gamingtools.tools.audio.BoostController
import com.catsmoker.app.features.gamingtools.tools.cleaner.CleaningFeature
import com.catsmoker.app.features.gamingtools.tools.graphics.DeveloperOptionsManager
import com.catsmoker.app.features.gamingtools.tools.graphics.AutoForceStopManager
import com.catsmoker.app.features.gamingtools.engine.EsportsEngine
import com.catsmoker.app.features.gamingtools.tools.art.ArtOptimizer
import com.catsmoker.app.features.gamingtools.tools.service.AutoForceStopService
import com.catsmoker.app.features.gamingtools.tools.crosshair.CrosshairOverlayService
import com.catsmoker.app.features.gamingtools.tools.firewall.GameVpnService
import com.catsmoker.app.features.gamingtools.tools.overlay.PerformanceOverlayService
import com.catsmoker.app.system.shell.ShellRunner
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import javax.inject.Inject

@HiltViewModel
class GamingToolsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gamingEngine: GamingEngine,
    private val shellRunner: ShellRunner,
    private val developerOptionsManager: DeveloperOptionsManager,
    private val esportsEngine: EsportsEngine,
    private val artOptimizer: ArtOptimizer,
    private val boostController: BoostController,
    private val autoForceStopManager: AutoForceStopManager
) : ViewModel() {

    data class UiState(
        val isRooted: Boolean = false,
        val isShizukuActive: Boolean = false,
        val isOverlayRunning: Boolean = false,
        val isCrosshairRunning: Boolean = false,
        val selectedCrosshair: String = "scope2.png",
        val isVpnRunning: Boolean = false,
        val isDndEnabled: Boolean = false,
        val isBoostingRam: Boolean = false,
        val showRamResult: Boolean = false,
        val isResettingDefaults: Boolean = false,
        val showResetResult: Boolean = false,
        val isScanningJunk: Boolean = false,
        val isCleaningJunk: Boolean = false,
        val boostLevel: Int = 0,
        val games: List<GameInfo> = emptyList(),
        val allApps: List<GameInfo> = emptyList(),
        val isPickingGame: Boolean = false,
        val maintenanceLog: List<String> = emptyList(),
        val scanResults: Map<CleaningFeature.Category, CleaningFeature.ScanResult> = emptyMap(),
        val angleSelections: Map<String, String> = emptyMap(),
        val showAggressiveCleanWarning: Boolean = false,
        val isAutoForceStopActive: Boolean = false,
        val autoForceStopPackages: Set<String> = emptySet(),
        val fancyImeAnimations: Boolean = true,
        val clockSeconds: Boolean = false,
        
        // Resolution Changer State
        val widthInput: String = "",
        val heightInput: String = "",
        val dpiInput: String = "",
        val resMethod: Int = 0,
        val resLog: List<String> = emptyList(),
        val showResWarning: Boolean = false
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toasts: SharedFlow<String> = _toasts.asSharedFlow()

    val gamingState = gamingEngine.state
    val isFixedPerformanceMode = gamingEngine.isFixedPerformanceMode
    val boosterLog = gamingEngine.boosterLog
    val boosterProgress = gamingEngine.boosterProgress
    val animationScales = gamingEngine.animationScales
    val alwaysFinishActivities = gamingEngine.alwaysFinishActivities
    val backgroundProcessLimit = gamingEngine.backgroundProcessLimit
    val refreshRateLock = gamingEngine.refreshRateLock

    private val appPrefs by lazy { context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE) }

    private var defaultWidth = 0
    private var defaultHeight = 0
    private var defaultDpi = 0
    private var pendingResApply: Triple<Int, Int, Int>? = null

    private val serviceStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            when (intent.action) {
                CrosshairOverlayService.ACTION_CROSSHAIR_SERVICE_STARTED ->
                    _uiState.update { it.copy(isCrosshairRunning = true) }
                CrosshairOverlayService.ACTION_CROSSHAIR_SERVICE_STOPPED ->
                    _uiState.update { it.copy(isCrosshairRunning = false) }
            }
        }
    }

    private var vpnCollectorJob: Job? = null

    init {
        _uiState.update {
            it.copy(
                selectedCrosshair = appPrefs.getString("selected_scope", "scope2.png") ?: "scope2.png",
                boostLevel = appPrefs.getInt("boost_level", 0),
                autoForceStopPackages = autoForceStopManager.getSelectedPackages(),
                fancyImeAnimations = !developerOptionsManager.isFancyImeAnimationsDisabled(),
                clockSeconds = developerOptionsManager.isClockSecondsEnabled()
            )
        }
        boostController.applyBoost(_uiState.value.boostLevel)

        val filter = IntentFilter().apply {
            addAction(CrosshairOverlayService.ACTION_CROSSHAIR_SERVICE_STARTED)
            addAction(CrosshairOverlayService.ACTION_CROSSHAIR_SERVICE_STOPPED)
        }
        ContextCompat.registerReceiver(context, serviceStateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        viewModelScope.launch {
            checkRootStatus()
        }
        syncState()

        vpnCollectorJob = viewModelScope.launch {
            GameVpnService.isRunningState.collect { running ->
                _uiState.update { it.copy(isVpnRunning = running) }
            }
        }

        viewModelScope.launch {
            angleSelectionsFlow()
        }

        initResDefaults()
    }

    override fun onCleared() {
        boostController.release()
        try {
            context.unregisterReceiver(serviceStateReceiver)
        } catch (_: Exception) {}
        vpnCollectorJob?.cancel()
    }

    private suspend fun angleSelectionsFlow() {
        val selections = developerOptionsManager.getAngleDriverSelections()
        _uiState.update { it.copy(angleSelections = selections) }
    }

    // ---- Privilege / Shizuku ----

    private suspend fun checkRootStatus() {
        val rooted = try { shellRunner.isRootAvailable() } catch (_: Exception) { false }
        _uiState.update { it.copy(isRooted = rooted) }
    }

    fun refreshPrivilegeState() {
        viewModelScope.launch {
            checkRootStatus()
            shellRunner.refreshShizukuPermission()
            _uiState.update { it.copy(isShizukuActive = shellRunner.shizukuHasPermission.value) }
        }
    }

    // ---- Game library ----

    fun syncGames() {
        viewModelScope.launch(Dispatchers.IO) {
            val pm = context.packageManager
            val userAdded = appPrefs.getStringSet("user_games", emptySet()) ?: emptySet()
            val removedGames = appPrefs.getStringSet("removed_games", emptySet()) ?: emptySet()

            val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
            val activities = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)

            val newGames = mutableListOf<GameInfo>()
            for (ri in activities) {
                val ai = ri.activityInfo.applicationInfo
                val pkg = ai.packageName
                if (pkg in removedGames) continue
                if (ai.category == ApplicationInfo.CATEGORY_GAME || pkg in userAdded) {
                    newGames.add(GameInfo(ai.loadLabel(pm).toString(), pkg, ai.loadIcon(pm)))
                }
            }
            val games = newGames.distinctBy { it.packageName }
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(games = games) }
            }
        }
    }

    fun loadAllApps() {
        viewModelScope.launch(Dispatchers.IO) {
            val pm = context.packageManager
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .asSequence()
                .filter { ((it.flags and ApplicationInfo.FLAG_SYSTEM) == 0) || (it.category == ApplicationInfo.CATEGORY_GAME) }
                .map { ai -> GameInfo(ai.loadLabel(pm).toString(), ai.packageName, ai.loadIcon(pm)) }
                .sortedBy { it.appName.lowercase() }
                .toList()

            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(allApps = apps) }
            }
        }
    }

    fun onAddGameClicked() {
        _uiState.update { it.copy(isPickingGame = true) }
        loadAllApps()
    }

    fun dismissGamePicker() {
        _uiState.update { it.copy(isPickingGame = false) }
    }

    fun addGameToLibrary(pkg: String) {
        val userAdded = appPrefs.getStringSet("user_games", emptySet())?.toMutableSet() ?: mutableSetOf()
        userAdded.add(pkg)
        appPrefs.edit { putStringSet("user_games", userAdded) }
        _uiState.update { it.copy(isPickingGame = false) }
        syncGames()
    }

    fun removeGameFromLibrary(pkg: String) {
        val userAdded = appPrefs.getStringSet("user_games", emptySet())?.toMutableSet() ?: mutableSetOf()
        userAdded.remove(pkg)
        appPrefs.edit { putStringSet("user_games", userAdded) }
        syncGames()
    }

    // ---- Tool Actions ----

    fun syncState() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val dndEnabled = nm.currentInterruptionFilter != android.app.NotificationManager.INTERRUPTION_FILTER_ALL
        _uiState.update {
            it.copy(
                isOverlayRunning = PerformanceOverlayService.isRunning,
                isCrosshairRunning = CrosshairOverlayService.isRunning,
                isVpnRunning = GameVpnService.isRunningState.value,
                isDndEnabled = dndEnabled
            )
        }
    }

    fun toggleOverlay(enable: Boolean) {
        val intent = Intent(context, PerformanceOverlayService::class.java)
        if (enable) ContextCompat.startForegroundService(context, intent) else context.stopService(intent)
        _uiState.update { it.copy(isOverlayRunning = enable) }
    }

    fun toggleCrosshair(enable: Boolean) {
        if (enable) startCrosshairService() else context.stopService(Intent(context, CrosshairOverlayService::class.java))
        _uiState.update { it.copy(isCrosshairRunning = enable) }
    }

    private fun startCrosshairService() {
        val intent = Intent(context, CrosshairOverlayService::class.java).apply {
            putExtra("scope_asset_name", _uiState.value.selectedCrosshair)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun onSelectCrosshair(scope: String) {
        appPrefs.edit { putString("selected_scope", scope) }
        _uiState.update { it.copy(selectedCrosshair = scope) }
        if (_uiState.value.isCrosshairRunning) startCrosshairService()
    }

    fun startVpnServiceInternal() {
        val intent = Intent(context, GameVpnService::class.java).apply {
            action = GameVpnService.ACTION_CONNECT
            _uiState.value.games.firstOrNull()?.let { putExtra(GameVpnService.EXTRA_GAME_PACKAGE, it.packageName) }
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun stopVpn() {
        context.startService(Intent(context, GameVpnService::class.java).apply { action = GameVpnService.ACTION_DISCONNECT })
    }

    fun toggleDnd(enable: Boolean): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (enable && !nm.isNotificationPolicyAccessGranted) return false
        nm.setInterruptionFilter(if (enable) android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY else android.app.NotificationManager.INTERRUPTION_FILTER_ALL)
        _uiState.update { it.copy(isDndEnabled = enable) }
        return true
    }

    fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(context)

    // ---- System cleaner ----

    fun scanForJunk() {
        _uiState.update { it.copy(maintenanceLog = emptyList(), isScanningJunk = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val results = CleaningFeature.scan(context, _uiState.value.isRooted, null)
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(scanResults = results.associateBy { r -> r.category }, isScanningJunk = false) }
            }
        }
    }

    fun onPerformMaintenance(categories: List<CleaningFeature.Category>) {
        if (categories.any { it.isAggressive }) {
            pendingCleanCategories = categories
            _uiState.update { it.copy(showAggressiveCleanWarning = true) }
        } else {
            performCategorizedMaintenance(categories)
        }
    }

    private var pendingCleanCategories: List<CleaningFeature.Category> = emptyList()

    fun confirmAggressiveClean() {
        _uiState.update { it.copy(showAggressiveCleanWarning = false) }
        performCategorizedMaintenance(pendingCleanCategories)
        pendingCleanCategories = emptyList()
    }

    fun dismissAggressiveCleanWarning() {
        _uiState.update { it.copy(showAggressiveCleanWarning = false) }
    }

    private fun performCategorizedMaintenance(categories: List<CleaningFeature.Category>) {
        _uiState.update { it.copy(isCleaningJunk = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val logs = CleaningFeature.clean(shellRunner, categories)
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(isCleaningJunk = false, maintenanceLog = it.maintenanceLog + logs) }
            }
        }
    }

    // ---- Gaming engine actions ----

    fun activateGamingMode() = viewModelScope.launch {
        esportsEngine.applyOptimizations(null, null)
        gamingEngine.toggleGamingMode(true)
    }
    fun deactivateGamingMode() = viewModelScope.launch {
        esportsEngine.revertOptimizations(null, null)
        gamingEngine.toggleGamingMode(false)
    }

    fun boostRam() = viewModelScope.launch {
        _uiState.update { it.copy(isBoostingRam = true) }
        gamingEngine.execute("pm trim-caches 4G")
        gamingEngine.execute("am compact background")
        gamingEngine.manualBoostRam()
        _uiState.update { it.copy(isBoostingRam = false) }
    }

    fun resetDefaults() = viewModelScope.launch {
        _uiState.update { it.copy(isResettingDefaults = true) }
        gamingEngine.resetToDeviceDefaults()
        _uiState.update { it.copy(isResettingDefaults = false) }
    }

    fun runBooster(mode: String, force: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(isCleaningJunk = true) }
            artOptimizer.compileAllUserApps(mode, force) { _, _ -> }
            _uiState.update { it.copy(isCleaningJunk = false) }
        }
    }

    fun stopBooster() {
        artOptimizer.cancel()
        context.stopService(Intent(context, AutoForceStopService::class.java))
    }

    fun toggleFixedPerformance(enabled: Boolean) = viewModelScope.launch {
        if (enabled) esportsEngine.applyOptimizations(null, null)
        else esportsEngine.revertOptimizations(null, null)
        gamingEngine.toggleFixedPerformanceMode(enabled)
    }
    fun onBoostChange(level: Int) {
        appPrefs.edit { putInt("boost_level", level) }
        boostController.applyBoost(level)
        _uiState.update { it.copy(boostLevel = level) }
    }

    fun setAnimationScales(w: Float, t: Float, a: Float) = viewModelScope.launch { gamingEngine.setAnimationScales(w, t, a) }
    fun toggleAlwaysFinish(enabled: Boolean) = viewModelScope.launch {
        developerOptionsManager.setAlwaysFinishActivities(enabled)
        gamingEngine.toggleAlwaysFinishActivities(enabled)
    }
    fun toggleBackgroundLimit(enabled: Boolean) = viewModelScope.launch {
        developerOptionsManager.setBackgroundProcessLimit(enabled)
    }

    fun toggleRefreshRateLock(enabled: Boolean) = viewModelScope.launch {
        gamingEngine.toggleRefreshRateLock(enabled)
    }

    fun toggleFancyIme(disabled: Boolean) = viewModelScope.launch {
        developerOptionsManager.setFancyImeAnimations(disabled)
        _uiState.update { it.copy(fancyImeAnimations = !disabled) }
    }

    fun toggleClockSeconds(enabled: Boolean) = viewModelScope.launch {
        developerOptionsManager.setClockSeconds(enabled)
        _uiState.update { it.copy(clockSeconds = enabled) }
    }

    fun toggleAutoForceStop(enabled: Boolean) {
        if (enabled) AutoForceStopService.start(context)
        else AutoForceStopService.stop(context)
        _uiState.update { it.copy(isAutoForceStopActive = enabled) }
    }

    fun toggleAutoForceStopPackage(pkg: String) {
        val newSet = autoForceStopManager.togglePackage(pkg)
        _uiState.update { it.copy(autoForceStopPackages = newSet) }
    }

    fun setAngleDriver(pkg: String, driver: String?) = viewModelScope.launch {
        developerOptionsManager.setAngleDriverSelection(pkg, driver)
        _uiState.update {
            val current = it.angleSelections.toMutableMap()
            if (driver == null) current.remove(pkg) else current[pkg] = driver
            it.copy(angleSelections = current)
        }
    }

    fun launchGame(pkg: String) = viewModelScope.launch {
        gamingEngine.toggleGamingMode(true, pkg)
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
        intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent?.let { context.startActivity(it) }
    }

    // ---- Resolution Changer Logic ----

    fun onResWidthChange(v: String) = _uiState.update { it.copy(widthInput = v) }
    fun onResHeightChange(v: String) = _uiState.update { it.copy(heightInput = v) }
    fun onResDpiChange(v: String) = _uiState.update { it.copy(dpiInput = v) }
    fun onResMethodSelected(m: Int) = _uiState.update { it.copy(resMethod = m) }

    private fun initResDefaults() {
        val m = DisplayMetrics()
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(m)
        defaultWidth = m.widthPixels; defaultHeight = m.heightPixels; defaultDpi = m.densityDpi
        _uiState.update { it.copy(widthInput = defaultWidth.toString(), heightInput = defaultHeight.toString(), dpiInput = defaultDpi.toString()) }
    }

    fun applyResolutionChanges() {
        val state = _uiState.value
        val w = state.widthInput.toIntOrNull(); val h = state.heightInput.toIntOrNull(); val d = state.dpiInput.toIntOrNull()
        if (w != null && h != null && d != null) {
            if (abs(w - defaultWidth).toFloat() / defaultWidth > 0.5f) {
                pendingResApply = Triple(w, h, d)
                _uiState.update { it.copy(showResWarning = true) }
            } else {
                executeResolution(w, h, d)
            }
        }
    }

    fun confirmResWarning() {
        _uiState.update { it.copy(showResWarning = false) }
        pendingResApply?.let { (w, h, d) -> executeResolution(w, h, d) }
        pendingResApply = null
    }

    fun dismissResWarning() {
        _uiState.update { it.copy(showResWarning = false) }
        pendingResApply = null
    }

    fun resetResolutionChanges() {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = shellRunner.exec("wm size reset; wm density reset").isNotBlank()
            if (ok) {
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(widthInput = defaultWidth.toString(), heightInput = defaultHeight.toString(), dpiInput = defaultDpi.toString()) }
                    logRes("Resolution reset")
                }
            }
        }
    }

    private fun executeResolution(w: Int, h: Int, d: Int) {
        val cmd = "wm size ${w}x${h}; wm density $d"
        viewModelScope.launch(Dispatchers.IO) {
            val ok = shellRunner.exec(cmd).isNotBlank()
            withContext(Dispatchers.Main) { if (ok) logRes("Applied: ${w}x${h} @ ${d}dpi") else logRes("Failed to apply") }
        }
    }

    private fun logRes(m: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        _uiState.update { it.copy(resLog = it.resLog + "[$time] $m") }
    }
}
