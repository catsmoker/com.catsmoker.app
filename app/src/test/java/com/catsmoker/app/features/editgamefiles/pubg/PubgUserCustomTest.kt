package com.catsmoker.app.features.editgamefiles.pubg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The merge-preserve writer's contract + the shipped template's shape (TODO M190/M191). */
class PubgUserCustomTest {

    private val template = "[UserCustom DeviceProfile]\n+CVars=0ABC\n".toByteArray(Charsets.UTF_8)

    @Test
    fun keepsEverythingFromTheBackupMarkerOn() {
        val current = "[UserCustom DeviceProfile]\n+CVars=OLD\n[BackUp DeviceProfile]\n+CVars=STOCK1\n+CVars=STOCK2\n"
        val merged = PubgUserCustom.mergePreservingBackup(current.toByteArray(Charsets.UTF_8), template)
        assertEquals(
            "[UserCustom DeviceProfile]\n+CVars=0ABC\n\n[BackUp DeviceProfile]\n+CVars=STOCK1\n+CVars=STOCK2\n",
            String(merged, Charsets.UTF_8)
        )
    }

    @Test
    fun noCurrentFileWritesTheTemplateAlone() {
        val merged = PubgUserCustom.mergePreservingBackup(null, template)
        assertEquals("[UserCustom DeviceProfile]\n+CVars=0ABC\n", String(merged, Charsets.UTF_8))
    }

    @Test
    fun fileWithoutTheMarkerWritesTheTemplateAlone() {
        val current = "[UserCustom DeviceProfile]\n+CVars=OLD\n".toByteArray(Charsets.UTF_8)
        val merged = PubgUserCustom.mergePreservingBackup(current, template)
        assertEquals("[UserCustom DeviceProfile]\n+CVars=0ABC\n", String(merged, Charsets.UTF_8))
    }

    @Test
    fun templateWithoutTrailingNewlineStillGetsTheBlankLine() {
        val bare = "[UserCustom DeviceProfile]\n+CVars=0ABC".toByteArray(Charsets.UTF_8)
        val current = "[BackUp DeviceProfile]\n+CVars=STOCK\n"
        val merged = PubgUserCustom.mergePreservingBackup(current.toByteArray(Charsets.UTF_8), bare)
        assertEquals(
            "[UserCustom DeviceProfile]\n+CVars=0ABC\n\n[BackUp DeviceProfile]\n+CVars=STOCK\n",
            String(merged, Charsets.UTF_8)
        )
    }

    @Test
    fun shippedTemplateIsTheReferenceHexShape() {
        val asset = File("src/main/assets/PUBG/UserCustom/DeviceProfile.ini")
        assertTrue("template asset missing at ${asset.absolutePath}", asset.exists())
        val text = asset.readText(Charsets.UTF_8)
        assertTrue("template must end with a newline", text.endsWith("\n"))
        val lines = text.trimEnd('\n').split("\n")
        assertEquals("[UserCustom DeviceProfile]", lines.first())
        assertEquals("header plus 35 cvar lines", 36, lines.size)
        val hex = Regex("^\\+CVars=[0-9A-F]+$")
        assertTrue("every body line must be a hex cvar", lines.drop(1).all { hex.matches(it) })
    }
}
