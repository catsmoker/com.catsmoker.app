package com.catsmoker.app.features.logs

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogcatStreamerTest {

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
    fun startUsesThreadtimeTailArgs() {
        var seen: Array<String>? = null
        val streamer = LogcatStreamer(processFactory = { args ->
            seen = args
            FakeProcess(ByteArrayInputStream(ByteArray(0)), alive = false)
        })
        streamer.start()
        streamer.stop()
        assertArrayEquals(arrayOf("logcat", "-v", "threadtime", "-T", "200"), seen)
    }

    @Test
    fun startPumpsCannedLines() {
        val lines = "one\ntwo\nthree\n".toByteArray()
        var process: FakeProcess? = null
        val streamer = LogcatStreamer(processFactory = {
            FakeProcess(ByteArrayInputStream(lines), alive = true).also { process = it }
        })
        assertTrue(streamer.start())
        try {
            awaitTrue { streamer.snapshots.value == listOf("one", "two", "three") }
        } finally {
            streamer.stop()
        }
        assertEquals(listOf("one", "two", "three"), streamer.snapshots.value)
        assertTrue(process?.destroyed == true)
    }

    @Test
    fun secondStartWhileRunningReturnsFalse() {
        val streamer = LogcatStreamer(processFactory = {
            FakeProcess(ByteArrayInputStream("one\n".toByteArray()), alive = true)
        })
        assertTrue(streamer.start())
        try {
            assertFalse(streamer.start())
        } finally {
            streamer.stop()
        }
    }

    @Test
    fun stopDestroysTheProcess() {
        var process: FakeProcess? = null
        val streamer = LogcatStreamer(processFactory = {
            FakeProcess(object : InputStream() {
                override fun read(): Int {
                    Thread.sleep(200)
                    return -1
                }
            }, alive = true).also { process = it }
        })
        assertTrue(streamer.start())
        streamer.stop()
        assertTrue(process?.destroyed == true)
        assertFalse(streamer.isRunning)
    }

    @Test
    fun factoryThrowMeansStartFalse() {
        val streamer = LogcatStreamer(processFactory = { throw java.io.IOException("no logcat") })
        assertFalse(streamer.start())
        assertFalse(streamer.isRunning)
    }

    @Test
    fun deadProcessMeansStartFalse() {
        val streamer = LogcatStreamer(processFactory = {
            FakeProcess(ByteArrayInputStream(ByteArray(0)), alive = false)
        })
        assertFalse(streamer.start())
        assertFalse(streamer.isRunning)
    }
}
