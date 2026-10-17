package com.catsmoker.app.features.logs

import com.catsmoker.app.shared.util.LogcatEntryParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The app's live log buffer: exactly one shared `logcat` reader for the whole
 * process, started once from [com.catsmoker.app.system.CatsmokerApp.onCreate] and
 * observed by the System Logs screen.
 *
 * Why this shape:
 * - The reader is a plain app-UID `logcat` ([LogcatStreamer]): no root/Shizuku,
 *   no permission, nothing on disk. On user builds that yields this app's own
 *   lines; on userdebug/eng builds the daemon may also hand over system and
 *   other-app lines — so every snapshot passes [filterOwnLogs] and only this
 *   process's pid (plus unparseable framing lines) ever reaches the buffer,
 *   the screen, or a share. Other apps' private logs are dropped, not displayed.
 * - One process, 500 ms snapshot batches, a 2000-line cap ([LogcatTailBuffer]):
 *   bounded memory, no recomposition storm, negligible battery.
 * - No Service: the reader is a daemon thread owned by the app process. It runs
 *   while the process lives (foreground or background) and dies with it — which
 *   is exactly the required lifetime, so a permanent service would be pure cost.
 * - [clear] empties the buffer without touching the reader: clearing never stops
 *   collection and new lines keep arriving.
 */
@Singleton
class AppLogCollector @Inject constructor(
    private val streamer: LogcatStreamer,
) {

    /**
     * Test seam for [selfPid]: the JVM suites run against android.jar stubs where
     * `Process.myPid()` throws, so tests set this instead. Production never sets it.
     */
    internal var selfPidOverride: Int? = null

    private val selfPid: Int
        get() = selfPidOverride ?: android.os.Process.myPid()

    /**
     * The live tail, newest last. A cold mapping over the reader's snapshots —
     * no extra scope or hop: whoever collects (the ViewModel) gets each batch
     * filtered inline.
     */
    val logs: Flow<List<String>> = streamer.snapshots
        .map { lines -> filterOwnLogs(lines, selfPid) }

    val isRunning: Boolean
        get() = streamer.isRunning

    private val _collectionFailed = MutableStateFlow(false)

    /** True when the reader could not start (no `logcat` binary) — the screen's error state. */
    val collectionFailed: StateFlow<Boolean> = _collectionFailed.asStateFlow()

    private val startAttempted = AtomicBoolean(false)

    /**
     * Starts the reader unless it was already started (or attempted). Idempotent:
     * opening the Logs screen repeatedly never spawns a second reader. Blocks up
     * to ~200 ms on the start grace — call off the main thread.
     */
    fun ensureStarted() {
        if (!startAttempted.compareAndSet(false, true)) return
        if (!streamer.start()) {
            _collectionFailed.value = true
        }
    }

    /** Drops the buffered tail; collection continues, new lines keep arriving. */
    fun clear() {
        streamer.clear()
    }

    /** Test teardown only: production code never stops the app-level reader. */
    internal fun stopForTests() {
        streamer.stop()
    }
}

/**
 * Keeps this process's lines plus unparseable framing (headers like
 * `--------- beginning of ...`, which carry no pid); drops every other pid's
 * lines. Pure for testability — see `AppLogCollectorTest`.
 */
fun filterOwnLogs(lines: List<String>, pid: Int): List<String> =
    lines.filter { line ->
        val parsed = LogcatEntryParser.parse(line)
        parsed == null || parsed.pid == pid
    }
