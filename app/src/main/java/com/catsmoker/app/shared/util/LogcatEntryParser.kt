package com.catsmoker.app.shared.util

/**
 * One parsed logcat line in `threadtime` format
 * (`09-25 14:03:11.123  PID  TID  LEVEL  Tag: message`).
 */
data class LogcatEntry(
    val timestamp: String,
    val pid: Int,
    val tid: Int,
    val level: Char,
    val tag: String,
    val message: String,
)

/**
 * Parses `threadtime` logcat lines into [LogcatEntry], after the reference's
 * `LogcatEntryParser` (`reference/debug-overlay/.../LogcatEntryParser.kt`, read before this
 * file was written): the epoch/threadtime shape decodes to timestamp + level + tag + pid +
 * tid + message, and anything else — brief format, headers, prose — stays unparsed (null)
 * so callers keep their existing heuristics for foreign lines instead of misreading them.
 *
 * The contributor registry, crash-chain capture and exit history around the reference's
 * parser stay deliberately unported — the streaming-reader half now feeds the app-level
 * [com.catsmoker.app.features.logs.AppLogCollector] buffer the Logs screen observes.
 * This parser's one job is making the viewer's coloring exact.
 */
object LogcatEntryParser {

    private val THREADTIME = Regex(
        """^(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\s+(\d+)\s+(\d+)\s+([VDIWEF])\s+([^:]+): (.*)$"""
    )

    fun parse(line: String): LogcatEntry? {
        val match = THREADTIME.find(line.trim()) ?: return null
        val (timestamp, pid, tid, level, tag, message) = match.destructured
        return LogcatEntry(
            timestamp = timestamp,
            pid = pid.toIntOrNull() ?: return null,
            tid = tid.toIntOrNull() ?: return null,
            level = level.single(),
            tag = tag.trim(),
            message = message
        )
    }

    /** The threadtime level of one line, or null when the line is not threadtime-shaped. */
    fun levelOf(line: String): Char? = parse(line)?.level
}
