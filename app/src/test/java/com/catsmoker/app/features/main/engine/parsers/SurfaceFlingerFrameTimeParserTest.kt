package com.catsmoker.app.features.main.engine.parsers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The SurfaceFlinger percentile reader, pinned to the `--timestats -dump` shapes the
 * POCO X3 NFC (Android 13) actually prints: a per-layer `present2present` histogram
 * after each `averageFPS`, and a global `presentToPresent` histogram before the layers.
 *
 * The dashboard already has the average and the missed count; percentiles are the part
 * that says whether the average hides stutter. A parser that reads the wrong histogram
 * would be worse than none, so both shapes are asserted here.
 */
class SurfaceFlingerFrameTimeParserTest {

    @Test
    fun readsTheForegroundLayersPresentHistogram() {
        val dump = """
            SurfaceFlinger TimeStats:
            Legacy stats are as follows:
            averageFrameDuration = 5.014 ms
            presentToPresent histogram is as below:
            8ms=50 9ms=50

            displayRefreshRate = 120 fps
            renderRate = 120 fps
            uid = 1000
            layerName = com.example.game/com.example.game.Main#123
            averageFPS = 102.584
            present2present histogram is as below:
            0ms=0 8ms=80 9ms=10 10ms=5 16ms=3 33ms=2
        """.trimIndent()

        val stats = SurfaceFlingerFrameTimeParser.parse(dump)
        assertEquals(10, stats?.p95Ms)
        assertEquals(33, stats?.p99Ms)
        assertEquals(100L, stats?.samples)
    }

    @Test
    fun fallsBackToTheGlobalHistogramWhenNoLayerHasOne() {
        val dump = """
            SurfaceFlinger TimeStats:
            presentToPresent histogram is as below:
            0ms=0 8ms=95 16ms=5
        """.trimIndent()

        val stats = SurfaceFlingerFrameTimeParser.parse(dump)
        assertEquals(8, stats?.p95Ms)
        assertEquals(16, stats?.p99Ms)
        assertEquals(100L, stats?.samples)
    }

    @Test
    fun zeroCountBucketsDoNotInventSamples() {
        val dump = """
            presentToPresent histogram is as below:
            0ms=0 8ms=0 9ms=0 16ms=0
        """.trimIndent()
        assertNull(SurfaceFlingerFrameTimeParser.parse(dump))
    }

    @Test
    fun missingHistogramReadsAsNoReading() {
        assertNull(SurfaceFlingerFrameTimeParser.parse("averageFPS = 60.0"))
        assertNull(SurfaceFlingerFrameTimeParser.parse(""))
    }

    @Test
    fun percentileRanksUseTheFullWindow() {
        // 95 frames at 8 ms and 5 at 16 ms: the 95th frame is still in the 8 ms bucket.
        val stats = SurfaceFlingerFrameTimeParser.parse(
            "presentToPresent histogram is as below:\n8ms=95 16ms=5"
        )
        assertEquals(8, stats?.p95Ms)
        assertEquals(16, stats?.p99Ms)
    }
}
