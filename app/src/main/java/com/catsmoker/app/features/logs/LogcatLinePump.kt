package com.catsmoker.app.features.logs

import java.io.InputStream

/**
 * Pulls lines out of a streaming `logcat` stdout without ever blocking the caller: each
 * [drain] consumes only the bytes already available, splits them on newlines, and holds a
 * trailing partial line for the next drain. Complete non-blank lines land in [buffer] and
 * [onBatch] fires with the fresh snapshot only when at least one line arrived.
 */
class LogcatLinePump(
    private val input: InputStream,
    private val buffer: LogcatTailBuffer,
    private val onBatch: (List<String>) -> Unit = {},
) {

    /**
     * Bytes of the current line held back because no newline has arrived yet. Kept as bytes,
     * not text: an `available()` boundary can split a multi-byte character, and decoding only
     * complete lines keeps every emitted line valid UTF-8.
     */
    private val pending = java.io.ByteArrayOutputStream()

    /**
     * Consumes the bytes already available — never blocks — and returns how many non-blank
     * lines completed. A trailing unterminated tail is held, not emitted: `logcat` terminates
     * every line it prints, so an unterminated tail at kill time is a fragment, not a fact.
     * `IOException` propagates to the caller (the streamer treats it as stream death).
     */
    fun drain(): Int {
        val available = input.available()
        if (available <= 0) return 0
        val chunk = ByteArray(available)
        var read = 0
        while (read < available) {
            val n = input.read(chunk, read, available - read)
            if (n == -1) break
            read += n
        }
        if (read == 0) return 0
        pending.write(chunk, 0, read)
        val bytes = pending.toByteArray()
        var start = 0
        var added = 0
        for (i in bytes.indices) {
            if (bytes[i] == '\n'.code.toByte()) {
                val line = String(bytes, start, i - start, Charsets.UTF_8).trimEnd('\r')
                if (line.isNotBlank()) {
                    buffer.add(line)
                    added++
                }
                start = i + 1
            }
        }
        pending.reset()
        if (start < bytes.size) pending.write(bytes, start, bytes.size - start)
        if (added > 0) onBatch(buffer.snapshot())
        return added
    }

    fun close() {
        runCatching { input.close() }
    }
}
