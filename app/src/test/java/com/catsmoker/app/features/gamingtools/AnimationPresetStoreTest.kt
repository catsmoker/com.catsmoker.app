package com.catsmoker.app.features.gamingtools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Behaviour lock for the animation-scale preset library.
 *
 * Ported from `reference/gamingtools/Custom-Animations/.../utils/PresetManager.kt` (read in
 * full): UUID-keyed named presets holding the three animation scales, save / list / delete,
 * corrupt storage reading back as empty rather than crashing. Two deliberate divergences:
 * Gson-in-a-file instead of SharedPreferences (the house Gson-store pattern — JVM-testable
 * and atomically written), and save-time validation the reference never had
 * (blank names and absurd scales are refused instead of stored).
 */
class AnimationPresetStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = AnimationPresetStore(tmp.newFile("anim_presets.json"))

    @Test
    fun saveThenListRoundTrips() {
        val s = store()
        val saved = s.savePreset("Snappy", 0.5f, 0.5f, 0.5f)
        assertNotNull(saved)
        val all = s.getAll()
        assertEquals(1, all.size)
        assertEquals("Snappy", all[0].name)
        assertEquals(0.5f, all[0].window, 0f)
        assertEquals(0.5f, all[0].transition, 0f)
        assertEquals(0.5f, all[0].animator, 0f)
        assertEquals(saved!!.id, all[0].id)
    }

    @Test
    fun blankNameIsRefused() {
        val s = store()
        assertNull(s.savePreset("", 0.5f, 0.5f, 0.5f))
        assertNull(s.savePreset("   ", 0.5f, 0.5f, 0.5f))
        assertTrue(s.getAll().isEmpty())
    }

    @Test
    fun absurdScalesAreRefused() {
        val s = store()
        assertNull(s.savePreset("neg", -1f, 0.5f, 0.5f))
        assertNull(s.savePreset("nan", Float.NaN, 0.5f, 0.5f))
        assertNull(s.savePreset("huge", 11f, 0.5f, 0.5f))
        assertNull(s.savePreset("inf", Float.POSITIVE_INFINITY, 0.5f, 0.5f))
        assertTrue(s.getAll().isEmpty())
    }

    @Test
    fun zeroAndTenAreLegalEdges() {
        val s = store()
        assertNotNull(s.savePreset("off", 0f, 0f, 0f))
        assertNotNull(s.savePreset("slowmo", 10f, 10f, 10f))
        assertEquals(2, s.getAll().size)
    }

    @Test
    fun deleteExistingRemovesOnlyIt() {
        val s = store()
        val a = s.savePreset("a", 0.5f, 0.5f, 0.5f)!!
        val b = s.savePreset("b", 1f, 1f, 1f)!!
        assertTrue(s.delete(a.id))
        assertEquals(listOf(b), s.getAll())
    }

    @Test
    fun deleteMissingReturnsFalse() {
        assertFalse(store().delete("no-such-id"))
    }

    @Test
    fun corruptFileReadsEmpty() {
        val f = tmp.newFile("anim_presets.json")
        f.writeText("not json {{{")
        assertTrue(AnimationPresetStore(f).getAll().isEmpty())
    }

    @Test
    fun idsAreUnique() {
        val s = store()
        val ids = (1..20).map { s.savePreset("p$it", 0.5f, 0.5f, 0.5f)!!.id }.toSet()
        assertEquals(20, ids.size)
    }
}
