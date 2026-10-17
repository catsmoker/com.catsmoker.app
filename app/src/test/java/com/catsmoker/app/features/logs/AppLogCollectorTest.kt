package com.catsmoker.app.features.logs

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
/**
 * Pins the app-level log collector: one shared reader started once, a start
 * failure that reports instead of crashing, and a Clear that empties the buffer
 * without stopping collection.
 */
class AppLogCollectorTest {

    private class FakeProcess(
        private val stdout: InputStream,
        private val alive: Boolean,
    ) : Process() {
        var destroyed = false
            private set

        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = stdout
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0
        override fun exitValue(): Int {
            if (alive && !destroyed) throw IllegalThreadStateException("still running")
            return 0
        }

        override fun destroy() {
            destroyed = true
        }
    }

    private fun awaitTrue(timeoutMs: Long = 2000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("condition not met within ${timeoutMs}ms")
            }
            Thread.sleep(25)
        }
    }

    @Test
    fun failedStartReportsInsteadOfCrashing() {
        val collector = AppLogCollector(
            LogcatStreamer(processFactory = { throw java.io.IOException("no logcat") })
        )
        collector.selfPidOverride = 1234
        collector.ensureStarted()
        assertTrue(collector.collectionFailed.value)
        assertEquals(emptyList<String>(), runBlocking { collector.logs.first() })
    }

    @Test
    fun secondStartDoesNotSpawnASecondReader() {
        var starts = 0
        val collector = AppLogCollector(
            LogcatStreamer(processFactory = {
                starts++
                FakeProcess(ByteArrayInputStream("one\n".toByteArray()), alive = true)
            })
        )
        collector.selfPidOverride = 1234
        collector.ensureStarted()
        collector.ensureStarted()
        assertEquals(1, starts)
    }

    @Test
    fun clearEmptiesSnapshotsButKeepsCollecting() {
        val collector = AppLogCollector(
            LogcatStreamer(processFactory = {
                FakeProcess(ByteArrayInputStream("one\ntwo\n".toByteArray()), alive = true)
            })
        )
        collector.selfPidOverride = 1234
        collector.ensureStarted()
        try {
            awaitTrue { runBlocking { collector.logs.first() } == listOf("one", "two") }
            collector.clear()
            assertEquals(emptyList<String>(), runBlocking { collector.logs.first() })
            assertTrue(collector.isRunning)
        } finally {
            collector.stopForTests()
        }
    }

    @Test
    fun filterKeepsOwnPidAndUnparsedLinesOnly() {
        val lines = listOf(
            "10-16 11:30:00.000  1234  1234 I Catsmoker: own line",
            "10-16 11:30:00.001  9999  9999 E OtherApp: foreign line",
            "--------- beginning of system"
        )
        assertEquals(
            listOf(lines[0], lines[2]),
            filterOwnLogs(lines, pid = 1234)
        )
    }

    @Test
    fun filterEmptyStaysEmpty() {
        assertEquals(emptyList<String>(), filterOwnLogs(emptyList(), pid = 1234))
    }
}
