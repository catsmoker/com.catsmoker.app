package com.catsmoker.app.features.editgamefiles.hsr

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.catsmoker.app.R
import com.catsmoker.app.features.editgamefiles.EditHistory
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Screen state for the Honkai: Star Rail graphics editor.
 *
 * [settings] is null until a root read succeeds; the screen renders its failure states from
 * [loadFailure] instead, so "not loaded yet", "cannot load", and "loaded and editable" stay
 * three visibly different things. The settings object held here is the working copy — the
 * device's own values are re-read after every successful apply, so what the screen shows
 * afterwards is what the device holds, not what we asked it to hold.
 */
data class HsrGraphicsUiState(
    val loading: Boolean = true,
    val settings: HsrGraphicsSettings? = null,
    val gamePrefs: HsrGamePreferences? = null,
    val packageName: String? = null,
    val packageLabel: String? = null,
    val prefsPath: String? = null,
    val loadFailure: HsrReadResult.Failure? = null,
    /**
     * "Show anyway" override for a confirmed-missing install. False until the user taps the
     * small secondary button on the GAME NOT FOUND failure card — then the editor renders
     * below the card even though no install was found. Same contract as the File
     * Engineering screen's own override (EditGameFilesViewModel.UiState.showEditorAnyway):
     * the card stays visible, re-probes never clear the choice, and the flag is meaningless
     * once a real read succeeds. Seeding the working copy with defaults (below) is what
     * lets the existing editor render with nothing on the device to read.
     */
    val showEditorAnyway: Boolean = false,
    /**
     * The install probe's own answer, refreshed with every load: true when no install was
     * found, however the read failed. GAME_NOT_INSTALLED says so directly (the manager's
     * verdict already covers the root-only dynamic sweep); NO_ROOT never reaches any stage
     * that would, so the known-variant probe answers there instead — without it a
     * root-less device would never be offered "Show anyway" for a game that is not there.
     * Every other stage proves an install (or a file that answered), so those report
     * present and keep the existing behavior.
     */
    val gameMissing: Boolean = false,
    val applying: Boolean = false,
    val applyingPrefs: Boolean = false,
    val lastPrefsApply: String? = null,
    val hasBackup: Boolean = false,
    /** Undo/redo availability for the graphics working copy. */
    val canUndoSettings: Boolean = false,
    val canRedoSettings: Boolean = false,
    /** True when the graphics working copy differs from what was loaded/applied. */
    val settingsDirty: Boolean = false,
    /** How many graphics fields differ from the baseline (the pending-change count). */
    val pendingSettingsCount: Int = 0,
    /** True when the device's settings moved since the last baseline (game wrote behind us). */
    val settingsExternalChanged: Boolean = false,
    /** Undo/redo availability for the QoL-preferences working copy. */
    val canUndoPrefs: Boolean = false,
    val canRedoPrefs: Boolean = false,
    /** True when the QoL working copy differs from what was loaded/applied. */
    val prefsDirty: Boolean = false,
    /** How many QoL fields differ from the baseline. */
    val pendingPrefsCount: Int = 0,
    /** True when the device's QoL values moved since the last baseline. */
    val prefsExternalChanged: Boolean = false,
    /**
     * True when the loaded file holds the flat format (live HSR ≥4.x: sibling ints,
     * no settings blob). The screen limits itself to the sibling subset then — quality
     * tier + resolution — and applies never invent the blob.
     */
    val flatFormat: Boolean = false
)

