package com.catsmoker.app.features.editgamefiles.grid

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.catsmoker.app.R
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
 * Screen state for the GRID Autosport graphics editor. Mirrors [com.catsmoker.app.features.editgamefiles.hsr.HsrGraphicsUiState]'s
 * shape: null settings until a read succeeds, with load failures rendered as their own card
 * so "not loaded yet", "cannot load", and "loaded and editable" stay visibly different.
 */
data class GridUiState(
    val loading: Boolean = true,
    val read: GridPreferences.ReadResult? = null,
    /** The working edits — the screen mutates this, [edits] are what an apply pushes. */
    val edits: GridPreferences.Edits = GridPreferences.Edits(),
    /** Canonical ladder/switch names the file does not carry yet — their controls are disabled. */
    val missingKeys: Set<String> = emptySet(),
    val channelUsed: String? = null,
    val gameVersion: String? = null,
    val loadFailure: GridPreferencesManager.ReadResult.Failure? = null,
    val applying: Boolean = false,
    /** What the last apply actually did — success carries the read-back verdict, failure the stage. */
    val lastApply: String? = null,
    val hasBackup: Boolean = false,
    val canUseShell: Boolean = false,
    val hasSafGrant: Boolean = false,
    /**
     * "Show anyway" override for a confirmed-missing install. False until the user taps the
     * small secondary button on the GAME NOT FOUND failure card — then the editor renders
     * below the card even though no install was found. Same contract as the File
     * Engineering screen's own override (EditGameFilesViewModel.UiState.showEditorAnyway):
     * the card stays visible, re-probes never clear the choice, and the flag is meaningless
     * once a real read succeeds. Seeding an empty read (below) is what lets the existing
     * editor render with nothing on the device to read: every key reads absent, so the
     * controls grey out exactly as they do for an installed game whose file lacks them.
     */
    val showEditorAnyway: Boolean = false,
    /**
     * The install probe's own answer, refreshed with every load: true when the game's
     * package is absent, however the read failed. GAME_NOT_INSTALLED says so directly;
     * NO_CHANNEL cannot reach any stage that would, so the probe answers there instead —
     * without it a channel-less device would never be offered "Show anyway" for a game
     * that is not there. FILE_NOT_FOUND and READ_FAILED imply an install (or a file that
     * answered), so they report present and keep the existing behavior.
     */
    val gameMissing: Boolean = false
)

