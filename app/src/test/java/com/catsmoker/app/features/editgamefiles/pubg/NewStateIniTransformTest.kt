package com.catsmoker.app.features.editgamefiles.pubg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The New State INI transform's rules (TODO M168), pure-JVM. */
class NewStateIniTransformTest {

    private fun file(vararg lines: String): ByteArray =
        (lines.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8)

    @Test
    fun replacesTheWholeInGameLine() {
        val result = NewStateIniTransform.apply(
            file(
                "[/Script/Engine.GameUserSettings]",
                "InGameSetting=(Brightness=80.000000,728fe36b3e=LOW)",
                "bUseVSync=False"
            )
        )
        val text = String(result.bytes, Charsets.UTF_8)
        assertTrue(result.matched)
        assertTrue(result.changed)
        assertTrue(text.contains(NewStateIniTransform.REFERENCE_IN_GAME_LINE))
        assertFalse("the old tuple must not survive", text.contains("Brightness=80.000000"))
        assertEquals(1, result.changes.size)
    }

    @Test
    fun raisesLowFrameRatesToTheReferenceTarget() {
        val result = NewStateIniTransform.apply(file("FrameRateLimit=30.000000"))
        assertTrue(result.matched)
        assertTrue(result.changed)
        assertEquals("FrameRateLimit=90.000000\n", String(result.bytes, Charsets.UTF_8))
    }

    @Test
    fun leavesFrameRatesAtOrAboveTheTargetAlone() {
        for (value in listOf("90.000000", "120.000000")) {
            val input = file("FrameRateLimit=$value")
            val result = NewStateIniTransform.apply(input)
            assertTrue(result.matched)
            assertFalse("$value is already at or above the target", result.changed)
            assertTrue(result.bytes.contentEquals(input))
        }
    }

    @Test
    fun zeroesBothAudioKeys() {
        val result = NewStateIniTransform.apply(
            file("AudioQualityLevel=2", "LastConfirmedAudioQualityLevel=1")
        )
        val text = String(result.bytes, Charsets.UTF_8)
        assertTrue(result.matched)
        assertTrue(result.changed)
        assertTrue(text.contains("AudioQualityLevel=0"))
        assertTrue(text.contains("LastConfirmedAudioQualityLevel=0"))
    }

    @Test
    fun refusesAFileWithNoKnownKeys() {
        val input = "hello\n".toByteArray(Charsets.UTF_8)
        val result = NewStateIniTransform.apply(input)
        assertFalse(result.matched)
        assertFalse(result.changed)
        assertTrue(result.bytes.contentEquals(input))
    }

    @Test
    fun anAlreadyAppliedFileReportsNoChange() {
        val result = NewStateIniTransform.apply(
            file(
                NewStateIniTransform.REFERENCE_IN_GAME_LINE,
                "FrameRateLimit=90.000000",
                "AudioQualityLevel=0"
            )
        )
        assertTrue(result.matched)
        assertFalse(result.changed)
    }

    @Test
    fun crlfLinesKeepTheirEnding() {
        val input = "FrameRateLimit=30.000000\r\nbUseVSync=False\r\n".toByteArray(Charsets.UTF_8)
        val result = NewStateIniTransform.apply(input)
        assertEquals(
            "FrameRateLimit=90.000000\r\nbUseVSync=False\r\n",
            String(result.bytes, Charsets.UTF_8)
        )
    }

    @Test
    fun unrelatedLinesSurviveUntouched() {
        val result = NewStateIniTransform.apply(
            file("[/Script/Engine.GameUserSettings]", "ResolutionSizeX=2400", "FrameRateLimit=60.000000")
        )
        val text = String(result.bytes, Charsets.UTF_8)
        assertTrue(text.contains("[/Script/Engine.GameUserSettings]"))
        assertTrue(text.contains("ResolutionSizeX=2400"))
        assertTrue(text.contains("FrameRateLimit=90.000000"))
    }
}
