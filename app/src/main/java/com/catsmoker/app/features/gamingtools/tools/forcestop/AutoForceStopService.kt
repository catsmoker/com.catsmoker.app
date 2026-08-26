package com.catsmoker.app.features.gamingtools.tools.forcestop

import android.app.AppOpsManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.catsmoker.app.R
import com.catsmoker.app.system.MainActivity
import com.catsmoker.app.system.shell.ShellRunner
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import javax.inject.Inject

/**
 * Force-stops the app you just switched away from, unless you asked for it to be kept running.
 *
 * The list the user ticks is a *keep-alive* list. Anything not on it gets closed when you leave it —
 * which is what "Auto Force Stop" means, and the reverse of what this service used to do.
 *
 * Four kinds of package are never touched, regardless of the list, because closing them would break
 * the phone rather than free memory: this app, the home screen / launcher, the keyboard that is
 * currently selected, and anything preinstalled in the system image.
 *
 * Two things have to be true for any of it to work, and neither is granted by installing the app:
 * - **Usage access** (`PACKAGE_USAGE_STATS`), or `queryEvents` returns an empty iterator forever and
 *   the service never learns which app you left.
 * - **Root or Shizuku**, or `am force-stop` is refused for any package but our own.
 *
 * Both are checked every cycle and named in the notification. The service used to say
 * "Monitoring background applications…" regardless, which read as working while it was doing nothing
 * at all — the single most misleading state this feature could be in.
 */
@AndroidEntryPoint
class AutoForceStopService : Service() {

    @Inject
    lateinit var shellRunner: ShellRunner

    @Inject
    lateinit var keepAliveStore: KeepAliveStore

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)
    private var pollingJob: Job? = null

    /** Last text pushed to the notification, so an unchanged status is not re-posted every 2 s. */
    private var lastStatus: String? = null

    companion object {
        private const val CHANNEL_ID = "auto_force_stop_channel"
        private const val NOTIF_ID = 4201
        private const val POLL_INTERVAL_MS = 2000L

        fun start(context: Context) {
            val intent = Intent(context, AutoForceStopService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AutoForceStopService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Starting…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (pollingJob?.isActive != true) {
            pollingJob = scope.launch { pollLoop() }
        }
        return START_STICKY
    }

    private suspend fun pollLoop() {
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        var previousForegroundPackage: String? = null
        var lastEventTime = System.currentTimeMillis() - POLL_INTERVAL_MS
        var stoppedCount = 0

        while (true) {
            delay(POLL_INTERVAL_MS.milliseconds)
            val kept = keepAliveStore.getKeptPackages()

            // Re-checked every cycle rather than once at startup, so the service starts working the
            // moment the user grants what is missing — no restart, no toggling the switch again.
            if (!hasUsageAccess()) {
                postStatus("Needs usage access — tap to grant, nothing is being closed")
                continue
            }
            if (!shellRunner.hasPrivilege()) {
                postStatus("Needs root or Shizuku — Android refuses to close other apps, nothing is closed")
                continue
            }

            val now = System.currentTimeMillis()
            val current = queryLatestForegroundPackage(usageStatsManager, lastEventTime, now)
            lastEventTime = now

            if (current != null && current != previousForegroundPackage) {
                val left = previousForegroundPackage
                if (left != null && !kept.contains(left) && !isProtected(left)) {
                    // Counted from the shell's exit code, so the notification's tally is of apps the
                    // platform actually stopped rather than of commands sent.
                    val result = shellRunner.execSafeResult("am", "force-stop", left)
                    if (result.isSuccess) stoppedCount++
                }
                previousForegroundPackage = current
            }

            val keepNote = if (kept.isEmpty()) {
                "Closing every app you leave"
            } else {
                "Keeping ${kept.size} app(s) open"
            }
            postStatus(
                if (stoppedCount == 0) "$keepNote — none closed yet" else "$keepNote — $stoppedCount closed"
            )
        }
    }

    /**
     * Whether [packageName] must never be force-stopped, whatever the keep list says.
     *
     * Closing any of these frees nothing worth having and visibly breaks the device: killing the
     * launcher drops the user to a black screen, killing the active keyboard leaves them unable to
     * type, and killing a system package can restart system_server. Our own package is excluded too,
     * or the service would stop itself the moment the user left this screen.
     *
     * The launcher and keyboard are resolved live rather than hard-coded, so a third-party home screen
     * or keyboard is protected exactly like the stock one.
     */
    private fun isProtected(packageName: String): Boolean {
        if (packageName == this.packageName) return true
        if (packageName in launcherPackages()) return true
        if (packageName == currentInputMethodPackage()) return true
        return isSystemPackage(packageName)
    }

    /** Every package that can act as the home screen, so whichever one is in use is covered. */
    private fun launcherPackages(): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return try {
            packageManager.queryIntentActivities(intent, 0)
                .mapNotNull { it.activityInfo?.packageName }
                .toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    /** The package owning the keyboard the user has selected, or null when it cannot be read. */
    private fun currentInputMethodPackage(): String? {
        val id = try {
            Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        } catch (_: Exception) {
            null
        } ?: return null
        // The value is a ComponentName flattened as "pkg/.ServiceName".
        return id.substringBefore('/').takeIf { it.isNotBlank() }
    }

    private fun isSystemPackage(packageName: String): Boolean = try {
        val flags = packageManager.getApplicationInfo(packageName, 0).flags
        (flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
            (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    } catch (_: Exception) {
        // An unknown package is not something to experiment with force-stopping.
        true
    }

    /**
     * Whether the user has granted usage access to this app.
     *
     * `PACKAGE_USAGE_STATS` is an appop, not a runtime permission: `checkSelfPermission` always
     * reports it denied, so the op has to be read from [AppOpsManager] instead.
     */
    private fun hasUsageAccess(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName
                )
            }
        } catch (_: Exception) {
            return false
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun queryLatestForegroundPackage(usm: UsageStatsManager, begin: Long, end: Long): String? {
        val events = try {
            usm.queryEvents(begin, end)
        } catch (_: Exception) {
            return null
        }
        var latest: String? = null
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                latest = event.packageName
            }
        }
        return latest
    }

    private fun postStatus(text: String) {
        if (text == lastStatus) return
        lastStatus = text
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, "Auto Force Stop", NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): android.app.Notification {
        val pendingIntent = android.app.PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), android.app.PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Auto Force Stop")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    override fun onDestroy() {
        pollingJob?.cancel()
        job.cancel()
        super.onDestroy()
    }
}
