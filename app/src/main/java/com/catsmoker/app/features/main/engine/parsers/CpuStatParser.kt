package com.catsmoker.app.features.main.engine.parsers

object CpuStatParser {
    data class CpuTimes(
        val user: Long,
        val nice: Long,
        val system: Long,
        val idle: Long,
        val iowait: Long,
        val irq: Long,
        val softirq: Long,
        val steal: Long,
        val guest: Long = 0,
        val guestNice: Long = 0
    ) {
        val total = user + nice + system + idle + iowait + irq + softirq + steal + guest + guestNice
        val active = total - (idle + iowait)
    }

    fun parseTotalCpuLine(lines: List<String>): CpuTimes? {
        val line = lines.firstOrNull { it.startsWith("cpu ") } ?: return null
        
        val parts = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.size < 5) return null
        
        return try {
            CpuTimes(
                user = parts[1].toLong(),
                nice = parts[2].toLong(),
                system = parts[3].toLong(),
                idle = parts[4].toLong(),
                iowait = if (parts.size > 5) parts[5].toLongOrNull() ?: 0L else 0L,
                irq = if (parts.size > 6) parts[6].toLongOrNull() ?: 0L else 0L,
                softirq = if (parts.size > 7) parts[7].toLongOrNull() ?: 0L else 0L,
                steal = if (parts.size > 8) parts[8].toLongOrNull() ?: 0L else 0L,
                guest = if (parts.size > 9) parts[9].toLongOrNull() ?: 0L else 0L,
                guestNice = if (parts.size > 10) parts[10].toLongOrNull() ?: 0L else 0L
            )
        } catch (e: Exception) {
            android.util.Log.e("CpuStatParser", "Parse error: ${e.message}")
            null
        }
    }

    fun calculateCpuUsage(prev: CpuTimes, current: CpuTimes): Int? {
        val totalDiff = current.total - prev.total
        if (totalDiff <= 0L) return null
        val idleDiff = (current.idle + current.iowait) - (prev.idle + prev.iowait)
        val busyDiff = totalDiff - idleDiff
        return ((busyDiff * 100) / totalDiff).toInt().coerceIn(0, 100)
    }
}
