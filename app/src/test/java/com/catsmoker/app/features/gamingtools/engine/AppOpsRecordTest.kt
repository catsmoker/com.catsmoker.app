package com.catsmoker.app.features.gamingtools.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/** Behaviour lock for the M42 prior-mode record's prefs serialization. */
class AppOpsRecordTest {

    @Test
    fun roundTripKeepsEveryEntry() {
        val record = mapOf("com.a" to "allow", "com.b" to "default", "com.c" to "ignore")
        assertEquals(record, AppOpsRecord.decode(AppOpsRecord.encode(record)))
    }

    @Test
    fun malformedEntriesAreDropped() {
        val decoded = AppOpsRecord.decode(setOf("", "com.a", "=ignore", "com.b=", "com.c=weird"))
        assertEquals(emptyMap<String, String>(), decoded)
    }

    @Test
    fun corruptEntryCannotSwallowAnother() {
        val decoded = AppOpsRecord.decode(setOf("com.a=ignore", "com.b=x=allow"))
        assertEquals(mapOf("com.a" to "ignore"), decoded)
    }
}
