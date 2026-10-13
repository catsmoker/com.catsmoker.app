package com.catsmoker.app.features.settings

import android.app.Activity
import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.catsmoker.app.BuildConfig
import com.catsmoker.app.R
import com.catsmoker.app.system.VariantCapabilities
import com.catsmoker.app.system.ads.AdManager
import com.catsmoker.app.system.ads.RemoveAdsRepository
import com.catsmoker.app.system.config.AppearanceStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val adManager: AdManager,
    private val removeAdsRepository: RemoveAdsRepository,
) : ViewModel() {

    data class UiState(
        val adsEnabled: Boolean = true,
        /** Play variant only: true while the verified remove_ads purchase is on record. */
        val isAdFree: Boolean = false,
        /** Play variant only: localized Play price of remove_ads, null until Play answers. */
        val removeAdsPrice: String? = null,
        /** Play variant only: true while a Play Billing query is in flight. */
        val isBillingWorking: Boolean = false,
        val autoCheck: Boolean = false,
        val isPreRelease: Boolean = false,
        val isUpdating: Boolean = false,
        val updateProgress: Float = 0f,
        val updateDialog: UpdateDialog? = null,
        val themeMode: AppearanceStore.ThemeMode = AppearanceStore.ThemeMode.SYSTEM,
        /** BCP-47 tag, or "" for system default. Endonyms stay native — never localized. */
        val languageTag: String = AppearanceStore.LANGUAGE_SYSTEM,
    )

    data class UpdateDialog(
        val tagName: String,
        val downloadUrl: String?,
        val notes: String? = null,
        val sha256Hex: String? = null
    )

    /** One-shot UI actions the screen itself must perform (process restart for locale). */
    sealed interface UiEvent {
        data object RestartApp : UiEvent
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toasts: SharedFlow<String> = _toasts.asSharedFlow()

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    private val prefs by lazy { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }

    private var downloadJob: kotlinx.coroutines.Job? = null

    companion object {
        /**
         * Automatic checks run at most once per process: reopening Settings
         * must not re-hit the API. Manual checks always run. Play never
         * auto-checks — its section stays hidden and the stub answers static.
         */
        private var autoCheckedThisProcess = false

        private const val PREF_AUTO_CHECK = "auto_check_update"
        private const val PREF_DISMISSED_TAG = "update_dismissed_tag"
        private const val PREF_PENDING_TAG = "update_pending_tag"
    }

    init {
        val adsOn = adManager.isEnabled()
        // Full build checks for GitHub releases by default; Play keeps its
        // store-owned policy (section hidden, stub static) whatever this reads.
        val autoOn = prefs.getBoolean(PREF_AUTO_CHECK, VariantCapabilities.HAS_SELF_UPDATE)
        _uiState.update {
            it.copy(
                adsEnabled = adsOn,
                // Full variant: no purchase exists, never ad-free via billing.
                isAdFree = !VariantCapabilities.HAS_FREE_ADS_TOGGLE && !adsOn,
                autoCheck = autoOn,
                isPreRelease = prefs.getBoolean("use_prerelease", false),
                themeMode = AppearanceStore.themeModeSync(context),
                languageTag = AppearanceStore.languageTag(context)
            )
        }
        checkPendingUpdateResult()
        maybeAutoCheck(autoOn)
        if (!VariantCapabilities.HAS_FREE_ADS_TOGGLE) {
            // Play variant: the ad state follows only the verified purchase,
            // which arrives asynchronously from Play Billing — stay subscribed
            // so a purchase (or revocation) updates Settings without reopening it.
            viewModelScope.launch {
                removeAdsRepository.isAdFree.collect { adFree ->
                    _uiState.update { it.copy(isAdFree = adFree, adsEnabled = !adFree) }
                }
            }
            viewModelScope.launch {
                removeAdsRepository.displayPrice.collect { price ->
                    _uiState.update { it.copy(removeAdsPrice = price) }
                }
            }
            viewModelScope.launch {
                removeAdsRepository.isWorking.collect { working ->
                    _uiState.update { it.copy(isBillingWorking = working) }
                }
            }
            viewModelScope.launch {
                removeAdsRepository.messages.collect { message ->
                    _toasts.emit(message)
                }
            }
            refreshPurchases()
        }
    }

    fun onThemeModeChanged(mode: AppearanceStore.ThemeMode) {
        // Live-recomposes via AppearanceStore.themeMode — no activity restart needed.
        AppearanceStore.setThemeMode(context, mode)
        _uiState.update { it.copy(themeMode = mode) }
    }

    fun onLanguageChanged(tag: String) {
        if (tag == _uiState.value.languageTag) return
        AppearanceStore.setLanguage(context, tag)
        _uiState.update { it.copy(languageTag = tag) }
        // The application context — and every cached getString in the surviving ViewModels,
        // engine state and running services — resolves the language once, at process start.
        // A bare recreate() would leave all of those on the previous language (English-selected
        // UI showing Arabic), so the process restarts and everything re-resolves fresh.
        _events.tryEmit(UiEvent.RestartApp)
    }

    fun onAdsToggled(enabled: Boolean) {
        // The Play variant has no free toggle: ignore so that path can never
        // reach the ad gate there (its AdManager ignores setEnabled anyway).
        if (!VariantCapabilities.HAS_FREE_ADS_TOGGLE) return
        adManager.setEnabled(enabled)
        _uiState.update { it.copy(adsEnabled = enabled) }
    }

    /** Play variant only: opens the Google Play sheet for the remove_ads product. */
    fun onRemoveAdsClicked(activity: Activity) {
        if (VariantCapabilities.HAS_FREE_ADS_TOGGLE) return
        removeAdsRepository.launchPurchase(activity)
    }

    /**
     * Play variant only: re-checks the remove_ads entitlement with Play (also
     * restores it after reinstall). No-op on the full variant.
     */
    fun refreshPurchases() {
        if (VariantCapabilities.HAS_FREE_ADS_TOGGLE) return
        removeAdsRepository.refresh()
    }

    fun onAutoCheckToggled(enabled: Boolean) {
        prefs.edit { putBoolean(PREF_AUTO_CHECK, enabled) }
        _uiState.update { it.copy(autoCheck = enabled) }
    }

    /**
     * Silent automatic check, once per process: shows the dialog only for a
     * genuinely new release the user has not dismissed, and never toasts.
     * Manual checks always report. Turning the toggle off stops this path
     * entirely — nothing re-enables it.
     */
    private fun maybeAutoCheck(autoOn: Boolean) {
        if (!SelfUpdater.SUPPORTED || !autoOn || autoCheckedThisProcess) return
        autoCheckedThisProcess = true
        performUpdateCheck(_uiState.value.isPreRelease, silent = true)
    }

    /**
     * One-shot verdict on the installer handoff: if the version now installed
     * satisfies the pending tag, the update landed — say so once and clear the
     * marker. Otherwise clear it silently (cancelled/failed installs leave no
     * claim behind, and must not nag on every open).
     */
    private fun checkPendingUpdateResult() {
        val pending = prefs.getString(PREF_PENDING_TAG, null) ?: return
        prefs.edit { remove(PREF_PENDING_TAG) }
        if (!SelfUpdater.SUPPORTED) return
        if (SelfUpdater.isTagInstalled(pending, BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME)) {
            _toasts.tryEmit(context.getString(R.string.sys_update_installed, pending))
        }
    }

    fun onCheckUpdates() {
        // Play Store owns updates on that variant — the section is hidden there,
        // but guard the entry point anyway so a stale call can never self-update.
        if (!SelfUpdater.SUPPORTED) return
        _toasts.tryEmit(context.getString(R.string.sys_checking_updates))
        performUpdateCheck(_uiState.value.isPreRelease, silent = false)
    }

    /**
     * Records the dismissed tag so neither recomposition, activity recreation
     * nor the next silent auto-check re-prompts for the same release. Only a
     * strictly newer tag prompts again.
     */
    fun dismissUpdateDialog() {
        _uiState.value.updateDialog?.let { prefs.edit { putString(PREF_DISMISSED_TAG, it.tagName) } }
        _uiState.update { it.copy(updateDialog = null) }
    }

    fun startUpdateDownload() {
        if (!SelfUpdater.SUPPORTED) return
        val dialog = _uiState.value.updateDialog ?: return
        val url = dialog.downloadUrl
        _uiState.update { it.copy(updateDialog = null) }
        if (url == null) {
            _toasts.tryEmit(context.getString(R.string.sys_no_suitable_release))
            return
        }
        prefs.edit { putString(PREF_PENDING_TAG, dialog.tagName) }
        startUpdateDownload(url, dialog.sha256Hex)
    }

    /** Cancels an in-flight download; the pending marker stays until a verdict. */
    fun cancelUpdateDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _uiState.update { it.copy(isUpdating = false) }
        _toasts.tryEmit(context.getString(R.string.sys_update_cancelled))
    }

    private fun performUpdateCheck(isPreRelease: Boolean, silent: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = try {
                SelfUpdater.checkForUpdate(
                    isPreRelease, BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME
                )
            } catch (e: Exception) {
                SelfUpdater.CheckResult.Failed(e.message ?: "?")
            }
            withContext(Dispatchers.Main) {
                when (result) {
                    is SelfUpdater.CheckResult.NoReleases -> {
                        if (!silent) _toasts.tryEmit(context.getString(R.string.sys_no_releases))
                    }
                    is SelfUpdater.CheckResult.UpToDate -> {
                        if (!silent) {
                            _toasts.tryEmit(
                                context.getString(R.string.sys_up_to_date, BuildConfig.VERSION_NAME)
                            )
                        }
                    }
                    is SelfUpdater.CheckResult.Available -> {
                        val dismissed = prefs.getString(PREF_DISMISSED_TAG, null)
                        if (dismissed != result.info.tagName) {
                            _uiState.update {
                                it.copy(
                                    updateDialog = UpdateDialog(
                                        result.info.tagName,
                                        result.info.downloadUrl,
                                        result.info.notes,
                                        result.info.sha256Hex
                                    )
                                )
                            }
                        }
                    }
                    is SelfUpdater.CheckResult.Failed -> {
                        if (!silent) {
                            _toasts.tryEmit(
                                context.getString(R.string.sys_update_check_failed, result.message)
                            )
                        }
                    }
                }
            }
        }
    }

    private fun startUpdateDownload(url: String, expectedSha256: String?) {
        if (!SelfUpdater.SUPPORTED) return
        downloadJob?.cancel()
        _uiState.update { it.copy(isUpdating = true, updateProgress = 0f) }
        downloadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = SelfUpdater.downloadUpdate(context, url) { progress ->
                    _uiState.update { it.copy(updateProgress = progress) }
                }
                if (file == null) {
                    withContext(Dispatchers.Main) {
                        _uiState.update { it.copy(isUpdating = false) }
                        _toasts.tryEmit(context.getString(R.string.sys_no_suitable_release))
                    }
                    return@launch
                }
                // Verify before the installer ever sees the file: checksum when
                // the release published one, then package id + signing cert.
                // Anything unverifiable refuses with a reason — never installs.
                val digestOk = expectedSha256 == null ||
                    SelfUpdater.sha256HexOf(file)?.equals(expectedSha256, ignoreCase = true) == true
                if (!digestOk) {
                    file.delete()
                    withContext(Dispatchers.Main) {
                        _uiState.update { it.copy(isUpdating = false) }
                        _toasts.tryEmit(context.getString(R.string.sys_update_rejected, "checksum"))
                    }
                    return@launch
                }
                if (!SelfUpdater.isCompatibleApk(context, file)) {
                    file.delete()
                    withContext(Dispatchers.Main) {
                        _uiState.update { it.copy(isUpdating = false) }
                        _toasts.tryEmit(context.getString(R.string.sys_update_rejected, "signature"))
                    }
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(isUpdating = false) }
                    SelfUpdater.installUpdate(context, file)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                withContext(NonCancellable + Dispatchers.Main) {
                    _uiState.update { it.copy(isUpdating = false) }
                }
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(isUpdating = false) }
                    _toasts.tryEmit(context.getString(R.string.sys_download_failed, e.message ?: "?"))
                }
            }
        }
    }
}
