package com.catsmoker.app.features.editgamefiles.pubg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The native-hex `UserCustom.ini` corpus for a future `+CVars=` encoder (TODO M190/M212).
 *
 * Both files are shipped template blobs from the two decompiled reference tools
 * (`reference/pubg-closed-source-tools/`): the section is `[UserCustom DeviceProfile]`,
 * every cvar line is a `+CVars=<hex>` blob the engine decodes at runtime (proven native
 * encoding, not obfuscation — both tools emit it). Meanings of individual hex bodies are
 * UNVERIFIED: this test pins the corpus shape only, and no whole template is ever pushed
 * (see TODO M211/M189 — whole-blob push stays rejected).
 */
class PubgUserCustomCorpusTest {

    private fun corpus(name: String): List<String> {
        val file = File("src/test/resources/$name")
        assumeTrue("corpus file not found at ${file.absolutePath}", file.isFile)
        return file.readLines()
    }

    @Test
    fun euTemplateHasTwentyNineHexLines() {
        val lines = corpus("pubg_usercustom_eu.ini").filter { it.isNotBlank() }
        assertEquals("[UserCustom DeviceProfile]", lines.first().trim())
        val cvars = lines.filter { it.trim().startsWith("+CVars=") }
        assertEquals(30, lines.size)
        assertEquals(29, cvars.size)
        assertTrue(cvars.all { it.trim().removePrefix("+CVars=").matches(Regex("[0-9A-Fa-f]+")) })
    }

    @Test
    fun tqTemplateHasFortySixHexLines() {
        val lines = corpus("pubg_usercustom_tq.ini").filter { it.isNotBlank() }
        assertEquals("[UserCustom DeviceProfile]", lines.first().trim())
        val cvars = lines.filter { it.trim().startsWith("+CVars=") }
        assertEquals(47, lines.size)
        assertEquals(46, cvars.size)
        assertTrue(cvars.all { it.trim().removePrefix("+CVars=").matches(Regex("[0-9A-Fa-f]+")) })
    }

    @Test
    fun live460KeepsBothSectionsWithBackup() {
        // Read-only pull from PUBG Mobile 4.6.0 on POCO X3 NFC (2026-10-09):
        // the live file keeps the stock `[BackUp DeviceProfile]` section the
        // tools preserve (the M191 pairing rationale), and every content line
        // is a hex `+CVars=` blob — same container as the tool templates.
        val lines = corpus("pubg_usercustom_live460.ini").filter { it.isNotBlank() }
        val sections = lines.filter { it.trim().startsWith("[") }.map { it.trim() }
        assertEquals(
            listOf("[UserCustom DeviceProfile]", "[BackUp DeviceProfile]"),
            sections
        )
        val cvars = lines.filter { it.trim().startsWith("+CVars=") }
        assertEquals(78, cvars.size)
        assertTrue(cvars.all { it.trim().removePrefix("+CVars=").matches(Regex("[0-9A-Fa-f]+")) })
    }

    @Test
    fun live460SharesMostEuLinesVerbatim() {
        // 27 of the 29 eu template lines survive byte-identical in the 4.6.0
        // live file: the native encoding is stable across versions for those
        // keys — whatever they mean. A future encoder still needs the
        // key-to-value mapping (o.java native proof), but it will not be
        // chasing a moving format for the stable majority.
        val live = corpus("pubg_usercustom_live460.ini")
            .filter { it.trim().startsWith("+CVars=") }.toSet()
        val eu = corpus("pubg_usercustom_eu.ini")
            .filter { it.trim().startsWith("+CVars=") }.toSet()
        assertEquals(27, eu.intersect(live).size)
    }

    @Test
    fun liveRepullKeepsBackupAndStableMajorityWithTwoLineDrift() {
        // Read-only re-pull from the same PUBG Mobile install on POCO X3 NFC
        // (2026-10-16, md5 40c2a665fb79063cb0cc27310079d614, 76 hex lines):
        // two adjacent [UserCustom DeviceProfile] lines (43-44 of the Oct-9
        // pin) are gone — an in-game settings change, not format instability.
        // Every surviving line is byte-identical, both sections intact, and
        // [BackUp DeviceProfile] untouched (the M191 pairing rationale, live).
        // A future encoder must therefore read-modify-write, never
        // whole-template push (M211/M189) — templates go stale per install.
        val file = File("src/test/resources/pubg_usercustom_live20261016.ini")
        assertTrue("repull fixture missing at ${file.absolutePath}", file.isFile)
        val lines = file.readLines().filter { it.isNotBlank() }
        val sections = lines.filter { it.trim().startsWith("[") }.map { it.trim() }
        assertEquals(
            listOf("[UserCustom DeviceProfile]", "[BackUp DeviceProfile]"),
            sections
        )
        val cvars = lines.filter { it.trim().startsWith("+CVars=") }.map { it.trim() }
        assertEquals(76, cvars.size)
        assertTrue(cvars.all { it.removePrefix("+CVars=").matches(Regex("[0-9A-Fa-f]+")) })
        val pinned = File("src/test/resources/pubg_usercustom_live460.ini")
            .readLines().filter { it.trim().startsWith("+CVars=") }.map { it.trim() }.toSet()
        assertTrue("repull adds lines the Oct-9 pin never had", pinned.containsAll(cvars))
        val dropped = pinned - cvars.toSet()
        assertEquals(
            setOf(
                "+CVars=0B573F0B18141C3014090B160F1C34161D1C30172F1C0B002A1416160D114448",
                "+CVars=0B57292C3B3E3D1C0F101A1C3F292A2F1C0B002A1416160D11444F49"
            ),
            dropped
        )
    }

    @Test
    fun templatesShareTheSectionButDifferInBodies() {        // Same native container, different tier sets — the reason a future encoder must learn
        // from BOTH (dual-tool corpus), not average them.
        val eu = corpus("pubg_usercustom_eu.ini").filter { it.trim().startsWith("+CVars=") }.toSet()
        val tq = corpus("pubg_usercustom_tq.ini").filter { it.trim().startsWith("+CVars=") }.toSet()
        assertTrue("corpora overlap (shared native lines)", eu.intersect(tq).isNotEmpty())
        assertTrue("corpora differ (distinct tier sets)", (eu - tq).isNotEmpty() && (tq - eu).isNotEmpty())
    }
}
