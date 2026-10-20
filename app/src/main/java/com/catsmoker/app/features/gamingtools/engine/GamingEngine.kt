package com.catsmoker.app.features.gamingtools.engine

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.core.content.edit
import com.catsmoker.app.R
import com.catsmoker.app.shared.data.model.GamingOptimizationSnapshot
import com.catsmoker.app.shared.data.model.SettingValue
import com.catsmoker.app.features.gamingtools.engine.parsers.DexoptStatusParser
import com.catsmoker.app.features.gamingtools.tools.firewall.BackgroundDataRestrictor
import com.catsmoker.app.features.gamingtools.tools.forcestop.SuspendExemptStore
import com.catsmoker.app.features.gamingtools.tools.forcestop.SuspendListStore
import com.catsmoker.app.features.gamingtools.tools.interventions.GameInterventions
import com.catsmoker.app.system.shell.ShellRunner
import com.catsmoker.app.shared.util.DeviceCapabilities
import com.catsmoker.app.shared.util.isVivoOrIqoo
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * One entry of the Gaming Mode refusal note, in a form that survives a language switch.
 *
 * The engine (and the ViewModel holding its report) lives on the application context, whose
 * locale is frozen at process start, and both survive the activity `recreate()` a language
 * change performs — a `context.getString` resolved here would stay in the previous language no
 * matter what the user picks (the PUBG-profile-label bug of 2026-09-16 was the same shape).
 * So the engine carries the string-resource ID plus its format arguments, and the card resolves
 * them with `stringResource` at composition, which always follows the current language.
 * [Raw] is the escape hatch for text that is not ours to translate: the device's own refusal
 * words (shell output, exception messages), carried verbatim per the honesty rules.
 */
sealed interface GamingModeNotice {
    data class Res(@StringRes val resId: Int, val args: List<Any> = emptyList()) : GamingModeNotice
    data class Raw(val text: String) : GamingModeNotice
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
    /**
     * Whether Qualcomm's vendor GPU mode was confirmed at `performance` (with
     * `vendor.gfx.low_quality` at `1`) by reading both properties back.
     *
     * The reference project ships a native root daemon for exactly this payload
     * (`reference/spoofdevice/GameUnlocker-main/cpp/controller.cpp`, read in full: a daemon on an
     * abstract UNIX socket that sets the two properties while a whitelisted game process is
     * connected and restores them when the last one exits). The daemon itself is not portable
     * here — opening the connection from inside the game needs Zygisk injection — but its payload
     * is, and Gaming Mode is this app's equivalent lifecycle: applied on activation, restored from
     * the snapshot on deactivation. The daemon logs its `setprop` and never reads the property
     * back; this reports the device's answer instead. Its `isQualcomm()` `ro.hardware` prefix
     * table is deliberately not ported — the property's own existence is the gate, and a curated
     * chipset-name list goes stale the same way a curated module-id list does.
     *
     * null when the device carries neither property (every non-Qualcomm SoC) — not applicable
     * rather than failed, so the report row is omitted.
     */
    val gpuPerformanceMode: Boolean? = null,
    /**
     * Whether Qualcomm's `debug.vendor.qti.game.fps` hint was confirmed at the panel's measured
     * peak by reading the property back.
     *
     * The reference sets both this and its `persist.` twin to a hard-coded 120 at boot in
     * `post-fs-data.sh` (`reference/spoofdevice/GameUnlocker-main/common/post-fs-data.sh`, read
     * in full — those two `setprop` lines are that file's whole payload). Three deliberate
     * divergences:
     *  - **Session, not boot.** Gaming Mode is this app's lifecycle — the hint is applied on
     *    activation and the prior value restored from the snapshot on deactivation, the same
     *    contract as every other switch here.
     *  - **The panel's measured peak, not a hard-coded 120.** The same `maxHz` the refresh-rate
     *    lock verified is what gets hinted; 120 on a 90 Hz panel would be a number the hardware
     *    cannot honor.
     *  - **The `persist.` twin is deliberately not set.** `setprop` cannot delete a property, so
     *    a `persist.vendor.qti.game.fps` that did not exist would become a permanent,
     *    reboot-surviving, device-wide change that deactivation could overwrite but never
     *    remove — exactly the class of change the Magisk channel's narrowing rejected. The
     *    `debug.` prop is reversible in the only way Android offers: a reboot clears the debug
     *    property area. Until then, a hint that did not exist before stays hinting the panel's
     *    own peak — bounded, and stated here rather than hidden.
     *
     * null when the device's own property dump carries no `ro.vendor.qti.*` property (every
     * non-Qualcomm SoC) — not applicable, so the report row is omitted. The gate is the dump
     * itself rather than the reference's `isQualcomm()` `ro.hardware` prefix table, for the same
     * reason [gpuPerformanceMode] gives: a curated chipset list goes stale.
     */
    val qtiGameFps: Boolean? = null,
    /**
     * Whether MediaTek's game-mode knobs (GED boost parameters + PPM policy states from
     * HunterX-Reborn-II's service payload) were confirmed by read-back.
     *
     * null when the device carries neither `/sys/module/ged/parameters` nor `/proc/ppm`
     * (every non-MediaTek SoC, and MediaTek builds without the nodes) — not applicable
     * rather than failed, so the report row is omitted. Per-node presence gates mean a
     * partial table still applies what exists; the value is true only when every attempted
     * node read back at its target.
     */
    val mediaTekGameMode: Boolean? = null,
    /**
     * Whether the Snapdragon widen-only channel was confirmed by read-back.
     *
     * The channel has two halves, both from HunterX-Reborn-II's payload: [SnapdragonCpuWiden]
     * (policy ceilings raised to each policy's own hardware max + game-visible cpusets unioned
     * toward the full set) and [SnapdragonKernelNodes] (schedutil up rate limiter removed and
     * hispeed jump raised, `msm_performance` touch boost enabled and declared CPU ceilings raised,
     * `core_ctl` floors/ceilings raised to each cluster's real core count, `cpu_boost` input boost
     * enabled and its per-CPU frequencies raised toward each policy's max).
     *
     * null when the device has no widenable node (no policy headroom, no narrow game cpuset and
     * none of the node families above — the normal case on non-Snapdragon SoCs and on Snapdragons
     * already at their maxima) — not applicable rather than failed, so the report row is omitted.
     * Like every other switch here this applies only inside an explicit Gaming Mode activation and
     * restores the snapshot priors on deactivation; a refused node is reported, never hidden, and
     * one family's refusal never blocks the nodes that did land.
     */
    val snapdragonCpuMode: Boolean? = null,
    /** Refresh rate the panel was actually pinned to, or null when the ROM ignored the keys. */
    val lockedRefreshHz: Int? = null,
    val touchResponseBoost: Boolean = false,
    /**
     * The touch speed (`pointer_speed`) the read-back confirmed, or null when no speed was
     * chosen or the write was refused. Shown beside the touch row so a faster/slower pointer
     * never looks identical to stock.
     */
    val pointerSpeed: Int? = null,
    val suspendedPackages: Int = 0,
    val suspendFailures: Int = 0,
    val dndEngaged: Boolean = false,
    /** null when no game was targeted, so the UI can say "not applicable" instead of "failed". */
    val networkWhitelisted: Boolean? = null,
    /** `always_finish_activities` confirmed at 1 by a read-back. */
    val discardActivities: Boolean = false,
    /** `max_cached_processes=1` confirmed present in `activity_manager_constants`. */
    val processLimit: Boolean = false,
    /**
     * Metered-background data blocked for the apps that are not the game.
     *
     * null means "left alone deliberately" — the user's own Background Data Restriction switch was
     * already on before activation, so this run neither engaged nor owns it.
     */
    val backgroundDataRestricted: Boolean? = null,
    /**
     * Whether the `device_config game_overlay` intervention for the target game was confirmed by a
     * read-back.
     *
     * null when no game was targeted or the device is older than Android 12, where game
     * interventions do not exist — both are "not applicable", not "failed", and the report row is
     * omitted rather than shown as refused.
     */
    val gameInterventionApplied: Boolean? = null,
    /**
     * The render scale the confirmed intervention carries, or null when the entry is FPS-only
     * (or no intervention landed). Shown beside the frame-cap row so a downscaled game never
     * looks identical to a full-resolution one.
     */
    val gameInterventionDownscale: Float? = null,
    /**
     * Whether the second-layer notification suppression is armed.
     *
     * null means the user has not granted notification access, so
     * [GamingNotificationListener] is never bound — "not applicable", not "failed". true means
     * the listener is connected and cancelling; each individual cancellation is best-effort,
     * so the field reports the layer being armed rather than promising every notification was
     * swallowed.
     */
    val notificationSuppression: Boolean? = null,
    /**
     * Refused optimizations, as resource IDs resolved by the card at composition — never
     * pre-resolved strings (see [GamingModeNotice] for why resolving here would freeze the
     * previous language across a language switch).
     */
    val unavailable: List<GamingModeNotice> = emptyList()
)

/**
 * Whether the device accepted a fixed-performance-mode request, and what it said if it did not.
 *
 * The toggle used to set its own state from the request, so it read "on" on builds where the command
 * does not exist. [accepted] is now the framework's answer and [message] is the device's own text.
 */
