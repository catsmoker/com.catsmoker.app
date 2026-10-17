package com.catsmoker.app.features.logs

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns one continuous `logcat` process behind [AppLogCollector]'s app-level buffer.
 *
 * The process runs while the collector lives (the app process) and is destroyed
 * on [stop]. The buffer is bounded ([MAX_LINES]) and snapshots go out at most
 * every [POLL_INTERVAL_MS], so a log burst cannot grow memory or recompose the
 * list unboundedly. Without `READ_LOGS` the stream carries only this app's own
 * lines — stated, not worked around.
 */
class LogcatStreamer(
    private val processFactory: (Array<String>) -> Process = { args ->
        ProcessBuilder(*args).redirectErrorStream(true).start()
    },
    private val maxLines: Int = MAX_LINES,
    private val pollIntervalMs: Long = POLL_INTERVAL_MS,
) {

    companion object {
        /** Recent tail first, then follow — and explicit `threadtime`, the only shape parsed. */
        val ARGS = arrayOf("logcat", "-v", "threadtime", "-T", "200")

        /** Oldest evicted past this; a screenful is hundreds of lines, this is hours. */
        const val MAX_LINES = 2000

        /** Drain cadence: well under human notice, well above recomposition pain. */
        const val POLL_INTERVAL_MS = 500L

        /** Dead-on-arrival check: a missing `logcat` binary fails the exec at once. */
        const val START_GRACE_MS = 200L
    }

    private val buffer = LogcatTailBuffer(maxLines)
    private val _snapshots = MutableStateFlow<List<String>>(emptyList())
    val snapshots: StateFlow<List<String>> = _snapshots.asStateFlow()

    @Volatile
    private var running = false
    private var process: Process? = null

    val isRunning: Boolean
        get() = running

    /**
     * Launches the reader; false when one already runs, the factory throws, or the process
     * is dead after the start grace (missing binary). The grace sleep runs on the caller's
     * thread — [LogsViewModel] calls this off the main thread.
     */
    @Synchronized
    fun start(): Boolean {
        if (running) return false
        val proc = try {
            processFactory(ARGS)
        } catch (_: Exception) {
            return false
        }
        try {
            Thread.sleep(START_GRACE_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            runCatching { proc.destroy() }
            return false
        }
        if (!isAlive(proc)) {
            runCatching { proc.destroy() }
            return false
        }
        process = proc
        val pump = LogcatLinePump(proc.inputStream, buffer) { _snapshots.value = it }
        running = true
        // No join on teardown: the daemon reader notices `running` within one poll interval
        // and exits on its own, so stop() stays safe to call from any thread.
        kotlin.concurrent.thread(isDaemon = true, name = "logcat-stream") {
            try {
                while (running) {
                    try {
                        pump.drain()
                    } catch (_: java.io.IOException) {
                        break
                    }
                    try {
                        Thread.sleep(pollIntervalMs)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            } finally {
                pump.close()
            }
        }
        return true
    }

    /**
     * Stops the reader and destroys the process; safe to call when not running, including
     * on the main thread — teardown only signals and destroys, never joins. The daemon
     * reader notices within one poll interval and exits on its own.
     */
    @Synchronized
    fun stop() {
        running = false
        runCatching { process?.destroy() }
        process = null
    }

    /** Drops the buffered tail and the last snapshot. */
    @Synchronized
    fun clear() {
        buffer.clear()
        _snapshots.value = emptyList()
    }

    private fun isAlive(process: Process): Boolean =
        try {
            process.exitValue()
            false
        } catch (_: IllegalThreadStateException) {
            true
        }
}
