package com.catsmoker.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Permanent lock on the mechanisms three reference arrivals must never bring into the app.
 *
 * Audited 2026-10-19 against `reference/AGENT.md`:
 *
 * - **TMPAD** (`gamingtools/TMPAD`) — its usable command map (game_overlay,
 *   `cmd game list-*`, `wm size/density`, `pm compile`/`trim-caches`, SurfaceFlinger
 *   timestats, the thermal-service parser) is already implemented. The tail this test
 *   bans is the part its own verdict lists as do-not-copy: `packages.xml` rewriting and
 *   install-session forging, thermal headroom/override forging, and blind writes.
 * - **JordanTweaks 120FPS** (`file-engineering/JordanTweaks-120FPS-Unlock`) — hard
 *   `debug.*fps*` caps, thermal override and vsync-disable props. Its one useful tail
 *   (the OEM refresh-key enum) is being read by `OemRefreshKeys` as a read-only probe;
 *   those key names are not banned, the cap-style writes are.
 * - **BoostX** (`file-engineering/BoostX-Max-CPU-Performance`) — synthetic CPU load,
 *   thermal-disable `persist.*` props and the operator prop. Its only legitimate
 *   contribution is vendor key-name vocabulary, now carried read-only by
 *   `VendorPerformanceKeys`.
 *
 * A syntactic ban, like `WuwaRemovedFeaturesTest`: the point is that a future session
 * cannot port the counter-example by copy-paste, whatever its reasoning.
 */
class ReferenceRejectionsTest {

    private val sourceRoots = listOf(
        "src/main/java",
        "src/main/jni",
        "src/full/java",
        "src/full/jni",
        "src/playstore/java"
    )

    /** Token → which reference it came from and why it may not appear in app sources. */
    private val banned = mapOf(
        // TMPAD: rewriting the package database / forging install sessions.
        "packages.xml" to "TMPAD package-database rewrite",
        "abx2xml" to "TMPAD package-database decode",
        "install-create" to "TMPAD install-session forging",
        "install-write" to "TMPAD install-session forging",
        "install-commit" to "TMPAD install-session forging",
        // TMPAD / JordanTweaks: forging thermal state instead of reporting it.
        "override-status" to "thermal-status override (JordanTweaks/TMPAD)",
        "thermalservice headroom" to "thermal-headroom forging (TMPAD)",
        // BoostX: turning the thermal engine off with persist props.
        "persist.sys.thermal.config" to "BoostX thermal-disable prop",
        "persist.vendor.mtk.thermal.config" to "BoostX thermal-disable prop",
        // JordanTweaks: hardcoded FPS ceilings and cap-style debug props.
        "debug.hwui.fpslimit" to "hard FPS cap (JordanTweaks)",
        "debug.hwui.fps_limit" to "hard FPS cap (JordanTweaks)",
        "debug.performance.cap" to "hard FPS cap (JordanTweaks)",
        "debug.sf.frame_rate_multiple_threshold" to "cap-style SurfaceFlinger prop (JordanTweaks)",
        "debug.sf.scroll_boost_refreshrate" to "blind refresh-rate prop (JordanTweaks)",
        "debug.sf.touch_boost_refreshrate" to "blind refresh-rate prop (JordanTweaks)",
        // JordanTweaks: disabling vsync entirely.
        "debug.hwui.disable_vsync" to "vsync disable (JordanTweaks)",
        "debug.egl.swapinterval" to "vsync disable (JordanTweaks)",
        "debug.gr.swapinterval" to "vsync disable (JordanTweaks)",
        "debug.sf.swapinterval" to "vsync disable (JordanTweaks)",
        // The one command shape TMPAD's map exposed as wrong on a real device:
        // `cmd game set --mode performance` throws NumberFormatException on Android 13
        // (surya/POCO X3, 2026-10-19); the mode is set through `cmd game mode`.
        "cmd game set --mode" to "invalid cmd game set --mode syntax (TMPAD cross-check)"
    )

    @Test
    fun appSourcesCarryNoReferenceCounterExample() {
        for (root in sourceRoots) {
            val dir = File(root)
            if (!dir.isDirectory) continue
            dir.walkTopDown()
                .filter { it.isFile && (it.extension == "kt" || it.extension == "java" || it.extension == "c") }
                .forEach { file ->
                    val text = file.readText()
                    banned.forEach { (token, why) ->
                        assertFalse(
                            "${file.path} resurrects $why — token: $token",
                            text.contains(token)
                        )
                    }
                }
        }
    }

    @Test
    fun banListItselfIsReal() {
        // A guard that scans nothing passes for the wrong reason: the roots and the list must
        // both exist and have content, or this whole file is decoration.
        assertTrue("expected at least the main source root", File("src/main/java").isDirectory)
        assertTrue("ban list went empty", banned.size >= 15)
    }
}
