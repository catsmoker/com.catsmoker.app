package com.catsmoker.app.features.main.engine.parsers

/**
 * Frame-time percentiles from the same `dumpsys SurfaceFlinger --timestats -dump` the
 * dashboard already reads for its average and missed count.
 *
 * The average and the jank count were the whole frame-pacing story until now, and an
 * average is exactly what hides stutter: 60 fps can mean a clean 16 ms every frame or a
 * 33 ms hitch every third. TMPAD's command map pointed at the timestats histogram as the
 * cheaper surface than its own `--latency` pass — and it is already in every dump the
 * engine takes, so this reads what the device is already saying instead of adding a
 * second, layer-addressed command.
 *
 * Two shapes are parsed, in preference order:
 * 1. The first per-layer `present2present` histogram after an `averageFPS` — the
 *    foreground layer's frame pacing, the number the game actually produced.
 * 2. The global `presentToPresent` histogram before the layer blocks — the display's
 *    compositor pacing, used when no layer carried a histogram.
 *
 * Buckets are the histogram's own millisecond labels (`16ms=1429`). The percentile rank
 * is `ceil(samples * p / 100)`, the standard nearest-rank definition, so a window with
 * one frame still yields that frame's bucket rather than a fabricated interpolation.
 * Null means "this dump carried no histogram", never a zero.
 */
object SurfaceFlingerFrameTimeParser {

    data class FrameTimeStats(
        val p95Ms: Int,
        val p99Ms: Int,
        val samples: Long
    )

    private val LAYER_FPS = Regex("""averageFPS\s*=\s*[0-9.]+""")
    private val LAYER_HISTOGRAM = Regex("""present2present histogram is as below:""")
    private val GLOBAL_HISTOGRAM = Regex("""presentToPresent histogram is as below:""")
    private val BUCKET = Regex("""(\d+)ms=(\d+)""")

    fun parse(dump: String): FrameTimeStats? {
        layerHistogram(dump)?.let { return it }
        val globalStart = GLOBAL_HISTOGRAM.find(dump)?.range?.last ?: return null
        return histogramStats(dump.substring(globalStart))
    }

    /** The first histogram that follows an `averageFPS` line, or null when there is none. */
    private fun layerHistogram(dump: String): FrameTimeStats? {
        val average = LAYER_FPS.find(dump) ?: return null
        val histogram = LAYER_HISTOGRAM.find(dump, average.range.last) ?: return null
        return histogramStats(dump.substring(histogram.range.last))
    }

    /**
     * Parses the first non-blank line after [text] starts — the histogram itself, because
     * `text` begins on the header line's tail (its colon) and the buckets follow on the
     * next line.
     */
    private fun histogramStats(text: String): FrameTimeStats? {
        val line = text.lineSequence()
            .drop(1)
            .firstOrNull { it.isNotBlank() }
            ?: return null
        val buckets = BUCKET.findAll(line).mapNotNull { match ->
            val ms = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val count = match.groupValues[2].toLongOrNull() ?: return@mapNotNull null
            if (count <= 0L) null else ms to count
        }.toList()
        val total = buckets.sumOf { it.second }
        if (total <= 0L) return null
        return FrameTimeStats(
            p95Ms = percentile(buckets, total, 95),
            p99Ms = percentile(buckets, total, 99),
            samples = total
        )
    }

    /** Nearest-rank percentile over ascending histogram buckets (null-safe by construction). */
    private fun percentile(buckets: List<Pair<Int, Long>>, total: Long, p: Int): Int {
        val rank = (total * p + 99L) / 100L
        var cumulative = 0L
        for ((ms, count) in buckets) {
            cumulative += count
            if (cumulative >= rank) return ms
        }
        return buckets.last().first
    }
}
