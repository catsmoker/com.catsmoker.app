package com.catsmoker.app.features.gamingtools

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.catsmoker.app.features.gamingtools.engine.AnimationScaleKind
import com.catsmoker.app.features.gamingtools.engine.GamingEngine
import com.catsmoker.app.features.gamingtools.engine.GamingModeService
import com.catsmoker.app.shared.data.model.GameInfo
import com.catsmoker.app.features.gamingtools.tools.audio.AudioBoostController
import com.catsmoker.app.features.gamingtools.tools.cleaner.CleaningFeature
import com.catsmoker.app.features.gamingtools.tools.graphics.GameDeveloperOptions
import com.catsmoker.app.features.gamingtools.tools.forcestop.KeepAliveStore
import com.catsmoker.app.features.gamingtools.tools.booster.AppBoosterService
import com.catsmoker.app.features.gamingtools.tools.forcestop.AutoForceStopService
import com.catsmoker.app.features.gamingtools.tools.crosshair.CrosshairOverlayService
import com.catsmoker.app.features.gamingtools.tools.dns.DnsFeature
import com.catsmoker.app.features.gamingtools.tools.firewall.BackgroundDataRestrictor
import com.catsmoker.app.features.gamingtools.tools.firewall.VpnFirewall
import com.catsmoker.app.features.gamingtools.tools.overlay.PerformanceOverlayService
import com.catsmoker.app.system.shell.ShellRunner
import com.catsmoker.app.shared.util.DisplayMetricsProvider
import com.catsmoker.app.shared.util.formatBytes
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
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
    private val gameDeveloperOptions: GameDeveloperOptions,
    private val audioBoostController: AudioBoostController,
    private val keepAliveStore: KeepAliveStore,
    private val backgroundDataRestrictor: BackgroundDataRestrictor,
    private val vpnFirewall: VpnFirewall,
    private val dnsFeature: DnsFeature,
    /** Shared with spoof profiles so both features quote the same display numbers. */
    private val displayMetrics: DisplayMetricsProvider
) : ViewModel() {

    data class UiState(
        val isRooted: Boolean = false,
        val isShizukuActive: Boolean = false,
        /**
         * Whether `Settings.Global` can be written at all — root, Shizuku, or a granted
         * `WRITE_SECURE_SETTINGS`. The last one works without either privileged channel, so gating
         * the animation scales on root/Shizuku alone would hide a control that does work.
         */
        val canWriteGlobalSettings: Boolean = false,
        val isOverlayRunning: Boolean = false,
        val isCrosshairRunning: Boolean = false,
        val selectedCrosshair: String = "scope2.png",
        /**
         * The real network-policy state read from `cmd netpolicy`, or null before the first read.
         * Every field inside it can itself be null when the device would not report that part.
         */
        val backgroundDataStatus: BackgroundDataRestrictor.Status? = null,
        /** True while a `cmd netpolicy` write is in flight, so the switch cannot be double-driven. */
        val isChangingBackgroundData: Boolean = false,
        /** Whether this app is the one holding the restriction on, per its own record. */
        val backgroundDataEngaged: Boolean = false,
        /**
         * The local VPN's real condition — established or not, how many apps Android accepted into
         * it, and why the last attempt failed. Independent of Data Saver above: either switch, both,
         * or neither can be on.
         */
        val vpnFirewallState: VpnFirewall.State = VpnFirewall.State(),
        /** True while the VPN is being started or stopped. */
        val isChangingVpnFirewall: Boolean = false,
        /**
         * Set when Android's VPN consent dialog has to be shown before the switch can do anything.
         * Consumed by the screen, which is the only place that can launch an Activity.
         */
        val vpnConsentRequest: Boolean = false,
        /**
         * The real Private DNS configuration read from the device, or null before the first read.
         * Includes the resolvers the active network is actually using, which is the only honest
         * evidence that a change took effect.
         */
        val dnsStatus: DnsFeature.Status? = null,
        /** True while a `private_dns_*` write is in flight. */
        val isChangingDns: Boolean = false,
        val isDndEnabled: Boolean = false,
        val isBoostingRam: Boolean = false,
        /** Last RAM-boost outcome, exactly as measured. null before the first run. */
        val ramResult: String? = null,
        val isScanningJunk: Boolean = false,
        val isCleaningJunk: Boolean = false,
        val boostLevel: Int = 0,
        val audioOutput: String? = null,
        val games: List<GameInfo> = emptyList(),
        val allApps: List<GameInfo> = emptyList(),
        val isPickingGame: Boolean = false,
        /**
         * Outcome of the last clean, or null when none has run since this screen opened.
         *
         * This replaced a rolling list of log lines rendered in a terminal view: the same measured
         * counts, held as numbers so the card can state the result in one line.
         */
        val cleanResult: CleaningFeature.CleanResult? = null,
        /** Last junk scan, including what it could not reach. null until the first scan runs. */
        val scanReport: CleaningFeature.ScanReport? = null,
        val showAggressiveCleanWarning: Boolean = false,
        val isAutoForceStopActive: Boolean = false,
        /**
         * The apps the user chose to *keep* running — Auto Force Stop closes every other app you
         * switch away from and leaves these alone.
         *
         * This list used to be the opposite: the apps to close. Inverting it is what makes the
         * feature match its name, and it also means an empty list is a valid, meaningful setting
         * ("close everything") rather than a reason to shut the service down.
         */
        val autoForceStopKeepPackages: Set<String> = emptySet(),

        // Resolution Changer State
        /**
         * This panel's own resolution and density, or null when the device would not report it.
         * Null must be shown as unavailable — never as zeros.
         */
        val nativeResolution: DisplayMetricsProvider.Snapshot? = null,
        /** Which API or command produced [nativeResolution], so the numbers stay attributable. */
        val resolutionSource: String = "",
        /** The `wm size` / `wm density` override in force, or null when the panel runs natively. */
        val activeOverride: DisplayMetricsProvider.Snapshot? = null,
        val resolutionOptions: List<ResolutionOption> = emptyList(),
        val selectedResolutionId: String? = null,
        val widthInput: String = "",
        val heightInput: String = "",
        val dpiInput: String = "",
        /** Why the typed values cannot be applied, or null when they can. */
        val resValidationError: String? = null,
        val isApplyingResolution: Boolean = false,
        val resLog: List<String> = emptyList(),
        /** Set while a risky-but-legal change is waiting for confirmation; carries the real reason. */
        val resWarning: String? = null
    ) {
        /**
         * Whether the width/height/DPI fields accept typing.
         *
         * Only the Custom entry does. Under a preset the fields are a read-out of what that preset
         * would apply, and letting them be edited made them a claim the preset no longer backed — the
         * numbers on screen and the numbers about to be written could differ with nothing saying so.
         */
        val resolutionEditable: Boolean get() = selectedResolutionId == CUSTOM_OPTION_ID
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toasts: SharedFlow<String> = _toasts.asSharedFlow()

    val gamingState = gamingEngine.state
    /** What activation actually applied, verified per setting. Drives the hero card's result rows. */
    val gamingReport = gamingEngine.report
    val isFixedPerformanceMode = gamingEngine.isFixedPerformanceMode
    val boosterLog = gamingEngine.boosterLog
    val boosterState = gamingEngine.boosterState
    val animationScales = gamingEngine.animationScales
    val alwaysFinishActivities = gamingEngine.alwaysFinishActivities
    val backgroundProcessLimit = gamingEngine.backgroundProcessLimit
    /** The three Developer Options gaming switches, as last read from the device. */
    val gameDevOptions = gameDeveloperOptions.state

    private val appPrefs by lazy { context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE) }

    /** Target held while the confirmation dialog is up, so the dialog cannot alter what gets applied. */
    private var pendingResApply: DisplayMetricsProvider.Snapshot? = null

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

    private var scanJob: Job? = null
    private var cleanJob: Job? = null

    init {
        _uiState.update {
            it.copy(
                selectedCrosshair = appPrefs.getString("selected_scope", "scope2.png") ?: "scope2.png",
                boostLevel = appPrefs.getInt("boost_level", 0),
                autoForceStopKeepPackages = keepAliveStore.getKeptPackages()
            )
        }
        audioBoostController.applyBoost(_uiState.value.boostLevel)

        val filter = IntentFilter().apply {
            addAction(CrosshairOverlayService.ACTION_CROSSHAIR_SERVICE_STARTED)
            addAction(CrosshairOverlayService.ACTION_CROSSHAIR_SERVICE_STOPPED)
        }
        ContextCompat.registerReceiver(context, serviceStateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        viewModelScope.launch {
            checkRootStatus()
        }
        syncState()

        viewModelScope.launch {
            // The VPN's own report of what it established, so the switch follows the interface rather
            // than the fact that a start was requested.
            vpnFirewall.state.collect { state ->
                _uiState.update { it.copy(vpnFirewallState = state) }
            }
        }

        viewModelScope.launch {
            // AudioBoostController re-attaches its effects when the output route changes; mirror the
            // device it landed on so the card shows what is actually being boosted.
            audioBoostController.outputDevice.collect { device ->
                _uiState.update { it.copy(audioOutput = device) }
            }
        }

        refreshResolutionState()
    }

    override fun onCleared() {
        audioBoostController.release()
        try {
            context.unregisterReceiver(serviceStateReceiver)
        } catch (_: Exception) {}
        super.onCleared()
    }

    // ---- Privilege / Shizuku ----

    private suspend fun checkRootStatus() {
        val rooted = try { shellRunner.isRootAvailable() } catch (_: Exception) { false }
        _uiState.update {
            it.copy(isRooted = rooted, canWriteGlobalSettings = gamingEngine.canWriteAnimationScales())
        }
    }

    fun refreshPrivilegeState() {
        viewModelScope.launch {
            checkRootStatus()
            shellRunner.refreshShizukuPermission()
            _uiState.update {
                it.copy(
                    isShizukuActive = shellRunner.shizukuHasPermission.value,
                    canWriteGlobalSettings = gamingEngine.canWriteAnimationScales()
                )
            }
            // `wm size` needs a privileged channel, so a channel appearing can turn a metrics-only
            // reading into the panel's real native resolution.
            refreshResolutionState()
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
                isDndEnabled = dndEnabled
            )
        }
        // Developer Options edits the same three settings, so re-read them rather than trusting the
        // value cached when this screen was last open.
        gamingEngine.refreshAnimationScales()
        // Data Saver can equally be changed from Settings, so the switch is driven by a fresh read of
        // the policy rather than by whatever this app last did.
        refreshBackgroundDataStatus()
        refreshDnsStatus()
        // Same for the Developer Options switches: they are the platform's own settings, and both
        // Developer Options and gaming mode can have moved them since this screen was last open.
        viewModelScope.launch { gameDeveloperOptions.refresh() }
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

    /**
     * Turns Android's Data Saver on and exempts the game library from it.
     *
     * One of the two independent halves of Background Data Restriction; the other is [setVpnFirewall].
     * They are separate mechanisms with separate strengths, so the user picks either, both, or
     * neither — see [BackgroundDataRestrictor] and [VpnFirewall] for what each one really does.
     */
    fun setBackgroundDataRestriction(enable: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(isChangingBackgroundData = true) }
            val outcome = if (enable) {
                backgroundDataRestrictor.enable(_uiState.value.games.map { it.packageName })
            } else {
                backgroundDataRestrictor.disable()
            }
            _toasts.tryEmit(outcome.message)
            _uiState.update { it.copy(isChangingBackgroundData = false) }
            refreshBackgroundDataStatus()
        }
    }

    /**
     * Turns the local VPN block on or off.
     *
     * Needs no root or Shizuku, but does need Android's one-time VPN consent. When that is missing the
     * switch does *not* move: [UiState.vpnConsentRequest] is raised instead, the screen launches the
     * system dialog, and [onVpnConsentResult] decides what actually happened.
     */
    fun setVpnFirewall(enable: Boolean) {
        viewModelScope.launch {
            if (!enable) {
                _uiState.update { it.copy(isChangingVpnFirewall = true) }
                vpnFirewall.stop()
                _uiState.update { it.copy(isChangingVpnFirewall = false) }
                _toasts.tryEmit("Other apps can use the internet again")
                return@launch
            }
            if (vpnFirewall.prepareIntent() != null) {
                _uiState.update { it.copy(vpnConsentRequest = true) }
                return@launch
            }
            startVpnFirewall()
        }
    }

    /**
     * Android's VPN consent Intent, or null when consent is already in place.
     *
     * The dialog belongs to the system and can only be shown from an Activity, so the Intent is handed
     * to the screen rather than launched here.
     */
    fun vpnConsentIntent(): Intent? = vpnFirewall.prepareIntent()

    /** Called by the screen once Android's consent dialog has been answered. */
    fun onVpnConsentResult(granted: Boolean) {
        _uiState.update { it.copy(vpnConsentRequest = false) }
        if (!granted) {
            _toasts.tryEmit("You said no, so nothing is blocked")
            return
        }
        viewModelScope.launch { startVpnFirewall() }
    }

    private suspend fun startVpnFirewall() {
        _uiState.update { it.copy(isChangingVpnFirewall = true) }
        val refusal = vpnFirewall.start(_uiState.value.games.map { it.packageName })
        _uiState.update { it.copy(isChangingVpnFirewall = false) }
        if (refusal != null) _toasts.tryEmit(refusal)
    }

    /** Re-reads the real policy state, so the toggle reflects the system and not a local guess. */
    fun refreshBackgroundDataStatus() {
        viewModelScope.launch {
            val status = backgroundDataRestrictor.status()
            _uiState.update {
                it.copy(
                    backgroundDataStatus = status,
                    backgroundDataEngaged = backgroundDataRestrictor.isEngaged()
                )
            }
            // Cross-checked against the connectivity service rather than trusted from our own record:
            // Android tears a VPN down when another VPN app starts, without telling us, and a switch
            // still showing "on" would then be claiming a block that no longer exists.
            val vpnState = vpnFirewall.state.value
            if (vpnState.running && vpnFirewall.systemReportsVpn() == false) {
                vpnFirewall.stop()
            }
        }
    }

    // ---- Private DNS ----

    /**
     * Re-reads `private_dns_mode`, `private_dns_specifier` and the resolvers the active network is
     * using. Private DNS is equally settable from Settings, so nothing here is cached from a write.
     */
    fun refreshDnsStatus() {
        viewModelScope.launch {
            val status = dnsFeature.status()
            _uiState.update { it.copy(dnsStatus = status) }
        }
    }

    /**
     * Points Private DNS at one of the providers, or clears the choice.
     *
     * Replaces buttons that wrote `net.dns1`/`net.dns2` — properties nothing on Android has read since
     * version 5 — so every press reported success and changed nothing. See [DnsFeature].
     */
    fun applyDnsProvider(provider: DnsFeature.Provider) = changeDns { dnsFeature.apply(provider) }

    fun setDnsAutomatic() = changeDns { dnsFeature.setAutomatic() }

    fun disableDns() = changeDns { dnsFeature.disable() }

    private fun changeDns(action: suspend () -> DnsFeature.Outcome) {
        viewModelScope.launch {
            _uiState.update { it.copy(isChangingDns = true) }
            val outcome = action()
            _toasts.tryEmit(outcome.message)
            _uiState.update { it.copy(isChangingDns = false) }
            // The resolver list takes a moment to be renegotiated, so this read can still show the
            // previous servers. Reporting what is there now is still honest; the user can re-open the
            // card to see the new list once the network has settled.
            refreshDnsStatus()
        }
    }

    fun toggleDnd(enable: Boolean): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (enable && !nm.isNotificationPolicyAccessGranted) return false
        nm.setInterruptionFilter(if (enable) android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY else android.app.NotificationManager.INTERRUPTION_FILTER_ALL)
        _uiState.update { it.copy(isDndEnabled = enable) }
        return true
    }

    fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(context)

    /**
     * Intent that opens this app's all-files-access screen, which is what the cleaner needs before
     * it can walk shared storage on Android 11+. Returns null below API 30, where the runtime
     * storage permission covers it instead.
     */
    fun allFilesAccessIntent(): Intent? {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return null
        return try {
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
                data = "package:${context.packageName}".toUri()
            }
        } catch (_: Exception) {
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        }
    }

    // ---- System cleaner ----

    fun scanForJunk() {
        // A storage sweep can take a while; a second tap replaces the first instead of
        // racing it to publish results.
        scanJob?.cancel()
        _uiState.update { it.copy(cleanResult = null, scanReport = null, isScanningJunk = true) }
        scanJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val report = CleaningFeature.scan(context, shellRunner)
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(scanReport = report) }
                }
                // Say plainly whether anything could be read at all: an empty result after a real
                // sweep and an empty result because storage was unreachable are different facts.
                _toasts.tryEmit(
                    when {
                        !report.scannedAnything -> "Could not look through your storage — see the notes below"
                        report.results.isEmpty() -> "All done: nothing to clean"
                        // Empty files and folders are found items that free no bytes; leading with
                        // the size would report them as nothing at all.
                        report.totalBytes == 0L -> "Found ${report.totalItems} empty things — they take up no space"
                        else -> "Found ${formatBytes(report.totalBytes)} in ${report.totalItems} things"
                    }
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _toasts.tryEmit("Could not finish looking: ${e.message ?: "something went wrong"}")
            } finally {
                // Not a suspend call, so it still runs after cancellation.
                _uiState.update { it.copy(isScanningJunk = false) }
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
        if (cleanJob?.isActive == true) return
        val report = _uiState.value.scanReport
        if (report == null) {
            _toasts.tryEmit("Press Scan first, so there is something to clean")
            return
        }
        val resultsToClean = report.results.filter { it.category in categories }
        if (resultsToClean.isEmpty()) {
            _toasts.tryEmit("Nothing was found in what you ticked")
            return
        }
        _uiState.update { it.copy(isCleaningJunk = true, cleanResult = null) }
        cleanJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = CleaningFeature.clean(shellRunner, resultsToClean)
                withContext(Dispatchers.Main) {
                    _uiState.update { state ->
                        // Drop the cleaned categories: those paths are gone, so their sizes
                        // would otherwise stay on screen as phantom reclaimable space.
                        state.copy(
                            cleanResult = result,
                            scanReport = state.scanReport?.let { current ->
                                current.copy(
                                    results = current.results.filterNot { it.category in categories }
                                )
                            }
                        )
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _toasts.tryEmit("Could not finish cleaning: ${e.message ?: "something went wrong"}")
            } finally {
                _uiState.update { it.copy(isCleaningJunk = false) }
            }
        }
    }

    // ---- Gaming engine actions ----

    // GamingEngine snapshots the settings it later restores, so nothing may write them first:
    // anything applied before captureAndSaveSnapshot() gets recorded as the "original" value and
    // survives deactivation. It already covers every command the old esports pass ran.
    fun activateGamingMode() = viewModelScope.launch {
        gamingEngine.toggleGamingMode(true)
        // The foreground service is what keeps the process alive while gaming mode holds system
        // settings, and its onTaskRemoved is the safety net that reverts them if the app is swiped
        // away. It belongs to activation — starting it from "Launch game" left it running with no
        // gaming mode behind it.
        ContextCompat.startForegroundService(context, Intent(context, GamingModeService::class.java))
        // Gaming mode writes the refresh-rate keys itself, so the standalone switch has to follow
        // the device rather than keep showing what it read before activation.
        gameDeveloperOptions.refresh()
    }
    fun deactivateGamingMode() = viewModelScope.launch {
        gamingEngine.toggleGamingMode(false)
        context.stopService(Intent(context, GamingModeService::class.java))
        gameDeveloperOptions.refresh()
    }

    fun boostRam() = viewModelScope.launch {
        _uiState.update { it.copy(isBoostingRam = true, ramResult = null) }
        // manualBoostRam() owns the whole sequence, including the cache trim, so its before/after
        // reading covers everything that was freed rather than starting after the trim.
        val result = gamingEngine.manualBoostRam()
        val message = when {
            result.freedMb == null ->
                "RAM Boost: ${result.stoppedCount} app(s) stopped — memory figures unavailable on this device"
            else -> "RAM Boost: ${result.freedMb} MB freed (${result.stoppedCount} app(s) stopped)"
        }
        _uiState.update { it.copy(isBoostingRam = false, ramResult = message) }
        _toasts.emit(message)
    }

    fun runBooster(mode: String, force: Boolean) {
        // Compiling every user app takes minutes, so it belongs to a foreground service rather
        // than viewModelScope — closing the screen must not abandon a half-finished dexopt run.
        // Progress reaches the UI through gamingEngine.boosterLog / boosterState.
        ContextCompat.startForegroundService(context, AppBoosterService.startIntent(context, mode, force))
    }

    fun stopBooster() {
        // Same launch route as the notification's Stop action: the service posts its notification
        // immediately on either path, which is what a startForegroundService start requires.
        ContextCompat.startForegroundService(context, AppBoosterService.stopIntent(context))
    }

    // Only the governor lock. Refresh rate and touch response belong to gaming mode, which
    // snapshots them — turning this switch off must not delete settings the user never changed.
    fun toggleFixedPerformance(enabled: Boolean) = viewModelScope.launch {
        // The engine reports whether the framework accepted the request, so a build without the
        // command produces a real explanation instead of a switch that flips and does nothing.
        val outcome = gamingEngine.toggleFixedPerformanceMode(enabled)
        if (!outcome.accepted || !enabled) _toasts.emit(outcome.message)
    }
    fun onBoostChange(level: Int) {
        appPrefs.edit { putInt("boost_level", level) }
        audioBoostController.applyBoost(level)
        _uiState.update { it.copy(boostLevel = level) }
    }

    /**
     * Sets one animation scale, the way Developer Options does — one setting, applied on selection.
     *
     * The engine verifies the write by reading the setting back, so the toast reports what the
     * system actually holds now rather than that a command was issued.
     */
    fun setAnimationScale(scale: AnimationScaleKind, value: Float) = viewModelScope.launch {
        if (gamingEngine.setAnimationScale(scale, value)) {
            _toasts.tryEmit("${scale.label}: ${formatScale(value)}")
        } else {
            _toasts.tryEmit("${scale.label} could not be changed${animationScaleBlockReason()}")
        }
    }

    /** Why a write was refused, when the reason is knowable. Empty when the cause is device-specific. */
    private fun animationScaleBlockReason(): String =
        if (gamingEngine.canWriteAnimationScales()) "" else " — needs root or Shizuku"

    /** `0.5x`, `1x`, `10x` — trailing `.0` dropped, as Developer Options labels them. */
    private fun formatScale(value: Float): String =
        if (value % 1f == 0f) "${value.toInt()}x" else "${value}x"

    // GamingEngine owns both toggles: it holds the StateFlow the UI observes and merges
    // activity_manager_constants without clobbering unrelated entries.
    fun toggleAlwaysFinish(enabled: Boolean) = viewModelScope.launch {
        gamingEngine.toggleAlwaysFinishActivities(enabled)
    }

    fun toggleBackgroundLimit(enabled: Boolean) = viewModelScope.launch {
        gamingEngine.toggleBackgroundProcessLimit(enabled)
    }

    /**
     * The three Developer Options gaming switches, each reporting what the device actually allows.
     *
     * This replaces a "Refresh Rate Lock" card that wrote both refresh-rate keys fire-and-forget and
     * flipped its own switch regardless of the result. [GameDeveloperOptions] verifies every write.
     */
    fun setShowRefreshRate(enabled: Boolean) = viewModelScope.launch {
        val result = gameDeveloperOptions.setShowRefreshRate(enabled)
        _toasts.tryEmit(devOptionToast("Show refresh rate", enabled, result))
    }

    fun setForcePeakRefreshRate(enabled: Boolean) = viewModelScope.launch {
        val result = gameDeveloperOptions.setForcePeakRefreshRate(enabled)
        _toasts.tryEmit(devOptionToast("Always use the fastest screen speed", enabled, result))
    }

    fun setGameDefaultFrameRateDisabled(enabled: Boolean) = viewModelScope.launch {
        val result = gameDeveloperOptions.setGameDefaultFrameRateDisabled(enabled)
        _toasts.tryEmit(devOptionToast("Let games run at full speed", enabled, result))
    }

    /** Names the setting and what the device did with it — never a success the read-back denies. */
    private fun devOptionToast(
        label: String,
        requested: Boolean,
        state: GameDeveloperOptions.ToggleState
    ): String = when {
        state.enabled == requested -> "$label: ${if (requested) "on" else "off"}"
        state.enabled == null ->
            "$label — your phone would not say what happened${state.unavailableReason?.let { " ($it)" }.orEmpty()}"
        else ->
            "$label could not be changed${state.unavailableReason?.let { " — $it" }.orEmpty()}"
    }

    fun toggleAutoForceStop(enabled: Boolean) {
        if (enabled) AutoForceStopService.start(context)
        else AutoForceStopService.stop(context)
        _uiState.update { it.copy(isAutoForceStopActive = enabled) }
    }

    fun toggleAutoForceStopKeepPackage(pkg: String) {
        val newSet = keepAliveStore.toggleKeptPackage(pkg)
        _uiState.update { it.copy(autoForceStopKeepPackages = newSet) }
    }

    /**
     * Starts the game and nothing else.
     *
     * It deliberately does not touch gaming mode: activation changes refresh rate, suspends other
     * apps and turns on Do Not Disturb, and doing that as a side effect of "Launch" applied
     * system-wide changes the user never asked for — and left them applied, because the launch path
     * never called the matching deactivation. Gaming mode is the hero card's switch alone.
     */
    fun launchGame(pkg: String) = viewModelScope.launch {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            _toasts.tryEmit("This app cannot be opened from here")
            return@launch
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            _toasts.tryEmit("Could not open it: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ---- Resolution Changer Logic ----

    /** One entry in the resolution picker. Every fixed entry is a scaling of the real panel size. */
    data class ResolutionOption(
        val id: String,
        val label: String,
        /** What applying this entry would run. Null for [CUSTOM_OPTION_ID], which the user fills in. */
        val target: DisplayMetricsProvider.Snapshot?
    )

    /** What `wm size` and `wm density` report right now. */
    private data class WmState(
        val physical: DisplayMetricsProvider.Snapshot?,
        val override: DisplayMetricsProvider.Snapshot?
    )

    fun onResWidthChange(v: String) = updateResInputs(width = v)
    fun onResHeightChange(v: String) = updateResInputs(height = v)
    fun onResDpiChange(v: String) = updateResInputs(dpi = v)

    /**
     * Selects a picker entry and shows exactly what it would apply.
     *
     * A fixed entry fills the fields with its own numbers, so the user always sees the values that
     * are about to be written rather than a percentage that hides them.
     */
    fun onResOptionSelected(id: String) {
        val option = _uiState.value.resolutionOptions.firstOrNull { it.id == id } ?: return
        val target = option.target
        if (target == null) {
            _uiState.update { it.copy(selectedResolutionId = id) }
            return
        }
        _uiState.update {
            it.copy(
                selectedResolutionId = id,
                widthInput = target.widthPixels.toString(),
                heightInput = target.heightPixels.toString(),
                dpiInput = target.densityDpi.toString()
            )
        }
        revalidateResInputs()
    }

    /**
     * Applies a typed value.
     *
     * Only reachable while the Custom entry is selected — the fields are read-only under a preset, so
     * that a preset's numbers cannot drift away from what the preset actually applies. The guard is
     * repeated here rather than left to the UI: a state update that silently reinterpreted a preset as
     * custom is exactly the kind of thing a screen change could reintroduce.
     */
    private fun updateResInputs(width: String? = null, height: String? = null, dpi: String? = null) {
        if (_uiState.value.selectedResolutionId != CUSTOM_OPTION_ID) return
        _uiState.update {
            it.copy(
                widthInput = width ?: it.widthInput,
                heightInput = height ?: it.heightInput,
                dpiInput = dpi ?: it.dpiInput
            )
        }
        revalidateResInputs()
    }

    /**
     * Reads the display through the shared [DisplayMetricsProvider] and, when a privileged channel
     * exists, through `wm size` / `wm density` as well.
     *
     * The two are not interchangeable: the metrics report the resolution *currently in force*, so
     * they follow an existing override, while `wm` also names the panel's own resolution. Presets
     * are therefore built from the `wm` reading whenever it is available, and the source of the
     * numbers is published so the screen can attribute them.
     */
    private fun refreshResolutionState() {
        viewModelScope.launch {
            val metrics = displayMetrics.current()
            val wm = readWmState()

            val native = wm?.physical ?: metrics.takeIf { it.isValid }
            val override = wm?.override
            val options = buildResolutionOptions(native)

            _uiState.update { state ->
                // Keep whatever the user has already typed; only seed the fields on the first read.
                val seed = state.widthInput.isBlank() && state.heightInput.isBlank()
                val applied = override ?: native
                state.copy(
                    nativeResolution = native,
                    resolutionSource = when {
                        wm?.physical != null -> DisplayMetricsProvider.Source.SHELL_WM.plainLabel
                        native != null -> metrics.source.plainLabel
                        else -> DisplayMetricsProvider.Source.UNAVAILABLE.plainLabel
                    },
                    activeOverride = override,
                    resolutionOptions = options,
                    selectedResolutionId = state.selectedResolutionId
                        ?: options.firstOrNull { it.target?.isSameSizeAndDensity(applied) == true }?.id,
                    widthInput = if (seed) applied?.widthPixels?.toString() ?: "" else state.widthInput,
                    heightInput = if (seed) applied?.heightPixels?.toString() ?: "" else state.heightInput,
                    dpiInput = if (seed) applied?.densityDpi?.toString() ?: "" else state.dpiInput
                )
            }
            revalidateResInputs()
        }
    }

    /**
     * Builds the picker entries: the native resolution, a set of downscales, and a custom entry.
     *
     * Only scalings the platform will actually accept survive — [DisplayMetricsProvider.scaledBy]
     * drops anything that would fall below the minimum surface size or outside `wm density`'s range
     * — so the list never offers a value that cannot be applied. Nothing is offered at all when the
     * panel size could not be read, because every entry is derived from it.
     */
    private fun buildResolutionOptions(
        native: DisplayMetricsProvider.Snapshot?
    ): List<ResolutionOption> {
        if (native == null || !native.isValid) return emptyList()
        val scalings = SCALE_PERCENTAGES.mapNotNull { percent ->
            val target = displayMetrics.scaledBy(percent, native) ?: return@mapNotNull null
            // A scaling that rounds back onto the native size would be a duplicate entry.
            if (percent != 100 && target.isSameSizeAndDensity(native)) return@mapNotNull null
            ResolutionOption(
                id = "scale_$percent",
                label = if (percent == 100) "Native" else "$percent%",
                target = target
            )
        }
        return scalings + ResolutionOption(CUSTOM_OPTION_ID, "Custom", null)
    }

    /**
     * Re-checks the typed values and publishes the reason they cannot be applied, if any.
     *
     * Everything rejected here is something the platform or the system UI genuinely cannot handle;
     * values that merely deserve caution are allowed through and confirmed instead.
     */
    private fun revalidateResInputs() {
        _uiState.update { it.copy(resValidationError = validateResInputs(it)) }
    }

    private fun validateResInputs(state: UiState): String? {
        val native = state.nativeResolution
        val width = state.widthInput.trim().toIntOrNull()
        val height = state.heightInput.trim().toIntOrNull()
        val dpi = state.dpiInput.trim().toIntOrNull()

        if (state.widthInput.isBlank() || state.heightInput.isBlank() || state.dpiInput.isBlank()) {
            return "Fill in all three numbers"
        }
        if (width == null || height == null || dpi == null) return "Use whole numbers only"

        val min = DisplayMetricsProvider.MIN_DIMENSION_PX
        if (width < min || height < min) return "Width and height must be at least $min"
        if (dpi < DisplayMetricsProvider.MIN_DENSITY_DPI || dpi > DisplayMetricsProvider.MAX_DENSITY_DPI) {
            return "DPI has to be between ${DisplayMetricsProvider.MIN_DENSITY_DPI} and " +
                "${DisplayMetricsProvider.MAX_DENSITY_DPI} — your phone will not take anything else"
        }
        if (native != null && native.isValid) {
            val maxWidth = native.widthPixels * MAX_SUPERSAMPLE_FACTOR
            val maxHeight = native.heightPixels * MAX_SUPERSAMPLE_FACTOR
            if (width > maxWidth || height > maxHeight) {
                return "Too big — more than ${MAX_SUPERSAMPLE_FACTOR}× your screen's own " +
                    "${native.sizeLabel}, which your phone cannot draw"
            }
        }
        return null
    }

    /**
     * Applies the typed values, asking for confirmation first when they are risky but legal.
     *
     * The confirmation cases are real hazards rather than a blanket warning: an aspect ratio the
     * panel does not have letterboxes or crops the system UI, and rendering above the native pixel
     * count costs GPU time for no visible gain on this panel.
     */
    fun applyResolutionChanges() {
        val state = _uiState.value
        val error = validateResInputs(state)
        if (error != null) {
            _uiState.update { it.copy(resValidationError = error) }
            logRes("Not changed: $error")
            return
        }
        val target = DisplayMetricsProvider.Snapshot(
            widthPixels = state.widthInput.trim().toInt(),
            heightPixels = state.heightInput.trim().toInt(),
            densityDpi = state.dpiInput.trim().toInt(),
            source = DisplayMetricsProvider.Source.SHELL_WM
        )

        val warning = resolutionWarning(target, state.nativeResolution)
        if (warning != null) {
            pendingResApply = target
            _uiState.update { it.copy(resWarning = warning) }
        } else {
            executeResolution(target)
        }
    }

    private fun resolutionWarning(
        target: DisplayMetricsProvider.Snapshot,
        native: DisplayMetricsProvider.Snapshot?
    ): String? {
        if (native == null || !native.isValid) {
            return "Your phone would not say what its real screen size is, so ${target.label} cannot " +
                "be checked against it. A size your screen cannot do may leave it unreadable until " +
                "you press Reset."
        }
        val nativeAspect = maxOf(native.widthPixels, native.heightPixels).toFloat() /
            minOf(native.widthPixels, native.heightPixels)
        val targetAspect = maxOf(target.widthPixels, target.heightPixels).toFloat() /
            minOf(target.widthPixels, target.heightPixels)
        if (abs(targetAspect - nativeAspect) / nativeAspect > MAX_ASPECT_DRIFT) {
            return "${target.sizeLabel} is a different shape from your screen (${native.sizeLabel}), " +
                "so the picture will look stretched or cut off, and some buttons may end up off the " +
                "edge. Use it anyway?"
        }
        if (target.widthPixels > native.widthPixels || target.heightPixels > native.heightPixels) {
            return "${target.sizeLabel} is bigger than your screen's ${native.sizeLabel}. Your phone " +
                "will draw more than it can show and then shrink it back down, which uses more " +
                "battery for no extra detail. Use it anyway?"
        }
        return null
    }

    fun confirmResWarning() {
        _uiState.update { it.copy(resWarning = null) }
        pendingResApply?.let { executeResolution(it) }
        pendingResApply = null
    }

    fun dismissResWarning() {
        _uiState.update { it.copy(resWarning = null) }
        pendingResApply = null
        logRes("Cancelled")
    }

    fun resetResolutionChanges() {
        viewModelScope.launch {
            if (!requirePrivilegeForResolution()) return@launch
            _uiState.update { it.copy(isApplyingResolution = true) }
            // Each reset is its own command so a failure names the one that failed; `wm` prints
            // nothing on success, which makes the exit code the only usable signal.
            val size = shellRunner.execSafeResult("wm", "size", "reset")
            val density = shellRunner.execSafeResult("wm", "density", "reset")
            _uiState.update { it.copy(isApplyingResolution = false) }

            if (!size.isSuccess) logRes("Could not put the size back${failureDetail(size)}")
            if (!density.isSuccess) logRes("Could not put the sharpness back${failureDetail(density)}")

            refreshResolutionState()
            val after = readWmState()
            when {
                after == null ->
                    if (size.isSuccess && density.isSuccess) {
                        logRes("Reset — your phone would not confirm it, though")
                    }
                after.override == null ->
                    logRes("Reset — back to ${after.physical?.label ?: "your screen's own size"}")
                else -> logRes("Still changed: ${after.override.label}")
            }
        }
    }

    private fun executeResolution(target: DisplayMetricsProvider.Snapshot) {
        viewModelScope.launch {
            if (!requirePrivilegeForResolution()) return@launch
            _uiState.update { it.copy(isApplyingResolution = true) }
            // Size before density: the density that suits a size is only meaningful once it is set.
            val size = shellRunner.execSafeResult("wm", "size", target.sizeLabel)
            val density = if (size.isSuccess) {
                shellRunner.execSafeResult("wm", "density", target.densityDpi.toString())
            } else {
                null
            }
            _uiState.update { it.copy(isApplyingResolution = false) }

            if (!size.isSuccess) {
                logRes("Could not change the size${failureDetail(size)}")
                refreshResolutionState()
                return@launch
            }
            if (density != null && !density.isSuccess) {
                logRes("Size changed, but the sharpness did not${failureDetail(density)}")
            }

            refreshResolutionState()
            // `wm` exits 0 without checking the value took, so the read-back is what decides.
            val after = readWmState()
            val applied = after?.override ?: after?.physical
            when {
                applied == null -> logRes("Set to ${target.label} — your phone would not confirm it, though")
                applied.isSameSizeAndDensity(target) -> logRes("Set to ${target.label}")
                else -> logRes("Asked for ${target.label} but your phone is showing ${applied.label}")
            }
        }
    }

    /** @return true when a privileged channel exists; logs the real reason when it does not. */
    private suspend fun requirePrivilegeForResolution(): Boolean {
        val privileged = withContext(Dispatchers.IO) { shellRunner.hasPrivilege() }
        if (!privileged) {
            logRes("Needs root or Shizuku. Android does not let a normal app resize the screen.")
            _toasts.tryEmit("Changing the screen size needs root or Shizuku")
        }
        return privileged
    }

    /**
     * Parses `wm size` and `wm density`.
     *
     * @return both readings, or null when there is no privileged channel or neither command
     *   produced a parseable line — never a guessed value.
     */
    private suspend fun readWmState(): WmState? = withContext(Dispatchers.IO) {
        if (!shellRunner.hasPrivilege()) return@withContext null
        val sizeOut = shellRunner.execSafeResult("wm", "size").takeIf { it.isSuccess }?.stdout.orEmpty()
        val densityOut = shellRunner.execSafeResult("wm", "density").takeIf { it.isSuccess }?.stdout.orEmpty()
        if (sizeOut.isBlank() && densityOut.isBlank()) return@withContext null

        val physicalSize = PHYSICAL_SIZE.find(sizeOut)
        val overrideSize = OVERRIDE_SIZE.find(sizeOut)
        val physicalDensity = PHYSICAL_DENSITY.find(densityOut)?.groupValues?.get(1)?.toIntOrNull()
        val overrideDensity = OVERRIDE_DENSITY.find(densityOut)?.groupValues?.get(1)?.toIntOrNull()

        fun snapshot(
            match: MatchResult?,
            dpi: Int?,
            fallbackDpi: Int?
        ): DisplayMetricsProvider.Snapshot? {
            val width = match?.groupValues?.get(1)?.toIntOrNull() ?: return null
            val height = match.groupValues[2].toIntOrNull() ?: return null
            // `wm density` reports no override when only the size was changed, so the physical
            // density is the density actually in effect then.
            val density = dpi ?: fallbackDpi ?: return null
            return DisplayMetricsProvider.Snapshot(
                width, height, density, DisplayMetricsProvider.Source.SHELL_WM
            )
        }

        val physical = snapshot(physicalSize, physicalDensity, null)
        val override = if (overrideSize != null || overrideDensity != null) {
            snapshot(overrideSize ?: physicalSize, overrideDensity, physicalDensity)
        } else {
            null
        }
        if (physical == null && override == null) null else WmState(physical, override)
    }

    private fun failureDetail(result: ShellRunner.ExecResult): String =
        result.text.takeIf { it.isNotBlank() }?.let { ": ${it.lineSequence().first().trim()}" }.orEmpty()

    private fun logRes(m: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        _uiState.update { it.copy(resLog = it.resLog + "[$time] $m") }
    }

    private companion object {
        const val CUSTOM_OPTION_ID = "custom"

        /** Downscales offered in the picker. All are safe: every one stays at or below native. */
        val SCALE_PERCENTAGES = listOf(100, 90, 80, 75, 66, 50)

        /** Beyond this the target's aspect ratio no longer matches the panel's. */
        const val MAX_ASPECT_DRIFT = 0.02f

        /** Rendering further above native than this is refused outright, not just warned about. */
        const val MAX_SUPERSAMPLE_FACTOR = 2

        val PHYSICAL_SIZE = Regex("Physical size:\\s*(\\d+)x(\\d+)")
        val OVERRIDE_SIZE = Regex("Override size:\\s*(\\d+)x(\\d+)")
        val PHYSICAL_DENSITY = Regex("Physical density:\\s*(\\d+)")
        val OVERRIDE_DENSITY = Regex("Override density:\\s*(\\d+)")
    }
}

/** Size and density equality, ignoring which API each reading came from. */
private fun DisplayMetricsProvider.Snapshot.isSameSizeAndDensity(
    other: DisplayMetricsProvider.Snapshot?
): Boolean = other != null &&
    widthPixels == other.widthPixels &&
    heightPixels == other.heightPixels &&
    densityDpi == other.densityDpi
