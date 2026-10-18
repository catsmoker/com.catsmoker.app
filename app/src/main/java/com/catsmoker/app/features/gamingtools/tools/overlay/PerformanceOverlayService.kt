package com.catsmoker.app.features.gamingtools.tools.overlay

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import com.catsmoker.app.R
import com.catsmoker.app.features.main.engine.MetricsEngine
import com.catsmoker.app.shared.data.model.FpsSource
import com.catsmoker.app.shared.data.model.MetricReadStatus
import com.catsmoker.app.shared.util.CatsmokerNotifications
import com.catsmoker.app.shared.util.DisplayMetricsProvider
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import javax.inject.Inject

/**
 * The performance readout window, and its reposition (move) mode.
 *
 * The window is `FLAG_NOT_TOUCHABLE` outside move mode so taps in its area still reach the game,
 * which is why dragging is a mode rather than a permanent property — the crosshair service's
 * reasoning, and its mechanics, apply here verbatim. Move mode toggles only the touchability flag
 * and shows an outline; exits are the in-app button and the notification's Done action, so a
 * dragged panel can always be released even mid-game.
 *
 * The position is absolute from the top-left corner in [PerformanceOverlayPositionStore], and is
 * clamped to the display on the way in as well as on the way out — a stored value can be stale by
 * the time it is read, because the app's own Resolution Changer can have shrunk the panel between
 * drags. Screen size comes from [DisplayMetricsProvider], the one owner of that question in this
 * codebase; if it cannot read the display, or the view has not been measured, the drag is left
 * unclamped rather than clamped against an invented number.
 */
@AndroidEntryPoint
class PerformanceOverlayService : Service() {

    @Inject
    lateinit var metricsEngine: MetricsEngine

    @Inject
    lateinit var positionStore: PerformanceOverlayPositionStore

