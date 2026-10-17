package com.catsmoker.app.features.gamingtools.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Behaviour lock for the M42 background-op verdicts.
 *
 * The shapes are the device's own (POCO X3, Android 15 LineageOS, 2026-10-17): a package
 * with no override answers two lines, a written mode answers one, and a restored default
 * stays visible as its own line.
 */
class AppOpsVerdictTest {

    @Test
    fun explicitModeLineIsParsed() {
        assertEquals("ignore", AppOpsVerdict.parseMode("RUN_IN_BACKGROUND: ignore"))
        assertEquals("allow", AppOpsVerdict.parseMode("RUN_IN_BACKGROUND: allow"))
        assertEquals("default", AppOpsVerdict.parseMode("RUN_IN_BACKGROUND: default"))
    }

    @Test
    fun noOverrideAnswersDefault() {
        assertEquals("default", AppOpsVerdict.parseMode("No operations.\nDefault mode: allow"))
    }

    @Test
    fun unreadableAnswersAreNull() {
        assertNull(AppOpsVerdict.parseMode(""))
        assertNull(AppOpsVerdict.parseMode("Error: package not found"))
        assertNull(AppOpsVerdict.parseMode("RUN_IN_BACKGROUND: weird"))
    }

    @Test
    fun parsingIsCaseInsensitive() {
        assertEquals("allow", AppOpsVerdict.parseMode("run_in_background: ALLOW"))
    }
}
