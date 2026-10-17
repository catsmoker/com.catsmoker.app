package com.catsmoker.app.features.logs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The System Logs screen is a live viewer for ordinary users: a log list plus
 * one three-dot menu (Copy / Share / Clear), nothing else.
 *
 * This test bans the removed dashboard pieces from the Logs sources so they
 * cannot creep back: refresh buttons, filters, recording, exit history, source
 * cards, status lines, advanced sections, and the privileged snapshot/clear
 * paths. Recording infrastructure ([SessionRecorder]) and exit history
 * ([AppExitReader]) had no other callers and were deleted with their UI.
 */
class LogsScreenSimplicityTest {

    private val logsDir = File(
        "src/main/java/com/catsmoker/app/features/logs"
    )

    private fun source(name: String): String {
        val file = File(logsDir, name)
        assertTrue("expected logs source missing: $name", file.isFile)
        return file.readText()
    }

    @Test
    fun deletedHelpersStayDeleted() {
        assertFalse(
            "SessionRecorder.kt must not come back (recording left the Logs screen)",
            File(
                "src/main/java/com/catsmoker/app/features/main/engine/SessionRecorder.kt"
            ).exists()
        )
        assertFalse(
            "AppExitHistory.kt must not come back (exits left the Logs screen)",
            File(logsDir, "AppExitHistory.kt").exists()
        )
    }

    @Test
    fun menuHasExactlyCopyShareClear() {
        val screen = source("LogsScreen.kt")
        assertTrue(screen.contains("R.string.logs_copy"))
        assertTrue(screen.contains("R.string.logs_share"))
        assertTrue(screen.contains("R.string.logs_clear"))
        assertEquals(
            "the overflow menu must hold exactly Copy/Share/Clear",
            3,
            "DropdownMenuItem".toRegex().findAll(screen).count()
        )
    }

    @Test
    fun screenHasNoDashboardLeftovers() {
        val screen = source("LogsScreen.kt")
        listOf(
            "R.string.logs_refresh",
            "R.string.logs_live_start",
            "R.string.logs_live_stop",
            "R.string.logs_filter_placeholder",
            "R.string.logs_recording",
            "R.string.logs_exits",
            "R.string.logs_source",
            "R.string.logs_status",
            "R.string.logs_advanced",
            "R.string.logs_mode",
            "onRefresh",
            "toggleStreaming",
            "toggleRecording",
            "loadExits",
            "filterQuery",
            "TextField",
            "LogcatStreamer(",
            "SessionRecorder",
            "AppExitReader",
            "refreshLogs"
        ).forEach { token ->
            assertFalse("LogsScreen.kt still carries $token", screen.contains(token))
        }
    }

    @Test
    fun viewModelHasNoDashboardLeftovers() {
        val vm = source("LogsViewModel.kt")
        listOf(
            "ShellRunner",
            "SessionRecorder",
            "refreshLogs",
            "toggleStreaming",
            "toggleRecording",
            "loadExits",
            "filterQuery",
            "isStreaming",
            "privileged",
            "LogcatStreamer("
        ).forEach { token ->
            assertFalse("LogsViewModel.kt still carries $token", vm.contains(token))
        }
        listOf("AppLogCollector", "fun copyLogs", "fun shareLogs", "fun clearLogs").forEach { token ->
            assertTrue("LogsViewModel.kt must carry $token", vm.contains(token))
        }
    }
}