@HiltViewModel
class HsrGraphicsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gameManager: HsrGameManager
) : ViewModel() {

    sealed class HsrEvent {
        data class Toast(val message: String, val isLong: Boolean = false) : HsrEvent()
    }

    private val _uiState = MutableStateFlow(HsrGraphicsUiState())
    val uiState: StateFlow<HsrGraphicsUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<HsrEvent>()
    val events: SharedFlow<HsrEvent> = _events.asSharedFlow()

    /**
     * Undo/redo for the two working copies. Histories hold pre-edit snapshots; baselines reset
     * on every load/apply/restore, so "dirty" always means "differs from what the device was
     * last shown to hold" rather than from a stale first load.
     */
    private val settingsHistory = EditHistory<HsrGraphicsSettings>()
    private val prefsHistory = EditHistory<HsrGamePreferences>()

    init {
        refresh()
    }

    /**
     * The GAME NOT FOUND failure card's small secondary "Show anyway" action: reveals the
     * existing editor below the card even though no install was found. With nothing on the
     * device to read, the working copy is seeded with the settings' own defaults (the same
     * values the screen preview renders) and baselined onto them, so the editor reads clean
     * rather than dirty and every slider stays adjustable. Applies attempted from there go
     * through the manager like any other apply and report the missing install honestly —
     * the failure card stays visible above, so the screen never pretends a game is there.
     * One-way: stays set until the ViewModel is cleared; a later successful read simply
     * replaces the seeded copy with what the device holds.
     */
    fun onShowEditorAnyway() {
        val state = _uiState.value
        if (state.gameMissing && state.settings == null) {
            settingsHistory.setBaseline(HsrGraphicsSettings())
            prefsHistory.setBaseline(HsrGamePreferences())
            _uiState.update {
                it.copy(
                    settings = HsrGraphicsSettings(),
                    gamePrefs = HsrGamePreferences(),
                    showEditorAnyway = true
                )
            }
        } else {
            _uiState.update { it.copy(showEditorAnyway = true) }
        }
    }

    /** Full reload — clears the manager's package/path caches so a fresh install is found. */
    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, loadFailure = null) }
            gameManager.resetCaches()
            when (val result = gameManager.readCurrentSettings()) {
                is HsrReadResult.Success -> {
                    // External-change probe BEFORE re-baselining: compare what the device holds
                    // now against what the editor was last shown. First load has no baseline,
                    // so it never reports a change — there is nothing to differ from yet.
                    val settingsMoved = settingsHistory.differsFromBaseline(result.settings)
                    val prefsMoved = prefsHistory.differsFromBaseline(result.gamePrefs)
                    settingsHistory.setBaseline(result.settings)
                    prefsHistory.setBaseline(result.gamePrefs)
                    _uiState.update {
                        it.copy(
                            loading = false,
                            settings = result.settings,
                            gamePrefs = result.gamePrefs,
                            packageName = result.packageName,
                            packageLabel = result.packageLabel,
                            prefsPath = result.prefsPath,
                            hasBackup = gameManager.hasBackup(),
                            canUndoSettings = false,
                            canRedoSettings = false,
                            settingsDirty = false,
                            pendingSettingsCount = 0,
                            settingsExternalChanged = settingsMoved,
                            canUndoPrefs = false,
                            canRedoPrefs = false,
                            prefsDirty = false,
                            pendingPrefsCount = 0,
                            prefsExternalChanged = prefsMoved,
                            // A successful read is the device proving the game is there — a
                            // stale missing flag must not hide the status card afterwards.
                            gameMissing = false,
                            flatFormat = result.flatFormat
                        )
                    }
                }
                is HsrReadResult.Failure -> _uiState.update { state ->
                    // A "Show anyway" tap that landed while a reload was in flight leaves the
                    // flag set with no working copy yet — seed here too, so the retry that
                    // confirms "still missing" still reveals the editor instead of nothing.
                    val missing = result.stage == HsrReadResult.Stage.GAME_NOT_INSTALLED ||
                        (result.stage == HsrReadResult.Stage.NO_ROOT &&
                            !gameManager.isAnyKnownVariantInstalled())
                    val seed = state.settings == null && missing && state.showEditorAnyway
                    if (seed) {
                        settingsHistory.setBaseline(HsrGraphicsSettings())
                        prefsHistory.setBaseline(HsrGamePreferences())
                    }
                    state.copy(
                        loading = false,
                        loadFailure = result,
                        settings = if (seed) HsrGraphicsSettings() else state.settings,
                        gamePrefs = if (seed) HsrGamePreferences() else state.gamePrefs,
                        gameMissing = missing,
                        hasBackup = gameManager.hasBackup()
                    )
                }
            }
        }
    }

    /**
     * Recomputes the pending-change counts after any working-copy transition. Counts are
     * derived, never stored, so they cannot disagree with what the screen shows.
     */
    private fun HsrGraphicsUiState.withSessionFlags(): HsrGraphicsUiState {
        val s = settings
        val p = gamePrefs
        return copy(
            pendingSettingsCount = if (s != null) {
                settingsHistory.baselineOrNull()?.let { pendingGraphicsFields(it, s).size } ?: 0
            } else 0,
            pendingPrefsCount = if (p != null) {
                prefsHistory.baselineOrNull()?.let { pendingPrefsFields(it, p).size } ?: 0
            } else 0
        )
    }

    /**
     * Applies one of the reference's five one-tap tiers as a starting point — one undo step,
     * every slider stays adjustable after. See [HsrGraphicsSettings.withGraphicsPreset].
     */
    fun applyGraphicsPreset(level: Int) {
        updateSettings { it.withGraphicsPreset(level) }
    }

    fun updateSettings(transform: (HsrGraphicsSettings) -> HsrGraphicsSettings) {
        _uiState.update { state ->
            state.settings?.let {
                // Pre-edit snapshot: a slider drag records each tick (capped at 50), which is
                // what makes every step of the drag individually undoable.
                settingsHistory.push(it)
                val next = transform(it)
                state.copy(
                    settings = next,
                    canUndoSettings = settingsHistory.canUndo,
                    canRedoSettings = settingsHistory.canRedo,
                    settingsDirty = settingsHistory.isDirty(next)
                ).withSessionFlags()
            } ?: state
        }
    }

    fun updatePrefs(transform: (HsrGamePreferences) -> HsrGamePreferences) {
        _uiState.update { state ->
            state.gamePrefs?.let {
                prefsHistory.push(it)
                val next = transform(it)
                state.copy(
                    gamePrefs = next,
                    canUndoPrefs = prefsHistory.canUndo,
                    canRedoPrefs = prefsHistory.canRedo,
                    prefsDirty = prefsHistory.isDirty(next)
                ).withSessionFlags()
            } ?: state
        }
    }

    /** Undoes one graphics edit, or does nothing when there is nothing to undo. */
    fun undoSettings() {
        _uiState.update { state ->
            val current = state.settings ?: return@update state
            val restored = settingsHistory.undo(current) ?: return@update state
            state.copy(
                settings = restored,
                canUndoSettings = settingsHistory.canUndo,
                canRedoSettings = settingsHistory.canRedo,
                settingsDirty = settingsHistory.isDirty(restored)
            ).withSessionFlags()
        }
    }

    /** Redoes one undone graphics edit, or does nothing when there is nothing to redo. */
    fun redoSettings() {
        _uiState.update { state ->
            val current = state.settings ?: return@update state
            val restored = settingsHistory.redo(current) ?: return@update state
            state.copy(
                settings = restored,
                canUndoSettings = settingsHistory.canUndo,
                canRedoSettings = settingsHistory.canRedo,
                settingsDirty = settingsHistory.isDirty(restored)
            ).withSessionFlags()
        }
    }

    /** Undoes one QoL-preference edit, or does nothing when there is nothing to undo. */
    fun undoPrefs() {
        _uiState.update { state ->
            val current = state.gamePrefs ?: return@update state
            val restored = prefsHistory.undo(current) ?: return@update state
            state.copy(
                gamePrefs = restored,
                canUndoPrefs = prefsHistory.canUndo,
                canRedoPrefs = prefsHistory.canRedo,
                prefsDirty = prefsHistory.isDirty(restored)
            ).withSessionFlags()
        }
    }

    /** Redoes one undone QoL-preference edit, or does nothing when there is nothing to redo. */
    fun redoPrefs() {
        _uiState.update { state ->
            val current = state.gamePrefs ?: return@update state
            val restored = prefsHistory.redo(current) ?: return@update state
            state.copy(
                gamePrefs = restored,
                canUndoPrefs = prefsHistory.canUndo,
                canRedoPrefs = prefsHistory.canRedo,
                prefsDirty = prefsHistory.isDirty(restored)
            ).withSessionFlags()
        }
    }

    /** Same flow as [onApply], against the QoL keys. Speed-up needs a logged-in user id. */
    fun onApplyPrefs() {
        val prefs = _uiState.value.gamePrefs ?: return
        if (_uiState.value.applyingPrefs) return
        viewModelScope.launch {
            _uiState.update { it.copy(applyingPrefs = true, lastPrefsApply = null) }

            val gameStopped = gameManager.forceStopGame()
            when (val result = gameManager.writeGamePreferences(prefs)) {
                is HsrWriteResult.Success -> {
                    val stopLine = if (gameStopped) context.getString(R.string.gf_hsr_stopped_first) else ""
                    val verdict = if (result.verified) {
                        context.getString(R.string.gf_hsr_verified)
                    } else {
                        context.getString(R.string.gf_hsr_not_verified, result.verificationDetail)
                    }
                    val backupLine = result.backup?.let { context.getString(R.string.gf_hsr_backup_taken, it.formattedTimestamp()) } ?: context.getString(R.string.gf_hsr_no_backup)
                    _uiState.update { it.copy(applyingPrefs = false, lastPrefsApply = "${stopLine}$backupLine, $verdict") }
                    _events.emit(HsrEvent.Toast(context.getString(if (result.verified) R.string.gf_hsr_prefs_verified else R.string.gf_hsr_readback_off), true))

                    (gameManager.readCurrentSettings() as? HsrReadResult.Success)?.let { fresh ->
                        // The device is the truth now: re-baseline so the applied values read
                        // clean rather than dirty against the pre-apply load.
                        prefsHistory.setBaseline(fresh.gamePrefs)
                        _uiState.update {
                            it.copy(
                                gamePrefs = fresh.gamePrefs,
                                hasBackup = gameManager.hasBackup(),
                                canUndoPrefs = false,
                                canRedoPrefs = false,
                                prefsDirty = false,
                                pendingPrefsCount = 0,
                                prefsExternalChanged = false
                            )
                        }
                    }
                }
                is HsrWriteResult.Failure -> {
                    _uiState.update {
                        it.copy(applyingPrefs = false, lastPrefsApply = context.getString(R.string.gf_failed_stage_detail, result.stage, result.detail))
                    }
                    _events.emit(HsrEvent.Toast(context.getString(R.string.gf_apply_failed_detail, result.detail), true))
                }
            }
        }
    }

    /**
     * Stop the game (it would clobber the file with in-memory settings on exit), push the
     * working copy, then re-read the device. The report names every step that actually happened.
     * Flat-format files go through the sibling-only write, which never invents the blob.
     */
    fun onApply() {
        val settings = _uiState.value.settings ?: return
        if (_uiState.value.applying) return
        val flat = _uiState.value.flatFormat
        viewModelScope.launch {
            _uiState.update { it.copy(applying = true) }

            gameManager.forceStopGame()
            val result = if (flat) gameManager.writeFlatSettings(settings) else gameManager.writeSettings(settings)
            when (result) {
                is HsrWriteResult.Success -> {
                    _uiState.update { it.copy(applying = false) }
                    _events.emit(HsrEvent.Toast(context.getString(if (result.verified) R.string.gf_hsr_gfx_verified else R.string.gf_hsr_readback_off), true))

                    // Show what the device now holds, not what we asked for — and re-baseline
                    // both working copies onto it, so applied values read clean.
                    (gameManager.readCurrentSettings() as? HsrReadResult.Success)?.let { fresh ->
                        settingsHistory.setBaseline(fresh.settings)
                        prefsHistory.setBaseline(fresh.gamePrefs)
                        _uiState.update {
                            it.copy(
                                settings = fresh.settings,
                                gamePrefs = fresh.gamePrefs,
                                hasBackup = gameManager.hasBackup(),
                                canUndoSettings = false,
                                canRedoSettings = false,
                                settingsDirty = false,
                                pendingSettingsCount = 0,
                                settingsExternalChanged = false,
                                canUndoPrefs = false,
                                canRedoPrefs = false,
                                prefsDirty = false,
                                pendingPrefsCount = 0,
                                prefsExternalChanged = false
                            )
                        }
                    }
                }
                is HsrWriteResult.Failure -> {
                    _uiState.update { it.copy(applying = false) }
                    _events.emit(HsrEvent.Toast(context.getString(R.string.gf_apply_failed_detail, result.detail), true))
                }
            }
        }
    }

    fun onRestoreBackup() {
        viewModelScope.launch {
            when (val result = gameManager.restoreLatestBackup()) {
                null -> _events.emit(HsrEvent.Toast(context.getString(R.string.gf_no_backup_yet), false))
                is HsrWriteResult.Success -> {
                    _events.emit(HsrEvent.Toast(context.getString(R.string.gf_backup_restored), false))
                    (gameManager.readCurrentSettings() as? HsrReadResult.Success)?.let { fresh ->
                        settingsHistory.setBaseline(fresh.settings)
                        prefsHistory.setBaseline(fresh.gamePrefs)
                        _uiState.update {
                            it.copy(
                                settings = fresh.settings,
                                gamePrefs = fresh.gamePrefs,
                                hasBackup = gameManager.hasBackup(),
                                canUndoSettings = false,
                                canRedoSettings = false,
                                settingsDirty = false,
                                pendingSettingsCount = 0,
                                settingsExternalChanged = false,
                                canUndoPrefs = false,
                                canRedoPrefs = false,
                                prefsDirty = false,
                                pendingPrefsCount = 0,
                                prefsExternalChanged = false
                            )
                        }
                    }
                }
                is HsrWriteResult.Failure -> {
                    _events.emit(HsrEvent.Toast(context.getString(R.string.gf_restore_failed_detail, result.detail), true))
                }
            }
        }
    }
}
