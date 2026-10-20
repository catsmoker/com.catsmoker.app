package com.catsmoker.app.features.editgamefiles.wuwa

import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.catsmoker.app.R
import com.catsmoker.app.features.editgamefiles.ConfigBackupStore
import com.catsmoker.app.shared.util.DisplayMetricsProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Which of the two write channels a deploy was asked to use. */
enum class WuwaDeployChannel { SHELL, SAF }

/**
 * A preset id the UI or the store asked for that is not in [WuWaConfigGenerator.PRESETS].
 *
 * Carried as a string-resource reference plus the offending id — never a resolved string,
 * because the app locale can change while this ViewModel lives (see the localization rules).
 * When this is set, the querying path must explain the limitation instead of silently
 * substituting `balanced`.
 */
data class PresetNotice(@StringRes val messageRes: Int, val unsupportedId: String)

/**
 * Screen state for the Wuthering Waves config generator.
 *
 * The generated previews are recomputed from (preset, options) on every change —
 * the generator is pure and cheap, so what the preview shows is always exactly what a deploy
 * would push. Device-specific inputs are deliberately not read: the generator runs on its
 * universal lowest-tier fallback. [deployReport]/[deployFailure] carry what the last deploy *actually did*, per
 * the house convention: per-file push/verify status, whether the game was stopped, whether
 * the game's hash monitor was synced — with "not applicable" (null) kept distinct from both.
 */
data class WuwaConfigUiState(
    val loading: Boolean = true,
    /** null = the package could not be checked (query threw). */
    val gameInstalled: Boolean? = null,
    /**
     * "Show anyway" override for a confirmed-missing install. False until the user taps the
     * small secondary button on the install-first card — then the generator renders below
     * the card even though the game is absent. Same contract as the File Engineering
     * screen's own override (EditGameFilesViewModel.UiState.showEditorAnyway): the card
     * stays visible, refreshes never clear the choice, and the flag is meaningless once
     * the game is installed. No seeding is needed here — unlike the HSR/GRID editors, the
     * generator runs on device facts and its previews exist with no install at all.
     */
    val showEditorAnyway: Boolean = false,
    val preset: String = "balanced",
    /**
     * Set when [WuwaConfigViewModel.selectPreset] refused an unsupported id (selection kept),
     * or when a stored preset could not be restored; cleared by the next valid selection.
     * The card renders the explanation; nothing is substituted silently.
     */
    val presetNotice: PresetNotice? = null,
    // The generator's own defaults leave the two optional files off; the screen turns them on
    // so a first deploy delivers the full five-file set the game's monitor watches.
    val options: WuWaConfigGenerator.Options = WuWaConfigGenerator.Options(
        generateScalability = true,
        generateHardware = true
    ),
    /**
     * The raw `key=value` block backing [WuWaConfigGenerator.Options.cvarOverrides].
     * Text (not the map) is the UI's source of truth: the map is derived on every
     * keystroke via [WuWaConfigGenerator.parseCvarOverrides], and regenerating text
     * from the map would clobber typing. Malformed lines never reach the map.
     */
    val cvarOverrideText: String = "",
    val previews: Map<String, String> = emptyMap(),
    /**
     * Backup-vs-generated diff summary for the previewed Engine.ini; null means no
     * backup exists or its bytes would not decode — never a fabricated zero-diff.
     */
    val engineBackupDiff: WuwaDiffSummary? = null,
    /**
     * Accepted/rejected/RT-flagged cvars for the previewed Engine.ini, computed
     * inside generation from its pre/post-strip snapshots. Null before the first
     * generation runs.
     */
    val engineReport: WuwaGenerationReport.FileReport? = null,
    val applying: Boolean = false,
    /** True while the shader-cache cleanup is force-stopping the game and deleting folders. */
    val cleaningCache: Boolean = false,
    /**
     * True while [WuwaConfigViewModel.regenerate] recomputes [previews] for the current
     * (preset, options). Deploys are held until it settles, so what a deploy pushes is
     * always the configuration the screen currently shows — never a previous selection's.
     */
    val generating: Boolean = false,
    val deployReport: WuwaConfigManager.DeploySuccess? = null,
    val deployFailure: String? = null,
    val backups: List<ConfigBackupStore.Entry> = emptyList(),
    val canShell: Boolean = false,
    val hasSafUri: Boolean = false,
    val recommendation: WuwaSmartBrain.Recommendation? = null,
    /** This device's chipset, read once — an unscored note beside the recommendation. */
    val chipset: WuwaChipset.ChipsetInfo? = null,
    // NOTE (product requirement): Deploy History and Game Log Analysis are permanently
    // excluded — no history/analysis state lives here. Do not re-add. The tuner and the
    // installed-profile read Client.log internally for their own axes; that is not the
    // removed analysis surface.
    /**
     * True once the CVar-database tables (`assets/wuwa_cvars/`) have loaded.
     * Gates the hygiene switch: enabling without loaded tables would silently
     * optimize against nothing (see [WuwaCvarDbGate]).
     */
    val cvarDbLoaded: Boolean = false,
    /** True while the installed profile (databases + log + ini counts) is being read. */
    val readingProfile: Boolean = false,
    /**
     * The installed game profile; null until Read runs successfully. Survives
     * refreshes — it is device evidence, not a draft.
     */
    val installedProfile: WuwaProfileExtractor.InstalledProfile? = null,
    /** Why the last profile read could not happen, when it could not. */
    val profileFailure: String? = null,
    /** The auto-tune loop's state; null = never started or cleared. */
    val tuner: WuwaBenchmarkTuner.TunerState? = null,
    /** True while a tuner deploy or measurement is running. */
    val tunerBusy: Boolean = false,
    /** Imported community packs, newest first. */
    val communityPacks: List<WuwaCommunityPackStore.StoredPack> = emptyList(),
    /** True while a picked pack folder is being walked and parsed. */
    val importingPack: Boolean = false,
    /** Why the last pack import could not happen, when it could not. */
    val packFailure: String? = null,
    /** The "packId/variantName" whose deploy is running, if any. */
    val deployingPackKey: String? = null
)

