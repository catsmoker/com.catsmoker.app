package com.catsmoker.app.system.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shape-only port of the reference TargetList channel (M155).
 *
 * Covers: global+custom merge (per-field override, empty-object = global copy),
 * wrong-type tolerance, missing-global defaults, per-package resolve with
 * presence flag, and the fps:0 / presence-gate unload rule. No watcher, no
 * native, no socket — behavioral use is blocked on ban-risk review + NDK
 * consumer + profile-model approval (TODO §2 item 13).
 */
class UnityTargetListTest {

    private val referenceSample = """
        {
          "global": {"delay": 10, "mod_opcode": true, "fps": 120},
          "custom": {
            "com.nexon.bluearchive": {"fps": 90},
            "com.random.package.name.b": {"mod_opcode": false},
            "com.random.package.name.c": {"delay": 5},
            "com.random.package.name.d": {"description": "keep blank to use global parameters (delete this line)."}
          }
        }
    """.trimIndent()

    @Test
    fun globalParsesFromReferenceSample() {
        val parsed = UnityTargetList.parse(referenceSample)
        assertEquals(10, parsed.global.delaySec)
        assertEquals(120, parsed.global.fps)
        assertEquals(true, parsed.global.modOpcode)
    }

    @Test
    fun customFpsOverrideInheritsRestFromGlobal() {
        val parsed = UnityTargetList.parse(referenceSample)
        val cfg = parsed.custom.getValue("com.nexon.bluearchive")
        assertEquals(90, cfg.fps)
        assertEquals(10, cfg.delaySec)
        assertEquals(true, cfg.modOpcode)
    }

    @Test
    fun customModOpcodeOverrideInheritsRest() {
        val parsed = UnityTargetList.parse(referenceSample)
        val cfg = parsed.custom.getValue("com.random.package.name.b")
        assertEquals(false, cfg.modOpcode)
        assertEquals(120, cfg.fps)
        assertEquals(10, cfg.delaySec)
    }

    @Test
    fun descriptionOnlyCustomFallsBackToGlobal() {
        val parsed = UnityTargetList.parse(referenceSample)
        val cfg = parsed.custom.getValue("com.random.package.name.d")
        assertEquals(parsed.global, cfg)
    }

    @Test
    fun missingGlobalFallsBackToReferenceDefaults() {
        val parsed = UnityTargetList.parse("""{"custom": {}}""")
        assertEquals(5, parsed.global.delaySec)
        assertEquals(90, parsed.global.fps)
        assertEquals(true, parsed.global.modOpcode)
        assertEquals(-1f, parsed.global.scale)
    }

    @Test
    fun malformedJsonNeverThrows() {
        val parsed = UnityTargetList.parse("{not json")
        assertEquals(UnityTargetList.TargetConfig(), parsed.global)
        assertTrue(parsed.custom.isEmpty())
    }

    @Test
    fun resolveMarksCustomPresence() {
        val parsed = UnityTargetList.parse(referenceSample)
        val known = UnityTargetList.resolve(parsed, "com.nexon.bluearchive")
        assertTrue(known.hasCustom)
        assertEquals(90, known.config.fps)
        val unknown = UnityTargetList.resolve(parsed, "com.unknown.game")
        assertFalse(unknown.hasCustom)
        assertEquals(parsed.global, unknown.config)
    }

    @Test
    fun fpsZeroAlwaysUnloads() {
        assertTrue(UnityTargetList.shouldUnload(hasCustom = true, hasIl2cppMarker = false, fps = 0))
    }

    @Test
    fun noPresenceUnloads() {
        assertTrue(UnityTargetList.shouldUnload(hasCustom = false, hasIl2cppMarker = false, fps = 120))
    }

    @Test
    fun customOrMarkerWithPositiveFpsStaysLoaded() {
        assertFalse(UnityTargetList.shouldUnload(hasCustom = true, hasIl2cppMarker = false, fps = 120))
        assertFalse(UnityTargetList.shouldUnload(hasCustom = false, hasIl2cppMarker = true, fps = 120))
    }
}
