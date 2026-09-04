package com.catsmoker.app.features.gamingtools.tools.graphics

import com.catsmoker.app.system.shell.ShellRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `service call` reply reader, against output measured on a real device.
 *
 * These strings are not invented. The refusal cases were captured from a Pixel-class Android 16 device
 * (SDK 36) with Shizuku, running the exact command [GameDeveloperOptions] sends, and they are the
 * reason this test exists: a rejected transaction prints no `Failure` and exits 0, so the app read it
 * as applied and reported an overlay that had never been drawn.
 */
class GameDeveloperOptionsParcelTest {

    /** As `service` prints a rejected transaction — measured, including the exit code of 0. */
    private val refusedOutput =
        """Result: Parcel(Error: 0xffffffffffffffff "Operation not permitted")"""

    private fun exit0(stdout: String) = ShellRunner.ExecResult(0, stdout, "")

    @Test
    fun readsTheStatusOutOfARefusedReply() {
        assertEquals(
            "Operation not permitted",
            GameDeveloperOptions.parcelError(refusedOutput)
        )
    }

    @Test
    fun treatsARefusedReplyAsARefusalDespiteExitCodeZero() {
        val refusal = GameDeveloperOptions.transactionRefusal(exit0(refusedOutput))
        assertNotNull("a rejected transaction must not read as applied", refusal)
        // The device's own words survive the paraphrase, or the refusal cannot be diagnosed.
        assertTrue(refusal!!.contains("Operation not permitted"))
    }

    @Test
    fun refusedReplyIsNotReadAsAState() {
        // The hex status is not a reading. Letting it through made a refusal look like an answer.
        assertNull(GameDeveloperOptions.parseParcelInt(refusedOutput))
    }

    @Test
    fun readsTheOverlayStateOutOfAnInlineReply() {
        assertEquals(1, GameDeveloperOptions.parseParcelInt("Result: Parcel(00000001 '....')"))
        assertEquals(0, GameDeveloperOptions.parseParcelInt("Result: Parcel(00000000 '....')"))
    }

    @Test
    fun skipsTheOffsetColumnOfAHexdumpReply() {
        val output = """
            Result: Parcel(
              0x00000000: 00000001 00000000 '........'
            )
        """.trimIndent()
        // Without dropping `0x00000000:` the offset itself would be read as the value.
        assertEquals(1, GameDeveloperOptions.parseParcelInt(output))
    }

    @Test
    fun keepsAnEmptyReplyDistinctFromAnErrorReply() {
        assertNull(GameDeveloperOptions.parseParcelInt("Result: Parcel(NULL)"))
        assertNull(GameDeveloperOptions.parcelError("Result: Parcel(NULL)"))
        // A reply that carried no data is not a refusal — it is the pre-Android-12 query branch
        // answering nothing, and reporting it as a failure is what "not applicable" exists to avoid.
        assertNull(GameDeveloperOptions.transactionRefusal(exit0("Result: Parcel(NULL)")))
    }

    @Test
    fun anAcceptedCallIsNotARefusal() {
        assertNull(GameDeveloperOptions.transactionRefusal(exit0("Result: Parcel(00000001 '....')")))
    }
}
