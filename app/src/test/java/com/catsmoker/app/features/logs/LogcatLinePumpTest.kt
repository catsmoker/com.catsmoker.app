package com.catsmoker.app.features.logs

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogcatLinePumpTest {

    private fun pumpOf(
        bytes: ByteArray,
        maxLines: Int = 100,
        batches: MutableList<List<String>> = mutableListOf(),
    ): Pair<LogcatLinePump, LogcatTailBuffer> {
        val buffer = LogcatTailBuffer(maxLines)
        val pump = LogcatLinePump(ByteArrayInputStream(bytes), buffer, batches::add)
        return pump to buffer
    }

    @Test
    fun splitsLinesAndCallsBackWithSnapshot() {
        val batches = mutableListOf<List<String>>()
        val (pump, buffer) = pumpOf("one\ntwo\n".toByteArray(), batches = batches)
        assertEquals(2, pump.drain())
        assertEquals(listOf("one", "two"), buffer.snapshot())
        assertEquals(listOf(listOf("one", "two")), batches)
    }

    /** A stream the test feeds by hand: available bytes read without blocking, EOF on demand. */
    private class ManualStream : InputStream() {
        private val bytes = ArrayDeque<Byte>()
        var eof = false

        fun feed(text: String) {
            bytes.addAll(text.toByteArray().toList())
        }

        override fun available(): Int = bytes.size

        override fun read(): Int =
            if (bytes.isEmpty()) {
                if (eof) -1 else throw AssertionError("pump blocked with no data available")
            } else {
                bytes.removeFirst().toInt() and 0xFF
            }
    }

    @Test
    fun partialLineCompletesOnTheNextDrain() {
        val batches = mutableListOf<List<String>>()
        val buffer = LogcatTailBuffer(10)
        val stream = ManualStream()
        val pump = LogcatLinePump(stream, buffer, batches::add)
        // First drain: only "abc", no newline — held, nothing emitted.
        stream.feed("abc")
        assertEquals(0, pump.drain())
        assertTrue(buffer.snapshot().isEmpty())
        assertTrue(batches.isEmpty())
        // Second drain: "def\n" completes it — one line, one callback.
        stream.feed("def\n")
        assertEquals(1, pump.drain())
        assertEquals(listOf("abcdef"), buffer.snapshot())
        assertEquals(listOf(listOf("abcdef")), batches)
    }

    @Test
    fun skipsBlankLines() {
        val (pump, buffer) = pumpOf("one\n\n   \ntwo\n".toByteArray())
        assertEquals(2, pump.drain())
        assertEquals(listOf("one", "two"), buffer.snapshot())
    }

    @Test
    fun emptyDrainMakesNoCallback() {
        val batches = mutableListOf<List<String>>()
        val (pump, _) = pumpOf(ByteArray(0), batches = batches)
        assertEquals(0, pump.drain())
        assertTrue(batches.isEmpty())
    }

    @Test
    fun batchesDoNotExceedBufferCap() {
        val (pump, buffer) = pumpOf(
            "one\ntwo\nthree\nfour\n".toByteArray(),
            maxLines = 2,
        )
        assertEquals(4, pump.drain())
        assertEquals(listOf("three", "four"), buffer.snapshot())
    }

    @Test
    fun closeClosesTheInput() {
        var closed = false
        val tracking = object : InputStream() {
            override fun read(): Int = -1
            override fun close() {
                closed = true
                super.close()
            }
        }
        val pump = LogcatLinePump(tracking, LogcatTailBuffer(10))
        pump.close()
        assertTrue(closed)
    }

}