@HiltViewModel
class GridViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val manager: GridPreferencesManager
) : ViewModel() {

    sealed class GridEvent {
        data class Toast(val message: String, val isLong: Boolean = false) : GridEvent()
        data object LaunchFolderPicker : GridEvent()
    }

    private val _uiState = MutableStateFlow(GridUiState())
    val uiState: StateFlow<GridUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<GridEvent>()
    val events: SharedFlow<GridEvent> = _events.asSharedFlow()

    init {
        refresh()
    }

    /**
     * The GAME NOT FOUND failure card's small secondary "Show anyway" action: reveals the
     * existing editor below the card even though no install was found. With nothing on the
     * device to read, the read is seeded empty (every key absent), so the editor renders
     * exactly as it does for an installed game whose file lacks those keys — rows greyed,
     * captions honest — and applies attempted from there report the missing install through
     * the manager like any other failure. The failure card stays visible above, so the
     * screen never pretends a game is there. One-way: stays set until the ViewModel is
     * cleared; a later successful read simply replaces the seeded copy.
     */
    fun onShowEditorAnyway() {
        val state = _uiState.value
        if (state.gameMissing && state.read == null) {
            val seed = emptyShowAnywayRead()
            _uiState.update {
                it.copy(
                    read = seed,
                    edits = GridPreferences.Edits(),
                    missingKeys = missingKeys(seed),
                    showEditorAnyway = true
                )
            }
        } else {
            _uiState.update { it.copy(showEditorAnyway = true) }
        }
    }

    /**
     * The empty read a "Show anyway" reveal starts from: nothing held, every key absent.
     * Never a fabricated device value — nulls are exactly what the screen already renders
     * as greyed rows and "not in the file yet" captions for an installed game whose file
     * lacks them.
     */
    private fun emptyShowAnywayRead(): GridPreferences.ReadResult = GridPreferences.ReadResult(
        isFeralRegistry = false,
        gameVersion = null,
        screenWidth = null,
        screenHeight = null,
        fixedScreenHeight = null,
        maxFps = null,
        highMaxFps = null,
        fxaa = null,
        windowScale = null,
        ladder = GridPreferences.LADDER_KEYS.associateWith { null },
        anisotropic = null,
        switches = GridPreferences.SWITCH_TARGETS.keys.associateWith { null }
    )

    /** Full reload — re-reads the file through the best channel and re-derives the gates. */
    fun refresh() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    loading = true,
                    loadFailure = null,
                    canUseShell = manager.canUseShell(),
                    hasSafGrant = manager.hasSafGrant()
                )
            }
            when (val result = manager.readCurrent()) {
                is GridPreferencesManager.ReadResult.Success -> _uiState.update {
                    it.copy(
                        loading = false,
                        read = result.read,
                        edits = GridPreferences.Edits(),
                        missingKeys = missingKeys(result.read),
                        channelUsed = result.channelUsed,
                        gameVersion = result.read.gameVersion,
                        hasBackup = manager.hasBackup(),
                        // A successful read is the device proving the game is there — a
                        // stale missing flag must not hide the status card afterwards.
                        gameMissing = false
                    )
                }
                is GridPreferencesManager.ReadResult.Failure -> _uiState.update { state ->
                    // A "Show anyway" tap that landed while a reload was in flight leaves the
                    // flag set with no read yet — seed here too, so the retry that confirms
                    // "still missing" still reveals the editor instead of nothing. Any other
                    // stage keeps the previous behavior (no read, no seed).
                    val missing = result.stage == GridPreferencesManager.ReadResult.Stage.GAME_NOT_INSTALLED ||
                        (result.stage == GridPreferencesManager.ReadResult.Stage.NO_CHANNEL &&
                            !manager.isInstalled())
                    val seed = if (missing && state.showEditorAnyway) {
                        state.read ?: emptyShowAnywayRead()
                    } else {
                        null
                    }
                    state.copy(
                        loading = false,
                        loadFailure = result,
                        read = seed,
                        gameMissing = missing,
                        missingKeys = if (seed != null) missingKeys(seed) else state.missingKeys,
                        hasBackup = manager.hasBackup()
                    )
                }
            }
        }
    }

    /** Every key the file does not carry yet, spelled the way the UI's controls name them. */
    private fun missingKeys(read: GridPreferences.ReadResult): Set<String> = buildSet {
        if (read.screenWidth == null || read.screenHeight == null || read.fixedScreenHeight == null) {
            add("resolution")
        }
        if (read.maxFps == null || read.highMaxFps == null) add("fps")
        if (read.fxaa == null) add("fxaa")
        if (read.windowScale == null) add("window_scale")
        read.ladder.forEach { (key, value) -> if (value == null) add(key) }
        if (read.anisotropic == null) add(GridPreferences.ANISOTROPIC_KEY)
        read.switches.forEach { (key, value) -> if (value == null) add(key) }
    }

    // ── working-copy mutations ───────────────────────────────────────────────────────

    fun setResolution(height: Int) = _uiState.update {
        it.copy(edits = it.edits.copy(screenHeight = height, screenWidth = null))
    }

    fun setFps(fps: Int) = _uiState.update { it.copy(edits = it.edits.copy(fps = fps)) }

    fun setFxaa(on: Boolean) = _uiState.update { it.copy(edits = it.edits.copy(fxaa = on)) }

    fun setWindowScale(percent: Int) = _uiState.update {
        it.copy(edits = it.edits.copy(windowScale = percent.coerceIn(10, 100)))
    }

    fun setLadder(name: String, tier: String) = _uiState.update {
        it.copy(edits = it.edits.copy(ladder = it.edits.ladder + (name to tier)))
    }

    fun setAnisotropic(tier: String) = _uiState.update {
        it.copy(edits = it.edits.copy(anisotropic = tier))
    }

    fun setSwitch(name: String, on: Boolean) = _uiState.update {
        it.copy(edits = it.edits.copy(switches = it.edits.switches + (name to on)))
    }

    // ── apply / restore ──────────────────────────────────────────────────────────────

    fun onApply() {
        val edits = _uiState.value.edits
        if (_uiState.value.applying) return
        if (edits == GridPreferences.Edits()) {
            viewModelScope.launch { _events.emit(GridEvent.Toast(context.getString(R.string.gf_grid_nothing_changed), false)) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(applying = true, lastApply = null) }
            when (val result = manager.applyEdits(edits)) {
                is GridPreferencesManager.WriteResult.Success -> {
                    val report = buildString {
                        append(context.getString(R.string.gf_grid_applied_via, result.channelUsed))
                        if (result.gameStopped == true) append(context.getString(R.string.gf_grid_stopped_first))
                        if (result.gameStopped == null) append(context.getString(R.string.gf_grid_not_stopped))
                        append(result.backup?.let { context.getString(R.string.gf_grid_backup_taken, it.formattedTimestamp()) } ?: context.getString(R.string.gf_grid_no_backup))
                        append(context.getString(if (result.verified) R.string.gf_grid_verified else R.string.gf_grid_not_verified))
                        if (result.refused.isNotEmpty()) append(context.getString(R.string.gf_grid_refused, result.refused.joinToString("; ")))
                    }
                    _uiState.update { it.copy(applying = false, lastApply = report) }
                    _events.emit(
                        GridEvent.Toast(
                            context.getString(if (result.verified) R.string.gf_grid_applied_ok else R.string.gf_grid_applied_unverified),
                            true
                        )
                    )
                    refresh()
                }
                is GridPreferencesManager.WriteResult.Failure -> {
                    _uiState.update {
                        it.copy(applying = false, lastApply = context.getString(R.string.gf_failed_stage_detail, result.stage, result.detail))
                    }
                    _events.emit(GridEvent.Toast(context.getString(R.string.gf_apply_failed_detail, result.detail), true))
                }
            }
        }
    }

    fun onRestoreBackup() {
        viewModelScope.launch {
            when (val result = manager.restoreLatestBackup()) {
                null -> _events.emit(GridEvent.Toast(context.getString(R.string.gf_no_backup_yet), false))
                is GridPreferencesManager.WriteResult.Success -> {
                    _uiState.update { it.copy(lastApply = context.getString(R.string.gf_grid_restored, result.channelUsed, result.verified.toString())) }
                    _events.emit(GridEvent.Toast(context.getString(R.string.gf_backup_restored), false))
                    refresh()
                }
                is GridPreferencesManager.WriteResult.Failure -> {
                    _uiState.update { it.copy(lastApply = context.getString(R.string.gf_restore_failed_stage, result.stage, result.detail)) }
                    _events.emit(GridEvent.Toast(context.getString(R.string.gf_restore_failed_detail, result.detail), true))
                }
            }
        }
    }

    /** The SAF path needs a one-time pick of the game's Android/data root. */
    fun onSafFolderPicked(uri: android.net.Uri) {
        val kept = manager.onSafFolderPicked(uri)
        if (!kept) {
            viewModelScope.launch {
                _events.emit(GridEvent.Toast(context.getString(R.string.gf_grid_folder_grant_lost), true))
            }
        }
        refresh()
    }

    fun requestFolderPicker() {
        viewModelScope.launch { _events.emit(GridEvent.LaunchFolderPicker) }
    }
}