    @Inject
    lateinit var displayMetricsProvider: DisplayMetricsProvider

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }

    private var isMoveMode = false

    /** Slop below which a touch is a tap, not a drag — the platform's own threshold. */
    private var touchSlop = 0

    /**
     * The legal travel range for the window, or null when the display or the view size could not be
     * read. Cached because [clampToDisplay] runs on every move event; refreshed by
     * [refreshDragBounds] at the only points the answer can change.
     */
    private var dragBounds: OverlayPosition.Bounds? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every path calls startForeground, including the mode actions. Those arrive as fresh
        // onStartCommand calls on a service started with startForegroundService, and Android kills a
        // service that does not post its notification — so entering move mode must not skip it.
        startForeground(NOTIFICATION_ID, buildNotification())
        CatsmokerNotifications.attachOwner(this, "PerfOverlay")

        // Move mode is delivered as an action on the running service rather than a fresh start, so
        // a toggle must not rebuild the overlay and lose the position mid-drag.
        when (intent?.action) {
            ACTION_STOP -> {
                // The notification's Stop action. onDestroy removes the window, stops the metrics
                // engine, and reports ACTION_SERVICE_STOPPED — the same teardown the in-app
                // switch's stopService gets. Handled before the rest so it can never re-show the
                // overlay.
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_ENTER_MOVE_MODE -> {
                // Re-shows a hidden window (there is nothing to drag otherwise), or runs the full
                // start when this action was the first intent, so move mode can never be on with
                // nothing on screen or a frozen readout.
                if (!isRunning) startOverlay() else if (overlayView == null) showOverlay()
                setMoveMode(true)
                return START_NOT_STICKY
            }

            ACTION_EXIT_MOVE_MODE -> {
                setMoveMode(false)
                return START_NOT_STICKY
            }

            ACTION_RESET_POSITION -> {
                positionStore.clear()
                applyStoredPosition()
                return START_NOT_STICKY
            }

            ACTION_TOGGLE -> {
                // The notification's quick-toggle: hide the window but keep the service (and the
                // metrics engine) alive, or bring it back. Deliberately not a stop — a gamer hiding
                // the readout mid-match wants it one tap away, not a cold start away. The
                // notification is rebuilt so the action's label follows the new state.
                if (overlayView != null) {
                    // Leaving move mode first: hiding the window while "moving" would leave the
                    // mode claiming to take taps for a window that is no longer there.
                    setMoveMode(false)
                    hideOverlay()
                } else {
                    showOverlay()
                }
                getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification())
                return START_NOT_STICKY
            }
        }

        startOverlay()
        return START_NOT_STICKY
    }

    /**
     * The full start sequence: window, metrics engine, collector, and the persisted on-flag the
     * boot receiver reads. Split out so the move-mode action can bring a cold service to a fully
     * working state instead of a windowless or frozen one.
     */
    private fun startOverlay() {
        showOverlay()
        metricsEngine.start()
        startUpdating()
        isRunning = true
        // Remember that the overlay was deliberately on, so the boot receiver can bring it back
        // after a reboot or an app update. Cleared in onDestroy — the one teardown every stop
        // path funnels through.
        prefs.edit { putBoolean(KEY_WAS_RUNNING, true) }
        sendBroadcast(Intent(ACTION_SERVICE_STARTED).setPackage(packageName))
    }

    private fun showOverlay() {
        // No parent exists: the view is attached to the WindowManager, not a ViewGroup.
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_performance, null)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flagsFor(moveMode = isMoveMode),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = positionStore.x
            y = positionStore.y
        }
        // addView can throw (a revoked draw-over-apps grant, a window-type refusal); the window is
        // only recorded once it is actually attached, so the toggle's label never lies about it.
        runCatching { windowManager?.addView(view, params) }
            .onSuccess {
                overlayView = view
                overlayParams = params
                attachDragHandler(view)
                if (isMoveMode) view.foreground = getDrawable(R.drawable.bg_perf_move_halo)
                // Re-clamped once the view has been measured, not against the stored value alone:
                // the position can have been saved at a resolution the app's own Resolution Changer
                // has since replaced, and the view's own size is part of the legal range.
                view.post { applyStoredPosition() }
            }
    }

    /** The quick-toggle's other half: window gone, service and metrics still running. */
    private fun hideOverlay() {
        overlayView?.let { view ->
            runCatching { windowManager?.removeView(view) }
        }
        overlayView = null
        overlayParams = null
    }

    private fun startUpdating() {
        serviceScope.launch {
            // Resolved on every emission, not captured once: the quick-toggle replaces the
            // window, and a collector holding the old view's TextViews would keep updating a
            // detached view while the fresh one sat blank.
            metricsEngine.state.collect { state ->
                val fpsView = overlayView?.findViewById<TextView>(R.id.fpsNumber)
                val cpuView = overlayView?.findViewById<TextView>(R.id.cpuNumber)
                val clusterView = overlayView?.findViewById<TextView>(R.id.clusterNumber)
                val powerView = overlayView?.findViewById<TextView>(R.id.powerNumber)
                val ramView = overlayView?.findViewById<TextView>(R.id.ramNumber)
                val tempView = overlayView?.findViewById<TextView>(R.id.tempNumber)
                val headroomView = overlayView?.findViewById<TextView>(R.id.headroomNumber)
                // Frames per second, labelled by where the number came from: the vsync fallback
                // measures this app's frames, not the game's, and must not be passed off as FPS.
                val fpsLabel = if (state.fpsSource == FpsSource.Choreographer) "UI FPS" else "FPS"
                fpsView?.text = row(fpsLabel, state.fps?.toString(), state.fpsReadStatus)
                cpuView?.text = row("CPU", state.cpuPercentage?.let { "$it%" }, state.cpuReadStatus)
                clusterView?.text = row("Clus", clusterText(state), state.cpuClusterReadStatus)
                powerView?.text = row(
                    "Power",
                    state.powerW?.let { String.format(Locale.US, "%.2f W", it) },
                    state.powerReadStatus
                )
                ramView?.text = row(
                    "RAM",
                    state.ramUsedGb?.let { used ->
                        val total = state.ramTotalGb
                        if (total != null) {
                            String.format(Locale.US, "%.1f / %.1f GB", used, total)
                        } else {
                            String.format(Locale.US, "%.1f GB", used)
                        }
                    },
                    state.ramReadStatus
                )
                tempView?.text = row(
                    if (state.displayTempIsSoc) "SoC" else "Batt",
                    state.displayTempC?.let { String.format(Locale.US, "%.1f°C", it) },
                    state.displayTempReadStatus
                )
                headroomView?.text = row(
                    "Head",
                    state.thermalHeadroom?.let { "${(it * 100).toInt()}%" },
                    state.thermalHeadroomStatus
                )
            }
        }
    }

    /**
     * One overlay line.
     *
     * With no [value] the metric's own reason is printed — "needs root/Shizuku", "unsupported" —
     * because a 0 or a dash would claim a reading the device never gave.
     */
    private fun row(label: String, value: String?, status: MetricReadStatus): String =
        "$label: ${value ?: getString(status.labelRes)}"

    /**
     * Per-cluster MHz in the reference's compact shape (`U:2.8 P:2.2 E:1.6`, GHz with one
     * decimal), ultra first. Tiers the kernel did not place are omitted, not zeroed; all
     * missing means the row reports its own reason instead.
     */
    private fun clusterText(state: com.catsmoker.app.shared.data.model.MetricsState): String? {
        fun ghz(mhz: Int?): String? = mhz?.let { String.format(Locale.US, "%.1f", it / 1000f) }
        val parts = listOf(
            state.cpuClusterUltraMhz?.let { "U${ghz(it)}" },
            state.cpuClusterPerfMhz?.let { "P${ghz(it)}" },
            state.cpuClusterEffMhz?.let { "E${ghz(it)}" }
        ).filterNotNull()
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    /**
     * Window flags for a mode.
     *
     * `NOT_FOCUSABLE` is kept in both: the readout must never take the IME or the back key from the
     * game. Only touchability changes — which is the whole of the difference between the two modes.
     */
    private fun flagsFor(moveMode: Boolean): Int {
        val base = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        return if (moveMode) base else base or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
    }

    private fun setMoveMode(enabled: Boolean) {
        if (isMoveMode == enabled) return
        isMoveMode = enabled

        val view = overlayView
        val params = overlayParams
        if (view != null && params != null) {
            params.flags = flagsFor(moveMode = enabled)
            runCatching { windowManager?.updateViewLayout(view, params) }
            // The outline is the only on-screen difference between the modes, so the panel cannot be
            // silently taking taps with no cue that it will keep doing so.
            view.foreground = if (enabled) getDrawable(R.drawable.bg_perf_move_halo) else null
        }

        // The notification's action is the backstop exit from move mode, so it has to track the mode.
        runCatching {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, buildNotification())
        }

        isInMoveMode = enabled
        sendBroadcast(
            Intent(ACTION_MOVE_MODE_CHANGED)
                .setPackage(packageName)
                .putExtra(EXTRA_MOVE_MODE, enabled)
        )
    }

    private fun attachDragHandler(view: View) {
        // Params are absolute from the top-left corner, so the drag tracks them directly.
        var startRawX = 0f
        var startRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false

        view.setOnTouchListener { _, event ->
            val params = overlayParams ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Bounds are resolved per drag, not per move event, and a rotation or a
                    // resolution change between drags can only be observed here: this runs on the
                    // main thread for every ACTION_MOVE, and re-reading WindowManager a hundred
                    // times a second inside an overlay whose entire purpose is not to cost the
                    // game frames would be self-defeating.
                    refreshDragBounds()
                    startRawX = event.rawX
                    startRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startRawX
                    val dy = event.rawY - startRawY
                    if (!moved && (abs(dx) > touchSlop || abs(dy) > touchSlop)) moved = true
                    if (moved) {
                        val (x, y) = clampToDisplay(startX + dx.roundToInt(), startY + dy.roundToInt())
                        params.x = x
                        params.y = y
                        runCatching { windowManager?.updateViewLayout(view, params) }
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // Persisted on release rather than on every move event, so a drag is one write.
                    if (moved) {
                        positionStore.save(params.x, params.y)
                    } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                        // A touch that never passed the slop is a tap, not a drag. Nothing is bound
                        // to it, but it is still dispatched so the view reports the click to
                        // accessibility services rather than swallowing it.
                        view.performClick()
                    }
                    true
                }

                else -> false
            }
        }
    }

    /**
     * Holds a position inside the display, keeping the whole panel on-screen.
     *
     * A null [dragBounds] means the display or the view could not be read, and the position is
     * returned untouched: [DisplayMetricsProvider] reports that honestly instead of inventing a
     * size, and clamping against a made-up number would move the readout somewhere the user did
     * not put it.
     */
    private fun clampToDisplay(x: Int, y: Int): Pair<Int, Int> =
        dragBounds?.clamp(x, y) ?: (x to y)

    /**
     * Re-reads the display and caches the legal travel range.
     *
     * Called when a drag starts, when a stored position is applied, and on a configuration change —
     * the only moments the answer can differ, including a `wm size` change made by this app's own
     * Resolution Changer. The view's own size is part of the range, so an unmeasured view leaves
     * the bounds null rather than clamping against a guess.
     */
    private fun refreshDragBounds() {
        val snapshot = displayMetricsProvider.current()
        val view = overlayView
        dragBounds = if (!snapshot.isValid || view == null || view.width == 0 || view.height == 0) {
            null
        } else {
            val margin = (MOVE_EDGE_MARGIN_DP * resources.displayMetrics.density).roundToInt()
            OverlayPosition.bounds(
                displayWidth = snapshot.widthPixels,
                displayHeight = snapshot.heightPixels,
                viewWidth = view.width,
                viewHeight = view.height,
                marginPx = margin
            )
        }
    }

    /**
     * Re-clamps the readout after a rotation or resolution change.
     *
     * A position saved in portrait can sit outside a landscape screen's shorter axis, which would
     * leave the panel off the display — and in move mode, un-grabbable. Only the live window moves:
     * the stored value is deliberately left alone, so a later show puts the readout back where the
     * user left it instead of at a position flattened to the old screen's edge.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshDragBounds()
        val view = overlayView ?: return
        val params = overlayParams ?: return
        val (x, y) = clampToDisplay(params.x, params.y)
        if (x != params.x || y != params.y) {
            params.x = x
            params.y = y
            runCatching { windowManager?.updateViewLayout(view, params) }
        }
    }

    /**
     * Moves the window to the stored position.
     *
     * Clamped on read as well as on drag: the stored value may have been saved at a resolution the
     * app's own Resolution Changer has since replaced, so a position that was on-screen when
     * written can be off it now.
     */
    private fun applyStoredPosition() {
        val view = overlayView ?: return
        val params = overlayParams ?: return
        // Bounds are re-read here because this path is reached from a reset or a fresh show, with
        // no preceding drag to have refreshed them.
        refreshDragBounds()
        val (x, y) = clampToDisplay(positionStore.x, positionStore.y)
        params.x = x
        params.y = y
        runCatching { windowManager?.updateViewLayout(view, params) }
    }

    /**
     * The ongoing notification, with a quick-toggle and a Stop action so the overlay can be
     * driven entirely from the shade.
     *
     * The toggle's label follows the window's state, so the notification is re-posted by the
     * toggle branch of onStartCommand — a stale "Hide" on a hidden overlay would make the
     * button look broken. While move mode is on the notification carries a "Done moving" action as
     * the backstop exit — reachable even if the in-app button is behind the game. onDestroy removes
     * the window, stops the metrics engine, and clears [isRunning] — the same teardown the in-app
     * switch's stopService gets.
     * getForegroundService rather than getService, because from API 26 a service started from a
     * notification action must call startForeground, which onStartCommand does on entry.
     */
    private fun buildNotification(): android.app.Notification {
        val toggle = android.app.PendingIntent.getForegroundService(
            this,
            REQUEST_TOGGLE,
            Intent(this, PerformanceOverlayService::class.java).setAction(ACTION_TOGGLE),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val stop = android.app.PendingIntent.getForegroundService(
            this,
            REQUEST_STOP,
            Intent(this, PerformanceOverlayService::class.java).setAction(ACTION_STOP),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(
                if (isMoveMode) {
                    getString(R.string.gt_svc_perf_moving)
                } else {
                    getString(R.string.gt_svc_perf_title)
                }
            )
            .setSmallIcon(R.drawable.ic_stat_name)
            .setOngoing(true)
            .setGroup(CatsmokerNotifications.GROUP_KEY)
            .addAction(R.drawable.ic_action_name, if (overlayView != null) getString(R.string.gt_svc_perf_hide) else getString(R.string.gt_svc_perf_show), toggle)
            .addAction(R.drawable.ic_action_name, getString(R.string.notification_stop), stop)

        if (isMoveMode) {
            builder.setContentText(getString(R.string.gt_svc_perf_taking))
            val done = android.app.PendingIntent.getService(
                this,
                REQUEST_EXIT_MOVE_MODE,
                Intent(this, PerformanceOverlayService::class.java).setAction(ACTION_EXIT_MOVE_MODE),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(R.drawable.ic_action_name, getString(R.string.gt_svc_perf_done), done)
        }
        return builder.build()
    }

    override fun onDestroy() {
        CatsmokerNotifications.detachOwner(this, "PerfOverlay")
        if (overlayView != null) {
            windowManager?.removeView(overlayView)
        }
        overlayView = null
        overlayParams = null
        isMoveMode = false
        isInMoveMode = false
        metricsEngine.stop()
        serviceScope.cancel()
        isRunning = false
        // The overlay is gone by every path that ends here, so the boot receiver must not
        // resurrect it. A hard process kill skips this — the flag then stays true and the
        // overlay comes back on the next boot, which is the same tradeoff the reference makes:
        // erring towards restoring what the user last had on.
        prefs.edit { putBoolean(KEY_WAS_RUNNING, false) }
        sendBroadcast(Intent(ACTION_SERVICE_STOPPED).setPackage(packageName))
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        CatsmokerNotifications.ensureGroup(nm)
        nm.createNotificationChannel(
            CatsmokerNotifications.channel(CHANNEL_ID, getString(R.string.gt_svc_perf_channel), NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        var isRunning = false

        /** Whether the readout is currently taking touches. Mirrored to the UI so the card can say so. */
        var isInMoveMode = false

        /** Ends the service from the notification's Stop action; onDestroy does the real teardown. */
        const val ACTION_STOP = "com.catsmoker.app.STOP_PERFORMANCE_OVERLAY"
        /** The notification's quick-toggle: hide or re-show the window without stopping. */
        const val ACTION_TOGGLE = "com.catsmoker.app.TOGGLE_PERFORMANCE_OVERLAY"

        /** Enters, exits, or undoes move mode; each is delivered to the running service. */
        const val ACTION_ENTER_MOVE_MODE = "com.catsmoker.app.PERF_OVERLAY_ENTER_MOVE_MODE"
        const val ACTION_EXIT_MOVE_MODE = "com.catsmoker.app.PERF_OVERLAY_EXIT_MOVE_MODE"
        const val ACTION_RESET_POSITION = "com.catsmoker.app.PERF_OVERLAY_RESET_POSITION"

        /** The service's own reports, so the UI follows what it did rather than what was asked. */
        const val ACTION_SERVICE_STARTED = "com.catsmoker.app.PERF_OVERLAY_STARTED"
        const val ACTION_SERVICE_STOPPED = "com.catsmoker.app.PERF_OVERLAY_STOPPED"
        const val ACTION_MOVE_MODE_CHANGED = "com.catsmoker.app.PERF_OVERLAY_MOVE_MODE_CHANGED"
        const val EXTRA_MOVE_MODE = "move_mode"

        const val PREFS_NAME = "overlay_prefs"
        const val KEY_WAS_RUNNING = "perf_overlay_was_running"

        /** Keeps a dragged readout off the physical edges, where a cutout or rounded corner could clip it. */
        private const val MOVE_EDGE_MARGIN_DP = 16f

        private const val CHANNEL_ID = "perf_overlay_channel"
        private const val NOTIFICATION_ID = 104
        private const val REQUEST_STOP = 0
        private const val REQUEST_TOGGLE = 1
        private const val REQUEST_EXIT_MOVE_MODE = 2
    }
}
