package com.catsmoker.app.features.logs

/**
 * Bounded tail of log lines: newest at most [maxLines] survive, oldest evicted first.
 * Blank lines are dropped — they carry no fact.
 */
class LogcatTailBuffer(private val maxLines: Int) {

    init {
        require(maxLines > 0) { "maxLines must be positive" }
    }

    private val lines = ArrayDeque<String>()

    @Synchronized
    fun add(line: String) {
        if (line.isBlank()) return
        lines.addLast(line)
        while (lines.size > maxLines) lines.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    @Synchronized
    fun clear() {
        lines.clear()
    }

    val size: Int
        @Synchronized get() = lines.size
}
