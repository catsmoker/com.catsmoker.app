package com.catsmoker.app.features.logs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogcatTailBufferTest {

    @Test
    fun keepsInsertionOrder() {
        val buffer = LogcatTailBuffer(10)
        buffer.add("one")
        buffer.add("two")
        assertEquals(listOf("one", "two"), buffer.snapshot())
        assertEquals(2, buffer.size)
    }

    @Test
    fun evictsOldestBeyondMax() {
        val buffer = LogcatTailBuffer(3)
        buffer.add("one")
        buffer.add("two")
        buffer.add("three")
        buffer.add("four")
        assertEquals(listOf("two", "three", "four"), buffer.snapshot())
        assertEquals(3, buffer.size)
    }

    @Test
    fun ignoresBlankLines() {
        val buffer = LogcatTailBuffer(10)
        buffer.add("")
        buffer.add("   ")
        buffer.add("real")
        assertEquals(listOf("real"), buffer.snapshot())
        assertEquals(1, buffer.size)
    }

    @Test
    fun clearEmpties() {
        val buffer = LogcatTailBuffer(10)
        buffer.add("one")
        buffer.clear()
        assertTrue(buffer.snapshot().isEmpty())
        assertEquals(0, buffer.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonPositiveMax() {
        LogcatTailBuffer(0)
    }
}