data class FixedPerformanceOutcome(
    val accepted: Boolean,
    val message: String
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
    val isRunning: Boolean = false,    /** Package being compiled at this moment, or null between packages. */
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

/**
 * Whether one package is worth a `cmd package compile -m [mode]` run.
 *
 * The sweep's own skip rules, factored out so the single-game path honors them too: forcing
 * compiles everything; an app already at the requested filter — or at a strictly better one
 * (`speed`/`everything` cover a `speed-profile` target, which recompiling could only
 * downgrade from) — is done; a never-opened app at `verify` has no runtime profile for
 * `speed-profile` to work with ("nothing to do yet", not a failure — forced runs compile
 * them anyway). An unreadable status compiles, because asking the platform is the only way
 * to find out.
 */
fun shouldCompilePackage(currentStatus: String?, mode: String, force: Boolean): Boolean {
    if (force) return true
    if (isFilterOptimalForTarget(currentStatus, mode)) return false
    if (mode == "speed-profile" && currentStatus == "verify") return false
    return true
}

class GamingEngine(
    private val context: Context,
    private val shellRunner: ShellRunner,
    val refreshRates: DisplayRefreshRateProvider,
    private val backgroundDataRestrictor: BackgroundDataRestrictor,
    private val gameInterventions: GameInterventions,
    private val suspendListStore: SuspendListStore,
    private val suspendExemptStore: SuspendExemptStore,
    private val refreshController: RefreshRateController
) {
    private val _state = MutableStateFlow<GamingModeState>(GamingModeState.Idle)
    val state: StateFlow<GamingModeState> = _state.asStateFlow()

    private val _report = MutableStateFlow(GamingModeReport())
    val report: StateFlow<GamingModeReport> = _report.asStateFlow()

    /**
     * Whether [GamingNotificationListener] should be cancelling notifications right now.
     *
     * Read by the listener (which the system binds in this same process whenever the user has
     * granted notification access), set only by activation, deactivation and the boot-time
     * restore of a persisted active mode — never by the listener itself.
     */
    private val _notificationSuppressionActive = MutableStateFlow(false)
    val notificationSuppressionActive: StateFlow<Boolean> = _notificationSuppressionActive.asStateFlow()

    private val _isFixedPerformanceMode = MutableStateFlow(value = false)
    val isFixedPerformanceMode: StateFlow<Boolean> = _isFixedPerformanceMode.asStateFlow()

    private val _alwaysFinishActivities = MutableStateFlow(false)
    val alwaysFinishActivities: StateFlow<Boolean> = _alwaysFinishActivities.asStateFlow()

    private val _backgroundProcessLimit = MutableStateFlow(false)
    val backgroundProcessLimit: StateFlow<Boolean> = _backgroundProcessLimit.asStateFlow()

    /**
     * The render-scale the next activation writes into the `game_overlay` intervention, or null
     * for the FPS-only reference format.
     *
     * A pure preference — choosing it touches no setting — so the setter needs no privilege and
     * takes effect on the next activation, never mid-session (re-writing the table under a
     * running game would need a restart to take hold anyway). `0` in prefs means off, since 0
     * is outside the official 0.3..0.9 range and can never be a real choice.
     */
    private val _interventionDownscale = MutableStateFlow<Float?>(null)
    val interventionDownscale: StateFlow<Float?> = _interventionDownscale.asStateFlow()

    /**
     * The touch-speed choice for the next activation (`pointer_speed`, -7..+7), or null for
     * stock (the setting is then never touched).
     *
     * A pure preference like the render scale and frame cap: choosing it touches no setting,
     * takes effect on the next activation, never mid-session. `0` in prefs means stock, since
     * stock is exactly what "don't touch it" produces.
     */
    private val _pointerSpeedChoice = MutableStateFlow<Int?>(null)
    val pointerSpeedChoice: StateFlow<Int?> = _pointerSpeedChoice.asStateFlow()

    private val _boosterLog = MutableStateFlow<List<String>>(emptyList())
    val boosterLog: StateFlow<List<String>> = _boosterLog.asStateFlow()

    /**
     * The same lines as [boosterLog], typed. Every string line is also filed here as `INFO`;
     * lifecycle events (`ANALYZING`, `NO_PROFILE`, `SKIPPED`, …) carry their own type so a
     * future surface can tell "nothing to do yet" from "already done" without re-parsing
     * words. Capped at [MAX_BOOSTER_LOG_ENTRIES], oldest evicted first.
     */
    private val _boosterLogEntries = MutableStateFlow<List<BoosterLogEntry>>(emptyList())
    val boosterLogEntries: StateFlow<List<BoosterLogEntry>> = _boosterLogEntries.asStateFlow()

    private val _boosterState = MutableStateFlow(BoosterState())
    val boosterState: StateFlow<BoosterState> = _boosterState.asStateFlow()

    /**
     * Finished sweeps, newest last, persisted across process death by [BoosterHistoryStore].
     *
     * The in-memory booster log dies with the process; this is the "what did the last boost
     * actually do" that survives. Loaded off the main thread in [init], since reading the file
     * is real disk I/O however small.
     */
    private val _boosterHistory = MutableStateFlow<List<BoosterRun>>(emptyList())
    /**
     * Static device facts, resolved once: which mechanisms this device can honor.
     * Device facts never change at runtime, so there is no reason to re-probe them
     * per activation — and every version gate below reads from here instead of
     * scattering `Build.VERSION` checks.
     */
    private val deviceCapabilities: DeviceCapabilities.Flags by lazy {
        DeviceCapabilities.detect(
            DeviceCapabilities.DeviceInfo(
                manufacturer = Build.MANUFACTURER,
                brand = Build.BRAND,
                hardware = Build.HARDWARE,
                board = Build.BOARD,
                sdkInt = Build.VERSION.SDK_INT
            )
        )
    }
    val boosterHistory: StateFlow<List<BoosterRun>> = _boosterHistory.asStateFlow()

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

    /**
     * Serializes Gaming Mode toggles.
     *
     * The switch can be driven from three places at once — the in-app card, the foreground
     * service's notification Stop button, and `onTaskRemoved` when the app is swiped away — and
     * enable/disable both read-modify-write the same `affected_pkgs` record. Interleaved, an
     * enable's suspends land after a disable's clear and no later revert knows about them, which
     * leaves apps frozen with an empty record: exactly the "still suspended after turning it
     * off" leak.
     */
    private val toggleMutex = Mutex()

    private val boosterHistoryStore = BoosterHistoryStore(context)

    /** Mode and start time of the sweep in flight, for its own history entry when it ends. */
    @Volatile private var activeBoosterMode: String? = null
    @Volatile private var activeBoosterStartedAt: Long = 0L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var currentCompileProcess: Process? = null

    private val prefs = context.getSharedPreferences("gaming_engine_prefs", Context.MODE_PRIVATE)

    init {
        val isActive = prefs.getBoolean("is_active", false)
        val isFixedPerf = prefs.getBoolean("fixed_perf_manual", false)
        _interventionDownscale.value =
            GameInterventions.sanitizeDownscale(prefs.getFloat("intervention_downscale", 0f))
        _pointerSpeedChoice.value = PointerSpeed.sanitize(prefs.getInt("pointer_speed_choice", 0))
        
        if (isActive) {
            _state.value = GamingModeState.Active
            _isFixedPerformanceMode.value = true
            // A mode that survived process death keeps its suppression layer: the flag is what
            // the listener reads, and it has no persisted state of its own to restore from.
            _notificationSuppressionActive.value = isNotificationListenerEnabled()
            scope.launch { recoverPersistedState() }
        } else if (isFixedPerf) {
            _isFixedPerformanceMode.value = true
            scope.launch { reapplyFixedPerformanceMode() }
        }

        _alwaysFinishActivities.value = getAlwaysFinishActivities() == 1
        _backgroundProcessLimit.value = getGlobalString("activity_manager_constants")?.contains("max_cached_processes=1") == true

        refreshAnimationScales()

        scope.launch { _boosterHistory.value = boosterHistoryStore.load() }
    }

    // Single-purpose reader: every caller reads the always-finish flag, so there is
    // no key parameter to warn about.
    private fun getAlwaysFinishActivities(): Int {
        return try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.ALWAYS_FINISH_ACTIVITIES, 0)
        } catch (_: Exception) { 0 }
    }

    /**
     * Whether the user has granted this app notification access, which is what decides the
     * system will ever bind [GamingNotificationListener]. Reported as the difference between
     * "armed" and "not applicable" in [GamingModeReport.notificationSuppression].
     */
    private fun isNotificationListenerEnabled(): Boolean =
        androidx.core.app.NotificationManagerCompat
            .getEnabledListenerPackages(context)
            .contains(context.packageName)

    /**
     * Re-binds [GamingNotificationListener] with a disable/enable pulse on its component.
     *
     * Best-effort and silent by design: a refused toggle must not fail activation, and there
     * is nothing to report either way — the arming read right after says whether the listener
     * is granted, which is the only fact the report carries.
     */
    private suspend fun pulseNotificationListenerBinding() {
        runCatching {
            val component = ComponentName(context, GamingNotificationListener::class.java)
            val pm = context.packageManager
            pm.setComponentEnabledSetting(
                component,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            delay(100)
            pm.setComponentEnabledSetting(
                component,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
        }
    }

    private fun getGlobalString(key: String): String? {
        return Settings.Global.getString(context.contentResolver, key)
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

    // The vivo whitelist keys moved to [OemPackageResolver] (with two more the reference writes
    // and this engine used to skip); the constants there are the same strings these keys were.
    private val vivoGameCubeApps = OemPackageResolver.KEY_GAME_CUBE_APPS
    private val vivoSpeedModeApps = OemPackageResolver.KEY_SPEED_MODE_APPS
    private val vivoHighRefreshRateApps = OemPackageResolver.KEY_VIVO_HIGH_REFRESH_RATE_APPS
    private val vivoScreenRefreshRateAppsList = OemPackageResolver.KEY_VIVO_SCREEN_REFRESH_RATE_APPS_LIST

    private val hardWhitelist = setOf(
        "moe.shizuku.privileged.api",
        context.packageName,
        "com.topjohnwu.magisk"
    )

    suspend fun toggleGamingMode(active: Boolean, packageName: String? = null) {
        toggleMutex.withLock {
            withContext(Dispatchers.IO) {
                if (active) {
                    enableGamingMode(packageName)
                } else {
                    disableGamingMode()
                }
            }
        }
    }

    private suspend fun enableGamingMode(packageName: String?) {
        _state.value = GamingModeState.Enabling(0f, context.getString(R.string.gt_eng_init))
        shellRunner.refreshShizukuPermission()
        val isRoot = shellRunner.isRootAvailable()
        val hasShizuku = shellRunner.shizukuHasPermission.value
        if (!isRoot && !hasShizuku) {
            _state.value = GamingModeState.Error(context.getString(R.string.gt_eng_no_priv))
            return
        }
        try {
            _state.value = GamingModeState.Enabling(0.05f, context.getString(R.string.gt_eng_snapshot))
            // Read the user's real values first — before the cache trim, the suspends and every
            // `settings put` below — so deactivation restores their configuration and not ours.
            val restrictedBefore = runCatching { backgroundDataRestrictor.isEngaged() }
                .getOrDefault(false)
            if (!captureAndSaveSnapshot(packageName)) {
                _state.value = GamingModeState.Error(context.getString(R.string.gt_eng_snapshot_fail))
                return
            }
            // Read-only evidence pass for the OEM refresh and per-SoC keys the counter-example
            // references exposed but this app does not lever yet. It writes nothing; it puts the
            // device's own answer in the booster log so a future gated lever starts from
            // evidence instead of a copied script.
            logDeviceVendorEvidence(packageName)
            val unavailable = mutableListOf<GamingModeNotice>()

            _state.value = GamingModeState.Enabling(0.15f, context.getString(R.string.gt_eng_trim))
            execute(PrivilegeCommands.TRIM_CACHES)
            execute(PrivilegeCommands.COMPACT_BACKGROUND)
            runCatching { execute(PrivilegeCommands.PINNER_REPIN) }

            _state.value = GamingModeState.Enabling(0.35f, context.getString(R.string.gt_eng_suspend))
            // The automatic sweep plus the user's own extra list, minus the user's keep list.
            // `SuspendSweep.resolveTargets` owns that combination and its precedence — an app
            // the user keeps running is never frozen, even if it also sits on the extra list
            // (the UI keeps the two stores mutually exclusive; the engine resolves a stale
            // overlap as kept). Both halves share the one `affected_pkgs` record below, so
            // deactivation wakes everything through the same verified path with no second
            // bookkeeping — and kept apps are never in it because they were never frozen.
            val targets = SuspendSweep.resolveTargets(
                sweepTargets = getSuspendTargets(packageName),
                extraPicks = suspendListStore.getSuspendPackages(),
                exempt = suspendExemptStore.getExemptPackages(),
                activeGamePkg = packageName,
                selfPkg = context.packageName
            )
            val currentlyAffected = prefs.getStringSet("affected_pkgs", emptySet())?.toMutableSet() ?: mutableSetOf()
            // One `cmd package list packages -s` call before the loop: apps someone else already
            // suspended are skipped, so this session never records them as its own and
            // deactivation never unsuspends an app it never froze. An unreadable answer behaves
            // as before (nothing known suspended) rather than skipping work on a guess.
            val preSuspended = querySuspendedPackages()
            val freshTargets = targets.filter { it !in preSuspended }
            var suspendedNow = 0
            var suspendFailures = 0
            for (pkg in freshTargets) {
                if (pkg in currentlyAffected) continue
                // Record only what actually got suspended. disableGamingMode unsuspends exactly
                // this set, so a package listed here that was never frozen makes the revert lie —
                // and a package frozen but not listed would be left frozen for good.
                val result = shellRunner.execSafeResult(*PrivilegeCommands.suspendArgs(pkg))
                if (SuspendVerdict.isSuspendConfirmed(result.exitCode, result.stdout)) {
                    currentlyAffected.add(pkg)
                    suspendedNow++
                } else {
                    suspendFailures++
                }
            }
            prefs.edit { putStringSet("affected_pkgs", currentlyAffected) }
            if (suspendFailures > 0) {
                unavailable += GamingModeNotice.Res(R.string.gt_eng_un_suspend, listOf(suspendFailures))
            }

            // M42: the background-op layer beneath the suspend sweep. Every package this run
            // attempts to freeze also gets `RUN_IN_BACKGROUND` set to `ignore`, its prior mode
            // read first — so on ROMs that refuse `pm suspend` the restriction still lands, and
            // in every case deactivation restores exactly what was there (a package with no
            // override reads as `default`, stored and written back as that word). Unreadable
            // priors are skipped rather than guessed at, and — like the suspend record above —
            // each entry is persisted the moment it is confirmed, so a crash mid-activation
            // cannot leave an unrecorded write behind.
            val appOpsRecord = AppOpsRecord.decode(
                prefs.getStringSet("appops_prior", emptySet()) ?: emptySet()
            ).toMutableMap()
            var appOpsFailures = 0
            for (pkg in freshTargets) {
                // An entry means an earlier session already recorded this package; its original
                // prior is preserved and never re-read over ours.
                if (appOpsRecord.containsKey(pkg)) continue
                val priorAnswer = runCatching {
                    shellRunner.execSafeResult(*PrivilegeCommands.appOpsGetArgs(pkg))
                }.getOrNull()
                val priorMode = priorAnswer?.takeIf { it.isSuccess }?.stdout
                    ?.let { AppOpsVerdict.parseMode(it) } ?: continue
                val setAnswer = runCatching {
                    shellRunner.execSafeResult(*PrivilegeCommands.appOpsSetArgs(pkg, "ignore"))
                }.getOrNull()
                val readBack = if (setAnswer != null && setAnswer.isSuccess) {
                    runCatching { shellRunner.execSafeResult(*PrivilegeCommands.appOpsGetArgs(pkg)) }.getOrNull()
                } else null
                val confirmed = readBack != null && readBack.isSuccess &&
                    readBack.stdout.let { AppOpsVerdict.parseMode(it) } == "ignore"
                if (confirmed) {
                    appOpsRecord[pkg] = priorMode
                    prefs.edit { putStringSet("appops_prior", AppOpsRecord.encode(appOpsRecord)) }
                } else {
                    appOpsFailures++
                }
            }
            if (appOpsFailures > 0) {
                unavailable += GamingModeNotice.Res(R.string.gt_eng_un_appops, listOf(appOpsFailures))
            }

            _state.value = GamingModeState.Enabling(0.6f, context.getString(R.string.gt_eng_focus))
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            var dndEngaged = false
            if (nm.isNotificationPolicyAccessGranted) {
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
                // The filter change travels through ZenModeHelper and a config write, so reading it
                // back on the next line often still returns the old value — that race is why DND
                // "sometimes failed" while actually being applied. Poll briefly instead.
                dndEngaged = awaitInterruptionFilter(nm)
                if (!dndEngaged) unavailable += GamingModeNotice.Res(R.string.gt_eng_un_dnd_off)
            } else {
                unavailable += GamingModeNotice.Res(R.string.gt_eng_un_dnd_perm)
            }

            // Second layer beyond DND, from the reference's GamingNotificationListener: some
            // OEM skins post their own alerts around the interruption filter, and cancelling
            // the notification directly does not depend on the filter being honoured. Arming is
            // all activation does — the listener (bound by the system only once the user has
            // granted notification access) does the cancelling, including a purge of what is
            // already on screen.
            //
            // OriginOS/Vivo coma fix, also from the reference: a listener the system let fall
            // asleep does not wake on being armed, so arming fresh starts with a disable/enable
            // pulse on our own component to re-bind it. An app can always toggle its own
            // components — no permission involved — and a ROM that refuses the toggle still
            // arms below; the pulse is best-effort, never a gate.
            if (!_notificationSuppressionActive.value && isNotificationListenerEnabled()) {
                pulseNotificationListenerBinding()
            }
            val notificationSuppression = isNotificationListenerEnabled()
            _notificationSuppressionActive.value = notificationSuppression

            _state.value = GamingModeState.Enabling(0.9f, context.getString(R.string.gt_eng_hw))
            // Refresh lock via the shared RefreshRateController (F3): one writer for
            // min/peak_refresh_rate — Gaming Mode and "Always fastest" can no longer diverge.
            // Target is always the panel's measured maximum: maximum refresh, never a cap.
            val lock = refreshController.lockToPeak()
            val maxHz = lock.lockedHz
            val peakOk = lock.peakOk
            val minOk = lock.minOk
            val lockedHz = if (peakOk || minOk) maxHz else null
            if (lockedHz == null) {
                unavailable += GamingModeNotice.Res(R.string.gt_eng_un_refresh)
            }
            // vivo's own display-mode switch, written beside the standard rate keys: OriginOS
            // honours it over them on some builds. The mode is the enumerated one at the same
            // measured peak — never the reference's hardcoded id (see [VivoDisplayMode]).
            val vivoLock = refreshController.lockVendorDisplayMode()
            if (vivoLock.applicable && !vivoLock.ok) {
                unavailable += GamingModeNotice.Res(R.string.gt_eng_un_vivo)
            }
            // Touch sampling boost. Only OEMs that ship the key honour it; captureAndSaveSnapshot
            // already recorded the old value, so disableGamingMode puts it back.
            val touchOk = putSettingVerified("touch_response_speed", "2")
            if (!touchOk) unavailable += GamingModeNotice.Res(R.string.gt_eng_un_touch)
            // The user's touch-speed choice, if any. Same snapshot/restore contract as the boost
            // above; a refused write is reported rather than counted as done.
            var pointerSpeedApplied: Int? = null
            PointerSpeed.sanitize(_pointerSpeedChoice.value)?.let { speed ->
                if (putSettingVerified("pointer_speed", speed.toString())) {
                    pointerSpeedApplied = speed
                } else {
                    unavailable += GamingModeNotice.Res(R.string.gt_eng_un_pointer, listOf(speed.toString()))
                }
            }

            val fixedPerfOk = shellRunner
                .execSafeResult(*PrivilegeCommands.fixedPerformanceArgs(true))
                .isSuccess
            if (!fixedPerfOk) unavailable += GamingModeNotice.Res(R.string.gt_eng_un_fixed)

            // Qualcomm's vendor GPU mode. The payload is the reference daemon's whole job (see
            // [GamingModeReport.gpuPerformanceMode] for the lineage); gated on the property
            // existing at all, so non-Qualcomm silicon is "not applicable" rather than a refused
            // switch it never had.
            val gpuPerfOk = applyVendorGpuPerformance()
            if (gpuPerfOk == false) {
                unavailable += GamingModeNotice.Res(R.string.gt_eng_un_gpu)
            }

            // Qualcomm's game FPS hint, from the same reference's boot script (see
            // [GamingModeReport.qtiGameFps] for the lineage and the persist-twin decision).
            val qtiFpsOk = applyQtiGameFpsHint(maxHz)
            if (qtiFpsOk == false) {
                unavailable += GamingModeNotice.Res(R.string.gt_eng_un_qti)
            }

            // MediaTek's game-mode knobs (GED boost parameters + PPM policy states). Gated on
            // the nodes existing at all — non-MediaTek silicon is "not applicable" — root-only
            // (a shell-uid session cannot write /sys), and every write is read back; see
            // [GamingModeReport.mediaTekGameMode] for the lineage.
            val mtkNodes = readLastSnapshot()?.mediatekGameNodes.orEmpty()
            val mtkOk = applyMediaTekGameMode(mtkNodes)
            if (mtkOk == false) {
                unavailable += GamingModeNotice.Res(R.string.gt_eng_un_mtk)
            }

            // Snapdragon widen-only channel: policy ceilings and game cpusets ([SnapdragonCpuWiden])
            // plus the schedutil rate limit / hispeed jump / iowait boost, msm_performance touch
            // boost and CPU ceilings, core_ctl floors and ceilings and cpu_boost input boost
            // ([SnapdragonKernelNodes]). Gated on recorded priors — an empty map is "not
            // applicable" — root-only (a shell-uid session cannot write /sys), and every write is
            // read back; see [GamingModeReport.snapdragonCpuMode] for the lineage.
            val snapNodes = readLastSnapshot()?.snapdragonCpuNodes.orEmpty()
            val snapOk = applySnapdragonCpuMode(snapNodes)
            if (snapOk == false) {
                unavailable += GamingModeNotice.Res(R.string.gt_eng_un_snap)
            }

            // The two developer options, applied through the same verified helpers the Developer
            // Options card uses — so the switches there and the state here can never disagree.
            _state.value = GamingModeState.Enabling(0.92f, context.getString(R.string.gt_eng_dev))
            val discardOk = toggleAlwaysFinishActivities(true)
            if (!discardOk) {
                unavailable += GamingModeNotice.Res(R.string.gt_eng_un_discard)
            }
            val processLimitOk = toggleBackgroundProcessLimit(true)
            if (!processLimitOk) {
                unavailable += GamingModeNotice.Res(R.string.gt_eng_un_limit)
            }

            // Metered-background data. Left entirely alone when the user's own switch was already on:
            // engaging it again would make deactivation turn off a restriction we did not own.
            _state.value = GamingModeState.Enabling(0.95f, context.getString(R.string.gt_eng_data))
            var backgroundDataRestricted: Boolean? = null
            if (restrictedBefore) {
                unavailable += GamingModeNotice.Res(R.string.gt_eng_un_data_on)
            } else {
                val outcome = runCatching {
                    backgroundDataRestrictor.enable(listOfNotNull(packageName))
                }.getOrNull()
                backgroundDataRestricted = outcome?.success == true
                if (outcome == null) {
                    unavailable += GamingModeNotice.Res(R.string.gt_eng_un_data_silent)
                } else if (!outcome.success) {
                    unavailable += GamingModeNotice.Res(R.string.gt_eng_un_data_fail, listOf(outcome.message))
                }
            }

            var networkWhitelisted: Boolean? = null
            var gameInterventionApplied: Boolean? = null
            var gameInterventionDownscale: Float? = null
            if (packageName != null) {
                execute("pm unsuspend --user 0 $packageName")
                execute("cmd activity set-bg-restriction-level --user 0 $packageName unrestricted")
                execute("am set-standby-bucket --user 0 $packageName active")
                execute(PrivilegeCommands.deviceIdleWhitelistAdd(packageName))
                networkWhitelisted = applyPerGameOptimizations(packageName)

                // The game's own frame-cap table, raised to the panel's peak — the maximum,
                // never a throttle target. Skipped whole on Android 11 and older, where game
                // interventions do not exist — that is "not applicable", reported as null, not
                // a refusal. The render scale is the user's opt-in choice (null = FPS-only
                // reference entry); it is read here, at activation, so a change mid-session
                // waits for the next run instead of rewriting the table under a running game
                // that would need a restart to honor it anyway.
                if (deviceCapabilities.supportsGameInterventions) {
                    val downscale = _interventionDownscale.value
                    val outcome = gameInterventions.apply(packageName, maxHz, downscale)
                    gameInterventionApplied = outcome.applied
                    gameInterventionDownscale = if (outcome.applied) downscale else null
                    if (!outcome.applied) {
                        unavailable += GamingModeNotice.Res(
                            R.string.gt_eng_un_framecap,
                            listOf(
                                outcome.refusal?.let {
                                    GamingModeNotice.Res(R.string.gt_eng_un_framecap_no, listOf(it))
                                }
                                    ?: GamingModeNotice.Res(R.string.gt_eng_un_framecap_kept)
                            )
                        )
                    }
                }
            }
            execute(PrivilegeCommands.DEVICE_IDLE_FORCE)
            execute(PrivilegeCommands.KILL_ALL)

            prefs.edit {
                putBoolean("is_active", true)
                // Fixed performance mode and `cmd game set` can leave the thermal service in an
                // overridden state after the mode is off (some vendor HALs keep the override
                // until it is explicitly cleared). Only those two paths set this flag, so the
                // deactivation-side reset runs exactly when there is something to undo.
                if (fixedPerfOk || networkWhitelisted == true) {
                    putBoolean("thermal_recovery_needed", true)
                }
            }
            _isFixedPerformanceMode.value = fixedPerfOk
            _report.value = GamingModeReport(
                fixedPerformance = fixedPerfOk,
                gpuPerformanceMode = gpuPerfOk,
                qtiGameFps = qtiFpsOk,
                mediaTekGameMode = mtkOk,
                snapdragonCpuMode = snapOk,
                lockedRefreshHz = lockedHz,
                touchResponseBoost = touchOk,
                pointerSpeed = pointerSpeedApplied,
                suspendedPackages = suspendedNow,
                suspendFailures = suspendFailures,
                dndEngaged = dndEngaged,
                networkWhitelisted = networkWhitelisted,
                discardActivities = discardOk,
                processLimit = processLimitOk,
                backgroundDataRestricted = backgroundDataRestricted,
                gameInterventionApplied = gameInterventionApplied,
                // The scale the read-back confirmed, or null when the intervention landed
                // without one (or did not land at all) — the row shows "raised" plus this.
                gameInterventionDownscale = gameInterventionDownscale,
                // false means "not granted", which the report renders as absent rather than
                // refused — the user has simply not switched this layer on.
                notificationSuppression = if (notificationSuppression) true else null,
                unavailable = unavailable
            )
            _state.value = GamingModeState.Active
        } catch (e: Exception) {
            _state.value = GamingModeState.Error(e.message ?: context.getString(R.string.gt_eng_activation_fail))
        }
    }

    /**
     * Waits, briefly, for the notification interruption filter to actually reach INTERRUPTION_FILTER_NONE.
     *
     * `setInterruptionFilter` returns as soon as NotificationManagerService has the request; the zen
     * state it changes is published a moment later. Reading `currentInterruptionFilter` on the very
     * next line therefore reports the *previous* filter often enough to look intermittent, which is
     * exactly how "DND sometimes fails" presented. Six 50 ms checks cover that gap without turning a
     * genuinely refused filter into a long stall.
     *
     * @return whether the filter is INTERRUPTION_FILTER_NONE now. A false here is a real failure, not a race.
     */
    private suspend fun awaitInterruptionFilter(
        nm: NotificationManager,
        attempts: Int = 6
    ): Boolean {
        repeat(attempts) {
            if (runCatching { nm.currentInterruptionFilter }.getOrNull() == NotificationManager.INTERRUPTION_FILTER_NONE) return true
            delay(50.milliseconds)
        }
        return runCatching { nm.currentInterruptionFilter }.getOrNull() == NotificationManager.INTERRUPTION_FILTER_NONE
    }

    private suspend fun disableGamingMode() {
        _state.value = GamingModeState.Disabling
        // First, before any lever is reverted: the moment suppression stops, notifications
        // arrive normally again, and a device busy reporting its own restoration would have
        // its reports eaten by the layer this mode switched on.
        _notificationSuppressionActive.value = false
        // Anything gathered here survives into the report even when a later step throws, so a
        // half-finished revert still says what it did not get to.
        val revertProblems = mutableListOf<GamingModeNotice>()
        try {
            // Every unsuspend is verified by what the shell answered — the same standard
            // activation holds suspends to. A package that refused to wake stays in the record
            // instead of being deleted from it, so the next deactivation retries exactly the
            // leftovers rather than forgetting frozen apps. Dropping the whole set up front is
            // what made one refused unsuspend a permanently suspended app with no trace.
            //
            // The sweep covers the whole suspend-target universe, not just the record: sessions
            // reverted by older builds deleted their record before verifying, so their leftovers
            // have no entry in `affected_pkgs`. Waking an already-awake package is a confirmed
            // no-op, which is what makes this blind sweep safe — it can only wake our own past
            // leaks. `pm` takes a single package per call, so each chunk is N commands chained
            // with `;` in one fork (the same batching BackgroundDataRestrictor uses for
            // netpolicy); a refused chunk falls back to per-package isolation rather than
            // retaining the whole chunk as still suspended.
            val affected = prefs.getStringSet("affected_pkgs", emptySet()) ?: emptySet()
            val stillSuspended = mutableSetOf<String>()
            for (chunk in (affected + getSuspendTargets(null)).distinct().chunked(UNSUSPEND_BATCH_SIZE)) {
                if (chunk.size == 1) {
                    if (!unsuspendOne(chunk[0])) stillSuspended.add(chunk[0])
                    continue
                }
                val chained = chunk.joinToString("; ") { "pm unsuspend --user 0 $it" }
                val batchResult = runCatching { shellRunner.execResult(chained) }.getOrNull()
                if (batchResult != null &&
                    batchResult.isSuccess &&
                    SuspendVerdict.isUnsuspendConfirmed(batchResult.exitCode, batchResult.stdout)
                ) continue
                for (pkg in chunk) {
                    if (!unsuspendOne(pkg)) stillSuspended.add(pkg)
                }
            }
            if (stillSuspended.isEmpty()) {
                prefs.edit { remove("affected_pkgs") }
            } else {
                prefs.edit { putStringSet("affected_pkgs", stillSuspended) }
                revertProblems +=
                    GamingModeNotice.Res(R.string.gt_eng_still_suspended, listOf(stillSuspended.size))
            }
            // M42: the background ops set on activation go back too, each restore verified by
            // reading the mode back; a package that refuses stays recorded for the next
            // deactivation instead of being silently left restricted.
            revertProblems += restoreAppOpsRecord()
            execute(PrivilegeCommands.DEVICE_IDLE_UNFORCE)
            execute(PrivilegeCommands.FIXED_PERF_OFF)
            // Do Not Disturb is restored inside revertFromSnapshot, from the filter that was recorded
            // before activation — not to INTERRUPTION_FILTER_ALL, which would cancel a DND the user set.
            revertProblems += revertFromSnapshot()
            val thermalRefusal = if (!recoverThermalOverrideIfNeeded()) {
                GamingModeNotice.Res(R.string.gt_eng_thermal)
            } else {
                null
            }
            // Deactivation normally clears the report, but a refused step is the device's own
            // refusal text and belongs in front of the user rather than in a silent state change.
            _report.value = GamingModeReport(unavailable = revertProblems + listOfNotNull(thermalRefusal))
        } catch (e: Exception) {
            revertProblems += GamingModeNotice.Res(
                R.string.gt_eng_revert_fail,
                listOf(e.message ?: e.javaClass.simpleName)
            )
            _report.value = GamingModeReport(unavailable = revertProblems)
        } finally {
            prefs.edit { putBoolean("is_active", false); putBoolean("fixed_perf_manual", false) }
            _isFixedPerformanceMode.value = false
            _state.value = GamingModeState.Idle
        }
    }

    /**
     * Restores the `RUN_IN_BACKGROUND` op for every package whose prior mode this session
     * recorded, reading each restore back like every other lever this engine pulls.
     *
     * A package whose restore does not come back exactly as recorded is kept in the record
     * rather than dropped — the same "never delete an entry the device did not accept" rule
     * the unsuspend sweep follows — so the next deactivation retries it, and the count is
     * reported instead of silently leaving apps restricted. An absent record means nothing
     * was ever written, not an empty restore.
     */
    private suspend fun restoreAppOpsRecord(): List<GamingModeNotice> {
        val record = AppOpsRecord.decode(
            prefs.getStringSet("appops_prior", emptySet()) ?: emptySet()
        )
        if (record.isEmpty()) return emptyList()
        val stillRestricted = mutableMapOf<String, String>()
        for ((pkg, priorMode) in record) {
            val set = runCatching {
                shellRunner.execSafeResult(*PrivilegeCommands.appOpsSetArgs(pkg, priorMode))
            }.getOrNull()
            val readBack = if (set != null && set.isSuccess) {
                runCatching { shellRunner.execSafeResult(*PrivilegeCommands.appOpsGetArgs(pkg)) }.getOrNull()
            } else null
            val restored = readBack != null && readBack.isSuccess &&
                readBack.stdout.let { AppOpsVerdict.parseMode(it) } == priorMode
            if (!restored) stillRestricted[pkg] = priorMode
        }
        if (stillRestricted.isEmpty()) {
            prefs.edit { remove("appops_prior") }
        } else {
            prefs.edit { putStringSet("appops_prior", AppOpsRecord.encode(stillRestricted)) }
        }
        return if (stillRestricted.isEmpty()) emptyList()
        else listOf(GamingModeNotice.Res(R.string.gt_eng_still_restricted, listOf(stillRestricted.size)))
    }

    /**
     * Clears any thermal override the session may have left behind.
     *
     * Fixed performance mode and the GameManager game mode (`cmd game set`) can leave the
     * thermal service holding an override after every other lever is reverted — on some vendor
     * HALs the override stays engaged until it is explicitly cleared, which presents as the
     * phone running hot long after Gaming Mode is off. Activation records that one of those
     * paths was accepted ([thermal_recovery_needed]); this runs once, on deactivation, and
     * clears the flag only when `cmd thermalservice reset` actually answered with success.
     *
     * Modelled on the reference project's `EsportsOptimizationEngine.recoverThermalOverrideIfNeeded`
     * (`reference/gamingtools/booster`): guarded by a needs-recovery flag rather than run
     * unconditionally, judged by the exit code, and idempotent — a reset that never became
     * needed is a success, not a skipped failure.
     *
     * @return false when a reset was needed and the device refused it.
     */
    private suspend fun recoverThermalOverrideIfNeeded(): Boolean {
        if (!prefs.getBoolean("thermal_recovery_needed", false)) return true
        val result = runCatching {
            shellRunner.execSafeResult("cmd", "thermalservice", "reset")
        }.getOrNull() ?: return false
        if (!result.isSuccess) return false
        prefs.edit { remove("thermal_recovery_needed") }
        return true
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
            execute(PrivilegeCommands.TRIM_CACHES)
            execute(PrivilegeCommands.COMPACT_BACKGROUND)
            val targets = getSuspendTargets(null)
            attempted = targets.size
            for (pkg in targets) {
                // execute() never throws for a command that merely failed, so counting attempts
                // would report apps that were never stopped. Only a zero exit counts.
                if (shellRunner.execSafeResult(*PrivilegeCommands.forceStopArgs(pkg)).isSuccess) stoppedCount++
            }
            execute(PrivilegeCommands.KILL_ALL)
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
    private fun readAvailableMemoryBytes(): Long? =
        com.catsmoker.app.shared.util.MemAvailableReader.readAvailableBytesWithFallback(context)

    private fun parseMemAvailableBytes(): Long? =
        com.catsmoker.app.shared.util.MemAvailableReader.readAvailableBytes()

    /**
     * Turns Android's fixed-performance mode on or off, and says whether the device accepted it.
     *
     * `cmd power set-fixed-performance-mode-enabled` reaches PowerManagerService, which calls
     * `PowerHAL.setMode(MODE_FIXED_PERFORMANCE)`. That mode asks the SoC vendor's power HAL to hold
     * clocks at a fixed operating point instead of letting the governor scale them with load — the
     * point of it is *consistency*, not peak speed, and on many HALs the fixed point is deliberately
     * below the boost ceiling to keep the device thermally sustainable.
     *
     * Two real limits, both reported rather than hidden:
     * - The command was added in Android 11. On older builds `cmd power` does not know it.
     * - `MODE_FIXED_PERFORMANCE` is optional for vendors. A HAL that does not implement it accepts the
     *   call and does nothing, so a success here means "the framework accepted the request", which is
     *   as much as any caller — including Android's own CTS — can determine.
     */
    suspend fun toggleFixedPerformanceMode(enabled: Boolean): FixedPerformanceOutcome {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return FixedPerformanceOutcome(
                accepted = false,
                message = context.getString(R.string.gt_eng_fp_old, Build.VERSION.RELEASE)
            )
        }
        if (!shellRunner.hasPrivilege()) {
            return FixedPerformanceOutcome(
                accepted = false,
                message = context.getString(R.string.gt_needs_root_shizuku_change)
            )
        }

        val result = shellRunner.execSafeResult(
            *PrivilegeCommands.fixedPerformanceArgs(enabled)
        )
        // `cmd power` prints its complaint and returns non-zero when it does not recognise the
        // sub-command, so the exit code is a real answer here rather than the always-0 that
        // `settings put` gives.
        val accepted = result.isSuccess &&
            !result.stderr.contains("Unknown", ignoreCase = true) &&
            !result.stdout.contains("Unknown", ignoreCase = true)

        if (accepted && enabled) {
            // Housekeeping that pairs with the mode rather than being part of it: drop the memory
            // background apps are sitting on, and pin the framework so it is not paged back in
            // mid-frame. Neither is required for the mode to work.
            execute(PrivilegeCommands.COMPACT_BACKGROUND)
            execute(PrivilegeCommands.PINNER_REPIN)
        } else if (accepted) {
            execute(PrivilegeCommands.DEVICE_IDLE_UNFORCE)
        }

        val state = accepted && enabled
        prefs.edit { putBoolean("fixed_perf_manual", state) }
        _isFixedPerformanceMode.value = state

        return FixedPerformanceOutcome(
            accepted = accepted,
            message = if (accepted) {
                if (enabled) {
                    context.getString(R.string.gt_eng_fp_ok)
                } else {
                    context.getString(R.string.gt_eng_fp_off)
                }
            } else {
                context.getString(
                    R.string.gt_eng_fp_no,
                    result.text.trim().ifBlank { context.getString(R.string.gt_eng_fp_no_reason) }
                )
            }
        )
    }

    /**
     * Chooses the render scale the next Gaming Mode activation writes into the game's
     * `game_overlay` intervention, or null for the FPS-only reference entry.
     *
     * Out-of-range values are sanitized to null rather than refused: the stored result is
     * always either a scale the platform accepts or off. Pure preference write — no privilege,
     * no device contact — so it cannot fail and returns the stored value.
     */
    fun setInterventionDownscale(factor: Float?): Float? {
        val sanitized = GameInterventions.sanitizeDownscale(factor)
        prefs.edit { putFloat("intervention_downscale", sanitized ?: 0f) }
        _interventionDownscale.value = sanitized
        return sanitized
    }

    /**
     * Chooses the touch speed for the next Gaming Mode activation, or null for stock.
     *
     * Out-of-range values sanitize to stock rather than being refused: the stored result is
     * always either a speed the platform accepts or untouched. Pure preference write — returns
     * the stored value.
     */
    fun setPointerSpeedChoice(speed: Int?): Int? {
        val stored = PointerSpeed.sanitize(speed)
        prefs.edit { putInt("pointer_speed_choice", stored ?: 0) }
        _pointerSpeedChoice.value = stored
        return stored
    }

    /** What the panel can do (rates, seamless switches, adaptive support) — no privilege needed. */
    fun panelRefreshInfo(): DisplayRefreshRateProvider.PanelInfo = refreshRates.panelInfo()

    /** @return true when the setting actually holds the requested value afterwards. */
    suspend fun toggleAlwaysFinishActivities(enabled: Boolean): Boolean {
        val target = if (enabled) 1 else 0
        execute("settings put global always_finish_activities $target")
        // `settings put` is silent on success, so confirm by reading the value back.
        val applied = getAlwaysFinishActivities() == target
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
            upsertCsvKey(current)
        } else {
            removeCsvKey(current)
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
            addBoosterLog(context.getString(R.string.gt_eng_booster_running))
            return
        }
        // Per-app compile times from our own past successes, persisted on the way out so a
        // cancelled sweep keeps the successes it banked before the stop landed.
        val compiledTimes = readBoosterCompiledTimes().toMutableMap()
        try {
            _boosterLog.value = emptyList()
            _boosterLogEntries.value = emptyList()
            boosterCancelRequested.set(false)
            activeBoosterMode = mode
            activeBoosterStartedAt = System.currentTimeMillis()
            _boosterState.value = BoosterState(isRunning = true, outcome = BoosterOutcome.Running)
            addBoosterLog(
                if (force) context.getString(R.string.gt_eng_booster_start_force)
                else context.getString(R.string.gt_eng_booster_start)
            )

            // `cmd package compile` is refused for other packages without a privileged shell.
            // Checking first means the log says why nothing happened, instead of listing apps that
            // were never touched and then reporting a success that never took place.
            if (!shellRunner.hasPrivilege()) {
                val reason = context.getString(R.string.gt_eng_booster_no_priv)
                fail(BoosterOutcome.Unavailable(reason), context.getString(R.string.gt_eng_booster_unavail, reason))
                return
            }

            val apps = eligibleBoosterPackages(force)
            if (apps.isEmpty()) {
                val reason = context.getString(R.string.gt_eng_booster_no_apps)
                fail(BoosterOutcome.Unavailable(reason), context.getString(R.string.gt_eng_booster_unavail, reason))
                return
            }
            addBoosterLog(context.getString(R.string.gt_eng_booster_found, apps.size))
            _boosterState.update { it.copy(totalCount = apps.size) }

            val useRoot = shellRunner.isRootAvailable()
            addBoosterLog(
                if (useRoot) context.getString(R.string.gt_eng_booster_root)
                else context.getString(R.string.gt_eng_booster_shizuku)
            )

            // Without -f the platform skips an app that is already in the requested filter, so ask
            // it up front what each app is compiled with and say which ones are being skipped.
            val currentStatuses = if (force) emptyMap() else queryDexoptStatuses()

            // Pre-scan first: the tallies say what this run will do before it does anything, so
            // a sweep that skips everything still reports why instead of looking stalled.
            val analysis = analyzeDexoptStatuses(currentStatuses, mode, apps)
            logBooster(
                BoosterLogType.ANALYZING,
                null,
                context.getString(
                    R.string.gt_eng_booster_analysis,
                    analysis.appsNeedingOptimization,
                    analysis.appsAlreadyOptimized,
                    analysis.appsWithNoProfile,
                    analysis.unknownPackages.size
                )
            )

            // Per-app compile times from our own past successes: the 7-day re-optimization gate
            // and the update-after-compile rule need them, and the platform never reports them.
            // Absent times leave those rules dormant rather than guessing.
            // (`compiledTimes` is loaded before the try and persisted in the finally below.)

            for (pkg in apps) {
                if (boosterCancelRequested.get()) return

                // One decision path for skips: already optimal (or recently compiled to it) is
                // "already done"; `verify` under speed-profile is "nothing to do yet" — a
                // never-opened app with no runtime profile, not a failure. Forced runs compile
                // everything by explicit request.
                if (!force) {
                    val (needs, reason) = evaluateOptimization(
                        compilerFilter = currentStatuses[pkg],
                        lastCompilationTimeMs = compiledTimes[pkg],
                        lastUpdateTimeMs = lastUpdateTimeMs(pkg),
                        targetFilter = mode
                    )
                    if (!needs) {
                        if (reason is CompileSkipReason.NoProfile) {
                            logBooster(
                                BoosterLogType.NO_PROFILE,
                                pkg,
                                context.getString(R.string.gt_eng_booster_skip_verify, pkg)
                            )
                        } else {
                            logBooster(
                                BoosterLogType.SKIPPED,
                                pkg,
                                context.getString(R.string.gt_eng_booster_skip_done, pkg)
                            )
                        }
                        _boosterState.update { it.copy(currentPackage = null, skippedCount = it.skippedCount + 1) }
                        continue
                    }
                }

                _boosterState.update { it.copy(currentPackage = pkg) }
                addBoosterLog(context.getString(R.string.gt_eng_booster_working, pkg))
                val outcome = runCompileCommand(mode, force, pkg, useRoot)

                // A cancel that landed mid-compile: the partial result is not a failure of this app.
                if (boosterCancelRequested.get()) return

                if (outcome.succeeded) {
                    compiledTimes[pkg] = System.currentTimeMillis()
                    addBoosterLog(
                        context.getString(
                            R.string.gt_eng_booster_ok,
                            pkg,
                            outcome.detail.takeIf { it.isNotBlank() }
                                ?.let { context.getString(R.string.gt_eng_booster_ok_detail, it) }
                                .orEmpty()
                        )
                    )
                    _boosterState.update { it.copy(currentPackage = null, optimizedCount = it.optimizedCount + 1) }
                } else {
                    addBoosterLog(context.getString(R.string.gt_eng_booster_fail, pkg, outcome.detail))
                    _boosterState.update { it.copy(currentPackage = null, failedCount = it.failedCount + 1) }
                }
            }

            // The reference's own guard: a cancellation that arrived during the last compile must
            // not be reported as a completed sweep.
            if (boosterCancelRequested.get()) return

            val finished = _boosterState.value
            addBoosterLog(
                context.getString(
                    R.string.gt_eng_booster_done,
                    finished.optimizedCount,
                    finished.skippedCount,
                    finished.failedCount
                )
            )
            _boosterState.value = finished.copy(
                isRunning = false,
                currentPackage = null,
                outcome = BoosterOutcome.Completed
            )
            recordBoosterRun("completed")
        } catch (e: CancellationException) {
            markBoosterCancelled()
            throw e
        } catch (e: Exception) {
            val reason = e.message ?: e.javaClass.simpleName
            fail(
                BoosterOutcome.Failed(reason),
                context.getString(R.string.gt_eng_booster_failed, reason)
            )
        } finally {
            // Whatever happened, nothing is left compiling and a new run can start. Persist the
            // per-app compile times first, so successes banked before a cancel survive it.
            writeBoosterCompiledTimes(compiledTimes)
            if (boosterCancelRequested.get()) markBoosterCancelled()
            currentCompileProcess?.destroy()
            currentCompileProcess = null
            boosterActive.set(false)
            activeBoosterMode = null
        }
    }

    /**
     * What a single-game `speed-profile` compile reported.
     *
     * @param status the current dexopt filter when one was read, or null when unreadable.
     * @param skipped true when the platform had nothing to do (already compiled or no profile
     *   yet) — "nothing to do", never a failure.
     */
    data class SingleCompileResult(
        val pkg: String,
        val succeeded: Boolean,
        val skipped: Boolean,
        val status: String?,
        val detail: String
    )

    /**
     * Compiles one game with `cmd package compile -m speed-profile` — the pre-launch pass that
     * warms exactly the game about to be played instead of sweeping every installed app.
     *
     * Reuses the sweep's own machinery (privilege gate, cancel flag, compile classifier,
     * history record) but guards on the same [boosterActive] lock, so a single run and a sweep
     * can never walk the platform side by side. Skips by the same [shouldCompilePackage] rule
     * the sweep applies, read from the same `dumpsys package dexopt` source.
     */
    suspend fun runSinglePackageOptimization(pkg: String): SingleCompileResult {
        if (!boosterActive.compareAndSet(false, true)) {
            return SingleCompileResult(pkg, false, false, null, context.getString(R.string.gt_eng_booster_running))
        }
        try {
            boosterCancelRequested.set(false)
            activeBoosterMode = "speed-profile"
            activeBoosterStartedAt = System.currentTimeMillis()
            _boosterState.value = BoosterState(isRunning = true, outcome = BoosterOutcome.Running)
            addBoosterLog(context.getString(R.string.gt_eng_booster_working, pkg))

            if (!shellRunner.hasPrivilege()) {
                val reason = context.getString(R.string.gt_eng_booster_no_priv)
                _boosterState.value = BoosterState(isRunning = false, outcome = BoosterOutcome.Unavailable(reason))
                return SingleCompileResult(pkg, false, false, null, reason)
            }

            val status = queryDexoptStatuses()[pkg]
            if (!shouldCompilePackage(status, "speed-profile", false)) {
                _boosterState.value = BoosterState(isRunning = false, outcome = BoosterOutcome.Completed)
                recordBoosterRun("skipped")
                return SingleCompileResult(pkg, true, true, status, "")
            }

            val outcome = runCompileCommand("speed-profile", false, pkg, shellRunner.isRootAvailable())
            if (boosterCancelRequested.get()) {
                markBoosterCancelled()
                return SingleCompileResult(
                    pkg, false, false, status,
                    context.getString(R.string.gt_eng_booster_stopped, 0)
                )
            }
            _boosterState.value = if (outcome.succeeded) {
                BoosterState(isRunning = false, outcome = BoosterOutcome.Completed)
            } else {
                BoosterState(isRunning = false, outcome = BoosterOutcome.Failed(outcome.detail))
            }
            recordBoosterRun(if (outcome.succeeded) "completed" else "failed")
            return SingleCompileResult(pkg, outcome.succeeded, false, status, outcome.detail)
        } finally {
            if (boosterCancelRequested.get()) markBoosterCancelled()
            currentCompileProcess?.destroy()
            currentCompileProcess = null
            boosterActive.set(false)
            activeBoosterMode = null
        }
    }

    /**
     * Appends the finished run to the persisted history and the live flow, from the counts the
     * state holds right now. Not a read-back of anything — the counts were accumulated from
     * what the shell answered, which is the same standard a report row has to meet.
     */
    private fun recordBoosterRun(outcome: String) {
        val mode = activeBoosterMode ?: return
        val startedAt = activeBoosterStartedAt
        if (startedAt <= 0L) return
        val s = _boosterState.value
        val run = BoosterRun(
            startedAt = startedAt,
            durationMs = System.currentTimeMillis() - startedAt,
            mode = mode,
            optimized = s.optimizedCount,
            skipped = s.skippedCount,
            failed = s.failedCount,
            outcome = outcome
        )
        boosterHistoryStore.record(run)
        _boosterHistory.update { it + run }
    }

    /** Ends the run with a stated reason instead of a silent stop. */
    private fun fail(outcome: BoosterOutcome, logLine: String) {
        addBoosterLog(logLine)
        _boosterState.update { it.copy(isRunning = false, currentPackage = null, outcome = outcome) }
        // Only a run that actually started something is worth a history entry: Unavailable means
        // the sweep never began, and recording it would bury the runs that did.
        if (outcome is BoosterOutcome.Failed) recordBoosterRun("failed")
    }

    /**
     * The apps worth compiling: everything the user installed, plus any system app they added to
     * their own game library — with the two classes that can only waste a compile slot screened
     * out by [BoosterPackageClassifier]: overlay/RRO packages (no meaningful dex; the platform
     * refuses or no-ops them) and, for a `speed-profile` run, never-opened apps at status `verify`
     * (no runtime profile yet, so a profile-guided compile has nothing to work with). Both are
     * counted as [BoosterState.skippedCount] with their own log line — "nothing to do" is not
     * "already done" and not a failure — and neither is filtered in a forced run, where the user
     * explicitly asked for every package.
     */
    // Partial visibility is fine: an invisible app simply misses this sweep.
    private fun eligibleBoosterPackages(force: Boolean = false): List<String> {
        val userAdded = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
            .getStringSet("user_games", emptySet()) ?: emptySet()
        return context.packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            .asSequence()
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || it.packageName in userAdded }
            .map { it.packageName to it }
            // Recompiling ourselves restarts this process and takes the sweep down with it. The
            // reference excludes its own package for the same reason.
            .filter { (pkg, _) -> pkg != context.packageName }
            .filter { (pkg, info) ->
                // The classifier reads the APK's own path — no shell call, no per-app fork. In a
                // forced run every package the user asked for goes through, overlays included.
                force || !BoosterPackageClassifier.isOverlayLike(pkg, info.sourceDir)
            }
            .map { (pkg, _) -> pkg }
            .distinct()
            .toList()
            // The verify check needs the statuses, which come from one `dumpsys package dexopt`
            // call the caller already makes; keep the filter there (see runArtOptimization).
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
        addBoosterLog(context.getString(R.string.gt_eng_booster_stopped, current.processedCount))
        _boosterState.value = current.copy(
            isRunning = false,
            currentPackage = null,
            outcome = BoosterOutcome.Cancelled
        )
        // "Cancelled after 12" is a different fact about the sweep than "completed", and the
        // counts of how far it got are exactly what the history row is for.
        recordBoosterRun("cancelled")
    }

    private suspend fun queryDexoptStatuses(): Map<String, String> {
        return withContext(Dispatchers.IO) {
            DexoptStatusParser.parse(execute("dumpsys package dexopt"))
        }
    }

    /**
     * When this app last successfully compiled each package, epoch millis by package name.
     *
     * Stored as `pkg@time` strings under one prefs key: a structured store would be nicer, but
     * this is write-rarely (once per sweep) and read-once per sweep, and a corrupt entry is
     * simply dropped rather than failing the run. Times feed the 7-day re-optimization gate;
     * a package with no recorded time leaves the gate dormant for it.
     */
    private fun readBoosterCompiledTimes(): Map<String, Long> {
        val raw = prefs.getStringSet("booster_compiled_times", emptySet()) ?: emptySet()
        val out = HashMap<String, Long>(raw.size)
        for (entry in raw) {
            val at = entry.lastIndexOf('@')
            if (at <= 0) continue
            entry.substring(at + 1).toLongOrNull()?.let { out[entry.substring(0, at)] = it }
        }
        return out
    }

    private fun writeBoosterCompiledTimes(times: Map<String, Long>) {
        prefs.edit { putStringSet("booster_compiled_times", times.map { (pkg, t) -> "$pkg@$t" }.toSet()) }
    }

    /** When the package was last updated, or null when the package manager cannot say. */
    private fun lastUpdateTimeMs(pkg: String): Long? = runCatching {
        if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0)).lastUpdateTime
        } else {
            context.packageManager.getPackageInfo(pkg, 0).lastUpdateTime
        }
    }.getOrNull()

    /**
     * Packages the platform already reports as suspended (`cmd package list packages -s`).
     * Unreadable output means "nothing known suspended" — the sweep then behaves as before
     * rather than skipping work on a guess. An answer that fails [SuspendVerdict]'s
     * trustworthiness check (a broken `-s` flag listing nearly everything, as observed on
     * Android 10 MTK) is likewise discarded whole: excluding it would gut the sweep.
     */
    private suspend fun querySuspendedPackages(): Set<String> {
        val result = runCatching {
            shellRunner.execSafeResult("cmd", "package", "list", "packages", "-s")
        }.getOrNull() ?: return emptySet()
        if (result.exitCode != 0) return emptySet()
        val parsed = SuspendVerdict.parseSuspendedPackages(result.stdout)
        return if (SuspendVerdict.isSuspendListTrustworthy(parsed)) parsed else emptySet()
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
            exitCode != 0 -> CompileOutcome(false, text.ifBlank { context.getString(R.string.gt_eng_booster_exit, exitCode) })
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
            addBoosterLog(context.getString(R.string.gt_eng_booster_idle))
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
     * Appends one line to the booster log.
     *
     * [MutableStateFlow.update] rather than a read-then-write, because the sweep logs from the IO
     * dispatcher while a stop can come in from the main thread, and a lost line would be a step
     * that happened with nothing to show it.
     */
    private fun addBoosterLog(msg: String) {
        _boosterLog.update { it + msg }
        _boosterLogEntries.update { appendBoosterLogEntry(it, BoosterLogEntry(BoosterLogType.INFO, null, msg)) }
    }

    /**
     * Appends one typed line: the same words go to the string log the UI already shows, and
     * the type goes to [boosterLogEntries] for future surfacing.
     */
    private fun logBooster(type: BoosterLogType, pkg: String?, msg: String) {
        _boosterLog.update { it + msg }
        _boosterLogEntries.update { appendBoosterLogEntry(it, BoosterLogEntry(type, pkg, msg)) }
    }

    suspend fun execute(command: String): String = shellRunner.exec(command)

    // Partial visibility is fine: invisible packages are excluded, never suspended.
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
        // OEM bloat is FLAG_SYSTEM, so the user-app sweep above can never reach it — this list is
        // the only path a preinstalled package has into the sweep. Curated per vendor in
        // [OemPackageResolver] (the reference's OemPackageResolver.VIVO_SAFE_TO_SUSPEND, verbatim),
        // and already disjoint from [systemCritical]/[gamingDaemons], whose load-bearing vivo
        // processes must stay out.
        val oemApps = OemPackageResolver.packagesToSuspend(
            isVivoOrIqoo = isVivoOrIqoo(),
            isInstalled = { pkg -> installedApps.any { it.packageName == pkg } }
        ).filter { pkg ->
            pkg != activeGamePkg && pkg !in launcherPkgs &&
                pkg !in userGames &&
                try {
                    pm.getApplicationInfo(pkg, 0).category != ApplicationInfo.CATEGORY_GAME
                } catch (_: Exception) { true }
        }
        // The user's keep list is subtracted last, from all three halves: an app the user asked
        // to keep running is never a sweep target, so it never gets the AppOps background
        // restriction that rides this sweep either, and `manualBoostRam`'s force-stop pass
        // spares it. It also needs no wake-up on deactivation — it was never frozen.
        val exempt = suspendExemptStore.getExemptPackages()
        return (userApps + googleApps + oemApps).filterNot { it in exempt }.distinct()
    }

    /**
     * The packages the automatic sweep can reach on this device, for the keep list's picker:
     * every user-installed app plus the curated Google and OEM lists that are actually installed.
     *
     * Read-only, and deliberately the sweep's own vocabulary rather than "all installed apps":
     * a system app outside the curated lists is never suspended, so offering it as a keep target
     * would promise a protection nothing needs. The picker filters this to what it can label.
     */
    fun suspendCandidatePackages(): List<String> {
        val pm = context.packageManager
        val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val userApps = installedApps
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            .map { it.packageName }
        val installedNames = installedApps.mapTo(mutableSetOf()) { it.packageName }
        val googleApps = googleSafeToSuspend.filter { it in installedNames }
        val oemApps = OemPackageResolver.packagesToSuspend(
            isVivoOrIqoo = isVivoOrIqoo(),
            isInstalled = { pkg -> pkg in installedNames }
        )
        return (userApps + googleApps + oemApps).distinct()
    }

    private fun getLauncherPackages(): Set<String> {
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_HOME)
        return context.packageManager.queryIntentActivities(intent, 0)
            .mapNotNull { it.activityInfo?.packageName }
            .toSet()
    }

    /**
     * Reads the user's real state and stores it, before activation writes anything at all.
     *
     * Called as the very first step of [enableGamingMode] — ahead of the cache trim, the suspends and
     * every `settings put` — so what lands here is the user's own configuration rather than a value
     * this app had already changed. A second activation over an active one would otherwise capture
     * the optimized values as if they were the originals, and deactivation would "restore" those.
     */
    private suspend fun captureAndSaveSnapshot(packageName: String?): Boolean {
        val minRefresh = readSettingOrNull("system", "min_refresh_rate") ?: return false
        val peakRefresh = readSettingOrNull("system", "peak_refresh_rate") ?: return false
        val touchSpeed = readSettingOrNull("system", "touch_response_speed") ?: return false
        // No abort here: pointer_speed is only ever written when the user picked a speed, so
        // an unreadable key means "leave it alone", not "un-restorable, touch nothing".
        val pointerSpeed = readSettingOrNull("system", "pointer_speed")
        val displayMode = readSettingOrNull("secure", "user_preferred_display_mode_id") ?: return false
        // The two developer options Gaming Mode now also sets. Both usually come back
        // `existed = false`, which is a real answer and tells the revert to delete rather than write.
        val alwaysFinish = readSettingOrNull("global", Settings.Global.ALWAYS_FINISH_ACTIVITIES)
        val amConstants = readSettingOrNull("global", "activity_manager_constants")
        // Whether the user's own Background Data Restriction switch was already on. Recorded before
        // activation touches netpolicy, so deactivation can tell "we engaged it" from "they did".
        val restrictedBefore = runCatching { backgroundDataRestrictor.isEngaged() }.getOrDefault(false)

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_RING)
        // The Do Not Disturb filter the user had. Read before activation forces
        // INTERRUPTION_FILTER_NONE, so a priority-only DND they set themselves comes back intact.
        val originalFilter = runCatching {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .currentInterruptionFilter
        }.getOrNull()
        
        val cr = context.contentResolver
        val origBrightnessMode = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
        val origRotation = Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 1)

        var uid: Int? = null
        var uidWhitelistedBefore = false
        if (packageName != null) {
            uid = runCatching { context.packageManager.getApplicationInfo(packageName, 0).uid }.getOrNull()
            uidWhitelistedBefore = (uid != null) && isUidWhitelisted(uid)
        }
        val isVivo = (packageName != null) && isVivoOrIqoo()
        val vivoCube = if (isVivo) getGlobalString(vivoGameCubeApps) else null
        val vivoSpeed = if (isVivo) getGlobalString(vivoSpeedModeApps) else null
        // The two high-refresh-rate whitelist CSVs the reference also writes. Same null contract
        // as the pair above: null when there is no game or the device is not vivo/iQOO, and a
        // blank string when the key simply was not set (meaning "delete it again on the way out").
        val vivoHighRefresh = if (isVivo) getGlobalString(vivoHighRefreshRateApps) else null
        val vivoScreenRefresh = if (isVivo) getGlobalString(vivoScreenRefreshRateAppsList) else null
        // The vivo display-mode lock's second write. Recorded whenever the device is vivo/iQOO
        // (not only when a game is set): the lock runs for the session, and the revert has to
        // be able to put the key back even if the game entry vanished meanwhile.
        val vivoRefreshRateMode = if (isVivoOrIqoo()) {
            readSettingOrNull("global", VivoDisplayMode.KEY_SCREEN_REFRESH_RATE_MODE)
        } else null
        // The game's own intervention-table entry, read *before* activation writes one, so the
        // revert can put back what was there or delete ours if nothing was. Game interventions
        // exist from Android 12 only; an unreadable answer is stored as null, which the revert
        // reads as "leave the flag alone" rather than guessing at its prior shape.
        val gameOverlay = if (packageName != null && deviceCapabilities.supportsGameInterventions) {
            when (val overlay = gameInterventions.readOverlay(packageName)) {
                is GameInterventions.OverlayValue.Set -> SettingValue(overlay.value, existed = true)
                is GameInterventions.OverlayValue.Unset -> SettingValue("", existed = false)
                is GameInterventions.OverlayValue.Unreadable -> null
            }
        } else null

        // Qualcomm's vendor GPU properties, as the vendor booted with them. A blank property (the
        // normal case on non-Qualcomm silicon) records existed = false, which both the
        // activation-side gate and the revert read as "never touch this property".
        val vendorGpuMode = propSettingValue(VENDOR_GPU_MODE)
        val vendorGfxLowQuality = propSettingValue(VENDOR_GFX_LOW_QUALITY)
        // The game FPS hint too. On a Qualcomm device that never set it this records
        // existed = false — and the revert then leaves our hint in place until a reboot clears
        // the debug property area, the one deletion Android offers. See the snapshot field's
        // KDoc; recorded either way so a prior value is never lost.
        val debugVendorQtiGameFps = propSettingValue(DEBUG_VENDOR_QTI_GAME_FPS)

        // MediaTek game-mode knobs, as the vendor booted with them. Only nodes the device
        // actually has are recorded — read failures mean "never touch", and an empty map tells
        // both the activation gate and the revert there is nothing to do.
        val mediatekGameNodes = captureMediaTekGameNodes()

        // Snapdragon widen-only priors (policy ceilings with headroom + narrow game cpusets, plus
        // every schedutil / msm_performance / core_ctl / cpu_boost node a rule would write), as the
        // kernel held them. Only nodes the device actually has are recorded — read failures mean
        // "never touch", and an empty map tells both the activation gate and the revert there is
        // nothing to do.
        val snapdragonCpuNodes = captureSnapdragonCpuNodes()

        val snapshot = GamingOptimizationSnapshot(
            activeGamePackage = packageName,
            activeGameUid = uid,
            timestamp = System.currentTimeMillis(),
            minRefreshRate = minRefresh,
            peakRefreshRate = peakRefresh,
            touchResponseSpeed = touchSpeed,
            pointerSpeed = pointerSpeed,
            userPreferredDisplayModeId = displayMode,
            affectedPackages = emptySet(),
            uidWhitelistedBefore = uidWhitelistedBefore,
            vivoGameCubeApps = vivoCube,
            vivoSpeedModeApps = vivoSpeed,
            vivoHighRefreshRateApps = vivoHighRefresh,
            vivoScreenRefreshRateAppsList = vivoScreenRefresh,
            vivoScreenRefreshRateMode = vivoRefreshRateMode,
            originalRingtoneVolume = currentVol,
            originalBrightnessMode = origBrightnessMode,
            originalRotation = origRotation,
            alwaysFinishActivities = alwaysFinish,
            activityManagerConstants = amConstants,
            backgroundDataRestrictedBefore = restrictedBefore,
            originalInterruptionFilter = originalFilter,
            gameOverlay = gameOverlay,
            vendorGpuMode = vendorGpuMode,
            vendorGfxLowQuality = vendorGfxLowQuality,
            debugVendorQtiGameFps = debugVendorQtiGameFps,
            mediatekGameNodes = mediatekGameNodes,
            snapdragonCpuNodes = snapdragonCpuNodes.takeIf { it.isNotEmpty() }
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
    // Single-namespace writer: every caller targets Settings.System, so there is no
    // namespace parameter to warn about.
    private suspend fun putSettingVerified(key: String, value: String): Boolean {
        shellRunner.execSafeResult("settings", "put", "system", key, value)
        val readBack = readSettingOrNull("system", key)?.takeIf { it.existed }?.value ?: return false
        val actual = readBack.toFloatOrNull()
        val wanted = value.toFloatOrNull()
        return if (actual != null && wanted != null) actual == wanted else readBack == value
    }

    /** @return true when the game's uid was actually added to the background-data whitelist. */
    private suspend fun applyPerGameOptimizations(packageName: String): Boolean {
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
            applyPerformanceGameMode(packageName)
            if (isVivoOrIqoo()) {
                // All four whitelist CSVs the reference writes, not just the first two: the skin's
                // high-refresh-rate lists decide whether the panel holds its peak for the game, so
                // writing only the Game Cube pair left the game fast-pathed in one surface and an
                // ordinary app in the other two. Snapshot already recorded each key's prior value.
                for (key in listOf(vivoGameCubeApps, vivoSpeedModeApps, vivoHighRefreshRateApps, vivoScreenRefreshRateAppsList)) {
                    val current = getGlobalString(key) ?: ""
                    execute("settings put global $key ${appendToCsv(current, packageName)}")
                }
            }
            // netpolicy has no read-back on its own, but the whitelist can be listed.
            return isUidWhitelisted(uid)
        } catch (_: Exception) {
            return false
        }
    }

    /**
     * Puts the game in the platform's performance mode through the GameManager CLI.
     *
     * The engine used to send `set` with `--mode performance --fps <peak>`, which is not
     * a shape `set` accepts: `set` parses its flags as an intervention config, and `--mode`
     * reaches an integer parse as the string "performance" —
     * `NumberFormatException: For input string: "performance"` on an Android 13 POCO X3
     * NFC (2026-10-19, found while cross-checking `reference/gamingtools/TMPAD`). Mode
     * selection is its own subcommand, and the device's own `list-modes` answer decides
     * whether performance is even offered, so a ROM without that path is reported as not
     * applicable rather than silently failing. The result is logged, never asserted: the
     * engine only claims what `list-modes` read back.
     */
    private suspend fun applyPerformanceGameMode(pkg: String) {
        if (!deviceCapabilities.supportsGameInterventions) return
        val before = shellRunner.execSafeResult("cmd", "game", "list-modes", pkg)
        val offered = GameCommandParser.parseModes(before.stdout)
        if (offered != null && !offered.offers("performance")) {
            logBooster(BoosterLogType.INFO, pkg, "Game mode: performance is not offered by this ROM")
            return
        }
        val set = shellRunner.execSafeResult(
            *GameCommandParser.performanceModeArgs(pkg).toTypedArray()
        )
        if (!set.isSuccess) {
            logBooster(BoosterLogType.INFO, pkg, "Game mode: performance refused (${set.text.trim()})")
            return
        }
        val after = shellRunner.execSafeResult("cmd", "game", "list-modes", pkg)
        val current = GameCommandParser.parseModes(after.stdout)?.current
        logBooster(
            BoosterLogType.INFO,
            pkg,
            if (current == "performance") {
                "Game mode: performance"
            } else {
                "Game mode: performance not confirmed"
            }
        )
    }

    /**
     * Records, without writing, which OEM refresh keys and per-SoC properties this
     * device already carries.
     *
     * Both vocabularies come from counter-example references (JordanTweaks' refresh
     * enum, BoostX's per-SoC modules); their scripts are not ported. This pass exists so
     * a future gated lever can be argued from the device's own answer — which key is
     * real, what rate or value it holds — instead of from a copied script. A device
     * that carries none of the candidates logs nothing, which is itself an answer.
     */
    private suspend fun logDeviceVendorEvidence(pkg: String?) {
        runCatching {
            val oem = refreshController.probeOemKeys()
            if (oem.isNotEmpty()) {
                logBooster(
                    BoosterLogType.INFO,
                    pkg,
                    "OEM refresh keys: " + oem.joinToString {
                        "${it.key.namespace}/${it.key.key}=${it.hz}"
                    }
                )
            }
        }
        val vendor = when {
            deviceCapabilities.isKirin -> VendorPerformanceKeys.Vendor.KIRIN
            deviceCapabilities.isExynos -> VendorPerformanceKeys.Vendor.EXYNOS
            deviceCapabilities.isUnisoc -> VendorPerformanceKeys.Vendor.UNISOC
            deviceCapabilities.isMediaTek -> VendorPerformanceKeys.Vendor.MEDIATEK
            else -> null
        } ?: return
        val keys = VendorPerformanceKeys.candidatesFor(vendor)
        if (keys.isEmpty()) return
        val props = runCatching { shellRunner.execSafeResult("getprop") }.getOrNull() ?: return
        val readings = VendorPerformanceKeys.readPresent(props.stdout, keys)
        if (readings.isNotEmpty()) {
            logBooster(
                BoosterLogType.INFO,
                pkg,
                "Vendor keys: " + readings.joinToString { "${it.key}=${it.value ?: "unset"}" }
            )
        }
    }

    /**
     * Restores everything the snapshot recorded, reporting what refused to go back.
     *
     * @return problems for the deactivation report as notices resolved by the card at
     *   composition (see [GamingModeNotice]); empty when everything landed. A missing or
     *   unparsable snapshot answers empty rather than accusing the device — there is no record
     *   to restore from, so there is nothing to verify against.
     */
    private suspend fun revertFromSnapshot(): List<GamingModeNotice> {
        val json = prefs.getString("last_snapshot", null) ?: return emptyList()
        val snapshot = GamingOptimizationSnapshot.fromJson(json) ?: return emptyList()
        val problems = mutableListOf<GamingModeNotice>()
        
        // Restore volume
        snapshot.originalRingtoneVolume?.let { vol ->
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            try { audioManager.setStreamVolume(AudioManager.STREAM_RING, vol, 0) } catch (_: Exception) {}
        }

        // Restore the Do Not Disturb filter the user actually had, which may itself have been a DND.
        snapshot.originalInterruptionFilter?.let { filter ->
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.isNotificationPolicyAccessGranted) {
                runCatching { nm.setInterruptionFilter(filter) }
            }
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
        restoreSetting("system", "pointer_speed", snapshot.pointerSpeed)
        restoreSetting("secure", "user_preferred_display_mode_id", snapshot.userPreferredDisplayModeId)
        // The vivo display-mode lock's vendor switch, back to the recorded value (or deleted
        // when it was not set). Null — every non-vivo device, and old snapshots — writes nothing.
        restoreSetting("global", VivoDisplayMode.KEY_SCREEN_REFRESH_RATE_MODE, snapshot.vivoScreenRefreshRateMode)

        // The two developer options, back to whatever they were — including "was not set at all",
        // which restoreSetting turns into a delete rather than a write of some assumed default.
        restoreSetting("global", Settings.Global.ALWAYS_FINISH_ACTIVITIES, snapshot.alwaysFinishActivities)
        restoreSetting("global", "activity_manager_constants", snapshot.activityManagerConstants)
        _alwaysFinishActivities.value =
            getAlwaysFinishActivities() == 1
        _backgroundProcessLimit.value =
            getGlobalString("activity_manager_constants").orEmpty().contains("max_cached_processes=1")

        // Lift the metered-background block only if this session put it there. When the user's own
        // switch was already on before activation, turning it off here would be undoing their
        // setting — unless a previous revert retained verified-still-blocked UIDs, which are
        // ours to retry. The outcome is reported rather than dropped: a refused unblock that
        // stays silent is another "still stopped after reverting".
        if (!snapshot.backgroundDataRestrictedBefore || backgroundDataRestrictor.hasRetainedBlocks()) {
            val dataOutcome = runCatching { backgroundDataRestrictor.disable() }.getOrNull()
            if (dataOutcome == null) {
                problems += GamingModeNotice.Res(R.string.gt_eng_un_data_revert)
            } else if (!dataOutcome.success) {
                // The restrictor's own words (already resolved where it runs) — carried verbatim.
                problems += GamingModeNotice.Raw(dataOutcome.message)
            }
        }

        if (snapshot.activeGamePackage != null) {
            val pkg = snapshot.activeGamePackage
            execute("cmd activity set-bg-restriction-level --user 0 $pkg adaptive_bucket")
            execute("am set-standby-bucket --user 0 $pkg working_set")
            execute(PrivilegeCommands.deviceIdleWhitelistRemove(pkg))
            execute("cmd game reset --user 0 $pkg")
            // The intervention table goes back to whatever it held before activation — a value put
            // back verbatim, or ours deleted when there was none. Only attempted when the snapshot
            // actually recorded the prior state (null means "do not touch it at all"). A refused
            // restore is left as-is rather than retried as a blind delete: deleting anyway would
            // destroy a prior entry the device told us existed but would not accept back.
            snapshot.gameOverlay?.let { previous ->
                gameInterventions.restore(pkg, previous)
            }
        }
        if (snapshot.activeGameUid != null && !snapshot.uidWhitelistedBefore) {
            execute("cmd netpolicy remove restrict-background-whitelist ${snapshot.activeGameUid}")
        }
        if (isVivoOrIqoo() && (snapshot.activeGamePackage != null)) {
            restoreGlobalSetting(vivoGameCubeApps, snapshot.vivoGameCubeApps)
            restoreGlobalSetting(vivoSpeedModeApps, snapshot.vivoSpeedModeApps)
            restoreGlobalSetting(vivoHighRefreshRateApps, snapshot.vivoHighRefreshRateApps)
            restoreGlobalSetting(vivoScreenRefreshRateAppsList, snapshot.vivoScreenRefreshRateAppsList)
        }
        // Qualcomm GPU mode, back to what the vendor booted with. Only when the snapshot recorded
        // the property as existing — a blank one was never set, and setprop can neither create nor
        // delete a property, so there is nothing an absent one could be restored *to*.
        restoreProp(VENDOR_GPU_MODE, snapshot.vendorGpuMode)
        restoreProp(VENDOR_GFX_LOW_QUALITY, snapshot.vendorGfxLowQuality)
        // The game FPS hint. Same rule — but a hint that did not exist before cannot be deleted,
        // so this returns it only when the vendor had one, and the created hint otherwise stays
        // until the next reboot. That bounded leftover is stated in the snapshot field's KDoc and
        // in GamingModeReport.qtiGameFps's, not hidden.
        restoreProp(DEBUG_VENDOR_QTI_GAME_FPS, snapshot.debugVendorQtiGameFps)
        // MediaTek game-mode nodes, back to the snapshot values. Only nodes the snapshot
        // recorded are touched — an empty map means there was never anything to restore.
        restoreMediaTekGameNodes(snapshot.mediatekGameNodes)
        // Snapdragon CPU nodes, back to the snapshot priors. Null (or empty) means the
        // capture found nothing widenable, so there is nothing to restore.
        restoreSnapdragonCpuNodes(snapshot.snapdragonCpuNodes.orEmpty())
        prefs.edit { remove("last_snapshot") }
        return problems
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

    /** CSV append now lives in [OemPackageResolver.appendPackage] (pure, and pinned by tests). */
    private fun appendToCsv(list: String, pkg: String): String = OemPackageResolver.appendPackage(list, pkg)

    private fun upsertCsvKey(csv: String): String {
        val kept = csv.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("max_cached_processes=") }
        return (kept + "max_cached_processes=1").joinToString(",")
    }

    private fun removeCsvKey(csv: String): String {
        return csv.split(",")
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("max_cached_processes=") }
            .joinToString(",")
    }

    /** One verified wake-up; false means still suspended, or the shell never answered. */
    private suspend fun unsuspendOne(pkg: String): Boolean {
        val result = runCatching {
            shellRunner.execSafeResult(*PrivilegeCommands.unsuspendArgs(pkg))
        }.getOrNull() ?: return false
        return SuspendVerdict.isUnsuspendConfirmed(result.exitCode, result.stdout)
    }

    private suspend fun restoreSetting(namespace: String, key: String, sv: SettingValue?) {        if (sv == null) return
        val cmd = if (sv.existed && sv.value.isNotBlank()) {
            "settings put $namespace $key ${sv.value}"
        } else {
            "settings delete $namespace $key"
        }
        execute(cmd)
    }

    /**
     * Sets Qualcomm's vendor GPU properties for the duration of Gaming Mode and confirms them by
     * read-back — the payload `GameUnlocker-main/cpp/controller.cpp` ships its daemon for, minus
     * the daemon (see [GamingModeReport.gpuPerformanceMode]).
     *
     * @return null when the device carries neither property — not applicable, every non-Qualcomm
     *   SoC — otherwise whether every property it does carry landed at its target value.
     */
    private suspend fun applyVendorGpuPerformance(): Boolean? {
        val modePresent = readProp(VENDOR_GPU_MODE).isNotBlank()
        val lowQualityPresent = readProp(VENDOR_GFX_LOW_QUALITY).isNotBlank()
        if (!modePresent && !lowQualityPresent) return null
        var confirmed = 0
        var attempted = 0
        if (modePresent) {
            attempted++
            if (setPropVerified(VENDOR_GPU_MODE, "performance")) confirmed++
        }
        if (lowQualityPresent) {
            attempted++
            if (setPropVerified(VENDOR_GFX_LOW_QUALITY, "1")) confirmed++
        }
        return confirmed == attempted
    }

    /**
     * Hints Qualcomm's perf framework at [peakHz] through `debug.vendor.qti.game.fps`, the whole
     * payload of `GameUnlocker-main/common/post-fs-data.sh` (see [GamingModeReport.qtiGameFps]
     * for why the `persist.` twin is deliberately not set and why the value is measured rather
     * than the reference's hard-coded 120).
     *
     * @return null when the device is not Qualcomm — not applicable — otherwise whether the
     *   property read back at [peakHz].
     */
    private suspend fun applyQtiGameFpsHint(peakHz: Int): Boolean? {
        if (!isQualcommDevice()) return null
        return setPropVerified(DEBUG_VENDOR_QTI_GAME_FPS, peakHz.toString())
    }

    /**
     * Whether the device's own property dump names a Qualcomm vendor property. The reference's
     * `isQualcomm()` matched `ro.hardware` against a prefix table (qcom, kalama, taro, …); the
     * dump itself is the measured fact — every Qualcomm device carries `ro.vendor.qti.*`
     * properties because its whole vendor stack defines them — and it cannot go stale the way a
     * chipset-name list does.
     */
    private suspend fun isQualcommDevice(): Boolean {
        val dump = shellRunner.execSafeResult("getprop")
        return dump.isSuccess && dump.stdout.contains("[ro.vendor.qti.")
    }

    /** The just-written snapshot, or null when none was recorded or it no longer parses. */
    private fun readLastSnapshot(): GamingOptimizationSnapshot? =
        prefs.getString("last_snapshot", null)?.let { GamingOptimizationSnapshot.fromJson(it) }

    /**
     * Captures the MediaTek game-mode nodes' current values for the snapshot. Only nodes the
     * device actually has are recorded — read failures (absent dir, SELinux denial) mean
     * "never touch", and an empty map tells both the activation gate and the revert there is
     * nothing to do. Reads need no privilege; only the writes below are root-gated.
     */
    private suspend fun captureMediaTekGameNodes(): Map<String, String> {
        val priors = linkedMapOf<String, String>()
        for (node in MediaTekGameMode.gedParams.keys) {
            readSysfsNode(node)?.let { priors[node] = it }
        }
        readSysfsNode(MediaTekGameMode.PPM_POLICY_STATUS_NODE)?.let {
            priors[MediaTekGameMode.PPM_POLICY_STATUS_NODE] = it
        }
        return priors
    }

    /**
     * Applies the MediaTek game-mode tables (GED boost parameters + PPM policy states from
     * HunterX-Reborn-II's service payload) and confirms every write by read-back.
     *
     * @return null when [priors] is empty — no nodes, not applicable — otherwise whether every
     *   attempted node landed at its target. Root-only: a shell-uid session cannot write
     *   `/sys`, and that surfaces here as a refusal rather than as silence.
     */
    private suspend fun applyMediaTekGameMode(priors: Map<String, String>): Boolean? {
        if (priors.isEmpty()) return null
        if (!shellRunner.isRootAvailable()) return false
        val plan = MediaTekGameMode.writePlan(priors)
        var attempted = 0
        var confirmed = 0
        for ((node, target) in plan) {
            attempted++
            if (writeSysfsVerified(node, target)) confirmed++
        }
        if (priors.containsKey(MediaTekGameMode.PPM_POLICY_STATUS_NODE)) {
            for (write in MediaTekGameMode.ppmGameWrites()) {
                attempted++
                shellRunner.execSafeResult("sh", "-c", "echo $write > ${MediaTekGameMode.PPM_POLICY_STATUS_NODE}")
            }
            // The node reports status text rather than re-writable content, so the read-back
            // is a re-parse: the dump must now describe exactly the states just written.
            if (verifyPpmStates(MediaTekGameMode.ppmPolicies)) confirmed += MediaTekGameMode.ppmPolicies.size
        }
        return confirmed == attempted
    }

    /**
     * Puts the MediaTek nodes back to their snapshot values. Only nodes the snapshot recorded
     * are touched; a PPM status dump is restored by re-issuing its parsed per-index states.
     * Root-gated like the apply — without it there is nothing that could have changed them
     * through this path.
     */
    private suspend fun restoreMediaTekGameNodes(priors: Map<String, String>) {
        if (priors.isEmpty() || !shellRunner.isRootAvailable()) return
        for ((node, prior) in priors) {
            if (node == MediaTekGameMode.PPM_POLICY_STATUS_NODE) {
                for (write in MediaTekGameMode.ppmRestoreWrites(prior)) {
                    shellRunner.execSafeResult("sh", "-c", "echo $write > $node")
                }
            } else if (isSafeSysfsValue(prior)) {
                shellRunner.execSafeResult("sh", "-c", "echo $prior > $node")
            }
        }
    }

    /**
     * Captures the Snapdragon widen-only priors for the snapshot: the channel's CPU half — the
     * current `scaling_max_freq` of each policy with headroom toward its own hardware max, plus the
     * current bodies of narrow game-visible cpusets — and, from [SnapdragonKernelNodes], the
     * schedutil rate limit / hispeed jump / iowait boost of every policy, `msm_performance`'s touch
     * boost and declared CPU ceilings, every `core_ctl` cluster's floor and ceiling, and
     * `cpu_boost`'s input boost switch and per-CPU frequencies.
     *
     * Only nodes the device actually has *and* a rule would actually write are recorded — an
     * unlistable policy dir, an unreadable node, an unparseable table or a node already at its
     * widen limit means "never touch", and an empty map tells both the activation gate and the
     * revert there is nothing to do. Policy dirs are enumerated through the privileged shell
     * because locked-down kernels hide even the listing from the app UID; every value is read with
     * [readSysfsNode], which needs no privilege by itself.
     */
    private suspend fun captureSnapdragonCpuNodes(): Map<String, String> {
        val priors = linkedMapOf<String, String>()
        val policies = readSnapdragonPolicyStates()
        for ((policyDir, state) in policies) {
            if (SnapdragonCpuWiden.policyMaxTarget(state) == null) continue
            readSysfsNode("$policyDir/${SnapdragonCpuWiden.SCALING_MAX_FREQ}")?.let {
                priors["$policyDir/${SnapdragonCpuWiden.SCALING_MAX_FREQ}"] = it
            }
        }
        captureCpusetPriors(priors)
        val cpuSets = readPolicyCpuSets()
        val hardwareMaxKhz = hardwareMaxByCpu(policies, cpuSets)
        captureSchedutilPriors(priors, policies)
        captureMsmPerformancePriors(priors, hardwareMaxKhz)
        captureCoreCtlPriors(priors, cpuSets)
        captureCpuBoostPriors(priors, hardwareMaxKhz)
        return priors
    }

    /**
     * cpuset priors: the current bodies of the game-visible cpusets, recorded only where
     * [SnapdragonCpuWiden.cpusetWritePlan] would actually add CPUs. An unreadable `present` node
     * records nothing at all, because a set widened against a guessed full set could narrow.
     */
    private suspend fun captureCpusetPriors(priors: MutableMap<String, String>) {
        val fullSet = readSysfsNode(CPU_PRESENT_NODE)
            ?.let(SnapdragonCpuWiden::parseCpuSet).orEmpty()
        if (fullSet.isEmpty()) return
        val cpusetPriors = SnapdragonCpuWiden.CPUSET_CANDIDATES.mapNotNull { node ->
            readSysfsNode(node)?.let { node to it }
        }.toMap()
        for (node in SnapdragonCpuWiden.cpusetWritePlan(cpusetPriors, fullSet).keys) {
            cpusetPriors[node]?.let { priors[node] = it }
        }
    }

    /**
     * schedutil priors for every policy: the up rate limiter (removed to 0), the hispeed jump
     * (raised to the policy's own hardware max, gated on a declared `hispeed_load`) and the iowait
     * boost switch. A policy without a `schedutil` directory — a different governor — contributes
     * nothing, and neither does a node whose rule yields no write.
     */
    private suspend fun captureSchedutilPriors(
        priors: MutableMap<String, String>,
        policies: Map<String, SnapdragonCpuWiden.PolicyState>
    ) {
        for ((policyDir, state) in policies) {
            val dir = "$policyDir/${SnapdragonKernelNodes.SCHEDUTIL_DIR_NAME}"
            val upNode = "$dir/${SnapdragonKernelNodes.UP_RATE_LIMIT_US}"
            readSysfsNode(upNode)?.let { now ->
                if (SnapdragonKernelNodes.noUpRateLimit(now) != null) priors[upNode] = now
            }
            val hispeedNode = "$dir/${SnapdragonKernelNodes.HISPEED_FREQ}"
            readSysfsNode(hispeedNode)?.let { now ->
                val load = readSysfsNode("$dir/${SnapdragonKernelNodes.HISPEED_LOAD}")
                if (SnapdragonKernelNodes.hispeedTarget(now, state.availableKhz, load) != null) {
                    priors[hispeedNode] = now
                }
            }
            val iowaitNode = "$dir/${SnapdragonKernelNodes.IOWAIT_BOOST_ENABLE}"
            readSysfsNode(iowaitNode)?.let { now ->
                if (SnapdragonKernelNodes.enableIfZero(now) != null) priors[iowaitNode] = now
            }
        }
    }

    /**
     * `msm_performance` priors: the touch boost switch and the declared per-CPU ceilings. A
     * `cpu_max_freq` body whose entries are all 0 declares no cap, so the node is not recorded (and
     * therefore never written): there is no ceiling to remove.
     */
    private suspend fun captureMsmPerformancePriors(
        priors: MutableMap<String, String>,
        hardwareMaxKhz: Map<Int, Long>
    ) {
        val base = SnapdragonKernelNodes.MSM_PERFORMANCE_PARAMETERS
        val touchNode = "$base/${SnapdragonKernelNodes.TOUCHBOOST}"
        readSysfsNode(touchNode)?.let { now ->
            if (SnapdragonKernelNodes.enableIfZero(now) != null) priors[touchNode] = now
        }
        val capNode = "$base/${SnapdragonKernelNodes.CPU_MAX_FREQ}"
        readSysfsNode(capNode)?.let { now ->
            if (SnapdragonKernelNodes.raiseExistingCapPairs(now, hardwareMaxKhz).isNotEmpty()) {
                priors[capNode] = now
            }
        }
    }

    /**
     * `core_ctl` priors: every cluster's `min_cpus` and `max_cpus`, recorded only where
     * [SnapdragonKernelNodes.clusterRaise] would raise them toward the cluster's real core count
     * (`related_cpus` of the policy owning the directory). Its hotplug heuristics
     * (`busy_up_thres`, `busy_down_thres`, `offline_delay_ms`, `task_thres`, `enable`) are never
     * recorded because no rule names them.
     */
    private suspend fun captureCoreCtlPriors(
        priors: MutableMap<String, String>,
        cpuSets: Map<String, Set<Int>>
    ) {
        for (dir in readCoreCtlDirs(cpuSets.values.flatten().toSet())) {
            val clusterSize = coreCtlCpu(dir)?.let { cpu -> cpuSets.values.firstOrNull { cpu in it }?.size }
                ?: continue
            for (name in listOf(SnapdragonKernelNodes.MIN_CPUS, SnapdragonKernelNodes.MAX_CPUS)) {
                val node = "$dir/$name"
                readSysfsNode(node)?.let { now ->
                    if (SnapdragonKernelNodes.clusterRaise(now, clusterSize) != null) priors[node] = now
                }
            }
        }
    }

    /**
     * `cpu_boost` priors: the module's master switch and the per-CPU input boost frequencies. The
     * frequencies are recorded only when the device declares a boost window
     * ([SnapdragonKernelNodes.INPUT_BOOST_MS] > 0) — a boost with no duration never happens, so
     * raising its frequencies would change nothing while looking like a success — and only where
     * [SnapdragonKernelNodes.raiseBoostPairs] would actually raise them. The window itself is never
     * written: its length is the device's own tuning, and a raise would be an invented constant.
     */
    private suspend fun captureCpuBoostPriors(
        priors: MutableMap<String, String>,
        hardwareMaxKhz: Map<Int, Long>
    ) {
        val base = SnapdragonKernelNodes.CPU_BOOST_PARAMETERS
        val enabledNode = "$base/${SnapdragonKernelNodes.INPUT_BOOST_ENABLED}"
        readSysfsNode(enabledNode)?.let { now ->
            if (SnapdragonKernelNodes.enableIfZero(now) != null) priors[enabledNode] = now
        }
        val window = readSysfsNode("$base/${SnapdragonKernelNodes.INPUT_BOOST_MS}")?.toLongOrNull()
        if (window == null || window <= 0L) return
        val freqNode = "$base/${SnapdragonKernelNodes.INPUT_BOOST_FREQ}"
        readSysfsNode(freqNode)?.let { now ->
            if (SnapdragonKernelNodes.raiseBoostPairs(now, hardwareMaxKhz).isNotEmpty()) priors[freqNode] = now
        }
    }

    /**
     * Applies the Snapdragon widen-only channel and confirms every write by read-back. The channel
     * is [SnapdragonCpuWiden] (policy ceilings raised to each policy's own hardware max;
     * game-visible cpusets unioned toward the full set) plus [SnapdragonKernelNodes] (the schedutil
     * up rate limiter removed and the hispeed jump raised; `msm_performance`'s touch boost enabled
     * and its declared CPU ceilings raised; `core_ctl` floors and ceilings raised to each cluster's
     * real core count; `cpu_boost`'s input boost enabled and its per-CPU frequencies raised toward
     * each policy's max).
     *
     * @return null when [priors] is empty — nothing widenable, not applicable — false when root is
     *   unavailable or any attempted node refused, true only when every attempted node read back at
     *   its target. Null as well when the priors evaporated before anything was attempted (a thermal
     *   daemon moving ceilings between capture and apply): reporting "widened" with zero writes
     *   would manufacture a success, and "refused" would blame the device for a race — not
     *   applicable is the honest answer either way. Writes stay strictly inside the snapshot priors:
     *   a node the capture never recorded is never written, even if a fresh read would widen it, and
     *   every target is recomputed from the device's live state at write time. One family's refusal
     *   never blocks the writes of the others; it does make the whole row read as refused, because
     *   the row is one switch.
     */
    private suspend fun applySnapdragonCpuMode(priors: Map<String, String>): Boolean? {
        if (priors.isEmpty()) return null
        if (!shellRunner.isRootAvailable()) return false
        val policies = readSnapdragonPolicyStates()
        val cpuSets = readPolicyCpuSets()
        val hardwareMaxKhz = hardwareMaxByCpu(policies, cpuSets)
        val outcomes = listOfNotNull(
            applyPolicyCeilings(priors, policies),
            applyCpusets(priors),
            applySchedutilNodes(priors, policies),
            applySwitchNode(priors, "${SnapdragonKernelNodes.MSM_PERFORMANCE_PARAMETERS}/" +
                SnapdragonKernelNodes.TOUCHBOOST),
            applyMsmCpuCaps(priors, hardwareMaxKhz),
            applyCoreCtlNodes(priors, cpuSets),
            applySwitchNode(priors, "${SnapdragonKernelNodes.CPU_BOOST_PARAMETERS}/" +
                SnapdragonKernelNodes.INPUT_BOOST_ENABLED),
            applyCpuBoostFreqNode(priors, hardwareMaxKhz)
        )
        if (outcomes.isEmpty()) return null
        return outcomes.all { it }
    }

    /** Policy ceilings: every captured policy's `scaling_max_freq` widened to its own hardware max. */
    private suspend fun applyPolicyCeilings(
        priors: Map<String, String>,
        policies: Map<String, SnapdragonCpuWiden.PolicyState>
    ): Boolean? = applyNodePlan(
        SnapdragonCpuWiden.policyMaxWritePlan(
            policies.filterKeys { dir ->
                priors.containsKey("$dir/${SnapdragonCpuWiden.SCALING_MAX_FREQ}")
            }
        )
    )

    /** Game-visible cpusets, unioned toward the kernel's present set. */
    private suspend fun applyCpusets(priors: Map<String, String>): Boolean? {
        val fullSet = readSysfsNode(CPU_PRESENT_NODE)
            ?.let(SnapdragonCpuWiden::parseCpuSet).orEmpty()
        return applyNodePlan(SnapdragonCpuWiden.cpusetWritePlan(priors, fullSet))
    }

    /**
     * schedutil writes: the up rate limiter removed to 0, the hispeed jump raised to the policy's
     * own hardware max (only where the device still declares a 1..100 `hispeed_load`) and the iowait
     * boost turned on. Every target is recomputed from a fresh read, and a node the prior map does
     * not carry is skipped — so a value that moved between capture and apply is never overwritten
     * with a stale plan.
     */
    private suspend fun applySchedutilNodes(
        priors: Map<String, String>,
        policies: Map<String, SnapdragonCpuWiden.PolicyState>
    ): Boolean? {
        val plan = linkedMapOf<String, String>()
        for ((policyDir, state) in policies) {
            val dir = "$policyDir/${SnapdragonKernelNodes.SCHEDUTIL_DIR_NAME}"
            val upNode = "$dir/${SnapdragonKernelNodes.UP_RATE_LIMIT_US}"
            if (priors.containsKey(upNode)) {
                SnapdragonKernelNodes.noUpRateLimit(readSysfsNode(upNode))?.let { plan[upNode] = it }
            }
            val hispeedNode = "$dir/${SnapdragonKernelNodes.HISPEED_FREQ}"
            if (priors.containsKey(hispeedNode)) {
                val load = readSysfsNode("$dir/${SnapdragonKernelNodes.HISPEED_LOAD}")
                SnapdragonKernelNodes.hispeedTarget(readSysfsNode(hispeedNode), state.availableKhz, load)
                    ?.let { plan[hispeedNode] = it }
            }
            val iowaitNode = "$dir/${SnapdragonKernelNodes.IOWAIT_BOOST_ENABLE}"
            if (priors.containsKey(iowaitNode)) {
                SnapdragonKernelNodes.enableIfZero(readSysfsNode(iowaitNode))?.let { plan[iowaitNode] = it }
            }
        }
        return applyNodePlan(plan)
    }

    /**
     * One enable-if-zero node: written only when the snapshot recorded it and it still declares 0.
     * Null — no write — when the node was never captured, is already on, or does not parse as 0/1.
     */
    private suspend fun applySwitchNode(priors: Map<String, String>, node: String): Boolean? {
        if (!priors.containsKey(node)) return null
        val target = SnapdragonKernelNodes.enableIfZero(readSysfsNode(node)) ?: return null
        return applyNodePlan(mapOf(node to target))
    }

    /**
     * `msm_performance/cpu_max_freq`: every declared per-CPU ceiling below its own policy's hardware
     * max raised to that max (only the raised entries are written; the kernel merges them into the
     * list it holds) and confirmed by a semantic read-back. Null when the node was never captured or
     * holds no declared ceiling below the hardware max.
     */
    private suspend fun applyMsmCpuCaps(
        priors: Map<String, String>,
        hardwareMaxKhz: Map<Int, Long>
    ): Boolean? {
        val node = "${SnapdragonKernelNodes.MSM_PERFORMANCE_PARAMETERS}/" +
            SnapdragonKernelNodes.CPU_MAX_FREQ
        if (!priors.containsKey(node)) return null
        val targets = SnapdragonKernelNodes.raiseExistingCapPairs(readSysfsNode(node), hardwareMaxKhz)
        if (targets.isEmpty()) return null
        return writeSysfsPairsVerified(node, targets)
    }

    /**
     * `core_ctl` writes: every cluster's `min_cpus`/`max_cpus` raised toward its real core count
     * (`related_cpus`), one shared plan for the whole family. Null when no captured node still has
     * headroom.
     */
    private suspend fun applyCoreCtlNodes(
        priors: Map<String, String>,
        cpuSets: Map<String, Set<Int>>
    ): Boolean? {
        val plan = linkedMapOf<String, String>()
        for (dir in readCoreCtlDirs(cpuSets.values.flatten().toSet())) {
            val clusterSize = coreCtlCpu(dir)?.let { cpu -> cpuSets.values.firstOrNull { cpu in it }?.size }
                ?: continue
            for (name in listOf(SnapdragonKernelNodes.MIN_CPUS, SnapdragonKernelNodes.MAX_CPUS)) {
                val node = "$dir/$name"
                if (!priors.containsKey(node)) continue
                SnapdragonKernelNodes.clusterRaise(readSysfsNode(node), clusterSize)?.let { plan[node] = it }
            }
        }
        return applyNodePlan(plan)
    }

    /**
     * `cpu_boost/input_boost_freq`: every per-CPU boost frequency below its own policy's hardware
     * max raised to that max, gated on the device still declaring a boost window
     * (`input_boost_ms` > 0). Null when the node was never captured, the window is gone, or nothing
     * has headroom.
     */
    private suspend fun applyCpuBoostFreqNode(
        priors: Map<String, String>,
        hardwareMaxKhz: Map<Int, Long>
    ): Boolean? {
        val base = SnapdragonKernelNodes.CPU_BOOST_PARAMETERS
        val node = "$base/${SnapdragonKernelNodes.INPUT_BOOST_FREQ}"
        if (!priors.containsKey(node)) return null
        val window = readSysfsNode("$base/${SnapdragonKernelNodes.INPUT_BOOST_MS}")?.toLongOrNull()
        if (window == null || window <= 0L) return null
        val targets = SnapdragonKernelNodes.raiseBoostPairs(readSysfsNode(node), hardwareMaxKhz)
        if (targets.isEmpty()) return null
        return writeSysfsPairsVerified(node, targets)
    }

    /**
     * Writes one plain node→value plan and counts the read-back. Null when the plan is empty —
     * "nothing to write", which callers merge as not applicable rather than as success or refusal.
     */
    private suspend fun applyNodePlan(plan: Map<String, String>): Boolean? {
        if (plan.isEmpty()) return null
        var attempted = 0
        var confirmed = 0
        for ((node, target) in plan) {
            attempted++
            if (writeSysfsVerified(node, target)) confirmed++
        }
        return confirmed == attempted
    }

    /**
     * Puts the Snapdragon nodes back to their snapshot priors — policy ceilings, cpusets, schedutil
     * rate limits and jumps, `msm_performance` switches and ceilings, `core_ctl` floors/ceilings and
     * `cpu_boost` switches and frequencies alike. Only nodes the snapshot recorded are touched;
     * root-gated like the apply — without it there is nothing that could have changed them through
     * this path. Best-effort like [restoreMediaTekGameNodes]: a refused restore is left as-is and
     * surfaces in the deactivation report rather than retried blindly.
     */
    private suspend fun restoreSnapdragonCpuNodes(priors: Map<String, String>) {
        if (priors.isEmpty() || !shellRunner.isRootAvailable()) return
        for ((node, prior) in priors) {
            // Comma- and colon-aware gate (not the shared one: cpuset priors like `0-5,7` need
            // commas and per-CPU boost priors like `0:0 4:1804800` need colons) — a refused prior is
            // left as-is and surfaces in the deactivation report rather than retried blindly.
            if (SnapdragonCpuWiden.isSafeNodeValue(prior)) {
                shellRunner.execSafeResult("sh", "-c", "echo $prior > $node")
            }
        }
    }

    /** Re-reads the PPM status dump and checks it describes exactly [expected] states. */
    private suspend fun verifyPpmStates(expected: List<Pair<Int, Int>>): Boolean {
        val dump = readSysfsNode(MediaTekGameMode.PPM_POLICY_STATUS_NODE) ?: return false
        val actual = MediaTekGameMode.ppmRestoreWrites(dump)
            .mapNotNull { line ->
                val parts = line.split(" ")
                if (parts.size != 2) null
                else {
                    val idx = parts[0].toIntOrNull()
                    val on = parts[1].toIntOrNull()
                    if (idx == null || on == null) null else idx to on
                }
            }
            .toMap()
        return expected.all { (idx, on) -> actual[idx] == on }
    }

    /** One sysfs/proc node read, or null when unreadable or blank. */
    private suspend fun readSysfsNode(node: String): String? {
        val result = shellRunner.execSafeResult("cat", node)
        return if (result.isSuccess) result.stdout.trim().takeIf { it.isNotEmpty() } else null
    }

    /**
     * The kernel's cpufreq policies as widening states: current ceiling plus the
     * frequency table, both read through the privileged shell. A policy whose
     * nodes do not read is skipped — it can be neither verified nor restored,
     * so it must not be written either.
     */
    private suspend fun readSnapdragonPolicyStates(): Map<String, SnapdragonCpuWiden.PolicyState> {
        val states = linkedMapOf<String, SnapdragonCpuWiden.PolicyState>()
        val listing = shellRunner.execSafeResult("ls", CPUFREQ_DIR)
        if (!listing.isSuccess) return states
        val dirs = listing.stdout.split(WHITESPACE)
            .filter { it.startsWith("policy") }
            .map { "$CPUFREQ_DIR/$it" }
        for (dir in dirs) {
            val current = readSysfsNode("$dir/${SnapdragonCpuWiden.SCALING_MAX_FREQ}")
                ?.toLongOrNull() ?: continue
            val available = readSysfsNode("$dir/${SnapdragonCpuWiden.SCALING_AVAILABLE_FREQUENCIES}")
                ?.let(KernelInfo::parseFreqTable).orEmpty()
            states[dir] = SnapdragonCpuWiden.PolicyState(current, available)
        }
        return states
    }

    /**
     * The kernel's cpufreq policies as `<policyDir> → related CPU ids`, read through the privileged
     * shell. A policy whose `related_cpus` does not read or parse contributes no CPUs: without the
     * kernel's own cluster membership it can gate neither a `core_ctl` raise nor a per-CPU
     * frequency target, and guessing one could invert the widen into a narrowing.
     */
    private suspend fun readPolicyCpuSets(): Map<String, Set<Int>> {
        val sets = linkedMapOf<String, Set<Int>>()
        val listing = shellRunner.execSafeResult("ls", CPUFREQ_DIR)
        if (!listing.isSuccess) return sets
        for (entry in listing.stdout.split(WHITESPACE).filter { it.startsWith("policy") }) {
            val dir = "$CPUFREQ_DIR/$entry"
            val cpus = readSysfsNode("$dir/${SnapdragonCpuWiden.RELATED_CPUS}")
                ?.let(SnapdragonCpuWiden::parseCpuSet).orEmpty()
            if (cpus.isNotEmpty()) sets[dir] = cpus
        }
        return sets
    }

    /**
     * Each CPU's own policy hardware max in kHz — `cpu → max(scaling_available_frequencies)` of the
     * policy the kernel reports it in — the raise target for the per-CPU `cpu:freq` nodes. A policy
     * without a parseable table contributes nothing, so its CPUs are never written.
     */
    private fun hardwareMaxByCpu(
        policies: Map<String, SnapdragonCpuWiden.PolicyState>,
        cpuSets: Map<String, Set<Int>>
    ): Map<Int, Long> {
        val maxByCpu = linkedMapOf<Int, Long>()
        for ((dir, cpus) in cpuSets) {
            val hardwareMax = policies[dir]?.availableKhz?.maxOrNull() ?: continue
            if (hardwareMax <= 0L) continue
            for (cpu in cpus) maxByCpu[cpu] = hardwareMax
        }
        return maxByCpu
    }

    /**
     * The `core_ctl` directories the device has, asked for by candidate path in one `ls -d` call.
     * A kernel without core_ctl prints nothing (and exits non-zero), which is exactly the empty
     * answer; a directory that answers is kept only when the path really names a `core_ctl` node.
     */
    private suspend fun readCoreCtlDirs(cpus: Set<Int>): List<String> {
        if (cpus.isEmpty()) return emptyList()
        val candidates = cpus.sorted().map {
            "/sys/devices/system/cpu/cpu$it/${SnapdragonKernelNodes.CORE_CTL_DIR_NAME}"
        }
        val listing = shellRunner.execSafeResult("ls", "-d", *candidates.toTypedArray())
        return listing.stdout.split(WHITESPACE)
            .filter { it.endsWith("/${SnapdragonKernelNodes.CORE_CTL_DIR_NAME}") }
            .sorted()
    }

    /** The CPU a `core_ctl` directory sits under (`…/cpu4/core_ctl` → 4), or null when unnamed. */
    private fun coreCtlCpu(dir: String): Int? =
        dir.substringBeforeLast("/${SnapdragonKernelNodes.CORE_CTL_DIR_NAME}")
            .substringAfterLast("/")
            .removePrefix("cpu")
            .toIntOrNull()

    /** Writes one sysfs value and confirms it by read-back. Root-only (see callers). */
    private suspend fun writeSysfsVerified(node: String, value: String): Boolean {
        if (!isSafeSysfsValue(value)) return false
        shellRunner.execSafeResult("sh", "-c", "echo $value > $node")
        return readSysfsNode(node) == value
    }

    /**
     * Writes a per-CPU `cpu:freq` body — only the entries being raised; the kernel merges them into
     * the list it holds — and confirms it by a *semantic* read-back: the node must parse back with
     * every planned CPU at its target. The body is digits, colons and spaces only, so it needs no
     * quoting. Root-only (see callers). False for an empty plan: a "confirmed" with nothing written
     * would be a manufactured success.
     */
    private suspend fun writeSysfsPairsVerified(node: String, targets: Map<Int, Long>): Boolean {
        if (targets.isEmpty()) return false
        val body = SnapdragonKernelNodes.formatFreqPairs(targets)
        shellRunner.execSafeResult("sh", "-c", "echo $body > $node")
        val after = readSysfsNode(node)?.let(SnapdragonKernelNodes::parseFreqPairs).orEmpty()
        return targets.all { (cpu, target) -> after[cpu] == target }
    }

    /**
     * Whether [value] is safe to embed in a `sh -c 'echo … > node'` script. Node values are
     * numbers and short tokens in practice; anything with shell metacharacters is refused
     * rather than quoted-and-hoped.
     */
    private fun isSafeSysfsValue(value: String): Boolean =
        value.isNotBlank() && value.all { it.isLetterOrDigit() || it in "._- " }

    /** @return the property's value, or "" when it is not set or could not be read. */
    private suspend fun readProp(key: String): String {
        val result = shellRunner.execSafeResult("getprop", key)
        return if (result.isSuccess) result.stdout.trim() else ""
    }

    /**
     * `setprop` prints nothing on success and its refusal goes to stderr, so — like
     * `settings put` — the read-back is the only honest signal. Root only: a shell-uid Shizuku
     * session cannot write vendor properties, and that surfaces here as a refusal rather than
     * as silence.
     */
    private suspend fun setPropVerified(key: String, value: String): Boolean {
        shellRunner.execSafeResult("setprop", key, value)
        return readProp(key) == value
    }

    /** A system property as a [SettingValue]: blank is `existed = false`, a failed read is null. */
    private suspend fun propSettingValue(key: String): SettingValue? {
        val result = shellRunner.execSafeResult("getprop", key)
        if (!result.isSuccess) return null
        val value = result.stdout.trim()
        return if (value.isEmpty()) SettingValue("", existed = false) else SettingValue(value, existed = true)
    }

    /** Puts a property back, only when the snapshot actually recorded it as existing. */
    private suspend fun restoreProp(key: String, sv: SettingValue?) {
        if (sv == null || !sv.existed || sv.value.isBlank()) return
        shellRunner.execSafeResult("setprop", key, sv.value)
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
            val unavailable = mutableListOf<GamingModeNotice>()
            // Same shared lock as activation (F3) — always the panel maximum.
            val lock = refreshController.lockToPeak()
            val maxHz = lock.lockedHz
            val peakOk = lock.peakOk
            val minOk = lock.minOk
            val lockedHz = if (peakOk || minOk) maxHz else null
            if (lockedHz == null) {
                unavailable += GamingModeNotice.Res(R.string.gt_eng_un_refresh)
            }
            // The vivo display-mode half re-asserted under the same rule as everything else in
            // this recovery: the snapshot still holds the device's originals, so the eventual
            // deactivation puts them back either way.
            val vivoLock = refreshController.lockVendorDisplayMode()
            if (vivoLock.applicable && !vivoLock.ok) {
                unavailable += GamingModeNotice.Res(R.string.gt_eng_un_vivo)
            }

            val touchOk = putSettingVerified("touch_response_speed", "2")
            if (!touchOk) unavailable += GamingModeNotice.Res(R.string.gt_eng_un_touch)

            // The touch-speed choice, re-asserted like everything else here — the snapshot still
            // holds the user's original, so the eventual deactivation restores it either way.
            var pointerSpeedApplied: Int? = null
            PointerSpeed.sanitize(_pointerSpeedChoice.value)?.let { speed ->
                if (putSettingVerified("pointer_speed", speed.toString())) {
                    pointerSpeedApplied = speed
                } else {
                    unavailable += GamingModeNotice.Res(R.string.gt_eng_un_pointer, listOf(speed.toString()))
                }
            }

            val fixedPerfOk = shellRunner
                .execSafeResult(*PrivilegeCommands.fixedPerformanceArgs(true))
                .isSuccess
            if (!fixedPerfOk) unavailable += GamingModeNotice.Res(R.string.gt_eng_un_fixed)
            // The Qualcomm GPU switch too, under the same re-assert-everything rule as the rest
            // of this recovery — the snapshot still holds the vendor's originals, so the eventual
            // deactivation restores them either way.
            val gpuPerfOk = applyVendorGpuPerformance()
            if (gpuPerfOk == false) {
                unavailable += GamingModeNotice.Res(R.string.gt_eng_un_gpu)
            }
            // The game FPS hint with the same rule; maxHz above is this run's measured peak.
            val qtiFpsOk = applyQtiGameFpsHint(maxHz)
            if (qtiFpsOk == false) {
                unavailable += GamingModeNotice.Res(R.string.gt_eng_un_qti)
            }
            // MediaTek knobs with the same re-assert rule; the snapshot still holds the
            // vendor's originals, so the eventual deactivation restores them either way.
            val mtkOk = applyMediaTekGameMode(readLastSnapshot()?.mediatekGameNodes.orEmpty())
            if (mtkOk == false) {
                unavailable += GamingModeNotice.Res(R.string.gt_eng_un_mtk)
            }
            // Snapdragon widen-only channel with the same re-assert rule; the snapshot
            // still holds the kernel's originals, so deactivation restores them either way.
            val snapOk = applySnapdragonCpuMode(readLastSnapshot()?.snapdragonCpuNodes.orEmpty())
            if (snapOk == false) {
                unavailable += GamingModeNotice.Res(R.string.gt_eng_un_snap)
            }
            execute(PrivilegeCommands.DEVICE_IDLE_FORCE)

            // OEM specific recovery
            if (isVivoOrIqoo()) {
                execute("cmd thermalservice reset")
            }

            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val dndEngaged = nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_NONE

            // Re-assert the two developer options and read them back. The snapshot still holds the
            // user's originals from the previous process, so deactivation restores those either way.
            val discardOk = toggleAlwaysFinishActivities(true)
            if (!discardOk) {
                unavailable += GamingModeNotice.Res(R.string.gt_eng_un_discard)
            }
            val processLimitOk = toggleBackgroundProcessLimit(true)
            if (!processLimitOk) {
                unavailable += GamingModeNotice.Res(R.string.gt_eng_un_limit)
            }

            val snapshot = prefs.getString("last_snapshot", null)
                ?.let { GamingOptimizationSnapshot.fromJson(it) }

            // Report the real netpolicy state rather than re-issuing the block: whatever this app set
            // before the restart is still in the kernel's uid rules, since netpolicy survives a
            // process death. Re-engaging would also overwrite the "was it already on" bookkeeping.
            val backgroundDataRestricted: Boolean? = when {
                snapshot?.backgroundDataRestrictedBefore == true -> null
                else -> runCatching { backgroundDataRestrictor.status().restrictedUids.isNotEmpty() }
                    .getOrDefault(false)
            }

            val pkg = snapshot?.activeGamePackage
            var networkWhitelisted: Boolean? = null
            if (pkg != null) {
                execute("pm unsuspend --user 0 $pkg")
                execute("cmd activity set-bg-restriction-level --user 0 $pkg unrestricted")
                execute("am set-standby-bucket --user 0 $pkg active")
                execute(PrivilegeCommands.deviceIdleWhitelistAdd(pkg))
                networkWhitelisted = snapshot.activeGameUid?.let { isUidWhitelisted(it) } == true
            }

            _isFixedPerformanceMode.value = fixedPerfOk
            _report.value = GamingModeReport(
                fixedPerformance = fixedPerfOk,
                gpuPerformanceMode = gpuPerfOk,
                qtiGameFps = qtiFpsOk,
                mediaTekGameMode = mtkOk,
                snapdragonCpuMode = snapOk,
                lockedRefreshHz = lockedHz,
                touchResponseBoost = touchOk,
                pointerSpeed = pointerSpeedApplied,
                // The suspends happened in the previous process; this set is the record of them and
                // is exactly what disableGamingMode will unsuspend.
                suspendedPackages = prefs.getStringSet("affected_pkgs", emptySet())?.size ?: 0,
                dndEngaged = dndEngaged,
                networkWhitelisted = networkWhitelisted,
                discardActivities = discardOk,
                processLimit = processLimitOk,
                backgroundDataRestricted = backgroundDataRestricted,
                unavailable = unavailable
            )
        } catch (_: Exception) {}
    }

    private suspend fun reapplyFixedPerformanceMode() {
        val applied = runCatching {
            shellRunner.execSafeResult(*PrivilegeCommands.fixedPerformanceArgs(true)).isSuccess
        }.getOrDefault(false)
        _isFixedPerformanceMode.value = applied
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")

        /** Packages per `pm unsuspend` fork in the deactivation sweep; mirrors UID_BATCH_SIZE. */
        private const val UNSUSPEND_BATCH_SIZE = 25

        /** Qualcomm's vendor GPU mode knob, and its texture-quality companion. */
        const val VENDOR_GPU_MODE = "vendor.gpu.mode"
        const val VENDOR_GFX_LOW_QUALITY = "vendor.gfx.low_quality"

        /**
         * Qualcomm's game FPS hint, set by Gaming Mode to the panel's measured peak. The
         * `persist.vendor.qti.game.fps` twin from the reference's `post-fs-data.sh` is
         * deliberately absent — see [GamingModeReport.qtiGameFps].
         */
        const val DEBUG_VENDOR_QTI_GAME_FPS = "debug.vendor.qti.game.fps"

        /** cpufreq policy root enumerating one dir per CPU cluster (`policy0`, …). */
        const val CPUFREQ_DIR = "/sys/devices/system/cpu/cpufreq"

        /** Kernel-reported present CPUs (`0-7`), the widening target for game cpusets. */
        const val CPU_PRESENT_NODE = "/sys/devices/system/cpu/present"
    }
}