@HiltViewModel
class WuwaConfigViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val manager: WuwaConfigManager,
    private val displayMetrics: DisplayMetricsProvider
) : ViewModel() {

    sealed class WuwaEvent {
        data class Toast(val message: String, val isLong: Boolean = false) : WuwaEvent()
        /** SAF deploy was requested with no persisted tree grant — the Route launches the picker. */
        data object LaunchFolderPicker : WuwaEvent()
    }

    /**
     * The generator's persisted preset + options (`wuwa_prefs`, own file). Declared
     * before [_uiState] on purpose: the initial state restores from it, so it must
     * exist first. A chosen preset survives process death instead of silently
     * resetting to `balanced`.
     */
    private val presetStore = WuwaPresetStore(context)

    /**
     * The stored preset exactly as persisted (null = none). Kept beside the sanitized
     * restore so an id this build no longer supports is *explained* on the card instead
     * of being silently swapped for `balanced`.
     */
    private val storedPresetRaw = presetStore.loadPresetRaw()

    private val _uiState = MutableStateFlow(
        WuwaConfigUiState(
            preset = WuwaPresetStore.sanitizePreset(storedPresetRaw),
            options = presetStore.loadOptions(WuwaConfigUiState().options),
            presetNotice = storedPresetRaw
                ?.takeIf { it != WuwaPresetStore.sanitizePreset(it) }
                ?.let { PresetNotice(R.string.gf_wuwa_preset_unsupported, it) }
        )
    )
    val uiState: StateFlow<WuwaConfigUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<WuwaEvent>()
    val events: SharedFlow<WuwaEvent> = _events.asSharedFlow()

    /** Parked deploy that waits for the SAF folder grant the picker is about to produce. */
    private var pendingSafDeploy: Map<String, String>? = null

    /** Parked backup restore waiting for the same grant (the entry to put back). */
    private var pendingSafRestore: ConfigBackupStore.Entry? = null

    /**
     * Parked *pack* deploy waiting for the same grant: pack id, variant name, and whether the
     * restricted cvars are to be stripped. Separate from [pendingSafDeploy] because a pack
     * deploy re-reads its files from the store when the grant lands rather than holding them.
     */
    private var pendingSafPackDeploy: Triple<String, String, Boolean>? = null

    /**
     * The auto-tune loop's persisted state. Gson in `filesDir`, written by the loop
     * itself; the reference's `benchmark_tuner_state.json` kept the loop alive across
     * process death and this file carries that contract here.
     */
    private val tunerStateFile = java.io.File(context.filesDir, "wuwa_tuner_state.json")

    /**
     * The known-CVar database behind the hygiene pass. Loaded once at startup on
     * IO (asset reads); every generation before that runs without the pass rather
     * than against half-loaded tables.
     */
    private val cvarDb = WuWaCvarDatabase(context)

    /**
     * This device's facts (GL renderer via EGL, model/SoC, panel resolution), read once per
     * ViewModel — the EGL probe creates a throwaway GL context, so it does not run per
     * keystroke. Only call from an IO context.
     */
    private var cachedDeviceFacts: WuWaConfigGenerator.DeviceInfo? = null

    private fun deviceFacts(): WuWaConfigGenerator.DeviceInfo =
        cachedDeviceFacts ?: WuwaDeviceInfo.read(displayMetrics).also { cachedDeviceFacts = it }

    init {
        refresh()
        resumeTunerState()
        loadCvarDb()
    }

    /**
     * The install-first card's small secondary "Show anyway" action: reveals the existing
     * generator below the card even though the game is absent. The previews are generated
     * from device facts and the selected preset/options, so browsing and editing work with
     * no install; deploys attempted from there report honestly through the manager. The
     * card stays visible above, so the screen never pretends the game is installed.
     * One-way: stays set until the ViewModel is cleared; refreshes preserve it.
     */
    fun onShowEditorAnyway() = _uiState.update { it.copy(showEditorAnyway = true) }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true) }
            data class RefreshOutcome(
                val installed: Boolean,
                val engineIni: String?,
                val recommendation: WuwaSmartBrain.Recommendation
            )
            val outcome = withContext(Dispatchers.IO) {
                // SmartBrain's measurable inputs are real reads: ActivityManager RAM + low-RAM
                // flag and the Vulkan feature flag. GPU/resolution stay unknown without a
                // log analysis (permanently excluded — see the state KDoc), so the unknown
                // tier scores unscored and the ladder never emits tiers needing unmeasured
                // evidence.
                val installed = try {
                    context.packageManager.getPackageInfo(WuwaConfigManager.PACKAGE, 0)
                    true
                } catch (_: Exception) {
                    false
                }
                // Device-facts-only score: Game Log Analysis is permanently excluded, so no
                // log axes ever feed this. The tuner and the installed profile read the log
                // for their own axes; the recommendation never sees it.
                val rec = computeRecommendation()
                RefreshOutcome(
                    installed = installed,
                    engineIni = if (manager.canDeployViaShell()) {
                        manager.readExistingEngineIni()
                    } else null,
                    recommendation = rec
                )
            }
            _uiState.update {
                it.copy(
                    loading = false,
                    gameInstalled = outcome.installed,
                    canShell = manager.canDeployViaShell(),
                    hasSafUri = manager.hasSafTreeUri(),
                    backups = manager.backups(),
                    recommendation = outcome.recommendation,
                    // Device-constant: read once per refresh, not per analysis.
                    chipset = it.chipset ?: WuwaChipset.detectDevice(),
                    communityPacks = manager.communityPacks()
                )
            }
            regenerate(outcome.engineIni)
        }
    }

    fun selectPreset(preset: String) {
        // Ids are lowercase identifiers. An id this build does not support is explained on
        // the card and the current selection is kept — never silently substituted, so what
        // the screen shows (and a deploy would push) stays what the user last chose.
        if (preset !in WuWaConfigGenerator.PRESETS) {
            _uiState.update {
                it.copy(presetNotice = PresetNotice(R.string.gf_wuwa_preset_unsupported, preset))
            }
            return
        }
        presetStore.savePreset(preset)
        _uiState.update { it.copy(preset = preset, presetNotice = null) }
        regenerate()
        // The score does not depend on the selection, but the card compares the two —
        // re-read device facts so the comparison never shows a stale recommendation.
        updateRecommendation()
    }

    /**
     * Builds the SmartBrain recommendation from real device reads: [WuwaDeviceInfo] (the
     * GL renderer string via EGL, the panel resolution, the model/SoC) plus ActivityManager
     * RAM and the low-RAM and Vulkan flags. Game Log Analysis is permanently excluded, so
     * the log-measured axes are always absent and named as unscored — but a GPU tier and a
     * resolution are measured on-device now, so the ladder can reach its upper rungs on
     * hardware that genuinely holds them.
     */
    private suspend fun computeRecommendation(): WuwaSmartBrain.Recommendation = withContext(Dispatchers.IO) {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        val ramMb = (memInfo.totalMem / (1024L * 1024L)).toInt()
        val facts = deviceFacts()
        WuwaSmartBrain.recommend(
            WuwaSmartBrain.DeviceSignals(
                gpu = facts.gpu,
                ramMb = ramMb,
                resolution = facts.resolution,
                vulkanAvailable = try {
                    context.packageManager.hasSystemFeature(
                        android.content.pm.PackageManager.FEATURE_VULKAN_HARDWARE_VERSION
                    )
                } catch (_: Exception) {
                    null
                },
                lowRamDevice = try {
                    activityManager.isLowRamDevice
                } catch (_: Exception) {
                    null
                },
                fpsActual = null,
                fpsCap = null,
                thermalEvents = null,
                gpuOom = null,
                dropFrames = null,
                autoAdjustTriggers = null,
                autoAdjustRecoveries = null,
                textureErrors = null,
                networkErrors = null,
                screenPct = null,
                activeCvars = emptyMap()
            )
        )
    }

    /**
     * Selects the SmartBrain recommendation as the preset — a one-tap `selectPreset`,
     * nothing more: nothing is deployed and no game file changes until the user deploys
     * the configuration the screen shows.
     */
    fun applyRecommendation() {
        val rec = _uiState.value.recommendation ?: return
        selectPreset(rec.preset)
        viewModelScope.launch {
            _events.emit(WuwaEvent.Toast(context.getString(R.string.gf_wuwa_rec_applied, rec.preset, rec.score), true))
        }
    }

    /**
     * Re-reads device facts into the recommendation without touching anything else.
     * The score never depends on the selected preset, but the card compares the two —
     * callers that change the selection refresh the comparison through this.
     */
    private fun updateRecommendation() {
        viewModelScope.launch(Dispatchers.IO) {
            val rec = computeRecommendation()
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(recommendation = rec) }
            }
        }
    }

    /**
     * One-tap installed-profile read: the game's databases (uid, servers, versions,
     * language), ini settings counts, and the log half when Client.log exists. Halves
     * fail independently inside the manager; read-only, nothing is deployed.
     */
    fun readInstalledProfile() {
        if (_uiState.value.readingProfile) return
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(readingProfile = true, profileFailure = null) }
            when (val result = manager.readInstalledProfile()) {
                is WuwaConfigManager.InstalledProfileResult.Read -> {
                    _uiState.update {
                        it.copy(readingProfile = false, installedProfile = result.profile, profileFailure = null)
                    }
                }
                is WuwaConfigManager.InstalledProfileResult.Failure -> {
                    _uiState.update { it.copy(readingProfile = false, profileFailure = result.detail) }
                }
            }
        }
    }

    /**
     * Loads the CVar-database tables once; flips the hygiene gate when they land.
     * A failed load leaves the switch disabled, never a pass against empty tables.
     */
    private fun loadCvarDb() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { cvarDb.load() }
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(cvarDbLoaded = cvarDb.isLoaded) }
            }
        }
    }

    /**
     * The CVar-database hygiene switch: enabling requires loaded tables (the screen
     * also disables the switch until they land). Disabling always works.
     */
    fun setOptimizeWithCvarDb(enabled: Boolean) {
        if (enabled && !WuwaCvarDbGate.canEnable(_uiState.value.cvarDbLoaded)) {
            viewModelScope.launch {
                _events.emit(WuwaEvent.Toast(context.getString(R.string.gf_wuwa_cvardb_needed), true))
            }
            return
        }
        updateOptions { it.copy(optimizeWithCvarDb = enabled) }
    }

    fun updateOptions(transform: (WuWaConfigGenerator.Options) -> WuWaConfigGenerator.Options) {
        val next = transform(_uiState.value.options)
        presetStore.saveOptions(next)
        _uiState.update { it.copy(options = next) }
        regenerate()
    }

    /**
     * Edits the custom-CVar block: the text is kept verbatim for the field, the
     * parsed map feeds the next generation (see [WuWaConfigGenerator.Options]).
     * Regenerates like any other option change, so the Preview section always
     * shows what the current text produces.
     */
    fun onCvarOverrideTextChange(text: String) {
        val next = _uiState.value.options.copy(
            cvarOverrides = WuWaConfigGenerator.parseCvarOverrides(text)
        )
        presetStore.saveOptions(next)
        _uiState.update {
            it.copy(
                cvarOverrideText = text,
                options = next
            )
        }
        regenerate()
    }

    /** Bumped per [regenerate] call; a stale job's result is dropped rather than published. */
    private var regenerationToken = 0L

    private fun regenerate(existingEngineIni: String? = null) {
        val token = ++regenerationToken
        val state = _uiState.value
        _uiState.update { it.copy(generating = true) }
        viewModelScope.launch(Dispatchers.IO) {
            // The device's own [Core.System] paths are worth a privileged read, but only when
            // root/Shizuku exists; otherwise the generator's snapshot fallback applies.
            val existing = existingEngineIni
                ?: if (state.canShell) manager.readExistingEngineIni() else null
            val configs = WuWaConfigGenerator.generate(
                state.preset,
                state.options,
                deviceFacts(),
                existing,
                // The hygiene pass runs only behind the switch AND loaded tables —
                // the gate keeps an enabled-without-tables state from silently
                // optimizing against nothing (see WuwaCvarDbGate).
                cvarDb = if (WuwaCvarDbGate.effectiveDb(
                        state.options.optimizeWithCvarDb, cvarDb.isLoaded
                    )
                ) {
                    cvarDb
                } else {
                    null
                }
            )
            // Pair the generated Engine.ini with the newest device backup, when one
            // exists and decodes: a pre/post diff the game was never needed for.
            // Local file bytes, so this stays on the IO dispatcher with the rest.
            val backupEntry = WuwaBackupDiff.matchEntry(state.backups, "Engine.ini")
            val backupDiff = WuwaBackupDiff.compare(
                backupEntry?.let { manager.readBackupBytes(it) },
                configs.engine
            )
            withContext(Dispatchers.Main) {
                // A newer regeneration supersedes this one — its previews must not be
                // overwritten by an older selection's, or a deploy could push files the
                // screen no longer shows.
                _uiState.update {
                    if (token != regenerationToken) it
                    else it.copy(
                        previews = configs.asMap(),
                        engineReport = configs.engineReport,
                        engineBackupDiff = backupDiff,
                        generating = false
                    )
                }
            }
        }
    }

    /**
     * M106(b): deletes the game's two shader-cache folders through the root/Shizuku channel.
     * The reference's "Protocol A - Clean Test" does this so the next launch recompiles
     * shaders; the manager force-stops the game first and reports deleted / absent /
     * refused separately. A missing channel is a toast, not a silent no-op.
     */
    fun cleanShaderCache() {
        if (_uiState.value.cleaningCache) return
        viewModelScope.launch {
            _uiState.update { it.copy(cleaningCache = true) }
            val result = manager.cleanShaderCache()
            _uiState.update { it.copy(cleaningCache = false) }
            when {
                result == null -> _events.emit(
                    WuwaEvent.Toast(context.getString(R.string.gf_wuwa_cache_no_channel), true)
                )
                result.stopDetail.isNotBlank() -> _events.emit(
                    WuwaEvent.Toast(
                        context.getString(R.string.gf_wuwa_cache_failed, "game stop refused: ${result.stopDetail}"),
                        true
                    )
                )
                result.refused.isNotEmpty() -> _events.emit(
                    WuwaEvent.Toast(
                        context.getString(R.string.gf_wuwa_cache_failed, result.refused.joinToString("; ")),
                        true
                    )
                )
                result.deleted.isEmpty() -> _events.emit(
                    WuwaEvent.Toast(context.getString(R.string.gf_wuwa_cache_none))
                )
                else -> _events.emit(
                    WuwaEvent.Toast(
                        context.getString(
                            R.string.gf_wuwa_cache_done,
                            result.deleted.joinToString(", "),
                            result.absent.size
                        ),
                        true
                    )
                )
            }
        }
    }

    fun onDeploy(channel: WuwaDeployChannel) {
        val state = _uiState.value
        // Nothing deploys while a deploy is running, and nothing deploys a preview set the
        // current (preset, options) has not finished producing — see [generating].
        if (state.applying || state.generating) return
        val files = state.previews
        if (files.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(applying = true, deployFailure = null) }
            if (channel == WuwaDeployChannel.SAF && !manager.hasSafTreeUri()) {
                // No persisted grant yet: park the deploy and ask for the folder. The picked
                // grant re-runs it — same parking pattern as the custom-upload channel.
                pendingSafDeploy = files
                _uiState.update { it.copy(applying = false) }
                _events.emit(WuwaEvent.LaunchFolderPicker)
                return@launch
            }
            val result = when (channel) {
                WuwaDeployChannel.SHELL -> manager.deployViaShell(files)
                WuwaDeployChannel.SAF -> manager.deployViaSaf(files)
            }
            handleDeployResult(result, _uiState.value.preset)
        }
    }

    /** Completes a parked SAF deploy once the folder grant exists. */
    fun onSafFolderPicked(uri: Uri) {
        viewModelScope.launch {
            if (!manager.onSafFolderPicked(uri)) {
                _events.emit(WuwaEvent.Toast(context.getString(R.string.gf_grant_lost), true))
                pendingSafDeploy = null
                pendingSafPackDeploy = null
                pendingSafRestore = null
                return@launch
            }
            _uiState.update { it.copy(hasSafUri = true) }
            val parked = pendingSafDeploy
            pendingSafDeploy = null
            if (parked != null) {
                _uiState.update { it.copy(applying = true, deployFailure = null) }
                handleDeployResult(manager.deployViaSaf(parked), _uiState.value.preset)
            }
            // A parked pack deploy waited for the same grant; it runs after (never beside) a
            // parked generated deploy — only one can have been parked at a time in practice.
            val parkedPack = pendingSafPackDeploy
            pendingSafPackDeploy = null
            if (parkedPack != null) {
                deployPackVariant(parkedPack.first, parkedPack.second, parkedPack.third)
            }
            // A parked restore waited for the same grant; it runs last.
            val parkedRestore = pendingSafRestore
            pendingSafRestore = null
            if (parkedRestore != null) {
                onRestoreBackup(parkedRestore)
            }
        }
    }

    private suspend fun handleDeployResult(result: WuwaConfigManager.DeployResult, preset: String) {
        when (result) {
            is WuwaConfigManager.DeployResult.Success -> {
                val s = result.success
                val verifiedCount = s.files.count { it.verified }
                val failed = s.files.filter { !it.pushed }
                val detail = buildString {
                    append(context.getString(R.string.gf_dep_files, s.files.size, verifiedCount))
                    if (failed.isNotEmpty()) append(context.getString(R.string.gf_dep_refused, failed.size, failed.joinToString { "${it.name} (${it.detail})" }))
                    append(context.getString(R.string.gf_dep_dot))
                    append(
                        when (s.gameStopped) {
                            true -> context.getString(R.string.gf_dep_stopped)
                            false -> context.getString(R.string.gf_dep_stop_refused)
                            else -> context.getString(R.string.gf_dep_saf)
                        }
                    )
                    append(
                        when (s.hashSynced) {
                            true -> context.getString(R.string.gf_dep_hash_ok)
                            false -> context.getString(R.string.gf_dep_hash_fail, s.hashDetail)
                            null -> context.getString(R.string.gf_dep_hash_na, s.hashDetail)
                        }
                    )
                }
                // NOTE (product requirement): Deploy History is permanently excluded —
                // deploys are not recorded anywhere. The deploy report above is the record.
                _uiState.update {
                    it.copy(
                        applying = false, deployReport = s, deployFailure = null,
                        backups = manager.backups()
                    )
                }
                _events.emit(
                    WuwaEvent.Toast(
                        context.getString(if (failed.isEmpty() && s.files.all { it.verified }) R.string.gf_deployed_ok else R.string.gf_deployed_issues),
                        true
                    )
                )
                _events.emit(WuwaEvent.Toast(detail, true))
            }
            is WuwaConfigManager.DeployResult.Failure -> {
                _uiState.update { it.copy(applying = false, deployFailure = context.getString(R.string.gf_failed_stage_detail, result.stage, result.detail)) }
                _events.emit(WuwaEvent.Toast(context.getString(R.string.gf_deploy_failed, result.detail), true))
            }
        }
    }

    fun onRestoreBackup(entry: ConfigBackupStore.Entry) {
        viewModelScope.launch {
            when (val result = manager.restoreBackup(entry)) {
                is WuwaConfigManager.DeployResult.Success -> {
                    _events.emit(WuwaEvent.Toast(context.getString(R.string.gf_backup_restored_name, entry.file.name.substringAfter('_')), true))
                    handleDeployResult(result, "restore")
                }
                is WuwaConfigManager.DeployResult.Failure -> {
                    if (result.stage == "saf-folder") {
                        // No grant yet: park the restore behind the picker like a deploy.
                        pendingSafRestore = entry
                        _events.emit(WuwaEvent.LaunchFolderPicker)
                    } else {
                        _events.emit(WuwaEvent.Toast(context.getString(R.string.gf_restore_failed_detail, result.detail), true))
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- community packs

    /**
     * Imports the pack folder the user picked: a one-shot walk, parse, and store — the pack's
     * files and READMEs land in the app's own store, so the picked folder's grant can die with
     * the picker. The refusal text is the device's/document provider's own words, and a pack
     * with nothing deployable is refused before it can sit in the list looking usable.
     */
    fun importCommunityPack(uri: Uri) {
        if (_uiState.value.importingPack) return
        viewModelScope.launch {
            _uiState.update { it.copy(importingPack = true, packFailure = null) }
            try {
                when (val result = withContext(Dispatchers.IO) { manager.importCommunityPack(uri) }) {
                    is WuwaConfigManager.PackImportResult.Failure -> {
                        _uiState.update { it.copy(packFailure = result.detail) }
                        _events.emit(WuwaEvent.Toast(result.detail, true))
                    }
                    is WuwaConfigManager.PackImportResult.Read -> {
                        val pack = result.pack
                        _uiState.update { it.copy(communityPacks = manager.communityPacks()) }
                        val notes = mutableListOf<String>()
                        notes.add(context.getString(R.string.gf_pack_variants, pack.variants.size, pack.variants.joinToString { it.name }))
                        if (pack.variants.any { it.forbiddenCount > 0 }) {
                            notes.add(context.getString(R.string.gf_pack_restricted))
                        }
                        if (pack.unknownFiles.isNotEmpty()) notes.add(context.getString(R.string.gf_pack_unknown_n, pack.unknownFiles.size))
                        _events.emit(WuwaEvent.Toast(context.getString(R.string.gf_pack_imported_toast, pack.name, notes.joinToString("; ")), true))
                    }
                }
            } finally {
                _uiState.update { it.copy(importingPack = false) }
            }
        }
    }

    fun deleteCommunityPack(id: String) {
        manager.deleteCommunityPack(id)
        _uiState.update { it.copy(communityPacks = manager.communityPacks()) }
    }

    /** Deploys a pack variant verbatim — refused here too if it carries restricted cvars. */
    fun deployCommunityVariant(packId: String, variantName: String) {
        deployPackVariant(packId, variantName, strip = false)
    }

    /** Deploys a pack variant with the restricted cvar lines removed, reporting the strip. */
    fun deployCommunityVariantStripped(packId: String, variantName: String) {
        deployPackVariant(packId, variantName, strip = true)
    }

    /**
     * The pack deploy itself. Channel preference is root/Shizuku → SAF:
     * SAF cannot stop the game or sync its hash monitor, so it is the last resort. The deploy
     * reuses the deploy machinery a generated config goes through — backups, md5 read-back,
     * game stop, hash sync, deploy history — and the history label names the pack and variant
     * (plus "(restricted stripped)" when the gate's other door was taken), so a pack deploy is
     * distinguishable from a generated one in the record, not just in the moment.
     */
    private fun deployPackVariant(packId: String, variantName: String, strip: Boolean) {
        val state = _uiState.value
        if (state.applying || state.deployingPackKey != null) return
        val pack = state.communityPacks.firstOrNull { it.id == packId } ?: return
        val variant = pack.variants.firstOrNull { it.name == variantName } ?: return
        if (!strip && variant.forbiddenCount > 0) {
            // The screen does not offer this button, but the gate is enforced here too —
            // an imported pack is not a way around the restricted-cvar rule the generator obeys.
            viewModelScope.launch {
                _events.emit(
                    WuwaEvent.Toast(
                        context.getString(R.string.gf_pack_refused_toast, variant.name, variant.forbiddenCount),
                        true
                    )
                )
            }
            return
        }
        viewModelScope.launch {
            val key = "$packId/$variantName"
            // `applying` is set too so the generated-config deploy buttons disable while this
            // runs — it is the same deploy machinery, and two deploys must not interleave.
            _uiState.update { it.copy(deployingPackKey = key, applying = true, deployFailure = null) }
            try {
                val stripped = if (strip) WuwaCommunityPack.stripVariant(variant) else null
                val files = stripped?.files ?: variant.files
                if (manager.canDeployViaShell()) {
                    handleDeployResult(manager.deployViaShell(files), packDeployLabel(pack, variant, stripped != null))
                } else if (manager.hasSafTreeUri()) {
                    handleDeployResult(manager.deployViaSaf(files), packDeployLabel(pack, variant, stripped != null))
                } else {
                    // No channel yet: park the deploy behind the game-folder grant the picker
                    // is about to produce — same parking pattern as the generated deploy.
                    pendingSafPackDeploy = Triple(packId, variantName, strip)
                    _uiState.update { it.copy(applying = false) }
                    _events.emit(WuwaEvent.LaunchFolderPicker)
                }
            } finally {
                _uiState.update { it.copy(deployingPackKey = null) }
            }
        }
    }

    private fun packDeployLabel(
        pack: WuwaCommunityPackStore.StoredPack,
        variant: WuwaCommunityPack.Variant,
        stripped: Boolean
    ): String = "community: ${pack.name}/${variant.name}" + if (stripped) " (restricted stripped)" else ""

    // ------------------------------------------------------------------- auto-tune

    /**
     * Restores a tuner loop interrupted by process death. A state persisted mid-deploy or

     * mid-measurement cannot be resumed where it stopped — the write or the read is already
     * gone — so those stages are demoted to WAITING_FOR_PLAY and the user re-confirms they
     * played, exactly the reference's own resume rule.
     */
    private fun resumeTunerState() {
        viewModelScope.launch(Dispatchers.IO) {
            val saved = WuwaBenchmarkTuner.loadState(tunerStateFile) ?: return@launch
            val resumed =
                if (saved.stage == WuwaBenchmarkTuner.TunerStage.DEPLOYING ||
                    saved.stage == WuwaBenchmarkTuner.TunerStage.CAPTURING
                ) {
                    saved.copy(stage = WuwaBenchmarkTuner.TunerStage.WAITING_FOR_PLAY)
                        .also { WuwaBenchmarkTuner.saveState(it, tunerStateFile) }
                } else {
                    saved
                }
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(tuner = resumed) }
            }
        }
    }

    /**
     * Starts the loop from what the screen already shows: the selected preset and the
     * frame-rate target are the first round's deployment, the same fields the reference's
     * `startTuner` took from its own generator options.
     */
    fun startAutoTune() {
        if (_uiState.value.tunerBusy) return
        val s = _uiState.value
        val state = WuwaBenchmarkTuner.TunerState(
            stage = WuwaBenchmarkTuner.TunerStage.DEPLOYING,
            round = 1,
            preset = s.preset,
            options = s.options,
            targetFps = s.options.fps
        )
        viewModelScope.launch {
            _uiState.update { it.copy(tuner = state, tunerBusy = true) }
            WuwaBenchmarkTuner.saveState(state, tunerStateFile)
            val next = withContext(Dispatchers.IO) { deployTunerRound(state) }
            finishTunerStep(next)
        }
    }

    /**
     * The user says they played a session. Measures the game's log, records the round, and
     * either completes the loop or deploys the next round's preset — deploy, measure, decide,
     * the reference's `captureAndAnalyze` in one place.
     */
    fun onTunerRoundPlayed() {
        val current = _uiState.value.tuner ?: return
        if (_uiState.value.tunerBusy || current.stage != WuwaBenchmarkTuner.TunerStage.WAITING_FOR_PLAY) return
        viewModelScope.launch {
            val capturing = current.copy(stage = WuwaBenchmarkTuner.TunerStage.CAPTURING)
            _uiState.update { it.copy(tuner = capturing, tunerBusy = true) }
            WuwaBenchmarkTuner.saveState(capturing, tunerStateFile)
            val afterCapture = withContext(Dispatchers.IO) { captureTunerRound(capturing) }
            val next =
                if (afterCapture.stage == WuwaBenchmarkTuner.TunerStage.DEPLOYING) {
                    withContext(Dispatchers.IO) { deployTunerRound(afterCapture) }
                } else {
                    afterCapture
                }
            finishTunerStep(next)
        }
    }

    /** Applies the loop's answer: the best preset and the option toggles it measured with. */
    fun applyTunerResult() {
        val t = _uiState.value.tuner ?: return
        val preset = WuwaPresetStore.sanitizePreset(t.finalPreset)
        presetStore.savePreset(preset)
        presetStore.saveOptions(t.options)
        _uiState.update { it.copy(preset = preset, options = t.options) }
        regenerate()
        updateRecommendation()
        viewModelScope.launch {
            _events.emit(WuwaEvent.Toast(context.getString(R.string.gf_tuner_applied, preset), true))
        }
    }

    /** Stops the loop between rounds. The rounds already measured stay in the results list. */
    fun stopTuner() {
        val current = _uiState.value.tuner ?: return
        if (_uiState.value.tunerBusy) return
        val stopped = current.copy(
            stage = WuwaBenchmarkTuner.TunerStage.COMPLETE,
            finalPreset = null,
            error = "Stopped by you after round ${current.round}."
        )
        WuwaBenchmarkTuner.saveState(stopped, tunerStateFile)
        _uiState.update { it.copy(tuner = stopped) }
    }

    /** Clears a finished loop so the card returns to its Start state. */
    fun resetTuner() {
        WuwaBenchmarkTuner.clearState(tunerStateFile)
        _uiState.update { it.copy(tuner = null) }
    }

    /**
     * Deploys one round. The channel is chosen root/Shizuku → SAF, the same preference the
     * manual deploy buttons state: SAF cannot stop the game or sync its config hash monitor,
     * so it is the tuner's last resort and the round report says which one ran. The deploy is
     * recorded in the deploy history like any manual one — the loop's rounds are deploys.
     */
    private suspend fun deployTunerRound(
        state: WuwaBenchmarkTuner.TunerState
    ): WuwaBenchmarkTuner.TunerState = withContext(Dispatchers.IO) {
        fun stopped(detail: String) = state.copy(
            stage = WuwaBenchmarkTuner.TunerStage.COMPLETE,
            error = detail
        )
        val existing =
            if (manager.canDeployViaShell()) manager.readExistingEngineIni() else null
        val configs = WuWaConfigGenerator.generate(
            state.preset,
            state.options,
            deviceFacts(),
            existing
        )
        val result = when {
            manager.canDeployViaShell() -> manager.deployViaShell(configs.asMap())
            manager.hasSafTreeUri() -> manager.deployViaSaf(configs.asMap())
            else -> null
        }
        when (result) {
            null -> stopped(context.getString(R.string.gf_tuner_no_channel))
            is WuwaConfigManager.DeployResult.Failure ->
                stopped(context.getString(R.string.gf_tuner_dep_failed, result.stage, result.detail))
            is WuwaConfigManager.DeployResult.Success -> {
                // NOTE (product requirement): Deploy History is permanently excluded —
                // tuner deploys are not recorded anywhere either.
                state.copy(stage = WuwaBenchmarkTuner.TunerStage.WAITING_FOR_PLAY)
            }
        }
    }

    /**
     * Measures one round from the game's own log — the one channel that knows what FPS the
     * session actually reached. No `AverageFPS` reading in the window is a *stop*, not a
     * measurement of zero: the game only writes those lines while playing, so the honest
     * answer is to say that and let the user try again after really playing.
     */
    private suspend fun captureTunerRound(
        state: WuwaBenchmarkTuner.TunerState
    ): WuwaBenchmarkTuner.TunerState = withContext(Dispatchers.IO) {
        fun stopped(detail: String) = state.copy(
            stage = WuwaBenchmarkTuner.TunerStage.COMPLETE,
            error = detail
        )
        when (val read = manager.readClientLog()) {
            is WuwaConfigManager.ClientLogResult.Failure ->
                stopped(context.getString(R.string.gf_tuner_no_log, read.detail))
            is WuwaConfigManager.ClientLogResult.Read -> {
                val (text, _) = WuwaLogDecryptor.decodeLogBytes(read.bytes)
                val measured = WuwaBenchmarkTuner.parseFpsSamples(text)
                        ?: return@withContext stopped(
                            context.getString(R.string.gf_tuner_no_fps, WuwaBenchmarkTuner.LINE_WINDOW)
                        )
                val results = state.results + WuwaBenchmarkTuner.RoundResult(
                    round = state.round,
                    preset = state.preset,
                    avgFps = measured.avgFps,
                    minFps = measured.minFps,
                    stabilityPct = measured.stabilityPct
                )
                if (measured.avgFps >= state.targetFps || state.round >= WuwaBenchmarkTuner.MAX_ROUNDS) {
                    state.copy(
                        stage = WuwaBenchmarkTuner.TunerStage.COMPLETE,
                        results = results,
                        finalPreset = state.preset
                    )
                } else {
                    state.copy(
                        stage = WuwaBenchmarkTuner.TunerStage.DEPLOYING,
                        round = state.round + 1,
                        preset = WuwaBenchmarkTuner.pickPresetForFps(state.preset, measured.avgFps, state.targetFps),
                        options = WuwaBenchmarkTuner.adjustOptionsForFps(state.options, measured.avgFps, state.targetFps),
                        results = results
                    )
                }
            }
        }
    }

    /** Persists and publishes a finished step, and says what happened in plain words. */
    private suspend fun finishTunerStep(next: WuwaBenchmarkTuner.TunerState) {
        WuwaBenchmarkTuner.saveState(next, tunerStateFile)
        _uiState.update {
            it.copy(
                tuner = next,
                tunerBusy = false,
                backups = manager.backups()
            )
        }
        when (next.stage) {
            WuwaBenchmarkTuner.TunerStage.WAITING_FOR_PLAY ->
                _events.emit(
                    WuwaEvent.Toast(
                        context.getString(R.string.gf_tuner_round_done, next.round),
                        true
                    )
                )
            WuwaBenchmarkTuner.TunerStage.COMPLETE ->
                if (next.error != null) {
                    _events.emit(WuwaEvent.Toast(context.getString(R.string.gf_tuner_stopped_toast, next.error), true))
                }
            // A completed loop is announced by the card itself, next to its results.
            // IDLE / DEPLOYING / CAPTURING need no event.
            else -> Unit
        }
    }
}
